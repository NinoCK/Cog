package com.bestassistant.commands

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Parts of the day a reminder can be for without a time ("tomorrow morning"), and the hour each means. */
internal enum class DayPart(val hour: Int, val meridiem: Spoken.Meridiem) {
    MORNING(9, Spoken.Meridiem.AM),
    AFTERNOON(15, Spoken.Meridiem.PM),
    EVENING(20, Spoken.Meridiem.PM),
    NIGHT(22, Spoken.Meridiem.PM),
}

/**
 * Turns the pieces of a spoken reminder time, in either language, into a [ReminderTime]: "in 20
 * minutes" ([after]), "at 5" ([clock], the next 5 o'clock), "tomorrow at 9" ([day] and [clock]),
 * "tonight" ([part]). Null when nothing says when.
 */
internal object ReminderTimes {

    fun build(now: LocalDateTime, after: Int?, day: LocalDate?, clock: Spoken.ClockTime?, part: DayPart?): ReminderTime? {
        if (after != null) return ReminderTime.After(after)
        val today = now.toLocalDate()
        if (clock != null) {
            val hinted = if (clock.meridiem == null && part != null && clock.hour in 1..12) clock.copy(meridiem = part.meridiem) else clock
            if (day == null || day == today) {
                // The next time the clock shows it: "at 5" in the morning is this afternoon.
                val time = hinted.resolve(now.toLocalTime())
                val date = if (time.isAfter(now.toLocalTime())) today else today.plusDays(1)
                return ReminderTime.At(date, time.hour, time.minute)
            }
            return ReminderTime.At(day, hourOnAnotherDay(hinted), hinted.minute)
        }
        if (part != null) {
            val date = day ?: today
            val at = date.atTime(part.hour, 0)
            // "This morning" when it's already afternoon: the next morning.
            return ReminderTime.At(if (at.isAfter(now)) date else date.plusDays(1), part.hour, 0)
        }
        // A day without a time: the morning of that day.
        if (day != null && day.isAfter(today)) return ReminderTime.At(day, DayPart.MORNING.hour, 0)
        return null
    }

    /** "Tomorrow at 5" is in the afternoon, "tomorrow at 8" in the morning, unless the user said. */
    private fun hourOnAnotherDay(time: Spoken.ClockTime): Int = when {
        time.meridiem == Spoken.Meridiem.AM -> time.hour % 12
        time.meridiem == Spoken.Meridiem.PM -> time.hour % 12 + 12
        time.hour == 0 || time.hour >= 12 -> time.hour
        time.hour <= LAST_AFTERNOON_HOUR -> time.hour + 12
        else -> time.hour
    }

    /** The next [day] from [today]: today itself only when [thisWeek] ("this saturday"). */
    fun next(today: LocalDate, day: DayOfWeek, thisWeek: Boolean): LocalDate {
        val ahead = (day.value - today.dayOfWeek.value).mod(DAYS_PER_WEEK)
        return today.plusDays(if (ahead == 0 && !thisWeek) DAYS_PER_WEEK.toLong() else ahead.toLong())
    }

    private const val LAST_AFTERNOON_HOUR = 6
    private const val DAYS_PER_WEEK = 7
}
