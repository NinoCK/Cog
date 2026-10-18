package com.bestassistant.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One event on the phone's calendars. A timed event has [start] and [end]; an all-day one has
 * [day] (its first day) and [days] (how many).
 */
@Immutable
data class CalendarEvent(
    val title: String,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val day: LocalDate,
    val days: Int,
    val location: String?,
) {
    val allDay: Boolean get() = start == null
}

/**
 * The calendars on the phone (Google Calendar and any other synced account), through Android's
 * calendar provider: nothing leaves the phone except what the calendar app syncs itself.
 */
class CalendarStore(private val context: Context) {

    fun canRead(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALENDAR) && canRead()

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The events on the [days] days from [from], in order; declined ones and hidden calendars left out. */
    fun events(from: LocalDate, days: Int = 1, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        if (!canRead()) return emptyList()
        val until = from.plusDays(days.toLong())
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.atStartOfDay(zone).toInstant().toEpochMilli()
        // All-day events are stored in UTC: look a day either side and sort them out below.
        val instances = instances(begin - DAY_MS, end + DAY_MS)
        return instances.mapNotNull { row ->
            if (row.allDay) {
                val first = Instant.ofEpochMilli(row.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = Instant.ofEpochMilli(row.end).atZone(ZoneOffset.UTC).toLocalDate() // exclusive
                if (!first.isBefore(until) || !last.isAfter(from)) return@mapNotNull null
                val count = (last.toEpochDay() - first.toEpochDay()).toInt().coerceAtLeast(1)
                CalendarEvent(row.title, null, null, maxOf(first, from), count, row.location)
            } else {
                // Overlapping the days asked about; an event with no length counts where it starts.
                val overlaps = row.begin < end && (row.end > begin || (row.end == row.begin && row.begin >= begin))
                if (!overlaps) return@mapNotNull null
                val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(row.begin), zone)
                val finish = LocalDateTime.ofInstant(Instant.ofEpochMilli(row.end), zone)
                CalendarEvent(row.title, start, finish, maxOf(start.toLocalDate(), from), 1, row.location)
            }
        }.distinctBy { listOf(it.title, it.start, it.day) }
            .sortedWith(compareBy<CalendarEvent>({ it.day }, { !it.allDay }, { it.start }))
    }

    /** The next timed event that hasn't ended yet, within a month. */
    fun next(now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): CalendarEvent? {
        if (!canRead()) return null
        val from = now.atZone(zone).toInstant().toEpochMilli()
        return instances(from, from + NEXT_DAYS * DAY_MS)
            .filter { !it.allDay && it.end > from }
            .minByOrNull { it.begin }
            ?.let { row ->
                val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(row.begin), zone)
                CalendarEvent(row.title, start, LocalDateTime.ofInstant(Instant.ofEpochMilli(row.end), zone), start.toLocalDate(), 1, row.location)
            }
    }

    private class Row(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

    private fun instances(begin: Long, end: Long): List<Row> = runCatching {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION)
        val selection = "${Instances.VISIBLE} = 1 AND ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Row(
                            title = c.getString(0)?.trim().orEmpty().ifEmpty { NO_TITLE },
                            begin = c.getLong(1),
                            end = c.getLong(2),
                            allDay = c.getInt(3) == 1,
                            location = c.getString(4)?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
        }.orEmpty()
    }.onFailure { Log.w(TAG, "Calendar query failed", it) }.getOrDefault(emptyList())

    /**
     * Adds an event to the user's main calendar: at [start] for [minutes], or all day on [day].
     * Returns the calendar's name, or null when there's no calendar to write to.
     */
    fun add(title: String, start: LocalDateTime?, day: LocalDate?, minutes: Int, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (!canWrite()) return null
        val calendar = mainCalendar() ?: return null
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar.first)
            put(Events.TITLE, title)
            if (start != null) {
                val begin = start.atZone(zone).toInstant().toEpochMilli()
                put(Events.DTSTART, begin)
                put(Events.DTEND, begin + minutes * 60_000L)
                put(Events.EVENT_TIMEZONE, zone.id)
            } else {
                val date = day ?: return null
                // All-day events run from UTC midnight to UTC midnight.
                put(Events.DTSTART, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.DTEND, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.ALL_DAY, 1)
                put(Events.EVENT_TIMEZONE, "UTC")
            }
        }
        return runCatching { context.contentResolver.insert(Events.CONTENT_URI, values) }
            .onFailure { Log.w(TAG, "Couldn't add the event", it) }
            .getOrNull()?.let { calendar.second }
    }

    /** The calendar app's own "new event" screen, filled in: for when PocketSearch may not write itself. */
    fun insertIntent(title: String?, start: LocalDateTime?, day: LocalDate?, minutes: Int, zone: ZoneId = ZoneId.systemDefault()): Intent =
        Intent(Intent.ACTION_INSERT).setData(Events.CONTENT_URI).apply {
            title?.let { putExtra(Events.TITLE, it) }
            if (start != null) {
                val begin = start.atZone(zone).toInstant().toEpochMilli()
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + minutes * 60_000L)
            } else if (day != null) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, day.atStartOfDay(zone).toInstant().toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            }
        }

    /**
     * Where new events go: the primary calendar of a Google account if there is one, else any
     * primary calendar, else the first visible one the user may write to. Its id and name.
     */
    private fun mainCalendar(): Pair<Long, String>? = runCatching {
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.IS_PRIMARY, Calendars.ACCOUNT_TYPE)
        val selection = "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}"
        context.contentResolver.query(Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            val all = buildList {
                while (c.moveToNext()) {
                    add(Triple(c.getLong(0) to (c.getString(1) ?: ""), c.getInt(2) == 1, c.getString(3) == GOOGLE))
                }
            }
            (all.firstOrNull { it.second && it.third } ?: all.firstOrNull { it.second } ?: all.firstOrNull { it.third } ?: all.firstOrNull())?.first
        }
    }.onFailure { Log.w(TAG, "Couldn't list calendars", it) }.getOrNull()

    private companion object {
        const val TAG = "Calendar"
        const val DAY_MS = 86_400_000L
        const val NEXT_DAYS = 31
        const val GOOGLE = "com.google"
        const val NO_TITLE = "(No title)"
    }
}
