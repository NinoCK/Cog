package com.bestassistant.commands

import com.bestassistant.namedays.NameDayCalendar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Days people name: "Christmas", "March 3rd", "the 3rd of March 2027", "Friday", "Eleni's name
 * day", "in 3 weeks". Where the Greek calendar differs, it wins: Easter is Orthodox Easter, as in
 * the name days.
 */
internal object Dates {

    /**
     * A day the user named. [name] is how to say it ("Christmas", "Eleni's name day"); null for a
     * plain date or a day counted from today, which are said as the date itself.
     */
    class Named(
        val name: String?,
        private val next: (LocalDate) -> LocalDate?,
        private val last: (LocalDate) -> LocalDate? = next,
        /** A holiday or a date, rather than "Friday" or "tomorrow": what "when is ..." can be about. */
        val isOccasion: Boolean = false,
        /** Somebody's name day. */
        val isNameDay: Boolean = false,
    ) {
        /** The day it means, seen from [today]: the next one (today included), or with [past] the last one. */
        fun resolve(today: LocalDate, past: Boolean = false): LocalDate? = if (past) last(today) else next(today)
    }

    private val months = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4, "apr" to 4,
        "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8, "september" to 9,
        "sept" to 9, "sep" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )
    private val monthPattern = months.keys.sortedByDescending { it.length }.joinToString("|")
    private val dayMonth = Regex("^(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? ($monthPattern)(?:,? (\\d{4}))?$")
    private val monthDay = Regex("^(?:on )?($monthPattern) (?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?:,? (\\d{4}))?$")
    private val weekday = Regex("^(?:on )?(?:(next|this|coming|the coming) )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$")
    private val inSome = Regex("^in (\\d+|a|an) (days?|weeks?|months?|years?)$")
    private val someFromNow = Regex("^(\\d+|a|an) (days?|weeks?|months?|years?) from (?:now|today)$")
    private val nameDay = Regex("^(?:the )?(?:name ?day (?:of|for) (.+)|(.+?)(?:'s|s'|')? name ?day)$")

    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
        "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14,
        "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19,
        "twentieth" to 20, "thirtieth" to 30,
    )

    /** "the twenty-third of march" -> "the 23 of march"; spoken numbers become digits too. */
    fun digits(text: String): String {
        val words = Spoken.digitize(text).split(' ')
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val next = words.getOrNull(i + 1)?.let { ordinals[it] }
            when {
                (word == "20" || word == "30") && next != null && next < 10 -> {
                    out += (word.toInt() + next).toString()
                    i++
                }
                word in ordinals -> out += ordinals.getValue(word).toString()
                else -> out += word
            }
            i++
        }
        return out.joinToString(" ")
    }

    /** What a plain-date phrase says, or a holiday, a weekday, a name day, "tomorrow", "in 3 weeks". */
    fun named(raw: String, today: LocalDate): Named? {
        val text = digits(raw.trim().removePrefix("the ").trim())
        if (text.isEmpty()) return null
        relativeDay(text)?.let { offset -> return fixed(today.plusDays(offset)) }
        date(text, today)?.let { return it }
        holiday(text)?.let { return it }
        weekday.matchEntire(text)?.let { m ->
            val day = DayOfWeek.valueOf(m.groupValues[2].uppercase(Locale.ROOT))
            return fixed(ReminderTimes.next(today, day, thisWeek = m.groupValues[1] == "this"))
        }
        if (text in setOf("weekend", "this weekend", "next weekend")) {
            val saturday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            return Named("the weekend", next = { if (it.dayOfWeek == DayOfWeek.SUNDAY) it else saturday }, last = { null })
        }
        (inSome.matchEntire(text) ?: someFromNow.matchEntire(text))?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: 1
            if (count !in 1..MAX_AHEAD) return null
            val date = when (m.groupValues[2].trimEnd('s')) {
                "day" -> today.plusDays(count.toLong())
                "week" -> today.plusWeeks(count.toLong())
                "month" -> today.plusMonths(count.toLong())
                else -> today.plusYears(count.toLong())
            }
            return fixed(date)
        }
        nameDay.matchEntire(text)?.let { m ->
            val who = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
            if (who.isEmpty() || who.split(' ').size > 2 || who in setOf("my", "your", "his", "her", "their", "today's")) return null
            val found = NameDayCalendar.find(who) ?: return null
            val shown = NameDayCalendar.latin(found.name).replaceFirstChar { it.titlecase(Locale.ROOT) }
            return Named(
                "$shown's name day",
                next = { NameDayCalendar.next(found, it).firstOrNull() },
                last = { day -> NameDayCalendar.next(found, day.minusYears(1)).lastOrNull { !it.isAfter(day) } },
                isOccasion = true,
                isNameDay = true,
            )
        }
        return null
    }

    private fun relativeDay(text: String): Long? = when (text) {
        "today", "now" -> 0
        "tomorrow" -> 1
        "the day after tomorrow", "day after tomorrow" -> 2
        "yesterday" -> -1
        "the day before yesterday", "day before yesterday" -> -2
        else -> null
    }

    private fun fixed(date: LocalDate) = Named(null, next = { date })

    /** "March 3rd", "3 March", "the 3rd of March 2027": with a year, that day; without, the next (or last) one. */
    private fun date(text: String, today: LocalDate): Named? {
        val (day, month, year) = dayMonth.matchEntire(text)?.let { m -> Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
            ?: monthDay.matchEntire(text)?.let { m -> Triple(m.groupValues[2], m.groupValues[1], m.groupValues[3]) }
            ?: return null
        val monthNumber = months.getValue(month)
        val dayNumber = day.toInt()
        if (year.isNotEmpty()) {
            val date = runCatching { LocalDate.of(year.toInt(), monthNumber, dayNumber) }.getOrNull() ?: return null
            return Named(null, next = { date }, isOccasion = true)
        }
        // 29 February only in leap years: the next (or last) one there is.
        fun inYear(y: Int) = runCatching { LocalDate.of(y, monthNumber, dayNumber) }.getOrNull()
        if ((2000..2008).none { inYear(it) != null }) return null
        return yearly(null) { inYear(it) }
    }

    /** A day that comes once a year, from [inYear]; null in years it doesn't happen. */
    private fun yearly(name: String?, inYear: (Int) -> LocalDate?): Named = Named(
        name,
        next = { from -> (from.year..from.year + 8).firstNotNullOfOrNull { y -> inYear(y)?.takeIf { !it.isBefore(from) } } },
        last = { from -> (from.year downTo from.year - 8).firstNotNullOfOrNull { y -> inYear(y)?.takeIf { !it.isAfter(from) } } },
        isOccasion = true,
    )

    private class Holiday(val name: String, val day: (Int) -> LocalDate)

    private fun on(month: Int, day: Int): (Int) -> LocalDate = { LocalDate.of(it, month, day) }

    private fun nth(month: Int, weekday: DayOfWeek, n: Int): (Int) -> LocalDate =
        { LocalDate.of(it, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, weekday)) }

    private fun easter(offset: Long): (Int) -> LocalDate = { NameDayCalendar.orthodoxEaster(it).plusDays(offset) }

    private val holidays: Map<String, Holiday> by lazy {
        val list = listOf(
            Holiday("Christmas", on(12, 25)) to listOf("christmas", "christmas day", "xmas"),
            Holiday("Christmas Eve", on(12, 24)) to listOf("christmas eve", "xmas eve"),
            Holiday("Boxing Day", on(12, 26)) to listOf("boxing day"),
            Holiday("New Year's Eve", on(12, 31)) to listOf("new years eve", "new year eve"),
            Holiday("New Year's Day", on(1, 1)) to listOf("new year", "new years", "new years day", "new year day"),
            Holiday("Epiphany", on(1, 6)) to listOf("epiphany", "theophany"),
            Holiday("Valentine's Day", on(2, 14)) to listOf("valentines day", "valentines", "valentine day", "st valentines day", "saint valentines day"),
            Holiday("St Patrick's Day", on(3, 17)) to listOf("st patricks day", "saint patricks day", "patricks day"),
            Holiday("Greek Independence Day", on(3, 25)) to listOf("greek independence day", "independence day in greece"),
            Holiday("April Fools' Day", on(4, 1)) to listOf("april fools day", "april fools", "april fool day"),
            Holiday("May Day", on(5, 1)) to listOf("may day", "labour day in greece"),
            Holiday("the Assumption", on(8, 15)) to listOf("assumption", "assumption day", "the assumption", "dormition", "assumption of mary"),
            Holiday("Ochi Day", on(10, 28)) to listOf("ochi day", "oxi day", "ohi day"),
            Holiday("Halloween", on(10, 31)) to listOf("halloween"),
            Holiday("Easter", easter(0)) to listOf("easter", "easter sunday", "orthodox easter", "greek easter", "pascha"),
            Holiday("Good Friday", easter(-2)) to listOf("good friday", "orthodox good friday"),
            Holiday("Clean Monday", easter(-48)) to listOf("clean monday", "kathara deftera"),
            Holiday("Pentecost", easter(49)) to listOf("pentecost", "orthodox pentecost"),
            Holiday("Catholic Easter", ::westernEaster) to listOf("catholic easter", "western easter"),
            Holiday("Mother's Day", nth(5, DayOfWeek.SUNDAY, 2)) to listOf("mothers day", "mother day"),
            Holiday("Father's Day", nth(6, DayOfWeek.SUNDAY, 3)) to listOf("fathers day", "father day"),
            Holiday("Thanksgiving", nth(11, DayOfWeek.THURSDAY, 4)) to listOf("thanksgiving", "thanksgiving day"),
            Holiday("Black Friday", { y -> nth(11, DayOfWeek.THURSDAY, 4)(y).plusDays(1) }) to listOf("black friday"),
        )
        list.flatMap { (holiday, names) -> names.map { it to holiday } }.toMap()
    }

    private fun holiday(text: String): Named? {
        val key = text.replace("'", "").removePrefix("the ").trim()
        val holiday = holidays[key] ?: return null
        return yearly(holiday.name) { runCatching { holiday.day(it) }.getOrNull() }
    }

    /** Easter in the Gregorian calendar (anonymous Gregorian algorithm). */
    fun westernEaster(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /**
     * Finds a calendar date in [words] ("on March 3rd", "the 3rd of March", "on Christmas Eve"),
     * removes it and returns the day it means from [today] (the next one). Holidays need an "on"
     * before them, so "buy easter eggs" stays what it is.
     */
    fun cut(words: MutableList<String>, today: LocalDate): LocalDate? {
        for (length in minOf(words.size, 6) downTo 1) {
            for (start in 0..words.size - length) {
                val span = words.subList(start, start + length).joinToString(" ")
                val spanText = digits(span)
                val explicit = date(spanText.removePrefix("on "), today)
                val holiday = if (explicit == null && span.startsWith("on ")) holiday(spanText.removePrefix("on ")) else null
                val found = (explicit ?: holiday)?.resolve(today) ?: continue
                repeat(length) { words.removeAt(start) }
                // "on" just before a bare date: "on the 3rd of march" already took it.
                if (start > 0 && words.getOrNull(start - 1) == "on") words.removeAt(start - 1)
                return found
            }
        }
        return null
    }

    private const val MAX_AHEAD = 1000
}
