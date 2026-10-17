package com.bestassistant.device

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.fastsearch.R
import java.util.Locale

/** Times and durations formatted the way the user's clock shows them (12 or 24 hour). */
object TimeText {

    fun clock(context: Context, hour: Int, minute: Int): String =
        LocalTime.of(hour, minute).format(timeFormatter(context))

    fun clock(context: Context, time: ZonedDateTime): String = time.format(timeFormatter(context))

    fun date(time: ZonedDateTime, locale: Locale = Locale.getDefault()): String =
        time.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale))

    private fun timeFormatter(context: Context): DateTimeFormatter {
        val locale = Locale.getDefault()
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** For reading aloud, in the assistant's language: "5 minutes", "1 hour and 30 minutes", "45 seconds". */
    fun spokenDuration(context: Context, totalSeconds: Int): String {
        val r = context.resources
        return spokenDuration(
            totalSeconds,
            and = context.getString(R.string.say_and),
            hours = { r.getQuantityString(R.plurals.spoken_hours, it, it) },
            minutes = { r.getQuantityString(R.plurals.spoken_minutes, it, it) },
            seconds = { r.getQuantityString(R.plurals.spoken_seconds, it, it) },
        )
    }

    /** Joins the parts of a duration: "2 hours, 1 minute and 5 seconds". */
    fun spokenDuration(
        totalSeconds: Int,
        and: String,
        hours: (Int) -> String,
        minutes: (Int) -> String,
        seconds: (Int) -> String,
    ): String {
        val h = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val sec = totalSeconds % 60
        val parts = buildList {
            if (h > 0) add(hours(h))
            if (m > 0) add(minutes(m))
            if (sec > 0) add(seconds(sec))
        }
        return when (parts.size) {
            0 -> seconds(0)
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " $and " + parts.last()
        }
    }

    /** "5 min", "1 h 30 min", "45 s" (or the assistant language's abbreviations). */
    fun duration(context: Context, totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (hours > 0) add(context.getString(R.string.duration_h, hours))
            if (minutes > 0) add(context.getString(R.string.duration_min, minutes))
            if (seconds > 0) add(context.getString(R.string.duration_s, seconds))
        }.joinToString(" ")
    }
}
