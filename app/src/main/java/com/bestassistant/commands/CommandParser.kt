package com.bestassistant.commands

import com.bestassistant.integration.RequestParser
import com.bestassistant.integration.Slot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Turns what the user typed or said into a [Command], or null when it's an ordinary search.
 *
 * Deliberately rule-based: every phrase that works is one a matcher below accepts, so the same
 * words always do the same thing. Matchers are strict about leftover words, so "alarm clock
 * app" or "call of duty" stay searches instead of turning into commands.
 */
object CommandParser {

    /** An installed integration's own requests, found by [parser] and run by the integration with [id]. */
    class Installed(val id: String, val parser: RequestParser)

    /** The installed integrations, asked in this order at their [Slot]s; kept up to date by the app. */
    @Volatile
    var integrations: List<Installed> = emptyList()

    /** A routine the user set up: what it's called, and the [phrases] that run it (already [normalize]d). */
    class RoutineTrigger(val name: String, val phrases: Set<String>)

    /** The user's routines; their phrases come before everything else. Kept up to date by the app. */
    @Volatile
    var routines: List<RoutineTrigger> = emptyList()

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Command? {
        val text = normalize(input)
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        return routine(text)
            ?: youTube(text)
            ?: reminder(text, now)
            ?: parking(text)
            ?: shareLocation(text)
            ?: currency(text)
            ?: conversion(text)
            ?: clockControls(text)
            ?: timer(text)
            ?: alarm(text, now)
            ?: calendar(text, now)
            ?: flashlight(text)
            ?: volume(text)
            ?: phoneSettings(text)
            ?: battery(text)
            ?: media(text)
            ?: integration(text, Slot.EARLY)
            ?: chance(text)
            ?: music(text)
            ?: weather(text)
            ?: clock(text)
            ?: dates(text, now)
            ?: nameDays(text, now)
            ?: calls(text)
            ?: camera(text)
            ?: navigate(text)
            ?: call(text)
            ?: message(text)
            ?: panel(text)
            ?: dictionary(text)
            ?: integration(text, Slot.LATE)
            ?: webSearch(text)
            ?: lookup(text)
            ?: openApp(text)
    }

    private fun routine(text: String): Command? =
        routines.firstOrNull { text in it.phrases }?.let { Command.Routine(it.name) }

    // region Normalisation

    private val politePrefixes = listOf(
        "hey assistant ", "ok assistant ", "ok pocket ", "okay pocket ", "hey pocket ", "pocket ", "hey ", "ok ", "okay ", "please ", "can you ", "could you ",
        "would you ", "will you ", "can u ", "i want to ", "i'd like to ", "i need to ", "go ahead and ",
        "just ", "quickly ",
    )
    private val politeSuffixes = listOf(" please", " for me", " thanks", " thank you", " right now", " now")

    internal fun normalize(input: String): String {
        var text = input.trim().lowercase(Locale.ROOT)
            .replace('’', '\'')
            // Commas between words are punctuation ("hey, set a timer"); inside numbers they're decimals.
            .replace(Regex(",(?=\\s|$)"), " ")
            .replace(Regex("\\s+"), " ")
            .trimEnd('?', '!', '.', ',', ' ')
        var changed = true
        while (changed) {
            changed = false
            for (prefix in politePrefixes) {
                if (text.startsWith(prefix) && text.length > prefix.length) {
                    text = text.removePrefix(prefix).trimStart(',', ' ')
                    changed = true
                }
            }
            for (suffix in politeSuffixes) {
                if (text.endsWith(suffix) && text.length > suffix.length) {
                    text = text.removeSuffix(suffix).trimEnd(',', ' ')
                    changed = true
                }
            }
        }
        return text
    }

    private fun words(text: String) = text.split(' ').filter { it.isNotEmpty() }

    /** True when every word of [text] is in [allowed]; used to reject sentences with extra intent. */
    private fun onlyWords(text: String, allowed: Set<String>) = words(text).all { it in allowed }

    // endregion

    // region Clock app

    private val timerWord = Regex("\\b(?:timer|countdown)s?\\b")
    private val showTimers = Regex("^(?:show |open |check |view |see |list )?(?:my |the |all )?(?:timers?|countdowns?)$")
    private val timerFillers = setOf(
        "set", "start", "create", "make", "put", "begin", "add", "a", "an", "the", "my", "new", "me", "for",
        "of", "on", "to", "and", "with", "called", "named", "labeled", "labelled", "label", "timer", "countdown",
        "minute", "up",
    )

    private fun timer(text: String): Command? {
        if (showTimers.matches(text)) return Command.ShowTimers
        if (!timerWord.containsMatchIn(text)) return null
        val window = longestDuration(words(text)) ?: return null
        val label = leftover(window.rest, timerFillers)
        if (window.seconds > MAX_TIMER_SECONDS) return null
        return Command.SetTimer(window.seconds, label)
    }

    private class DurationWindow(val seconds: Int, val rest: List<String>)

    /** The longest run of words that reads as a duration; the remaining words become the label. */
    private fun longestDuration(words: List<String>): DurationWindow? {
        for (length in words.size downTo 1) {
            for (start in 0..words.size - length) {
                val span = words.subList(start, start + length)
                // A lone number ("timer 5") means minutes; inside longer spans units are required.
                val seconds = Spoken.duration(span.joinToString(" "), bareNumberUnit = if (length == 1) 60 else null)
                    ?: continue
                return DurationWindow(seconds, words.subList(0, start) + words.subList(start + length, words.size))
            }
        }
        return null
    }

    private fun leftover(words: List<String>, fillers: Set<String>): String? =
        words.filter { it !in fillers }.joinToString(" ").trim().takeIf { it.isNotEmpty() }

    private val alarmWord = Regex("\\b(?:alarm|wake me|wake up|get me up)\\b")
    private val showAlarms = Regex("^(?:show |open |check |view |see |list )?(?:my |the |all )?alarms?$")
    private val relativeAlarm = Regex("^.*\\bin (.+)$")
    private val morning = Regex("\\b(?:in the morning|tomorrow morning|this morning|morning)\\b")
    private val evening = Regex("\\b(?:in the evening|this evening|tonight|in the afternoon|this afternoon|at night|evening|afternoon)\\b")
    private val alarmFillers = setOf(
        "set", "an", "a", "the", "my", "new", "alarm", "for", "at", "to", "wake", "me", "up", "get", "tomorrow",
        "today", "called", "named", "labeled", "labelled", "label", "on", "create", "make", "add", "please", "and",
    )

    private fun alarm(text: String, now: LocalDateTime): Command? {
        if (showAlarms.matches(text)) return Command.ShowAlarms
        if (!alarmWord.containsMatchIn(text)) return null

        // "wake me up in 8 hours", "alarm in 20 minutes"
        relativeAlarm.matchEntire(text)?.let { m ->
            Spoken.duration(m.groupValues[1])?.let { seconds ->
                val at = now.plusSeconds(seconds.toLong())
                return Command.SetAlarm(at.hour, at.minute)
            }
        }

        val meridiemHint = when {
            morning.containsMatchIn(text) -> Spoken.Meridiem.AM
            evening.containsMatchIn(text) -> Spoken.Meridiem.PM
            else -> null
        }
        val stripped = text.replace(morning, " ").replace(evening, " ").replace(Regex("\\s+"), " ").trim()
        val words = words(stripped)
        for (length in minOf(words.size, 5) downTo 1) {
            for (start in 0..words.size - length) {
                val time = Spoken.clock(words.subList(start, start + length).joinToString(" ")) ?: continue
                val rest = words.subList(0, start) + words.subList(start + length, words.size)
                // Leftover non-filler words are the label: "alarm 6am gym".
                val label = leftover(rest, alarmFillers)
                val withHint = if (time.meridiem == null && meridiemHint != null && time.hour in 1..12) {
                    time.copy(meridiem = meridiemHint)
                } else {
                    time
                }
                val resolved = withHint.resolve(now.toLocalTime())
                return Command.SetAlarm(resolved.hour, resolved.minute, label)
            }
        }
        return null
    }

    private val alarmControl = Regex(
        "^(stop|dismiss|silence|shut off|shut up|turn off|switch off|cancel|delete|remove|disable|skip|clear|get rid of)" +
            "(?: (.*?))? alarms?(?: clock)?(?: (?:for|at|set for) (.+))?$",
    )
    private val ringingVerbs = setOf("stop", "dismiss", "silence", "shut off", "shut up", "turn off", "switch off")
    private val alarmDeterminers = setOf(
        "the", "my", "this", "that", "a", "an", "of", "set", "for", "at", "tomorrow", "tomorrow's", "tonight", "tonight's", "today",
        "today's", "morning", "ringing", "current", "wake", "up",
    )
    private val snooze = Regex("^snooze(?: (?:the|my|this|that|it))?(?: alarm)?(?: (?:for |by )?(.+))?$")
    private val stopTimer = Regex(
        "^(?:stop|dismiss|silence|cancel|turn off|switch off|end|shut off|kill|delete|remove|clear)" +
            "(?: the| my| this| that| all| all the| all my| all of my)? (?:timers?|countdowns?)(?: that's ringing| ringing)?$",
    )

