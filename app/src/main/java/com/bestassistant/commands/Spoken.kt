package com.bestassistant.commands

import java.time.LocalTime

/**
 * Parsing for the pieces commands are built from (numbers, durations, clock times), written
 * the way people type and the way speech recognizers transcribe them.
 */
internal object Spoken {

    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /** Replaces number words with digits: "twenty-five minutes" -> "25 minutes". */
    fun digitize(text: String): String {
        val words = text.replace('-', ' ').split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val ten = tens[word]
            val unit = units[word]
            when {
                ten != null -> {
                    val next = words.getOrNull(i + 1)?.let { units[it] }
                    if (next != null && next in 1..9) {
                        out += (ten + next).toString()
                        i++
                    } else {
                        out += ten.toString()
                    }
                }
                unit != null -> out += unit.toString()
                else -> out += word
            }
            i++
        }
        return out.joinToString(" ")
    }

    private val number = "\\d+(?:[.,]\\d+)?"
    private val unitPattern = "hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s"
    private val durationPart = Regex("($number) ?($unitPattern)(?![a-z])( and a half)?")
    private val compact = Regex("^(\\d+)h(\\d{1,2})m?$")

    /**
     * A duration in seconds: "5 min", "1 hour and 30 minutes", "1h30", "an hour and a half",
     * "half an hour", "90 seconds". Returns null unless the whole text is a duration.
     */
    fun duration(raw: String, bareNumberUnit: Int? = null): Int? {
        val text = digitize(raw.trim())
            .replace(Regex("^(?:a )?half (?:an |a )?hour$"), "30 minutes")
            .replace(Regex("^(?:a )?quarter (?:of )?(?:an )?hour$"), "15 minutes")
            .replace(Regex("\\b(?:a|an) (?=hour|minute|second|min|sec|hr)"), "1 ")
            .replace(Regex("($number) and a half (hours?|minutes?|mins?|hrs?)"), "$1 $2 and a half")
            .trim()
        if (text.isEmpty()) return null

        compact.matchEntire(text)?.let { m ->
            val minutes = m.groupValues[2].toInt()
            if (minutes >= 60) return null
            return m.groupValues[1].toInt() * 3600 + minutes * 60
        }
        if (bareNumberUnit != null && Regex("^$number$").matches(text)) {
            return toSeconds(text, bareNumberUnit)
        }

        var total = 0.0
        var matched = 0
        var position = 0
        while (position < text.length) {
            val m = durationPart.find(text, position) ?: break
            val between = text.substring(position, m.range.first)
            if (between.isNotBlank() && between.trim().trim(',') !in setOf("", "and")) return null
            val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val half = if (m.groupValues[3].isNotEmpty()) 0.5 else 0.0
            total += (amount + half) * unitSeconds(m.groupValues[2])
            matched++
            position = m.range.last + 1
        }
        val rest = text.substring(position).trim()
        if (matched == 0) return null
        if (rest.isNotEmpty()) {
            // "1 hour 30" -> the trailing bare number is in the next-smaller unit.
            val trailing = rest.removePrefix("and ").trim().toIntOrNull() ?: return null
            val lastUnit = durationPart.findAll(text).last().groupValues[2]
            total += trailing * when (unitSeconds(lastUnit)) {
                3600 -> 60
                60 -> 1
                else -> return null
            }
        }
        val seconds = Math.round(total).toInt()
        return seconds.takeIf { it > 0 }
    }

    private fun toSeconds(value: String, unit: Int): Int? =
        value.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * unit).toInt() }?.takeIf { it > 0 }

    private fun unitSeconds(unit: String): Int = when {
        unit.startsWith("h") -> 3600
        unit.startsWith("m") -> 60
        else -> 1
    }

    /** A time of day, possibly ambiguous between morning and evening. */
    data class ClockTime(val hour: Int, val minute: Int, val meridiem: Meridiem?) {
        /** The next occurrence after [now], picking AM or PM when the user didn't say. */
        fun resolve(now: LocalTime): LocalTime {
            val candidates = when {
                meridiem == Meridiem.AM -> listOf(hour % 12)
                meridiem == Meridiem.PM -> listOf(hour % 12 + 12)
                hour == 0 || hour > 12 -> listOf(hour)
                hour == 12 -> listOf(12, 0)
                else -> listOf(hour, hour + 12)
            }
            val nowMinutes = now.hour * 60 + now.minute
            val best = candidates.minBy { h ->
                val diff = (h * 60 + minute - nowMinutes).mod(MINUTES_PER_DAY)
                if (diff == 0) MINUTES_PER_DAY else diff
            }
            return LocalTime.of(best, minute)
        }
    }

    enum class Meridiem { AM, PM }

    private val hhmm = Regex("^(\\d{1,2})(?:[:.h ](\\d{2}))?(?: ?(a\\.?m\\.?|p\\.?m\\.?|a|p))?(?: o'?clock)?$")
    private val fourDigits = Regex("^(\\d{3,4})(?: ?(a\\.?m\\.?|p\\.?m\\.?))?$")
    private val past = Regex("^(half|quarter|\\d{1,2})(?: minutes?)? past (\\d{1,2})(?: ?(a\\.?m\\.?|p\\.?m\\.?))?$")
    private val to = Regex("^(quarter|\\d{1,2})(?: minutes?)? to (\\d{1,2})(?: ?(a\\.?m\\.?|p\\.?m\\.?))?$")

    /** "7", "7:30", "7.30pm", "07:00", "1930", "noon", "half past 7", "quarter to 8 am". */
    fun clock(raw: String): ClockTime? {
        val text = digitize(raw.trim())
        when (text) {
            "noon", "midday" -> return ClockTime(12, 0, Meridiem.PM)
            "midnight" -> return ClockTime(0, 0, null)
        }
        hhmm.matchEntire(text)?.let { m ->
            val hourText = m.groupValues[1]
            val hour = hourText.toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            val meridiem = meridiem(m.groupValues[3])
            // A leading zero ("07:00") means the user is writing 24-hour time.
            val explicit24 = hourText.length == 2 && hourText.startsWith("0")
            return build(hour, minute, meridiem, explicit24)
        }
        fourDigits.matchEntire(text)?.let { m ->
            val digits = m.groupValues[1]
            val hour = digits.dropLast(2).toInt()
            val minute = digits.takeLast(2).toInt()
            return build(hour, minute, meridiem(m.groupValues[2]), explicit24 = digits.length == 4)
        }
        past.matchEntire(text)?.let { m ->
            val minutes = when (m.groupValues[1]) {
                "half" -> 30
                "quarter" -> 15
                else -> m.groupValues[1].toInt()
            }
            return build(m.groupValues[2].toInt(), minutes, meridiem(m.groupValues[3]), false)
        }
        to.matchEntire(text)?.let { m ->
            val minutes = if (m.groupValues[1] == "quarter") 15 else m.groupValues[1].toInt()
            if (minutes !in 1..59) return null
            val hour = m.groupValues[2].toInt()
            val previous = if (hour == 0) 23 else hour - 1
            return build(previous, 60 - minutes, meridiem(m.groupValues[3]), false)
        }
        return null
    }

    private fun meridiem(text: String): Meridiem? = when {
        text.startsWith("a") -> Meridiem.AM
        text.startsWith("p") -> Meridiem.PM
        else -> null
    }

    private fun build(hour: Int, minute: Int, meridiem: Meridiem?, explicit24: Boolean): ClockTime? {
        if (minute !in 0..59) return null
        return when {
            meridiem != null -> if (hour in 1..12) ClockTime(hour, minute, meridiem) else null
            hour !in 0..23 -> null
            // 24-hour notation is never ambiguous.
            explicit24 || hour == 0 || hour > 12 -> ClockTime(hour, minute, if (hour >= 12) Meridiem.PM else Meridiem.AM)
            else -> ClockTime(hour, minute, null)
        }
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
