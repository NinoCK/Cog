package com.bestassistant.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.bestassistant.language.AppLanguage
import com.fastsearch.R
import com.fastsearch.fastSearch
import org.json.JSONArray
import org.json.JSONObject

/** One reminder: when it goes off ([at], in epoch milliseconds) and what it says ([text], null: just "Reminder"). */
data class Reminder(val id: Int, val at: Long, val text: String?)

/**
 * The reminders the user asked for, kept on the phone only. Each is an AlarmManager alarm that
 * shows a notification at its time; they're also stored, so [ReminderReceiver] can set them again
 * after a restart (Android forgets alarms then).
 */
class Reminders(private val context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Reminders still to come, soonest first. */
    fun upcoming(now: Long = System.currentTimeMillis()): List<Reminder> = read().filter { it.at > now }.sortedBy { it.at }

    /** False when the user turned this app's notifications off: a reminder would never show. */
    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    @Synchronized
    fun add(at: Long, text: String?): Reminder {
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        val reminder = Reminder(id, at, text)
        write(read() + reminder, nextId = id + 1)
        schedule(reminder)
        return reminder
    }

    /** Cancels every reminder still to come; returns how many there were. */
    @Synchronized
    fun cancelAll(): Int {
        val all = read()
        for (reminder in all) alarms.cancel(alarmIntent(reminder))
        write(emptyList())
        return all.count { it.at > System.currentTimeMillis() }
    }

    /** It's time: show it, and forget it. */
    @Synchronized
    internal fun fire(id: Int) {
        val reminder = read().firstOrNull { it.id == id } ?: return
        write(read().filter { it.id != id })
        show(reminder)
    }

    /** After a restart: set every alarm again, and show the ones that came due while the phone was off. */
    @Synchronized
    internal fun restore() {
        val now = System.currentTimeMillis()
        val (due, coming) = read().partition { it.at <= now }
        write(coming)
        due.forEach(::show)
        coming.forEach(::schedule)
    }

    internal fun snooze(text: String?) {
        add(System.currentTimeMillis() + SNOOZE_MS, text)
    }

    private fun schedule(reminder: Reminder) {
        val intent = alarmIntent(reminder)
        // Exact alarms are granted to reminder apps; without them Android may deliver a few minutes late.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, intent)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, intent)
        }
    }

    private fun alarmIntent(reminder: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminder.id,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, reminder.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun show(reminder: Reminder) {
        if (!canNotify()) return
        val text = AppLanguage.wrap(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, text.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val notificationId = NOTIFICATION_BASE + reminder.id
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            context,
            notificationId * 4 + code,
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(EXTRA_NOTIFICATION, notificationId)
                .putExtra(EXTRA_TEXT, reminder.text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val done = action(ACTION_DONE, 1)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(reminder.text ?: text.getString(R.string.reminder_title))
            .apply { if (reminder.text != null) setContentText(text.getString(R.string.reminder_title)) }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(reminder.at)
            .setShowWhen(true)
            .setContentIntent(done)
            .setAutoCancel(true)
            .addAction(0, text.getString(R.string.reminder_done), done)
            .addAction(0, text.getString(R.string.reminder_snooze), action(ACTION_SNOOZE, 2))
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    private fun read(): List<Reminder> {
        val raw = prefs.getString(KEY_ALL, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Reminder(item.getInt("id"), item.getLong("at"), item.optString("text").takeIf { item.has("text") && it.isNotEmpty() })
            }
        }.getOrDefault(emptyList())
    }

    private fun write(reminders: List<Reminder>, nextId: Int? = null) {
        val array = JSONArray()
        for (reminder in reminders) {
            array.put(JSONObject().put("id", reminder.id).put("at", reminder.at).apply { reminder.text?.let { put("text", it) } })
        }
        prefs.edit {
            putString(KEY_ALL, array.toString())
            nextId?.let { putInt(KEY_NEXT_ID, it) }
        }
    }

    internal companion object {
        const val ACTION_FIRE = "com.bestassistant.reminders.FIRE"
        const val ACTION_DONE = "com.bestassistant.reminders.DONE"
        const val ACTION_SNOOZE = "com.bestassistant.reminders.SNOOZE"
        const val EXTRA_ID = "id"
        const val EXTRA_NOTIFICATION = "notification"
        const val EXTRA_TEXT = "text"
        private const val FILE = "reminders"
        private const val KEY_ALL = "all"
        private const val KEY_NEXT_ID = "next_id"
        private const val CHANNEL = "reminders"
        private const val NOTIFICATION_BASE = 1000
        private const val SNOOZE_MS = 10 * 60 * 1000L
    }
}

/** A reminder's alarm going off, its notification's buttons, and setting them all again after a restart. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = context.fastSearch.reminders
        when (intent.action) {
            Reminders.ACTION_FIRE -> reminders.fire(intent.getIntExtra(Reminders.EXTRA_ID, -1))
            Reminders.ACTION_DONE, Reminders.ACTION_SNOOZE -> {
                context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(Reminders.EXTRA_NOTIFICATION, -1))
                if (intent.action == Reminders.ACTION_SNOOZE) reminders.snooze(intent.getStringExtra(Reminders.EXTRA_TEXT))
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                reminders.restore()
        }
    }
}
