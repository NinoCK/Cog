package com.bestassistant.wake

import android.content.Context
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The offline speech model the wake word runs on: Vosk's small English model (Apache 2.0),
 * downloaded once from alphacephei.com and checked against a known SHA-256 before it's used.
 * Nothing is uploaded; the download is the only network traffic.
 */
class WakeModel(private val context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data object Failed : State
    }

    val directory: File get() = File(context.noBackupFilesDir, DIRECTORY)

    private val _state = MutableStateFlow(if (isInstalled()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun isInstalled(): Boolean = File(directory, "am/final.mdl").isFile && File(directory, "conf/model.conf").isFile

    fun download() {
        if (job?.isActive == true || isInstalled()) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = State.Downloading(0f)
            val archive = File(context.cacheDir, "wake-model.zip")
            val staging = File(context.noBackupFilesDir, "$DIRECTORY.partial")
            try {
                fetch(archive)
                ensureActive()
                if (sha256(archive) != SHA256) error("Checksum mismatch")
                staging.deleteRecursively()
                unzip(archive, staging)
                directory.deleteRecursively()
                if (!staging.renameTo(directory)) error("Couldn't install the model")
                _state.value = State.Ready
            } catch (e: Exception) {
                Log.w(TAG, "Model download failed", e)
                staging.deleteRecursively()
                _state.value = if (isInstalled()) State.Ready else State.Failed
            } finally {
                archive.delete()
            }
        }
    }

    fun delete() {
        job?.cancel()
        directory.deleteRecursively()
        _state.value = State.Missing
    }

    private suspend fun fetch(target: File) {
        val connection = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: SIZE_BYTES
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = -1
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = (done * 100 / total).toInt()
                        if (percent != reported) {
                            reported = percent
                            _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Unpacks the archive's single top-level folder into [target], refusing paths that escape it. */
    private fun unzip(archive: File, target: File) {
        val root = target.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val file = File(root, relative).canonicalFile
                if (!file.path.startsWith(root.path + File.separator)) error("Bad entry ${entry.name}")
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    private fun sha256(file: File): String {
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

    companion object {
        private const val TAG = "WakeModel"
        private const val DIRECTORY = "vosk-model-small-en-us"
        private const val URL_MODEL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        const val SIZE_BYTES = 41_205_931L

        /** Words of [phrase] the model can't recognise (it only knows its own dictionary). */
        fun unknownWords(model: Model, phrase: List<String>): List<String> {
            val lib = runCatching { Native.load("vosk", VoskWords::class.java) }.getOrNull() ?: return emptyList()
            return phrase.filter { word -> runCatching { lib.vosk_model_find_word(model.pointer, word) < 0 }.getOrDefault(false) }
        }
    }

    /** vosk_model_find_word isn't in the Java bindings; call the native library directly. */
    @Suppress("FunctionName")
    internal interface VoskWords : Library {
        fun vosk_model_find_word(model: Pointer, word: String): Int
    }
}