    /** "cancel my 7am alarm", "stop the alarm", "snooze for 5 minutes", "stop the timer". */
    private fun clockControls(text: String): Command? {
        if (stopTimer.matches(text)) return Command.StopTimer
        snooze.matchEntire(text)?.let { m ->
            val rest = m.groupValues[1]
            if (rest.isEmpty()) return Command.SnoozeAlarm()
            val seconds = Spoken.duration(rest, bareNumberUnit = 60) ?: return null
            return Command.SnoozeAlarm((seconds / 60).coerceIn(1, MAX_SNOOZE_MINUTES))
        }
        val m = alarmControl.matchEntire(text) ?: return null
        val verb = m.groupValues[1]
        val words = words("${m.groupValues[2]} ${m.groupValues[3]}")
        val meridiem = when {
            morning.containsMatchIn(text) -> Spoken.Meridiem.AM
            evening.containsMatchIn(text) -> Spoken.Meridiem.PM
            else -> null
        }
        val rest = words.filter { it !in alarmDeterminers && it !in setOf("in", "evening", "afternoon", "night", "tonight") }
        val target = when {
            "all" in rest || "every" in rest -> AlarmTarget.All
            "next" in rest || "upcoming" in rest -> AlarmTarget.Next
            rest.isEmpty() -> if (verb in ringingVerbs) AlarmTarget.Ringing else AlarmTarget.Next
            else -> {
                val time = Spoken.clock(rest.joinToString(" "))
                when {
                    time == null -> AlarmTarget.Labeled(rest.joinToString(" "))
                    time.meridiem == null && meridiem != null && time.hour in 1..12 -> {
                        val hour = if (meridiem == Spoken.Meridiem.PM) time.hour % 12 + 12 else time.hour % 12
                        AlarmTarget.At(hour, time.minute, meridiem == Spoken.Meridiem.PM)
                    }
                    time.meridiem == null -> AlarmTarget.At(time.hour, time.minute, null)
                    else -> {
                        val pm = time.meridiem == Spoken.Meridiem.PM
                        AlarmTarget.At(if (pm) time.hour % 12 + 12 else time.hour % 12, time.minute, pm)
                    }
                }
            }
        }
        return Command.DismissAlarm(target)
    }

    // endregion

    // region Calendar

    private val agendaAsk = Regex(
        "^(?:what(?:'s| is) (?:on )?|show(?: me)? |read(?: me)? |check |tell me |list |what does |how does |how's |how is )?" +
            "(?:my |the )(?:calendar|schedule|agenda|diary)(?: (?:look|looks) like)?(?: (?:for|on|like))?(?: (.+))?$",
    )
    private val agendaQuestion = Regex(
        "^(?:what (?:do|did) i have|what have i got|what am i doing|what are my plans|what plans do i have|" +
            "do i have (?:anything|any plans|plans)|is there anything|have i got anything|am i (?:free|busy|available))" +
            "(?: (?:on|planned|scheduled|booked|going on))?(?: (.+?))?(?: on my calendar| in my calendar)?$",
    )
    private val agendaMeetings = Regex(
        "^(?:do i have|have i got|are there) (?:any )?(?:meetings|events|appointments)(?: (?:on|planned|scheduled))?(?: (.+?))?(?: on my calendar)?$",
    )
    private val dayLooks = Regex("^(?:what does|how does|how's|how is) (?:my day|(.+?)) (?:look|looks|looking)(?: like)?$")
    private val nextEvent = Regex(
        "^(?:(?:what(?:'s| is)|when(?:'s| is)|where(?:'s| is)|tell me|show me|read me)(?: my| the)? )?next " +
            "(?:meeting|event|appointment|calendar event|thing on my calendar)$|^what(?:'s| is) next on my (?:calendar|schedule|agenda)$|" +
            "^what do i have next$",
    )

    private val eventLead = Regex(
        "^(?:(add|put|schedule|pencil in|block out|block)|(create|make|set up|new|plan|book|organise|organize|arrange))" +
            "(?: (?:a|an|the|my))?(?: new)?(?: (calendar event|calendar entry|event|appointment|meeting|entry))?" +
            "(?: (?:called|named|titled|about|with the title))?(?: (.+))?$",
    )
    private val calendarMention = Regex("(?:^| )(?:to|in|on|into|onto|for) (?:my |the )?(?:calendar|diary|agenda|schedule)(?: |$)")
    private val titleFillers = setOf("a", "an", "the", "my", "to", "for", "about", "called", "named", "on", "at", "and", "in", "from")

    private fun calendar(text: String, now: LocalDateTime): Command? {
        val today = now.toLocalDate()
        if (nextEvent.matches(text)) return Command.NextEvent
        agendaAsk.matchEntire(text)?.let { m -> return agenda(m.groupValues[1], today, required = false) }
        agendaMeetings.matchEntire(text)?.let { m -> return agenda(m.groupValues[1], today, required = false) }
        agendaQuestion.matchEntire(text)?.let { m -> return agenda(m.groupValues[1], today, required = true) }
        dayLooks.matchEntire(text)?.let { m -> return agenda(m.groupValues[1], today, required = m.groupValues[1].isNotEmpty()) }
        if (text in setOf("my calendar", "my schedule", "my agenda", "my day")) return Command.Agenda(today)
        return addEvent(text, now)
    }

