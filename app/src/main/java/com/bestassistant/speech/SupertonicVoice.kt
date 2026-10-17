package com.bestassistant.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Supertonic 3 (Supertone, OpenRAIL), a natural voice that speaks both English and Greek, run on
 * the phone by sherpa-onnx. The int8 model (about 145 MB) isn't in the APK: it's downloaded once
 * on request from sherpa-onnx's own Hugging Face copy, pinned to one commit, and every file is
 * checked against a known SHA-256 before it's used. Nothing is uploaded; replies never leave
 * the phone.
 *
 * Until the model is installed (or if it can't be loaded), [synthesize] returns null and the
 * phone's own voice is used.
 */
class SupertonicVoice(private val context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data object Failed : State
    }

    private val directory: File get() = File(context.noBackupFilesDir, DIRECTORY)

    private val _state = MutableStateFlow(if (isInstalled()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private val lock = Mutex()
    private var engine: OfflineTts? = null

    @Volatile
    private var failed = false

    fun isInstalled(): Boolean = FILES.all { File(directory, it.name).length() == it.size }

    /** Installed, and not known to fail loading on this phone. */
    fun isUsable(): Boolean = !failed && isInstalled()

    fun download() {
        if (job?.isActive == true || isInstalled()) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = State.Downloading(0f)
            val staging = File(context.noBackupFilesDir, "$DIRECTORY.partial")
            try {
                staging.mkdirs()
                var done = 0L
                for (file in FILES) {
                    val target = File(staging, file.name)
                    // A file finished by an earlier, interrupted attempt is kept.
                    if (target.length() != file.size || sha256(target) != file.sha256) {
                        fetch(file, target, done)
                        if (sha256(target) != file.sha256) error("Checksum mismatch for ${file.name}")
                    }
                    done += file.size
                }
                ensureActive()
                lock.withLock {
                    release()
                    directory.deleteRecursively()
                    if (!staging.renameTo(directory)) error("Couldn't install the voice")
                    failed = false
                }
                _state.value = State.Ready
            } catch (e: Exception) {
                Log.w(TAG, "Voice download failed", e)
                _state.value = if (isInstalled()) State.Ready else State.Failed
            }
        }
    }

    fun delete() {
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                release()
                directory.deleteRecursively()
                File(context.noBackupFilesDir, "$DIRECTORY.partial").deleteRecursively()
            }
            _state.value = State.Missing
        }
    }

    /** Loads the model if it isn't yet (a second or two); false when it can't be used. */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) { lock.withLock { load() != null } }

    /** Speaks [text] into samples in [language] ("en", "el"), or null when the voice can't be used. */
    suspend fun synthesize(text: String, language: String, male: Boolean): Pcm? = withContext(Dispatchers.Default) {
        lock.withLock {
            val tts = load() ?: return@withLock null
            val config = GenerationConfig(
                speed = SPEED,
                sid = speakerFor(male),
                numSteps = STEPS,
                extra = mapOf("lang" to language),
            )
            val audio = runCatching { tts.generateWithConfig(text, config) }
                .onFailure { Log.w(TAG, "Synthesis failed", it) }
                .getOrNull()
                ?.takeIf { it.samples.isNotEmpty() } ?: return@withLock null
            Pcm(toPcm16(audio.samples), audio.sampleRate)
        }
    }

    private fun load(): OfflineTts? {
        engine?.let { return it }
        if (failed || !isInstalled()) return null
        return runCatching {
            fun path(name: String) = File(directory, name).absolutePath
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = path("duration_predictor.int8.onnx"),
                        textEncoder = path("text_encoder.int8.onnx"),
                        vectorEstimator = path("vector_estimator.int8.onnx"),
                        vocoder = path("vocoder.int8.onnx"),
                        ttsJson = path("tts.json"),
                        unicodeIndexer = path("unicode_indexer.bin"),
                        voiceStyle = path("voice.bin"),
                    ),
                    numThreads = THREADS,
                ),
            )
            OfflineTts(config = config)
        }.onFailure {
            Log.w(TAG, "Could not load the Supertonic voice", it)
            failed = true
        }.getOrNull().also { engine = it }
    }

    private fun release() {
        engine?.release()
        engine = null
    }

    private suspend fun fetch(file: ModelFile, target: File, before: Long) {
        val connection = (URL("$BASE_URL/${file.name}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} for ${file.name}")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = before
                    var reported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val permille = (done * 1000 / SIZE_BYTES).toInt()
                        if (permille != reported) {
                            reported = permille
                            _state.value = State.Downloading((done.toFloat() / SIZE_BYTES).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal class ModelFile(val name: String, val size: Long, val sha256: String)

    companion object {
        private const val TAG = "SupertonicVoice"
        private const val DIRECTORY = "supertonic-3-int8"
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"

        internal val FILES = listOf(
            ModelFile("tts.json", 8_253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
            ModelFile("unicode_indexer.bin", 262_144, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
            ModelFile("voice.bin", 517_168, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
            ModelFile("duration_predictor.int8.onnx", 3_700_147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
            ModelFile("text_encoder.int8.onnx", 36_416_150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
            ModelFile("vocoder.int8.onnx", 25_991_073, "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
            ModelFile("vector_estimator.int8.onnx", 78_400_833, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        )
        val SIZE_BYTES = FILES.sumOf { it.size }

        /** voice.bin holds the preset voices F1–F5, then M1–M5: M2 (deep) and F2 were picked. */
        private const val MALE_SPEAKER = 6
        private const val FEMALE_SPEAKER = 1
        fun speakerFor(male: Boolean) = if (male) MALE_SPEAKER else FEMALE_SPEAKER

        /** Denoising steps: 8 sounds clean; 4 is twice as fast but can clip. */
        private const val STEPS = 8
        private const val SPEED = 1.05f
        private const val THREADS = 2

        fun toPcm16(samples: FloatArray): ShortArray =
            ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }
}
