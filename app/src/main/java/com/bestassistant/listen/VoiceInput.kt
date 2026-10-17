package com.bestassistant.listen

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/** What the microphone button shows. */
sealed interface ListenState {
    data object Idle : ListenState

    /** Recording; [level] is the input loudness, 0..1, for the pulse animation. */
    data class Listening(val level: Float, val heard: Boolean) : ListenState

    /** Speech ended; waiting for the final transcript. */
    data object Processing : ListenState
}

/**
 * Speech to text with the phone's recognition service, in-process: no popup, just callbacks.
 * The overlay shows the live transcript in the search bar and pulses the mic with the voice.
 *
 * Must be used from the main thread.
 */
class VoiceInput(
    private val context: Context,
    private val onState: (ListenState) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    /** Recognition ended without words (silence, no match, or an error); [code] is a SpeechRecognizer error. */
    private val onNothing: (code: Int) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var heard = false
    private var lastLevel = 0f
    private var active = false

    val isActive: Boolean get() = active

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Held while listening. A transient, exclusive focus makes music players pause rather than
     * duck, and they resume by themselves when it's given back, but only if they were playing.
     */
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    fun start(locale: Locale) {
        cancel()
        val engine = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: run {
            onNothing(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        recognizer = engine
        heard = false
        active = true
        audio.requestAudioFocus(focus)
        engine.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep speech on the device when the recognizer has an offline model.
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        onState(ListenState.Listening(0f, heard = false))
        try {
            engine.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            finish()
            onNothing(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** Stops recording and delivers what was heard so far. */
    fun stop() {
        if (!active) return
        recognizer?.stopListening()
    }

    /** Stops without delivering anything (e.g. the user started typing). */
    fun cancel() {
        val engine = recognizer ?: return
        recognizer = null
        active = false
        runCatching { engine.cancel() }
        runCatching { engine.destroy() }
        audio.abandonAudioFocusRequest(focus)
        onState(ListenState.Idle)
    }

    /** Runs before the result is delivered, so a spoken "pause" isn't undone by the music resuming. */
    private fun finish() {
        val engine = recognizer
        recognizer = null
        active = false
        engine?.let { runCatching { it.destroy() } }
        audio.abandonAudioFocusRequest(focus)
        onState(ListenState.Idle)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            heard = true
            onState(ListenState.Listening(lastLevel, heard = true))
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!active) return
            // Recognizers report roughly -2..10 dB; map to 0..1 and smooth a little.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            lastLevel = lastLevel * 0.5f + level * 0.5f
            onState(ListenState.Listening(lastLevel, heard))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (active) onState(ListenState.Processing)
        }

        override fun onError(error: Int) {
            if (!active) return
            finish()
            onNothing(error)
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            finish()
            if (text.isNullOrEmpty()) onNothing(SpeechRecognizer.ERROR_NO_MATCH) else onFinal(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "VoiceInput"
    }
}