    /** What days "today", "tomorrow", "on friday", "this week" are; null when [spec] says no day (and one is [required]). */
    private fun agenda(rawSpec: String, today: LocalDate, required: Boolean): Command.Agenda? {
        val spec = rawSpec.trim().removePrefix("for ").removePrefix("on ").removePrefix("like ").trim()
        if (spec.isEmpty()) return if (required) null else Command.Agenda(today)
        val untilSunday = DayOfWeek.SUNDAY.value - today.dayOfWeek.value + 1
        return when (spec) {
            "today", "this morning", "this afternoon", "this evening", "tonight", "now", "right now" -> Command.Agenda(today)
            "this week", "the week", "the rest of the week", "the rest of this week", "this week so far" -> Command.Agenda(today, untilSunday)
            "next week" -> Command.Agenda(today.plusDays(untilSunday.toLong()), 7)
            "this weekend", "the weekend", "weekend" -> when (today.dayOfWeek) {
                DayOfWeek.SATURDAY -> Command.Agenda(today, 2)
                DayOfWeek.SUNDAY -> Command.Agenda(today, 1)
                else -> Command.Agenda(today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SATURDAY)), 2)
            }
            "the next few days", "next few days", "the coming days" -> Command.Agenda(today, 3)
            else -> {
                val day = Dates.named(spec, today)?.resolve(today) ?: return null
                if (day.isBefore(today.minusDays(1))) null else Command.Agenda(day)
            }
        }
    }

    /** "add dentist tomorrow at 3", "schedule lunch with mom on friday at 1", "create an event". */
    private fun addEvent(text: String, now: LocalDateTime): Command.AddEvent? {
        val m = eventLead.matchEntire(text) ?: return null
        // "add" and "schedule" with a time are events; "put the heating on at 6" is the house's.
        val plainVerb = m.groupValues[1] in setOf("add", "schedule", "pencil in")
        val kind = m.groupValues[3]
        var rest = " ${m.groupValues[4]} "
        val mentioned = calendarMention.containsMatchIn(rest) || kind.startsWith("calendar")
        rest = rest.replace(calendarMention, " ")
        val words = words(rest).toMutableList()
        val minutes = cutDuration(words)
        val time = eventTime(words, now)
        // "create", "make" and "book" need to say it's an event: "book a table for 4 at 8" isn't one.
        if (!plainVerb && kind.isEmpty() && !mentioned) return null
        if (plainVerb && !mentioned && time == null && kind.isEmpty()) return null
        while (words.isNotEmpty() && words.first() in titleFillers) words.removeAt(0)
        while (words.isNotEmpty() && words.last() in titleFillers) words.removeAt(words.lastIndex)
        // A meeting or appointment is part of what it's called: "meeting with alex".
        if (kind == "meeting" || kind == "appointment") words.add(0, kind)
        val title = words.joinToString(" ").replaceFirstChar { it.titlecase(Locale.ROOT) }.takeIf { it.isNotEmpty() }
        return Command.AddEvent(title, time, minutes)
    }

    /** "for an hour", "for 30 minutes": how long the event lasts, removed from [words]. */
    private fun cutDuration(words: MutableList<String>): Int? {
        for (i in words.indices) {
            if (words[i] != "for") continue
            for (end in minOf(words.size, i + 6) downTo i + 2) {
                val seconds = Spoken.duration(words.subList(i + 1, end).joinToString(" ")) ?: continue
                repeat(end - i) { words.removeAt(i) }
                return (seconds / 60).coerceIn(1, MAX_EVENT_MINUTES)
            }
        }
        return null
    }

    /** When an event is: a time on a day, or (with only a day) the whole day. */
    private fun eventTime(words: MutableList<String>, now: LocalDateTime): EventTime? {
        val parts = whenParts(words, now)
        if (parts.after == null && parts.clock == null && parts.part == null) return parts.day?.let { EventTime.AllDay(it) }
        return when (val at = ReminderTimes.build(now, parts.after, parts.day, parts.clock, parts.part)) {
            is ReminderTime.At -> EventTime.At(at.date, at.hour, at.minute)
            is ReminderTime.After -> now.plusSeconds(at.seconds.toLong()).let { EventTime.At(it.toLocalDate(), it.hour, it.minute) }
            null -> null
        }
    }

    /** The answer to "When is it?": "tomorrow at 3", "on friday", "at 5". */
    fun eventTime(answer: String, now: LocalDateTime = LocalDateTime.now()): EventTime? {
        val text = normalize(answer)
        Spoken.clock(text.removePrefix("at "))?.let { clock ->
            val at = ReminderTimes.build(now, null, null, clock, null) as? ReminderTime.At ?: return null
            return EventTime.At(at.date, at.hour, at.minute)
        }
        return eventTime(words(text).toMutableList(), now)
    }

    // endregion

    // region Reminders

    private val reminderLead = Regex(
        "^(?:remind me|set (?:a |me a |up a )?reminder|create (?:a )?reminder|add (?:a )?reminder|make (?:a )?reminder|" +
            "new reminder|reminder|don't let me forget|do not let me forget)(?: (.+))?$",
    )
    private val showReminders = Regex(
        "^(?:(?:show|list|read|check|open|see|tell me)(?: me)? |what are )?(?:all )?(?:my |the )?(?:upcoming )?reminders$|" +
            "^(?:do i have any|any|what) reminders(?: do i have)?$",
    )
    private val cancelReminders = Regex("^(?:cancel|delete|remove|clear|turn off|stop)(?: all)?(?: of)?(?: my| the)? reminders?$")
    private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase(Locale.ROOT) }
    private val dayParts = mapOf("morning" to DayPart.MORNING, "afternoon" to DayPart.AFTERNOON, "evening" to DayPart.EVENING, "night" to DayPart.NIGHT)
    private val partPhrases = listOf(
        "in the morning" to DayPart.MORNING, "this morning" to DayPart.MORNING, "in the afternoon" to DayPart.AFTERNOON,
        "this afternoon" to DayPart.AFTERNOON, "in the evening" to DayPart.EVENING, "this evening" to DayPart.EVENING,
        "tonight" to DayPart.EVENING, "at night" to DayPart.NIGHT,
    )
    private val reminderFillers = setOf("to", "that", "about", "of", "for", "me", "and", "so", "on", "at")
    private val manyDays = Regex("^(\\d+) (days?|weeks?)$")

    private fun reminder(text: String, now: LocalDateTime): Command? {
        if (showReminders.matches(text)) return Command.ShowReminders
        if (cancelReminders.matches(text)) return Command.CancelReminders
        val m = reminderLead.matchEntire(text) ?: return null
        val words = words(m.groupValues[1]).toMutableList()
        val time = reminderTime(words, now)
        while (words.isNotEmpty() && words.first() in reminderFillers) words.removeAt(0)
        while (words.isNotEmpty() && words.last() in setOf("to", "please", "and")) words.removeAt(words.lastIndex)
        return Command.Remind(words.joinToString(" ").takeIf { it.isNotEmpty() }, time)
    }

    /** Finds and removes the words that say when ("in 20 minutes", "tomorrow at 9"), leaving what to remind of. */
    private fun reminderTime(words: MutableList<String>, now: LocalDateTime): ReminderTime? {
        val parts = whenParts(words, now)
        return ReminderTimes.build(now, parts.after, parts.day, parts.clock, parts.part)
    }

    /** The pieces of a "when": [after] seconds from now, or a [day] with a [clock] time or a [part] of the day. */
    private class WhenParts(val after: Int?, val day: LocalDate?, val clock: Spoken.ClockTime?, val part: DayPart?)

    /** Finds and removes the words that say when ("in 20 minutes", "tomorrow at 9", "on March 3rd"). */
    private fun whenParts(words: MutableList<String>, now: LocalDateTime): WhenParts {
        val today = now.toLocalDate()
        var part: DayPart? = null
        var day: LocalDate? = null
        for ((phrase, value) in partPhrases) {
            if (words.cut(phrase) >= 0) {
                part = value
                if (phrase == "tonight") day = today
                break
            }
        }
        var after = relative(words)

        val dayWords = listOf("the day after tomorrow" to 2L, "day after tomorrow" to 2L, "tomorrow" to 1L, "today" to 0L)
        for ((phrase, offset) in dayWords) {
            val at = words.cut(phrase)
            if (at < 0) continue
            day = today.plusDays(offset)
            // "tomorrow morning"
            words.getOrNull(at)?.let { dayParts[it] }?.let { part = it; words.removeAt(at) }
            break
        }
        if (day == null) {
            for (i in words.indices) {
                val weekday = weekdays[words[i]] ?: continue
                val before = words.getOrNull(i - 1)
                day = ReminderTimes.next(today, weekday, thisWeek = before == "this")
                words.removeAt(i)
                words.getOrNull(i)?.let { dayParts[it] }?.let { part = it; words.removeAt(i) }
                if (before in setOf("on", "this", "next")) words.removeAt(i - 1)
                break
            }
        }
        // "on March 3rd", "the 3rd of March", "on Christmas Eve".
        if (day == null) day = Dates.cut(words, today)

        // "set a reminder for 10 minutes", before "for 10" can read as 10 o'clock.
        if (after == null && words.firstOrNull() == "for") {
            after = (words.size downTo 2).firstNotNullOfOrNull { end ->
                Spoken.duration(words.subList(1, end).joinToString(" "))?.also { repeat(end) { words.removeAt(0) } }
            }
        }
        val clock = if (after == null) clockIn(words) else null
        return WhenParts(after, day, clock, part)
    }

    /** "in 20 minutes", "in an hour and a half", "20 minutes from now", "in 3 days": seconds from now. */
    private fun relative(words: MutableList<String>): Int? {
        for (i in words.indices) {
            if (words[i] != "in") continue
            for (end in minOf(words.size, i + 7) downTo i + 2) {
                val span = words.subList(i + 1, end).joinToString(" ")
                val seconds = Spoken.duration(span) ?: days(span) ?: continue
                repeat(end - i) { words.removeAt(i) }
                return seconds
            }
        }
        val fromNow = words.cut("from now")
        if (fromNow > 0) {
            for (start in maxOf(0, fromNow - 6) until fromNow) {
                val seconds = Spoken.duration(words.subList(start, fromNow).joinToString(" ")) ?: continue
                repeat(fromNow - start) { words.removeAt(start) }
                return seconds
            }
        }
        return null
    }

    private fun days(span: String): Int? {
        val m = manyDays.matchEntire(Spoken.digitize(span).replace(Regex("^an? "), "1 ")) ?: return null
        val count = m.groupValues[1].toInt().takeIf { it in 1..MAX_REMINDER_DAYS } ?: return null
        return count * (if (m.groupValues[2].startsWith("week")) 7 else 1) * SECONDS_PER_DAY
    }

    /**
     * "at 5", "at 7:30 pm", "5pm", "for noon": the time of day, removed from [words]. "At" comes
     * first, so "book a table for 4 at 8" is at 8.
     */
    private fun clockIn(words: MutableList<String>): Spoken.ClockTime? {
        val passes = listOf(setOf("at", "by", "around"), null, setOf("for"))
        for (prepositions in passes) {
            for (i in words.indices) {
                val preposition = prepositions != null
                if (preposition && words[i] !in prepositions!!) continue
                val start = if (preposition) i + 1 else i
                for (end in minOf(words.size, start + 5) downTo start + 1) {
                    val span = words.subList(start, end).joinToString(" ")
                    // Without "at", only times that can't be anything else: "5pm", "17:30", "noon".
                    if (!preposition && !explicitTime.containsMatchIn(span)) continue
                    val time = Spoken.clock(span) ?: continue
                    repeat(end - i) { words.removeAt(i) }
                    return time
                }
            }
        }
        return null
    }

    private val explicitTime = Regex("\\d[:.]\\d|\\d ?[ap]\\.?m|^noon$|^midnight$|o'?clock")

    /** Removes the words of [phrase] where they first appear, returning where that was, or -1. */
    private fun MutableList<String>.cut(phrase: String): Int {
        val parts = phrase.split(' ')
        for (i in 0..size - parts.size) {
            if (subList(i, i + parts.size) == parts) {
                repeat(parts.size) { removeAt(i) }
                return i
            }
        }
        return -1
    }

    /**
     * The answer to "When should I remind you?": "at 5", "in ten minutes", "tomorrow", or a bare
     * "5 pm". Null when it doesn't say a time.
     */
    fun reminderTime(answer: String, now: LocalDateTime = LocalDateTime.now()): ReminderTime? {
        val text = normalize(answer)
        // A time alone first ("5", "5 pm"); then the way a whole request reads it ("tomorrow at 9").
        Spoken.clock(text.removePrefix("at ").removePrefix("for "))?.let { return ReminderTimes.build(now, null, null, it, null) }
        return (reminder("remind me $text", now) as? Command.Remind)?.time
    }

    /** "Remind me" said alone, then what it's about: the whole request again, with the answer in it. */
    fun reminderAbout(answer: String, now: LocalDateTime = LocalDateTime.now()): Command.Remind {
        return reminder("remind me ${normalize(answer)}", now) as? Command.Remind ?: Command.Remind(answer.trim(), null)
    }

    // endregion

    // region YouTube, name days, parking, location

    private val youTubeName = "(?:youtube|you tube)"
    private val youTubeLead = Regex("^(?:search|look up|look for|find)(?: on| in)? $youTubeName(?: for)?(?: (.+))?$")
    private val youTubeFirst = Regex("^$youTubeName (?:search|search for|look up|find)(?: for)? (.+)$")
    private val onYouTube = Regex(
        "^(?:search for|search|look up|look for|find|show me|watch|i want to watch|let me watch|open|find me)(?: me)? (.+?) (?:on|in) $youTubeName$",
    )
    private val youTubeVideos = Regex("^(?:show me |find |search for |find me )?(?:a |some )?youtube videos? (?:of|about|on|for|with) (.+)$")

    private fun youTube(text: String): Command? {
        youTubeLead.matchEntire(text)?.let { m ->
            val query = m.groupValues[1].trim()
            // "search youtube music for ..." is the music app.
            if (query.startsWith("music")) return null
            return if (query.isEmpty()) Command.OpenApp("YouTube") else Command.YouTubeSearch(query)
        }
        val query = youTubeFirst.matchEntire(text)?.groupValues?.get(1)
            ?: onYouTube.matchEntire(text)?.groupValues?.get(1)
            ?: youTubeVideos.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        return query.trim().takeIf { it.isNotEmpty() }?.let(Command::YouTubeSearch)
    }

    private val nameDaysLead = Regex(
        "^(?:who|who's|who is|whose|who all) (?:is )?(?:celebrating|celebrates|celebrate|has (?:a |their )?name ?day|" +
            "have (?:a |their )?name ?days?|name ?days? (?:is|are) it|name ?day is it)(?: (today|tomorrow|yesterday))?$",
    )
    private val nameDaysList = Regex(
        "^(?:what are |what's |what is |show me |tell me |list |read )?(?:the )?(?:(today|tomorrow|yesterday)'s )?(?:greek )?" +
            "name ?days?(?: (?:for |of )?(today|tomorrow|yesterday))?$",
    )
    private val whoseNameDay = Regex("^whose name ?day is (?:it )?(today|tomorrow|yesterday)$")
    private val nameDayOf = listOf(
        Regex("^when(?: is|'s) (?:the )?name ?day (?:of|for) (.+)$"),
        Regex("^when(?: is|'s) (.+?)(?:'s|s'|') name ?day$"),
        Regex("^when(?: is|'s) (.+?) name ?day$"),
        Regex("^when does (.+?) (?:celebrate|have (?:a |their |his |her )?name ?day)(?: (?:their|his|her) name ?day)?$"),
        Regex("^(?:the )?name ?day (?:of|for) (.+)$"),
        Regex("^(.+?)(?:'s)? name ?day$"),
    )
    private val notNames = setOf(
        "my", "your", "his", "her", "their", "the", "today", "tomorrow", "yesterday", "greek", "a", "today's", "tomorrow's", "happy",
    )

    private fun nameDays(text: String, now: LocalDateTime): Command? {
        val day = (nameDaysLead.matchEntire(text) ?: whoseNameDay.matchEntire(text))?.groupValues?.get(1)
            ?: nameDaysList.matchEntire(text)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        if (day != null) {
            val offset = when (day) {
                "tomorrow" -> 1L
                "yesterday" -> -1L
                else -> 0L
            }
            return Command.NameDays(now.toLocalDate().plusDays(offset))
        }
        val name = nameDayOf.firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }?.trim() ?: return null
        if (name.isEmpty() || name in notNames || words(name).size > 3) return null
        return Command.NameDay(name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } })
    }

    private val saveParking = setOf(
        "remember where i parked", "remember where i parked my car", "remember where i parked the car", "remember where i've parked",
        "remember where my car is", "remember my parking spot", "remember my parking space", "remember my parking location",
        "remember this parking spot", "remember where the car is", "save my parking spot", "save my parking space",
        "save my parking location", "save parking spot", "save parking location", "save my parking", "save where i parked",
        "mark my parking spot", "note where i parked", "i parked here", "i've parked here", "i have parked here", "i just parked",
        "i just parked here", "my car is parked here", "i parked my car here", "i parked the car here",
    )
    private val findCarDirections = Regex(
        "^(?:(?:take|bring|walk|lead|guide|navigate|get|direct)(?: me)?(?: back)? to|(?:directions|navigation|route) (?:back )?to|" +
            "how do i get (?:back )?to) (?:my|the) (?:car|parked car|parking spot|parking space)$",
    )
    private val findCarMap = setOf(
        "where did i park", "where did i park my car", "where did i park the car", "where is my car", "where's my car",
        "where is the car", "where's the car", "find my car", "where did i leave my car", "where did i leave the car",
        "where is my car parked", "show me my car", "show me where i parked", "where am i parked", "where's my parking spot",
        "where is my parking spot", "where did i leave the car parked",
    )

    private fun parking(text: String): Command? = when {
        text in saveParking -> Command.SaveParking
        findCarDirections.matches(text) -> Command.FindCar(directions = true)
        text in findCarMap -> Command.FindCar(directions = false)
        else -> null
    }

    private val myLocation = "(?:my (?:current |exact )?(?:location|position|whereabouts)|where i am|where i'm at|where i'm)"
    private val locationTo = Regex("^(?:send|share|text|message|sms|give)(?: my)? (?:current )?(?:location|position|whereabouts|where i am) (?:to|with) (.+)$")
    private val personLocation = Regex("^(?:send|text|message|sms|tell|show|give) (.+?) $myLocation$")
    private val myLocationTo = Regex("^(?:send|share|text) $myLocation (?:to|with) (.+)$")

    private fun shareLocation(text: String): Command? {
        val who = (locationTo.matchEntire(text) ?: personLocation.matchEntire(text) ?: myLocationTo.matchEntire(text))
            ?.groupValues?.get(1)?.trim() ?: return null
        if (who.isEmpty() || who in setOf("me", "myself", "it", "us")) return null
        return Command.ShareLocation(who.removePrefix("to ").trim())
    }

    // endregion

    // region Device controls

    private val torchWords = setOf(
        "turn", "switch", "toggle", "the", "my", "on", "off", "flashlight", "flash", "light", "torch", "enable",
        "disable", "activate", "deactivate", "use",
    )

    private fun flashlight(text: String): Command? {
        val mentionsTorch = Regex("\\b(?:flashlight|flash light|torch)\\b").containsMatchIn(text)
        if (!mentionsTorch || !onlyWords(text, torchWords)) return null
        val words = words(text)
        val state = when {
            "on" in words || "enable" in words || "activate" in words -> Switch.ON
            "off" in words || "disable" in words || "deactivate" in words -> Switch.OFF
            else -> Switch.TOGGLE
        }
        return Command.Flashlight(state)
    }

    private val volumeSet = Regex("^(?:set |turn |change |put )?(?:the |media )?volume (?:to |at )?(\\d{1,3})(?: ?%| percent)?$")
    private val volumeSetReverse = Regex("^(?:set |turn |change |put )?(?:the )?(?:media )?volume (?:to |at )?(max|maximum|full|min|minimum|half|zero)$")

    private fun volume(text: String): Command? {
        volumeSet.matchEntire(text)?.let { m ->
            val percent = m.groupValues[1].toInt()
            return if (percent in 0..100) Command.Volume(VolumeChange.Set(percent)) else null
        }
        volumeSetReverse.matchEntire(text)?.let { m ->
            val percent = when (m.groupValues[1]) {
                "max", "maximum", "full" -> 100
                "half" -> 50
                else -> 0
            }
            return Command.Volume(VolumeChange.Set(percent))
        }
        val change = when (text) {
            "max volume", "maximum volume", "full volume" -> VolumeChange.Set(100)
            "volume up", "turn up the volume", "turn the volume up", "turn volume up", "turn it up", "louder",
            "increase volume", "increase the volume", "raise volume", "raise the volume", "make it louder", "volume +",
            -> VolumeChange.Up
            "volume down", "turn down the volume", "turn the volume down", "turn volume down", "turn it down",
            "quieter", "softer", "decrease volume", "decrease the volume", "lower volume", "lower the volume",
            "reduce volume", "reduce the volume", "make it quieter", "volume -",
            -> VolumeChange.Down
            "mute", "mute volume", "mute the volume", "volume mute", "mute media", "mute sound", "mute audio",
            "mute music",
            -> VolumeChange.Mute
            "unmute", "unmute volume", "unmute the volume", "volume unmute", "unmute media", "unmute sound",
            "unmute audio", "unmute music",
            -> VolumeChange.Unmute
            else -> return null
        }
        return Command.Volume(change)
    }

    private val brightnessSet = Regex(
        "^(?:set |turn |change |put |make |adjust )?(?:the )?(?:screen |display |phone )?brightness (?:to |at |level to |up to |down to )?(\\d{1,3})(?: ?%| percent)?$|" +
            "^(\\d{1,3})(?: ?%| percent) brightness$",
    )
    private val brightnessWords = Regex(
        "^(?:set |turn |change |put )?(?:the )?(?:screen |display )?brightness (?:to |at |all the way )?(max|maximum|full|highest|min|minimum|lowest|half|zero|up all the way)$|" +
            "^(max|maximum|full|highest|min|minimum|lowest) (?:screen )?brightness$",
    )
    private val brightnessUp = setOf(
        "brightness up", "increase brightness", "increase the brightness", "turn up the brightness", "turn the brightness up",
        "turn brightness up", "raise the brightness", "raise brightness", "brighter", "make it brighter", "make the screen brighter",
        "screen brighter", "more brightness", "brighten the screen", "brighten my screen", "brighten the display", "turn up brightness",
        "increase screen brightness", "make my screen brighter",
    )
    private val brightnessDown = setOf(
        "brightness down", "decrease brightness", "decrease the brightness", "lower the brightness", "lower brightness",
        "reduce the brightness", "reduce brightness", "turn down the brightness", "turn the brightness down", "turn brightness down",
        "dimmer", "dim the screen", "dim my screen", "dim the display", "make it darker", "make the screen darker",
        "make the screen dimmer", "screen dimmer", "less brightness", "turn down brightness", "decrease screen brightness",
        "make my screen darker", "make my screen dimmer",
    )
    private val autoBrightness = Regex(
        "^(?:(turn on|switch on|enable|activate|use)|(turn off|switch off|disable|deactivate|stop using))? ?(?:the )?" +
            "(?:auto|automatic|adaptive)[ -]?brightness(?: mode| setting)?(?: (on|off))?$",
    )

    private val ringerSilent = Regex(
        "^(?:(?:turn on|switch on|enable|activate|set|go|switch|change|put it) (?:to |on )?)?(?:the )?silent(?: mode)?(?: on)?$|" +
            "^(?:put|set|switch|turn|make|change) (?:my |the )?(?:phone|ringer|device|it|mobile) (?:on |to |into )?(?:silent|mute|silent mode)$|" +
            "^(?:silence|mute|quiet) (?:my |the )?(?:phone|ringer|ringtone|calls|device|mobile)$|^silence$|" +
            "^(?:turn|switch) off (?:my |the )?(?:ringer|ringtone)$|^(?:turn|switch) (?:my |the )?(?:ringer|ringtone) off$|^(?:ringer|ringtone) off$",
    )
    private val ringerVibrate = Regex(
        "^(?:(?:turn on|switch on|enable|activate|set|go|switch|change|put it) (?:to |on )?)?(?:the )?(?:vibrate|vibration|vibrate only)(?: mode)?(?: on| only)?$|" +
            "^(?:put|set|switch|turn|make|change) (?:my |the )?(?:phone|ringer|device|it|mobile) (?:on |to |into )?(?:vibrate|vibration|vibrate mode)$",
    )
    private val ringerNormal = Regex(
        "^(?:turn on|switch on|enable) (?:my |the )?(?:ringer|ringtone)$|^(?:turn|switch) (?:my |the )?(?:ringer|ringtone) (?:back )?on$|^(?:ringer|ringtone) on$|" +
            "^(?:ring|normal|sound|loud|ringer|general) mode$|" +
            "^(?:turn off|switch off|disable|deactivate|exit|stop|end|cancel|leave) (?:the )?(?:silent|vibrate|vibration)(?: mode)?$|^(?:silent|vibrate|vibration)(?: mode)? off$|" +
            "^(?:take|get) (?:my |the )?(?:phone|ringer|it) off (?:silent|vibrate|mute)$|^(?:unmute|unsilence) (?:my |the )?(?:phone|ringer|calls|device|mobile)$|" +
            "^(?:put|set|switch|turn|change) (?:my |the )?(?:phone|ringer|it) (?:on |to |back to |back on )?(?:ring|normal|sound|loud)(?: mode)?$",
    )

    /** Brightness and the ringer: "brightness 50%", "dim the screen", "silent mode", "vibrate". */
    private fun phoneSettings(raw: String): Command? {
        val text = Spoken.digitize(raw)
        brightnessSet.matchEntire(text)?.let { m ->
            val percent = m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt()
            return if (percent in 0..100) Command.Brightness(BrightnessChange.Set(percent)) else null
        }
        brightnessWords.matchEntire(text)?.let { m ->
            val percent = when (m.groupValues[1].ifEmpty { m.groupValues[2] }) {
                "max", "maximum", "full", "highest", "up all the way" -> 100
                "half" -> 50
                else -> 0
            }
            return Command.Brightness(BrightnessChange.Set(percent))
        }
        if (text in brightnessUp) return Command.Brightness(BrightnessChange.Up)
        if (text in brightnessDown) return Command.Brightness(BrightnessChange.Down)
        autoBrightness.matchEntire(text)?.let { m ->
            val off = m.groupValues[2].isNotEmpty() || m.groupValues[3] == "off"
            return Command.Brightness(BrightnessChange.Auto(on = !off))
        }
        return when {
            ringerNormal.matches(text) -> Command.Ringer(RingerMode.NORMAL)
            ringerSilent.matches(text) -> Command.Ringer(RingerMode.SILENT)
            ringerVibrate.matches(text) -> Command.Ringer(RingerMode.VIBRATE)
            else -> null
        }
    }

    private val coin = Regex("^(?:(?:flip|toss|throw|spin)(?: me)? (?:a |the |one |an )?coin|coin (?:flip|toss)|heads or tails|flip a coin heads or tails)$")
    private val dice = Regex(
        "^(?:roll|throw|toss|cast)(?: me)?(?: (?:a|an|the|one|some|(\\d+)))?(?: (\\d+)[ -]?sided)? (?:dice|die|d(\\d+))$|^(?:dice|die) roll$|" +
            "^roll (\\d+)?d(\\d+)$",
    )
    private val pickNumber = Regex(
        "^(?:pick|choose|give me|generate|select|tell me|get|think of|say)(?: me)?(?: a| an| one)?(?: random)? number" +
            "(?: (?:between|from) (-?\\d+) (?:and|to|-) (-?\\d+))?$",
    )
    private val randomNumber = Regex("^(?:a )?random number(?: (?:between|from) (-?\\d+) (?:and|to|-) (-?\\d+))?$")

    /** "flip a coin", "roll two dice", "roll a d20", "pick a number from 1 to 10". */
    private fun chance(raw: String): Command? {
        val text = Spoken.digitize(raw).replace(Regex("(?:^| )(?:a|1) hundred(?= |$)"), " 100").replace(Regex("(?:^| )(?:a|1) thousand(?= |$)"), " 1000")
            .replace(Regex("\\s+"), " ").trim()
        if (coin.matches(text)) return Command.Chance(ChanceKind.Coin)
        dice.matchEntire(text)?.let { m ->
            val count = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: 1
            val sides = (m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[5] }).toIntOrNull() ?: 6
            if (count !in 1..MAX_DICE || sides !in 2..MAX_SIDES) return null
            return Command.Chance(ChanceKind.Dice(count, sides))
        }
        val (m, default) = pickNumber.matchEntire(text)?.let { it to 10 } ?: randomNumber.matchEntire(text)?.let { it to 100 } ?: return null
        val low = m.groupValues[1].toIntOrNull() ?: 1
        val high = m.groupValues[2].toIntOrNull() ?: default
        return Command.Chance(ChanceKind.Number(minOf(low, high), maxOf(low, high)))
    }

    private val batteryLevel = Regex(
        "^(?:(?:what(?:'s| is)|how(?:'s| is)|check|show(?: me)?|tell me|read)(?: me)? )?(?:the |my )?(?:phone(?:'s)? |current )?battery" +
            "(?: level| status| percentage| percent| life| charge| left)?(?: at| now| like| level)?$|" +
            "^how much (?:battery|charge|power|juice)(?: life)?(?: (?:do i have|have i got|is|does my phone have|does the phone have|is there|remains))?" +
            "(?: left)?(?: on (?:my|the) phone)?$|" +
            "^(?:what|which) (?:percentage|percent) is (?:my|the) (?:battery|phone)(?: at)?$|^how charged is (?:my|the) (?:phone|battery)$|" +
            "^is (?:my|the) battery (?:low|full|dying|ok|okay|good)$",
    )
    private val batteryCharging = Regex(
        "^(?:is|are) (?:my |the )?(?:phone|battery|device|it) (?:charging|plugged in|being charged|on charge|connected to (?:the |a )?charger)$|" +
            "^am i charging$|^is it charging$|" +
            "^how long (?:until|till|before|to|will it take(?: for)?|does it take(?: for)?) (?:my |the )?(?:phone |battery |it )?(?:is |to be |to get |gets |will be )?(?:fully )?(?:charged|full|charge)$|" +
            "^when will (?:my |the )?(?:phone|battery|it) be (?:fully )?(?:charged|full)$",
    )

    private fun battery(text: String): Command? = when {
        batteryCharging.matches(text) -> Command.Battery(charging = true)
        batteryLevel.matches(text) -> Command.Battery()
        else -> null
    }

    private val nowPlaying = setOf(
        "what's playing", "what is playing", "whats playing", "what's this song", "what is this song", "what song is this",
        "what song is playing", "what's the song", "what song is that", "which song is this", "who sings this",
        "who sings this song", "who is singing", "who's singing", "what am i listening to", "name this song",
        "what's playing right now", "what's on", "song name", "what track is this", "what's this track",
    )
    private val likeSong = setOf(
        "like this song", "like this", "like the song", "like this track", "like it", "love this song", "heart this song",
        "save this song", "save this track", "save the song", "add this song to my liked songs", "add to liked songs",
        "add this to my liked songs", "add this song to liked songs", "add to my liked songs", "add this to liked songs",
    )
    private val shuffleOn = setOf(
        "shuffle", "shuffle on", "turn on shuffle", "turn shuffle on", "enable shuffle", "shuffle mode on", "start shuffling",
        "shuffle the songs", "shuffle songs", "shuffle my music", "shuffle the music",
    )
    private val shuffleOff = setOf(
        "shuffle off", "turn off shuffle", "turn shuffle off", "disable shuffle", "shuffle mode off", "stop shuffling",
        "stop shuffle", "no shuffle",
    )
    private val repeatOn = setOf(
        "repeat", "repeat on", "turn on repeat", "turn repeat on", "enable repeat", "repeat this song", "repeat the song",
        "repeat song", "loop this song", "loop the song", "put this song on repeat", "put it on repeat",
    )
    private val repeatOff = setOf(
        "repeat off", "turn off repeat", "turn repeat off", "disable repeat", "stop repeating", "no repeat", "stop looping",
    )

    private fun media(text: String): Command? {
        when (text) {
            in nowPlaying -> return Command.NowPlaying
            in likeSong -> return Command.LikeSong
            in shuffleOn -> return Command.Shuffle(true)
            in shuffleOff -> return Command.Shuffle(false)
            in repeatOn -> return Command.Repeat(true)
            in repeatOff -> return Command.Repeat(false)
        }
        val action = when (text) {
            "pause", "pause music", "pause the music", "pause song", "pause the song", "pause media", "pause playback",
            "pause it", "stop music", "stop the music", "stop playback", "stop playing", "stop the song",
            -> MediaAction.PAUSE
            "resume", "resume music", "resume the music", "resume playback", "resume song", "unpause", "play music",
            "continue playing", "continue the music", "play media", "keep playing",
            -> MediaAction.PLAY
            "next song", "next track", "skip song", "skip track", "skip this song", "skip this track",
            "play next song", "play the next song", "skip the song",
            -> MediaAction.NEXT
            "previous", "previous song", "previous track", "last song", "last track", "play previous song",
            "play the previous song", "go back a song", "back a song",
            -> MediaAction.PREVIOUS
            else -> return null
        }
        return Command.Media(action)
    }

    private val musicLead = Regex(
        "^(?:play|playing|put on|listen to|i want to listen to|start playing|shuffle)" +
            // "the" stays part of the title: "the beatles", "the number of the beast".
            "(?: me)?(?: (some songs by|some music by|something by|anything by|songs by|music by|the artist|the band|" +
            "the song|the track|the single|the album|the record|the playlist|my playlist|playlist|some|a few))? (.+)$",
    )
    private val musicApp: Regex by lazy {
        Regex("^(.+?) (?:on|in|with|using|from|via) (${MusicApp.allAliases.joinToString("|") { Regex.escape(it) }})$")
    }

    /** What follows "play" that isn't music: Play Store and friends, or the media keys above. */
    private val notMusic = setOf(
        "store", "games", "books", "movies", "console", "music", "media", "it", "something", "anything",
        "next", "previous", "back", "again",
    )
    private val likedSongs = setOf(
        "my liked songs", "liked songs", "my liked songs playlist", "the liked songs", "my likes", "my favorites",
        "my favourites", "my favorite songs", "my favourite songs", "my saved songs", "my favorite music",
        "my favourite music",
    )
    private val namedPlaylist = Regex("^(?:my |the )?(.+?) playlist$")
    private val playlistNamed = Regex("^(?:my |the )?playlist (?:called |named )?(.+)$")

    /** "fear of the dark by iron maiden"; the last "by" counts, so "stand by me by ben e king" works. */
    private val byArtist = Regex("^(.+) by (.+)$")

    /** What follows "by" in titles rather than an artist: "stand by me", "by my side". */
    private val notArtists = setOf("me", "you", "us", "him", "her", "them", "my side", "your side", "myself", "yourself", "now")

    private fun music(text: String): Command? {
        val lead = musicLead.matchEntire(text) ?: return null
        val qualifier = lead.groupValues[1]
        val rest = lead.groupValues[2].trim()
        val withApp = musicApp.matchEntire(rest)
        val spoken = (withApp?.groupValues?.get(1) ?: rest).removePrefix("some ").trim()
        val app = withApp?.groupValues?.get(2)?.let(MusicApp::fromName)
        if (spoken.isEmpty() || spoken in notMusic) return null
        // "play spotify" means open it, not search Spotify for "spotify".
        if (app == null && qualifier.isEmpty()) MusicApp.fromName(spoken)?.let { return Command.OpenApp(it.label) }
        if (spoken in likedSongs) return Command.PlayMusic(spoken, app, MusicKind.LIKED)

        val kind = when (qualifier) {
            "the song", "the track", "the single" -> MusicKind.SONG
            "the album", "the record" -> MusicKind.ALBUM
            "the playlist", "my playlist", "playlist" -> MusicKind.PLAYLIST
            "", "some", "a few" -> MusicKind.ANY
            else -> MusicKind.ARTIST
        }
        when (kind) {
            MusicKind.PLAYLIST -> {
                val name = spoken.removePrefix("called ").removePrefix("named ").removeSuffix(" playlist").trim()
                return Command.PlayMusic(name.ifEmpty { return null }, app, MusicKind.PLAYLIST)
            }
            MusicKind.ANY -> {
                // "my workout playlist", "playlist called road trip"
                (namedPlaylist.matchEntire(spoken) ?: playlistNamed.matchEntire(spoken))?.let { m ->
                    val name = m.groupValues[1].trim()
                    if (name !in setOf("the", "my", "a")) return Command.PlayMusic(name, app, MusicKind.PLAYLIST)
                }
            }
            else -> Unit
        }
        if (kind != MusicKind.ARTIST) {
            byArtist.matchEntire(spoken)?.let { m ->
                val title = m.groupValues[1].trim()
                val artist = m.groupValues[2].trim()
                if (title.isNotEmpty() && artist !in notArtists) {
                    return Command.PlayMusic(title, app, if (kind == MusicKind.ANY) MusicKind.SONG else kind, artist)
                }
            }
        }
        return Command.PlayMusic(spoken, app, kind)
    }

    // endregion

    // region Weather & time

    private val weatherLead = Regex(
        "^(?:what(?:'s| is| will be)(?: the)? |how(?:'s| is| will be)(?: the)? |show(?: me)?(?: the)? |check(?: the)? |" +
            "get(?: the)? |tell me the |current )?(?:weather|forecast|temperature)(?: forecast| like| outside)*(.*)$",
    )
    private val weatherQuestion = Regex(
        "^(?:will it|is it going to|is it gonna|is it|does it look like it(?:'s| is) going to|should i expect)(?: be)? " +
            "(?:rain|snow|sunny|cold|hot|warm|windy|raining|snowing|storm|stormy)(.*)$",
    )
    private val howHot = Regex("^how (?:hot|cold|warm) (?:is it|will it be)(.*)$")
    private val umbrella = Regex("^do i need (?:an umbrella|a jacket|a coat|sunscreen)(.*)$")
    private val placeWeather = Regex("^(.+?)(?:'s)? (?:weather|forecast)(?: (?:today|tomorrow|this week|now))?$")
    private val dayWords = mapOf(
        "today" to WeatherDay.TODAY, "tonight" to WeatherDay.TODAY, "now" to WeatherDay.NOW,
        "tomorrow" to WeatherDay.TOMORROW, "this week" to WeatherDay.WEEK, "week" to WeatherDay.WEEK,
        "this weekend" to WeatherDay.WEEK, "weekend" to WeatherDay.WEEK, "next few days" to WeatherDay.WEEK,
    )
    private val herePlaces = setOf("here", "my area", "my location", "outside", "current location", "near me", "around me", "me", "my city")

    private fun weather(text: String): Command? {
        val tail = weatherLead.matchEntire(text)?.groupValues?.get(1)?.takeIf { it.isEmpty() || it.startsWith(" ") }
            ?: weatherQuestion.matchEntire(text)?.groupValues?.get(1)
            ?: howHot.matchEntire(text)?.groupValues?.get(1)
            ?: umbrella.matchEntire(text)?.groupValues?.get(1)
        if (tail != null) return weatherTail(tail)

        placeWeather.matchEntire(text)?.let { m ->
            val subject = m.groupValues[1].removePrefix("the ").trim()
            val day = when {
                text.endsWith("tomorrow") || subject == "tomorrow" -> WeatherDay.TOMORROW
                text.endsWith("this week") -> WeatherDay.WEEK
                else -> WeatherDay.NOW
            }
            if (subject in dayWords) return Command.Weather(null, dayWords.getValue(subject))
            if (subject in herePlaces || subject == "local") return Command.Weather(null, day)
            // "london weather" yes; "weather channel app" isn't matched here, "bad weather" would be a search.
            if (words(subject).size > 3 || subject in setOf("bad", "good", "nice", "the", "app", "my")) return null
            return Command.Weather(placeName(subject), day)
        }
        return null
    }

    /** What follows "weather": optional day words and an optional "in <place>". */
    private fun weatherTail(rawTail: String): Command.Weather? {
        var tail = " ${rawTail.trim()} "
        var day = WeatherDay.NOW
        for ((phrase, value) in dayWords.entries.sortedByDescending { it.key.length }) {
            val pattern = Regex(" (?:for |on |over )?(?:the )?$phrase(?:'s)? ")
            if (pattern.containsMatchIn(tail)) {
                day = value
                tail = tail.replace(pattern, " ")
            }
        }
        tail = tail.replace(Regex(" (?:be|be like|like|outside|going to be|gonna be|today's) "), " ").trim()
        if (tail.isEmpty()) return Command.Weather(null, day)
        val place = Regex("^(?:in|for|at|near|around|of) (.+)$").matchEntire(tail)?.groupValues?.get(1)?.trim()
            // "weather london": a short bare tail is a place, unless it names something else.
            ?: tail.takeIf { words(it).size <= 3 && words(it).none { word -> word in notPlaces } }
            ?: return null
        if (place in herePlaces) return Command.Weather(null, day)
        return Command.Weather(placeName(place), day)
    }

    private val notPlaces = setOf(
        "app", "apps", "channel", "widget", "radar", "map", "maps", "station", "underground", "network",
        "settings", "alert", "alerts", "news", "report", "converter", "conversion", "sensor", "api", "data",
    )

    private fun placeName(raw: String) = raw.trim().removePrefix("the ")

    private val localTime = setOf(
        "time", "the time", "current time", "what time is it", "what's the time", "what is the time", "whats the time",
        "tell me the time", "time now", "date", "the date", "today's date", "todays date", "what's the date",
        "what is the date", "what's today's date", "what is today's date", "what's the date today", "what day is it",
        "what day is it today", "what's today", "what's the day","date today", "what is the day today",
    )
    private val timeIn = Regex("^(?:what(?:'s| is) the |the |current )?(?:local )?time (?:in|at) (.+)$")
    private val whatTimeIn = Regex("^what time is it (?:in|at) (.+)$")
    private val placeTime = Regex("^(.+?) (?:local )?time$")

    private fun clock(text: String): Command? {
        if (text in localTime) return Command.Clock(null, null)
        val place = timeIn.matchEntire(text)?.groupValues?.get(1)
            ?: whatTimeIn.matchEntire(text)?.groupValues?.get(1)
            ?: placeTime.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val found = WorldClock.lookup(place) ?: return null
        return Command.Clock(found.zone, found.name)
    }

    private val untilLead = Regex(
        "^(?:how many (?:more )?(days|weeks|sleeps)|how long|how much (?:longer|time)|countdown|count down|days|weeks)" +
            "(?: (?:is it|are there|are left|left|is left|do i have|do we have|remain|remaining|to go|have i got))?" +
            " (?:until|till|til|to|before) (.+?)(?: is| comes| starts)?$",
    )
    private val sinceLead = Regex(
        "^(?:how many (days|weeks)|how long|days|weeks)(?: (?:has it been|have passed|have gone by|is it|ago was it|was it|are there))? since (.+)$",
    )
    private val agoLead = Regex("^how (?:many (days|weeks)|long) ago (?:was|did|is) (.+?)(?: happen)?$")
    private val whenIs = Regex("^when(?: is|'s| will be| does| do| is it) (.+?)(?: this year| next year)?(?: fall| come| be| happen| start)?$")
    private val whatDay = Regex(
        "^(?:what|which) (?:day|weekday)(?: of the week)? (?:is|was|will be|does|did|will|falls|fall)(?: it)?(?: on)? (.+?)" +
            "(?: fall on| falls on| fall| be| land on| on)?(?: this year| next year)?$",
    )
    private val whatDate = Regex("^(?:what(?:'s| is) the date|what date is it|what date will it be|what will the date be|what date is|what date) (.+)$")

    /** "how many days until Christmas", "how long since new year", "what day is March 3rd", "when is Easter". */
    private fun dates(text: String, now: LocalDateTime): Command? {
        val today = now.toLocalDate()
        untilLead.matchEntire(text)?.let { m -> return daysBetween(m.groupValues[2], today, since = false, weeks = m.groupValues[1] == "weeks") }
        (sinceLead.matchEntire(text) ?: agoLead.matchEntire(text))?.let { m ->
            return daysBetween(m.groupValues[2], today, since = true, weeks = m.groupValues[1] == "weeks")
        }
        whenIs.matchEntire(text)?.let { m ->
            val named = Dates.named(m.groupValues[1], today) ?: return null
            // "When is Eleni's name day" is the name days' own question.
            if (!named.isOccasion || named.isNameDay || named.name == null) return null
            val date = named.resolve(today) ?: return null
            return Command.DaysUntil(date, named.name)
        }
        val asked = (whatDay.matchEntire(text) ?: whatDate.matchEntire(text))?.groupValues?.get(1) ?: return null
        val named = Dates.named(asked.removePrefix("it ").removePrefix("going to be "), today) ?: return null
        val date = named.resolve(today) ?: return null
        return Command.WhatDay(date, named.name)
    }

    private fun daysBetween(spec: String, today: LocalDate, since: Boolean, weeks: Boolean): Command? {
        val named = Dates.named(spec, today) ?: return null
        val date = named.resolve(today, past = since) ?: return null
        return Command.DaysUntil(date, named.name, since, weeks)
    }

    // endregion

    // region Apps & people

    private val photo = Regex("^(?:take|snap|shoot|capture|grab)(?: me)? (?:a |an |my )?(?:photo|picture|pic|selfie|snapshot|photograph)$")
    private val video = Regex("^(?:record|take|shoot|capture|film|start recording)(?: a| an)?(?: new)? video$")

    private fun camera(text: String): Command? = when {
        photo.matches(text) -> Command.Camera(video = false)
        video.matches(text) -> Command.Camera(video = true)
        else -> null
    }

    private val navigation = Regex(
        "^(?:navigate|navigation|directions|get directions|give me directions|show me directions|route|start navigation|start navigating)" +
            "(?: me)?(?: to| for)? (.+)$",
    )
    // "drive" and "walk" alone are too ambiguous ("drive files"); they need a "to".
    private val travelTo = Regex("^(?:drive|walk|cycle|take me|bring me|how do i get|how to get|how can i get|get me|head|let's go|lets go) (?:me )?to (.+)$")
    private val mapQuery = Regex("^(?:show |find )?(?:me )?(.+?) on (?:the |a )?maps?$")
    private val mapOf = Regex("^(?:show me |open )?(?:a )?map of (.+)$")

    /** "take me home", "drive to work", "go home": a saved place needs no "to" ("drive home"). */
    private val placeTrip = Regex(
        "^(?:navigate|navigation|directions|get directions|give me directions|route|take me|bring me|get me|drive|walk|cycle|" +
            "go|head|let's go|lets go|how do i get|how to get|how can i get|i want to go|start navigation)(?: me)?(?: back)?(?: to| for)? (.+)$",
    )
    private val savedPlaces = mapOf(
        "home" to SavedPlace.HOME, "my home" to SavedPlace.HOME, "my house" to SavedPlace.HOME, "my place" to SavedPlace.HOME,
        "the house" to SavedPlace.HOME,
        "work" to SavedPlace.WORK, "my work" to SavedPlace.WORK, "the office" to SavedPlace.WORK, "my office" to SavedPlace.WORK,
        "office" to SavedPlace.WORK, "my job" to SavedPlace.WORK, "my workplace" to SavedPlace.WORK,
    )

    /** "... with waze", "... on google maps": the navigation app the user asked for. */
    private val withNavigationApp: Regex by lazy {
        Regex("^(.+?) (?:on|in|with|using|via|through) (${NavigationApp.allAliases.joinToString("|") { Regex.escape(it) }})(?: app)?$")
    }

    /** "waze home", "waze to the airport" ("ways to save money" stays a search). */
    private val appFirst = Regex("^waze (?:me )?(?:to )?(.+)$")

    private fun navigate(text: String): Command? {
        var request = text
        var app: NavigationApp? = null
        withNavigationApp.matchEntire(text)?.let { m ->
            request = m.groupValues[1].trim()
            app = NavigationApp.fromName(m.groupValues[2])
        }
        if (app == null) {
            appFirst.matchEntire(request)?.let { m ->
                app = NavigationApp.WAZE
                request = "navigate to " + m.groupValues[1].trim()
            }
        }
        placeTrip.matchEntire(request)?.let { m ->
            val name = m.groupValues[1].trim()
            savedPlaces[name]?.let { place -> return Command.Navigate(placeName(place), place, app) }
        }
        val map = mapQuery.matchEntire(request) ?: mapOf.matchEntire(request)
        val destination = navigation.matchEntire(request)?.groupValues?.get(1)
            ?: travelTo.matchEntire(request)?.groupValues?.get(1)
            ?: map?.groupValues?.get(1)
            ?: return null
        val cleaned = destination.removePrefix("to ").removePrefix("the ").trim()
        if (cleaned.isEmpty() || cleaned in setOf("my phone", "my car keys")) return null
        savedPlaces[cleaned]?.let { return Command.Navigate(placeName(it), it, app) }
        return Command.Navigate(cleaned, app = app, map = map != null && app == null)
    }

    private fun placeName(place: SavedPlace) = when (place) {
        SavedPlace.HOME -> "home"
        SavedPlace.WORK -> "work"
    }

    private val missedCalls = setOf(
        "who called me", "who called", "who called me today", "who just called", "who just called me", "who called me earlier",
        "who called me last", "who was the last person to call me", "who was the last caller", "who was that calling",
        "missed calls", "missed call", "my missed calls", "any missed calls", "show my missed calls", "show missed calls",
        "show me my missed calls", "check missed calls", "check my missed calls", "read my missed calls", "do i have any missed calls",
        "did i miss any calls", "did i miss a call", "did i miss any phone calls", "have i missed any calls", "did anyone call",
        "did anyone call me", "did somebody call", "did somebody call me", "did someone call", "did someone call me", "has anyone called",
        "has anyone called me", "has anybody called me", "did anybody call me", "last call", "my last call", "who rang me", "who rang",
        "who's been calling me", "who has been calling me", "recent calls", "my recent calls", "who tried to call me",
    )
    private val callBack = Regex(
        "^(?:call (?:them |him |her |it |that number |the last number |the last caller |the last missed call |my last missed call |the missed call |" +
            "whoever called |whoever that was )?back|call back(?: (?:the|my) (?:last )?(?:missed )?(?:call|caller|number))?|" +
            "return (?:the |my |that )?(?:last )?(?:missed )?(?:phone )?call|call (?:the|my) last (?:missed )?(?:call|caller)|" +
            "call the person who (?:just )?called(?: me)?|call whoever (?:just )?called(?: me)?)$",
    )
    private val redial = Regex(
        "^(?:redial|redial (?:the |that )?(?:last )?number|call (?:the |that )?last number(?: (?:again|i called|i dialed|i dialled))?|" +
            "call that number again|call the last person i called|call again)$",
    )

    /** "who called me", "call back", "redial". */
    private fun calls(text: String): Command? = when {
        text in missedCalls -> Command.MissedCalls
        callBack.matches(text) -> Command.CallBack()
        redial.matches(text) -> Command.CallBack(redial = true)
        else -> null
    }

    private val callPattern = Regex("^(?:call|phone|ring|dial|facetime|video call)(?: up)? (.+?)(?: (?:on|at) (?:their |his |her )?(?:mobile|cell|work|home|phone))?$")
    private val phoneNumber = Regex("^\\+?[\\d][\\d\\s().-]{2,}$")

    private fun call(text: String): Command? {
        // "call mom back" is calling mom.
        val who = callPattern.matchEntire(text)?.groupValues?.get(1)?.trim()?.removeSuffix(" back")?.trim() ?: return null
        if (who.isEmpty() || who in setOf("me", "back", "it", "them")) return null
        if (phoneNumber.matches(who)) return Command.DialNumber(who.filter { it.isDigit() || it == '+' })
        return Command.Call(who)
    }

    private val messagePattern = Regex(
        "^(?:text|message|msg|sms|send (?:a |an )?(?:text|message|sms|msg)(?: message)? to|send) (.+?)" +
            "(?:(?: saying| that says| that| with the message| with|:) (.+))?$",
    )

    private fun message(text: String): Command? {
        val m = messagePattern.matchEntire(text) ?: return null
        // A bare "send" only counts with a message body ("send mom i'm on my way" is too ambiguous).
        if (text.startsWith("send ") && !text.startsWith("send a") && m.groupValues[2].isEmpty()) return null
        val who = m.groupValues[1].trim()
        if (who.isEmpty()) return null
        return Command.Message(who, m.groupValues[2].trim().takeIf { it.isNotEmpty() })
    }

    private val panelVerbs = setOf(
        "turn", "switch", "toggle", "enable", "disable", "on", "off", "the", "my", "settings", "setting", "open",
        "show", "panel", "connect", "to", "a", "go", "check", "manage", "options", "menu", "page",
    )
    private val panelNames = listOf(
        "wi-fi" to SettingsPanel.WIFI, "wifi" to SettingsPanel.WIFI, "wi fi" to SettingsPanel.WIFI,
        "wireless" to SettingsPanel.WIFI,
        "bluetooth" to SettingsPanel.BLUETOOTH, "blue tooth" to SettingsPanel.BLUETOOTH,
        "mobile data" to SettingsPanel.INTERNET, "cellular data" to SettingsPanel.INTERNET,
        "cellular" to SettingsPanel.INTERNET, "internet" to SettingsPanel.INTERNET, "data" to SettingsPanel.INTERNET,
        "airplane mode" to SettingsPanel.AIRPLANE, "aeroplane mode" to SettingsPanel.AIRPLANE,
        "flight mode" to SettingsPanel.AIRPLANE, "airplane" to SettingsPanel.AIRPLANE,
        "nfc" to SettingsPanel.NFC,
        "hotspot" to SettingsPanel.HOTSPOT, "hot spot" to SettingsPanel.HOTSPOT, "tethering" to SettingsPanel.HOTSPOT,
        "volume" to SettingsPanel.VOLUME, "sound" to SettingsPanel.VOLUME, "sounds" to SettingsPanel.VOLUME,
        "battery saver" to SettingsPanel.BATTERY, "power saving" to SettingsPanel.BATTERY,
        "power saver" to SettingsPanel.BATTERY, "battery" to SettingsPanel.BATTERY,
        "brightness" to SettingsPanel.DISPLAY, "display" to SettingsPanel.DISPLAY, "dark mode" to SettingsPanel.DISPLAY,
        "dark theme" to SettingsPanel.DISPLAY,
        "location" to SettingsPanel.LOCATION, "gps" to SettingsPanel.LOCATION,
        "location services" to SettingsPanel.LOCATION,
        "do not disturb" to SettingsPanel.DND, "dnd" to SettingsPanel.DND, "focus mode" to SettingsPanel.DND,
    ).sortedByDescending { it.first.length }

    private fun panel(text: String): Command? {
        for ((name, panel) in panelNames) {
            val pattern = Regex("(?:^| )${Regex.escape(name)}(?: |$)")
            if (!pattern.containsMatchIn(text)) continue
            val rest = text.replace(pattern, " ").trim()
            if (!onlyWords(rest, panelVerbs)) return null
            // A bare "data" or "display" is more likely a search; require a verb or "settings".
            if (rest.isEmpty() && name in setOf("data", "display", "sound", "sounds", "location", "airplane")) return null
            val words = words(rest)
            val requested = when {
                "on" in words || "enable" in words -> Switch.ON
                "off" in words || "disable" in words -> Switch.OFF
                "toggle" in words -> Switch.TOGGLE
                else -> null
            }
            return Command.OpenPanel(panel, requested)
        }
        return null
    }

    private val defineLead = Regex(
        "^(?:define|definition of|definition for|the definition of|the meaning of|meaning of|dictionary|look up the word|" +
            "look up the definition of|look up the meaning of|what(?:'s| is) the (?:definition|meaning|dictionary definition) of|" +
            "give me the definition of|tell me the meaning of|tell me the definition of)(?: the word| the term)? (.+)$",
    )
    private val wordMeaning = Regex("^(?:what does|what do)(?: the word| the term| an?| the)? (.+) mean$|^(.+?) (?:definition|meaning|dictionary definition)$")
    private val spellLead = Regex(
        "^(?:how (?:do you|do i|to|does one|would you|should i|can i|do we) spell|spell(?: out)?|spelling of|the spelling of|" +
            "what(?:'s| is) the spelling of|how is the word)(?: the word| the name)? (.+?)(?: spelled| spelt)?$",
    )
    private val spelled = Regex("^how (?:is|are|do you write|'s)(?: the word)? (.+?) (?:spelled|spelt|written)$")
    private val notWords = setOf("check", "checker", "checking", "check app", "book", "this", "that", "it", "me")

    /** "define serendipity", "what does ubiquitous mean", "how do you spell necessary": words, not topics. */
    private fun dictionary(text: String): Command? {
        (spellLead.matchEntire(text) ?: spelled.matchEntire(text))?.let { m ->
            val word = word(m.groupValues[1]) ?: return null
            return Command.Spell(word)
        }
        val asked = defineLead.matchEntire(text)?.groupValues?.get(1)
            ?: wordMeaning.matchEntire(text)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            ?: return null
        // Longer phrases are topics for Wikipedia.
        return word(asked)?.let(Command::Define)
    }

    private fun word(raw: String): String? {
        val word = raw.trim().trim('"', '“', '”', '\'').removePrefix("the word ").trim()
        if (word.isEmpty() || word in notWords || words(word).size > MAX_WORD_PARTS) return null
        return word
    }

    private val whatIs = Regex("^(?:what|who) (?:is|are|was|were)(?: an?| the)? (.+)$")
    private val whatsShort = Regex("^(?:what's|whats|who's|whos)(?: an?| the)? (.+)$")
    private val tellMe = Regex(
        "^(?:tell me about|tell me something about|define|definition of|meaning of|look up|lookup|" +
            "search wikipedia for|wikipedia|wiki)(?: an?| the)? (.+)$",
    )
    private val whatDoesMean = Regex("^what does(?: an?| the)? (.+) mean$")
    private val onWikipedia = Regex("^(.+?) (?:on |in )?wikipedia$")

    /** Questions that aren't about a topic, or that other parts of the assistant answer. */
    private val notTopics = setOf(
        "up", "new", "going on", "happening", "this", "that", "it", "there", "you", "your name", "my name",
        "the matter", "wrong", "next", "on", "playing", "this song", "calling",
    )

    private fun lookup(text: String): Command? {
        val topic = whatDoesMean.matchEntire(text)?.groupValues?.get(1)
            ?: whatIs.matchEntire(text)?.groupValues?.get(1)
            ?: whatsShort.matchEntire(text)?.groupValues?.get(1)
            ?: tellMe.matchEntire(text)?.groupValues?.get(1)
            ?: onWikipedia.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val cleaned = topic.trim().removePrefix("the ").trim()
        if (cleaned.isEmpty() || cleaned in notTopics) return null
        // "what is 12*7" is the calculator's.
        if (com.fastsearch.search.Calculator.evaluate(cleaned) != null) return null
        return Command.Lookup(cleaned)
    }

    // Longest first: "search the web for x" isn't a search for "the web for x".
    private val searchLeads = listOf(
        "search on the internet for", "search the internet for", "search duckduckgo for", "search on google for",
        "search the web for", "google search for", "search online for", "search google for", "search bing for",
        "look online for", "web search for", "search the web", "look up online", "google search", "search online",
        "find online", "web search", "search for", "search", "google",
    )
    private val searchPattern = Regex("^(?:${searchLeads.joinToString("|")})(?: for)? (.+)$")
    private val searchOnWeb = Regex("^(.+?) (?:on the web|on the internet|online|on google)$")
    private val findOnWeb = Regex("^(?:search for|look up|look for|search|find|google) (.+?) (?:on the web|on the internet|online|on google)$")

    /** "google maps", "google photos": the user is looking for the app, not searching Google. */
    private val googleApps = setOf(
        "maps", "photos", "drive", "play", "play store", "chrome", "calendar", "keep", "translate", "lens", "news", "home",
        "assistant", "pay", "wallet", "fit", "meet", "docs", "sheets", "slides", "earth", "tv", "files", "messages",
        "contacts", "phone", "clock", "calculator", "camera", "gemini", "one", "authenticator", "podcasts", "music",
        "app", "settings", "account", "voice", "duo", "chat", "gmail",
    )

    private fun webSearch(text: String): Command? {
        // "look up pizza places online"
        findOnWeb.matchEntire(text)?.let { return Command.WebSearch(it.groupValues[1].trim()) }
        val m = searchPattern.matchEntire(text) ?: return null
        val query = m.groupValues[1].trim()
        // "search wikipedia for ..." is the Wikipedia lookup's.
        if (query.startsWith("wikipedia") || query.startsWith("wiki ")) return null
        if (text.startsWith("google ") && !text.startsWith("google search") && query in googleApps) return null
        // "search for x on the web" says the same thing twice.
        val cleaned = searchOnWeb.matchEntire(query)?.groupValues?.get(1)?.trim() ?: query
        return cleaned.takeIf { it.isNotEmpty() }?.let(Command::WebSearch)
    }

    private val openPattern = Regex("^(?:open|launch|start|run|go to|show me) (?:up )?(?:the |my )?(.+?)(?: app| application)?$")

    private fun openApp(text: String): Command? {
        val name = openPattern.matchEntire(text)?.groupValues?.get(1)?.trim() ?: return null
        if (name.isEmpty()) return null
        panel(name)?.let { return it }
        return Command.OpenApp(name)
    }

    // endregion

    private fun integration(text: String, slot: Slot): Command? {
        for (installed in integrations) {
            installed.parser.parse(text, slot)?.let { return Command.Integration(installed.id, it) }
        }
        return null
    }

    private fun conversion(text: String): Command? = UnitConverter.parse(text)?.let(Command::Convert)

    private fun currency(text: String): Command? = Currencies.parse(text)

    private const val MAX_LENGTH = 160
    private const val MAX_TIMER_SECONDS = 24 * 3600
    private const val SECONDS_PER_DAY = 24 * 3600
    private const val MAX_REMINDER_DAYS = 365
    private const val MAX_SNOOZE_MINUTES = 60
    private const val MAX_EVENT_MINUTES = 24 * 60
    private const val MAX_DICE = 10
    private const val MAX_SIDES = 1000
    private const val MAX_WORD_PARTS = 3
}
