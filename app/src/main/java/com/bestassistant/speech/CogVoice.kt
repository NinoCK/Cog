package com.bestassistant.speech

import android.content.Context
import android.util.Log
import com.fastsearch.BuildConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cog's own voice: a Piper (VITS) model trained on the PC (see `voice/` in the project), run
 * entirely on the phone by sherpa-onnx. English only.
 *
 * The model is read from the APK's assets; espeak-ng's pronunciation data has to be a real
 * directory, so it's copied out of the assets once per app version. Loading takes a moment,
 * so [warmUp] when a reply is likely.
 */
class CogVoice(private val context: Context) {

    private val lock = Mutex()
    private var engine: OfflineTts? = null
    private var failed = false

    /** Whether this build carries the model at all. */
    val installed: Boolean by lazy {
        runCatching { context.assets.list(DIR)?.contains(MODEL) == true }.getOrDefault(false)
    }

    /** Loads the model if it isn't yet; false when it can't be used. */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) { lock.withLock { load() != null } }

    /** Speaks [text] into samples, or null when the model can't be used. */
    suspend fun synthesize(text: String): Pcm? = withContext(Dispatchers.Default) {
        lock.withLock {
            val tts = load() ?: return@withLock null
            val audio = runCatching { tts.generate(text, 0, SPEED) }
                .onFailure { Log.w(TAG, "Synthesis failed", it) }
                .getOrNull() ?: return@withLock null
            Pcm(toPcm16(audio.samples), audio.sampleRate)
        }
    }

    private fun load(): OfflineTts? {
        engine?.let { return it }
        if (failed || !installed) return null
        return runCatching {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$DIR/$MODEL",
                        tokens = "$DIR/tokens.txt",
                        dataDir = espeakData().absolutePath,
                    ),
                    numThreads = 2,
                ),
            )
            OfflineTts(context.assets, config)
        }.onFailure {
            Log.w(TAG, "Could not load the Cog voice", it)
            failed = true
        }.getOrNull().also { engine = it }
    }

    /** espeak-ng-data, copied from the assets the first time this app version runs. */
    private fun espeakData(): File {
        val target = File(context.filesDir, "espeak-ng-data")
        val stamp = File(target, ".version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (stamp.isFile && stamp.readText() == version) return target
        target.deleteRecursively()
        copyAssets("$DIR/espeak-ng-data", target)
        stamp.writeText(version)
        return target
    }

    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssets("$path/$child", File(target, child))
    }

    companion object {
        private const val TAG = "CogVoice"
        private const val DIR = "cog"
        private const val MODEL = "cog.onnx"

        /** Slightly unhurried: below 1 is slower. */
        private const val SPEED = 0.95f

        fun toPcm16(samples: FloatArray): ShortArray =
            ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }
}
