package com.bestassistant.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bestassistant.language.AppLanguage
import com.fastsearch.R
import com.fastsearch.assist.AssistActivity
import com.fastsearch.fastSearch
import com.fastsearch.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Shared between the wake-word service, the overlay and settings. */
object WakeControl {

    enum class Status { OFF, LOADING, LISTENING, PAUSED, FAILED }

    /** While the overlay is open it owns the microphone, so the wake word pauses. */
    val overlayOpen = MutableStateFlow(false)

    internal val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Words of the wake phrase the offline model doesn't know (so it could never hear them). */
    internal val _unknownWords = MutableStateFlow(emptyList<String>())
    val unknownWords: StateFlow<List<String>> = _unknownWords.asStateFlow()

    fun hasMicrophone(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Without "display over other apps", Android won't let a background service open the assistant. */
    fun canOpenFromBackground(context: Context) = Settings.canDrawOverlays(context)

    fun shouldRun(context: Context): Boolean {
        val app = context.fastSearch
        val settings = app.preferences.settings.value
        return settings.wakeWord && hasMicrophone(context) && app.wakeModel.isInstalled() &&
            WakePhrase.validate(settings.wakePhrase) == null
    }

    /**
     * Starts or stops the service to match the settings. Call from a visible screen: Android only
     * lets a foreground app start microphone access.
     */
    fun sync(context: Context) {
        val intent = Intent(context, WakeWordService::class.java)
        if (shouldRun(context)) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w("WakeControl", "Couldn't start the wake word", e)
            }
        } else {
            context.stopService(intent)
        }
    }
}

