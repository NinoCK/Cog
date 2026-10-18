package com.bestassistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.edit
import com.bestassistant.language.AppLanguage
import com.fastsearch.R
import com.fastsearch.data.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** A text-to-speech voice the user can pick as the base of the assistant's voice. */
data class VoiceOption(
    val name: String,
    val locale: Locale,
    /** Measured speaking pitch in Hz, once known: below [Pitch.MALE_BELOW] it sounds male. */
    val pitch: Float?,
) {
    val isMale: Boolean? get() = pitch?.let { it < Pitch.MALE_BELOW }
}

/**
 * Reads replies aloud with the phone's own text-to-speech engine.
 *
 * Lives as long as the process, so a reply keeps playing after the overlay closes. Picks a voice
 * that's installed on the device (never one that streams from a server), and briefly ducks
 * other audio while it talks.
 *
 * Engines don't say which voices are male or female, so each voice's pitch is measured once
 * (and remembered). A male or female voice is used when the phone has one; otherwise the voice
 * is resampled to move its formants and pitch, with the engine speaking faster or slower to keep
 * the tempo. Robot styles and gender changes synthesise each sentence to a file, process it,
 * and stream it to an AudioTrack while the next sentence is being prepared.
 */
class Speaker(private val context: Context, private val preferences: AppPreferences) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pitches = context.getSharedPreferences("voice_pitch", Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false
    private var pending: String? = null
    private var pendingThen: (() -> Unit)? = null

    /** The reply the engine is playing itself, and what to do once it has been said. */
    @Volatile
    private var afterReply: Pair<String, () -> Unit>? = null
    private val main = Handler(Looper.getMainLooper())
    private val counter = AtomicInteger()
    private var job: Job? = null

    @Volatile
    private var track: AudioTrack? = null
    private val synthesized = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Voice, pitch and rate are engine-wide: set them and queue the request as one step. */
    private val engineLock = Mutex()
    private var appliedVoice: String? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    /**
     * Says [text], interrupting whatever was being said. [then] runs on the main thread once it
     * has been said (or couldn't be), so a question can be followed by listening for the answer;
     * it doesn't run if something else interrupts it.
     */
    fun speak(text: String, then: (() -> Unit)? = null) {
        if (text.isBlank()) {
            if (then != null) main.post(then)
            return
        }
        val engine = tts ?: start()
        if (!ready) {
            pending = text // spoken as soon as the engine is up
            pendingThen = then
            return
        }
        stopPlayback()
        val settings = preferences.settings.value
        audio.requestAudioFocus(focus)
        job = scope.launch {
            var handedOff = false
            try {
                handedOff = say(engine, text, settings.voiceStyle, settings.voiceGender == VoiceGender.MALE, settings.systemVoice, then)
            } finally {
                // The engine's own playback gives the focus back when it finishes (onDone).
                if (!handedOff) {
                    audio.abandonAudioFocusRequest(focus)
                    if (then != null && isActive) main.post(then)
                }
            }
        }
    }

    /**
     * Connects to the speech engine ahead of time. Binding takes a moment on a cold start; doing
     * it when the overlay opens means a reply can start the instant a command runs, even though
     * the overlay closes right after (and Android soon freezes a backgrounded app).
     */
    fun warmUp() {
        if (tts == null) start()
    }

    fun stop() {
        pending = null
        pendingThen = null
        stopPlayback()
        audio.abandonAudioFocusRequest(focus)
    }

    /** A short robotic "bee-boop": the assistant heard its name. */
    fun chirp() {
        scope.launch {
            val samples = RobotVoice.chirp(CHIRP_RATE)
            val chirp = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format(CHIRP_RATE))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
            }.getOrNull() ?: return@launch
            try {
                chirp.write(samples, 0, samples.size)
                chirp.play()
                delay(samples.size * 1000L / CHIRP_RATE + 60)
            } finally {
                runCatching { chirp.stop() }
                chirp.release()
            }
        }
    }

    /** Installed voices for the assistant's language, for the voice picker. Waits for the engine. */
    suspend fun voices(): List<VoiceOption> {
        val engine = awaitEngine() ?: return emptyList()
        val language = AppLanguage.locale.language
        return installedVoices(engine)
            .filter { it.locale.language == language }
            .sortedWith(compareBy<Voice>({ it.locale.toLanguageTag() }, { it.name }))
            .map { VoiceOption(it.name, it.locale, knownPitch(it.name)) }
    }

    /** Measures the voices whose pitch isn't known yet, reporting the list after each one. */
    suspend fun measureVoices(onUpdate: (List<VoiceOption>) -> Unit) {
        val engine = awaitEngine() ?: return
        val language = AppLanguage.locale.language
        val all = installedVoices(engine).filter { it.locale.language == language }
        for (voice in all) {
            if (knownPitch(voice.name) != null) continue
            pitchOf(engine, voice)
            onUpdate(voices())
        }
    }

    private suspend fun awaitEngine(): TextToSpeech? {
        warmUp()
        withTimeoutOrNull(4_000) { while (!ready) delay(50) } ?: return null
        return tts
    }

    private fun stopPlayback() {
        afterReply = null
        job?.cancel()
        job = null
        track?.let { runCatching { it.pause(); it.flush() } }
        tts?.stop()
    }

    // region Choosing and shaping the voice

    /** Speaks [text]; true when the engine plays it directly (and will release the audio focus). */
    private suspend fun say(engine: TextToSpeech, text: String, style: VoiceStyle, male: Boolean, chosen: String?, then: (() -> Unit)?): Boolean {
        val voice = pickVoice(engine, style, male, chosen)
        val shift = Pitch.shiftFor(voice?.let { pitchOf(engine, it) }, male)
        val pitch = style.pitch * shift.pitch
        val rate = style.rate * shift.factor
        val effect = style.effect
        if (effect == null && !shift.changesSound) {
            engineLock.withLock {
                apply(engine, voice, pitch, rate)
                val id = "reply-${counter.getAndIncrement()}"
                afterReply = then?.let { id to it }
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            }
            return true
        }
        speakProcessed(engine, text, voice, pitch, rate, shift.factor, effect)
        return false
    }

    /**
     * The user's pick if they made one; otherwise the best installed voice for the language,
     * preferring one already known to be the wanted gender (it then needs no reshaping).
     */
    private fun pickVoice(engine: TextToSpeech, style: VoiceStyle, male: Boolean, chosen: String?): Voice? {
        val locale = AppLanguage.locale
        val candidates = installedVoices(engine).filter { it.locale.language == locale.language }
        candidates.firstOrNull { it.name == chosen }?.let { return it }
        val ranked = candidates.sortedWith(
            compareByDescending<Voice> { style.prefersBritish && it.locale.country == "GB" }
                .thenByDescending { it.locale.country == locale.country }
                .thenByDescending { it.quality },
        )
        return ranked.firstOrNull { voice -> knownPitch(voice.name)?.let { (it < Pitch.MALE_BELOW) == male } == true }
            ?: ranked.firstOrNull()
    }

    private fun knownPitch(name: String): Float? = pitches.getFloat(name, -1f).takeIf { it > 0f }

    /** The voice's speaking pitch: remembered, or measured once from a short calibration line. */
    private suspend fun pitchOf(engine: TextToSpeech, voice: Voice): Float? {
        knownPitch(voice.name)?.let { return it }
        val line = AppLanguage.wrap(context).getString(R.string.voice_calibration)
        val pcm = synthesize(engine, line, voice, pitch = 1f, rate = 1f) ?: return null
        val measured = Pitch.median(pcm.samples, pcm.rate) ?: return null
        pitches.edit { putFloat(voice.name, measured) }
        return measured
    }

    private fun apply(engine: TextToSpeech, voice: Voice?, pitch: Float, rate: Float) {
        if (voice != null) {
            if (voice.name != appliedVoice) {
                runCatching { engine.voice = voice }
                appliedVoice = voice.name
            }
        } else {
            val locale = AppLanguage.locale
            runCatching { if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale }
            appliedVoice = null
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
    }

    // endregion

    // region Processed speech

    private suspend fun speakProcessed(
        engine: TextToSpeech,
        text: String,
        voice: Voice?,
        pitch: Float,
        rate: Float,
        stretch: Float,
        effect: RobotEffect?,
    ) = coroutineScope {
        val chunks = Channel<Pcm>(capacity = 2)
        launch {
            try {
                for (sentence in sentences(text)) {
                    val pcm = synthesize(engine, sentence, voice, pitch, rate) ?: continue
                    var samples = Pitch.resample(pcm.samples, stretch)
                    if (effect != null) samples = RobotVoice.process(samples, pcm.rate, effect)
                    chunks.send(Pcm(samples, pcm.rate))
                }
            } finally {
                chunks.close()
            }
        }
        launch(Dispatchers.IO) { play(chunks) }
    }

    private suspend fun synthesize(engine: TextToSpeech, text: String, voice: Voice?, pitch: Float, rate: Float): Pcm? {
        val id = "chunk-${counter.getAndIncrement()}"
        val file = File(context.cacheDir, "tts-$id.wav")
        val done = CompletableDeferred<Boolean>()
        synthesized[id] = done
        try {
            val queued = engineLock.withLock {
                apply(engine, voice, pitch, rate)
                engine.synthesizeToFile(text, Bundle(), file, id)
            }
            if (queued != TextToSpeech.SUCCESS) return null
            if (withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) { done.await() } != true) return null
            return Wav.parse(file.readBytes())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Synthesis failed", e)
            return null
        } finally {
            synthesized.remove(id)
            file.delete()
        }
    }

    private suspend fun play(chunks: ReceiveChannel<Pcm>) {
        var current: AudioTrack? = null
        var frames = 0L
        var rate = 0
        try {
            for (pcm in chunks) {
                val output = current ?: newTrack(pcm.rate).also {
                    current = it
                    track = it
                    rate = pcm.rate
                    it.play()
                }
                var offset = 0
                while (offset < pcm.samples.size) {
                    coroutineContext.ensureActive()
                    val written = output.write(pcm.samples, offset, pcm.samples.size - offset)
                    if (written <= 0) return
                    offset += written
                }
                frames += pcm.samples.size
            }
            // Let the last sentence finish before giving the audio focus back.
            val output = current ?: return
            val deadline = System.currentTimeMillis() + frames * 1000 / rate.coerceAtLeast(1) + 1_000
            while (output.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) delay(30)
        } finally {
            current?.let {
                runCatching { it.stop() }
                it.release()
            }
            if (track === current) track = null
        }
    }

    private fun newTrack(rate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format(rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, rate)) // about half a second
            .build()
    }

    private fun format(rate: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    // endregion

    private fun start(): TextToSpeech {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Text-to-speech unavailable ($status)")
                return@TextToSpeech
            }
            ready = true
            val queued = pending
            val then = pendingThen
            pending = null
            pendingThen = null
            if (queued != null) {
                speak(queued, then)
            } else {
                // Measure the voice ahead of the first reply, so that reply isn't delayed by it.
                val settings = preferences.settings.value
                scope.launch {
                    pickVoice(engine, settings.voiceStyle, settings.voiceGender == VoiceGender.MALE, settings.systemVoice)
                        ?.let { pitchOf(engine, it) }
                }
            }
        }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                val synthesis = utteranceId?.let(synthesized::get)
                if (synthesis != null) synthesis.complete(true) else finishedReply(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                val synthesis = utteranceId?.let(synthesized::get)
                if (synthesis != null) synthesis.complete(false) else finishedReply(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let(synthesized::get)?.complete(false)
            }
        })
        tts = engine
        return engine
    }

    private fun finishedReply(utteranceId: String?) {
        audio.abandonAudioFocusRequest(focus)
        val after = afterReply ?: return
        if (after.first != utteranceId) return
        afterReply = null
        main.post(after.second)
    }

    /** Voices that are on the phone: network voices would send every reply to a server. */
    private fun installedVoices(engine: TextToSpeech): List<Voice> =
        runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired && Voice.QUALITY_VERY_LOW < it.quality }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }

    companion object {
        private const val TAG = "Speaker"
        private const val SYNTHESIS_TIMEOUT_MS = 8_000L
        private const val CHIRP_RATE = 24_000

        /**
         * Splits a reply into sentences, so the first one can play while the next is prepared.
         * Very short pieces are kept together to avoid choppy pauses.
         */
        fun sentences(text: String): List<String> {
            val parts = text.trim().split(Regex("(?<=[.!?;:…])\\s+")).filter { it.isNotBlank() }
            val merged = ArrayList<String>()
            for (part in parts) {
                val last = merged.lastOrNull()
                if (last != null && last.length < MIN_CHUNK) merged[merged.lastIndex] = "$last $part" else merged += part
            }
            return merged
        }

        private const val MIN_CHUNK = 40
    }
}