/**
 * Listens for the wake phrase with an offline Vosk model and opens the assistant when it's
 * heard. Audio never leaves this service: it's decoded in memory and discarded.
 *
 * Runs as a microphone foreground service with a persistent notification, and stops
 * listening while the assistant overlay is open (the overlay's own recognizer needs the mic).
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var phrase: List<String> = emptyList()
    private var lastTrigger = 0L
    private var recovery: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val phraseText = fastSearch.preferences.settings.value.wakePhrase
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, listeningNotification(phraseText), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "Not allowed to listen right now", e)
            WakeControl._status.value = WakeControl.Status.FAILED
            stopSelf()
            return
        }
        WakeControl._status.value = WakeControl.Status.LOADING
        scope.launch {
            val directory = fastSearch.wakeModel.directory.path
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    Model(directory)
                }.onFailure { Log.w(TAG, "Model failed to load", it) }.getOrNull()
            }
            if (loaded == null) {
                WakeControl._status.value = WakeControl.Status.FAILED
                stopSelf()
                return@launch
            }
            model = loaded
            combine(
                fastSearch.preferences.settings.map { it.wakePhrase }.distinctUntilChanged(),
                WakeControl.overlayOpen,
            ) { text, open -> text to open }.collect { (text, open) ->
                if (WakePhrase.words(text) != phrase) {
                    phrase = WakePhrase.words(text)
                    WakeControl._unknownWords.value = WakeModel.unknownWords(loaded, phrase)
                    notificationManager().notify(NOTIFICATION_ID, listeningNotification(text))
                }
                if (open) pause() else listen()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            fastSearch.preferences.update { it.copy(wakeWord = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        pause()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        WakeControl._status.value = WakeControl.Status.OFF
        super.onDestroy()
    }

    private fun listen() {
        if (speech != null) return
        val loaded = model ?: return
        recovery?.cancel()
        try {
            val engine = recognizer ?: Recognizer(loaded, SAMPLE_RATE).also { recognizer = it }
            engine.reset()
            val service = SpeechService(engine, SAMPLE_RATE)
            speech = service
            service.startListening(listener)
            WakeControl._status.value = WakeControl.Status.LISTENING
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the microphone", e)
            speech = null
            WakeControl._status.value = WakeControl.Status.FAILED
            retryLater()
        }
    }

    private fun pause() {
        val service = speech ?: return
        speech = null
        runCatching { service.stop() }
        runCatching { service.shutdown() }
        if (WakeControl._status.value == WakeControl.Status.LISTENING) WakeControl._status.value = WakeControl.Status.PAUSED
    }

    /** Another app may hold the microphone (a call, a recording); try again in a while. */
    private fun retryLater() {
        recovery?.cancel()
        recovery = scope.launch {
            delay(RETRY_MS)
            if (!WakeControl.overlayOpen.value) listen()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onError(exception: Exception?) {
            Log.w(TAG, "Recognition stopped", exception)
            pause()
            retryLater()
        }

        override fun onTimeout() = Unit
    }

    private fun check(json: String?, key: String) {
        if (speech == null || json.isNullOrEmpty()) return
        val text = runCatching { JSONObject(json).optString(key) }.getOrNull()
        if (text.isNullOrEmpty()) return
        if (WakePhrase.find(text, phrase) != null) trigger()
    }

    private fun trigger() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        lastTrigger = now
        // Stop before anything else so the overlay's recognizer gets the microphone.
        pause()
        fastSearch.speaker.chirp()
        val open = Intent(this, AssistActivity::class.java)
            .setAction(AssistActivity.ACTION_WAKE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (WakeControl.canOpenFromBackground(this)) {
            runCatching { startActivity(open) }.onFailure { Log.w(TAG, "Couldn't open the assistant", it) }
        } else {
            // Android blocks background activity starts without "display over other apps": offer a tap instead.
            notificationManager().notify(ALERT_ID, tapToTalkNotification(open))
        }
        // If the overlay never takes the microphone, resume listening.
        recovery?.cancel()
        recovery = scope.launch {
            delay(OVERLAY_GRACE_MS)
            if (!WakeControl.overlayOpen.value) listen()
        }
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun strings(): Context = AppLanguage.wrap(this)

    private fun listeningNotification(phrase: String): Notification {
        val text = strings()
        val settings = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val turnOff = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(text.getString(R.string.wake_notification_title, phrase))
            .setContentText(text.getString(R.string.wake_notification_body))
            .setContentIntent(settings)
            .addAction(0, text.getString(R.string.wake_notification_stop), turnOff)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun tapToTalkNotification(open: Intent): Notification {
        val text = strings()
        val tap = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(text.getString(R.string.wake_alert_title))
            .setContentText(text.getString(R.string.wake_alert_body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_TIMEOUT_MS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16_000f
        private const val COOLDOWN_MS = 2_500L
        private const val OVERLAY_GRACE_MS = 6_000L
        private const val RETRY_MS = 15_000L
        private const val ALERT_TIMEOUT_MS = 15_000L
        private const val NOTIFICATION_ID = 41
        private const val ALERT_ID = 42
        const val REMINDER_ID = 43
        private const val ACTION_TURN_OFF = "com.bestassistant.wake.TURN_OFF"
        private const val CHANNEL_LISTENING = "wake_word"
        private const val CHANNEL_ALERT = "wake_alert"
        const val CHANNEL_REMINDER = "wake_reminder"

        fun createChannels(context: Context) {
            val text = AppLanguage.wrap(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_LISTENING, text.getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) },
                    NotificationChannel(CHANNEL_ALERT, text.getString(R.string.wake_alert_channel), NotificationManager.IMPORTANCE_HIGH)
                        .apply { setShowBadge(false) },
                    NotificationChannel(CHANNEL_REMINDER, text.getString(R.string.wake_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { setShowBadge(false) },
                ),
            )
        }
    }
}

/**
 * After a reboot or an update, Android doesn't let an app start listening on the microphone
 * from the background. Post a one-tap reminder that turns the wake word back on.
 */
class WakeRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = context.fastSearch.preferences.settings.value
        if (!settings.wakeWord) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        WakeWordService.createChannels(context)
        val text = AppLanguage.wrap(context)
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WakeWordService.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(text.getString(R.string.wake_reminder_title, settings.wakePhrase))
            .setContentText(text.getString(R.string.wake_reminder_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(WakeWordService.REMINDER_ID, notification)
    }
}
