package com.bestassistant.commands

import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Greek requests ("βάλε ξυπνητήρι στις 7 το πρωί", "παίξε Βασίλη Παπακωνσταντίνου στο Spotify",
 * "τι καιρό κάνει αύριο στη Θεσσαλονίκη") turned into the same [Command]s as English.
 *
 * Like [CommandParser], every phrase that works is one a matcher below accepts. Matching
 * happens on an accent-free copy of the text (so "ξυπνητήρι" and "ξυπνητηρι" are the same),
 * while names, songs, places and labels are cut from the original, accents and all.
 */
object GreekCommandParser {

    private val greek = Locale.forLanguageTag("el")
    private val greekLetter = Regex("[\\u0370-\\u03FF\\u1F00-\\u1FFF]")

    fun isGreek(text: String) = greekLetter.containsMatchIn(text)

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Command? {
        val t = prepare(input) ?: return null
        return conversion(t)
            ?: timer(t)
            ?: alarm(t, now)
            ?: flashlight(t)
            ?: volume(t)
            ?: media(t)
            ?: panel(t)
            ?: music(t)
            ?: weather(t)
            ?: clock(t)
            ?: camera(t)
            ?: navigate(t)
            ?: call(t)
            ?: message(t)
            ?: lookup(t)
            ?: openApp(t)
    }

    // region Text

    /**
     * The request in two aligned forms of the same length: [shown] is lowercase with accents,
     * [key] is accent-free with final sigma folded, for matching. A range in one is the same
     * words in the other.
     */
    internal class Text(val shown: String, val key: String) {
        val keys: List<String> = key.split(' ')
        val words: List<String> = shown.split(' ')

        fun cut(range: IntRange) = shown.substring(range).trim()
        fun cut(group: MatchGroup?) = group?.let { cut(it.range) }?.takeIf { it.isNotEmpty() }
    }

    private val politePrefixes = listOf(
        "σε παρακαλω ", "παρακαλω ", "μπορεισ να ", "μπορειτε να ", "θα μπορουσεσ να ", "θα μπορουσατε να ",
        "θελω να ", "θα ηθελα να ", "θελω ", "θα ηθελα ", "γρηγορα ", "απλα ", "λοιπον ", "γεια σου ", "γεια ",
        "εε ", "ε ", "ει ", "ok ", "οκ ", "οκει ", "okay ", "hey ", "κογκ ", "cog ", "αν μπορεισ ",
    )
    private val politeSuffixes = listOf(" σε παρακαλω", " παρακαλω", " ευχαριστω", " αμεσωσ", " γρηγορα", " αν μπορεισ")

    internal fun prepare(input: String): Text? {
        var shown = input.trim().lowercase(greek)
            .replace('’', '\'').replace('΄', '\'').replace(';', ';')
            .replace(Regex("[«»\"“”]"), " ")
            // Commas between words are punctuation; inside numbers ("2,5") they're decimals.
            .replace(Regex(",(?=\\s|$)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('?', '!', '.', ',', ';', ' ')
        var key = fold(shown)
        var changed = true
        while (changed) {
            changed = false
            for (prefix in politePrefixes) {
                if (key.startsWith(prefix) && key.length > prefix.length) {
                    key = key.substring(prefix.length)
                    shown = shown.substring(prefix.length)
                    changed = true
                }
            }
            for (suffix in politeSuffixes) {
                if (key.endsWith(suffix) && key.length > suffix.length) {
                    key = key.dropLast(suffix.length)
                    shown = shown.dropLast(suffix.length)
                    changed = true
                }
            }
        }
        if (key.isBlank() || key.length > MAX_LENGTH) return null
        return Text(shown, key)
    }

    /** Accent-free, final-sigma-free, one character for one character. */
    internal fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            val folded = when {
                c == 'ς' -> 'σ'
                c in 'Ͱ'..'Ͽ' || c in 'ἀ'..'῿' -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
                else -> c
            }
            append(folded)
        }
    }

    // endregion

    // region Numbers, durations, times

    private val units = mapOf(
        "μηδεν" to 0, "ενα" to 1, "ενασ" to 1, "μια" to 1, "μιαν" to 1, "μιασ" to 1, "ενοσ" to 1,
        "δυο" to 2, "τρια" to 3, "τρεισ" to 3, "τεσσερα" to 4, "τεσσερισ" to 4, "πεντε" to 5, "εξι" to 6,
        "επτα" to 7, "εφτα" to 7, "οκτω" to 8, "οχτω" to 8, "εννεα" to 9, "εννια" to 9, "δεκα" to 10,
        "εντεκα" to 11, "δωδεκα" to 12, "δεκατρια" to 13, "δεκατρεισ" to 13, "δεκατεσσερα" to 14,
        "δεκατεσσερισ" to 14, "δεκαπεντε" to 15, "δεκαεξι" to 16, "δεκαεξη" to 16, "δεκαεπτα" to 17,
        "δεκαεφτα" to 17, "δεκαοκτω" to 18, "δεκαοχτω" to 18, "δεκαεννεα" to 19, "δεκαεννια" to 19,
    )
    private val tens = mapOf(
        "εικοσι" to 20, "τριαντα" to 30, "σαραντα" to 40, "πενηντα" to 50, "εξηντα" to 60,
        "εβδομηντα" to 70, "ογδοντα" to 80, "ενενηντα" to 90,
    )

    /** "δυόμισι", "μιάμιση": a number and a half, in one word. */
    private val andAHalf = mapOf(
        "μιαμιση" to "1.5", "μιαμισυ" to "1.5", "εναμιση" to "1.5", "εναμισυ" to "1.5", "εναμισησ" to "1.5",
        "δυομιση" to "2.5", "δυομισι" to "2.5", "τρεισημιση" to "3.5", "τρισημιση" to "3.5",
        "τεσσερισημιση" to "4.5", "πεντεμιση" to "5.5", "εξιμιση" to "6.5", "εφταμιση" to "7.5", "επταμιση" to "7.5",
        "οχτωμιση" to "8.5", "οκτωμιση" to "8.5", "εννιαμιση" to "9.5", "δεκαμιση" to "10.5",
    )

    /** Replaces number words with digits, in accent-free text: "είκοσι πέντε λεπτά" -> "25 λεπτα". */
    internal fun digitize(key: String): String {
        val words = key.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val ten = tens[word]
            when {
                // "τοις εκατό" is "percent", not a hundred.
                (word == "εκατο" || word == "εκατον") && words.getOrNull(i - 1) != "τοισ" -> out += "100"
                ten != null -> {
                    val next = words.getOrNull(i + 1)?.let { units[it] }
                    if (next != null && next in 1..9) {
                        out += (ten + next).toString()
                        i++
                    } else {
                        out += ten.toString()
                    }
                }
                word in units -> out += units.getValue(word).toString()
                word in andAHalf -> out += andAHalf.getValue(word)
                else -> out += word
            }
            i++
        }
        return out.joinToString(" ")
    }

    private val number = "\\d+(?:[.,]\\d+)?"
    private val unitWords = "ωρεσ|ωρα|ωρων|ωρασ|ωρακι|ω|λεπτα|λεπτο|λεπτων|λεπτου|λεπτακια|λεπτακι|λεπ|λ|" +
        "δευτερολεπτα|δευτερολεπτο|δευτερολεπτων|δευτ|δ|h|min|mins|s|sec|secs"
    private val durationPart = Regex("($number) ?($unitWords)(?![\\p{L}])")

    /**
     * A duration in seconds when the whole of [raw] (accent-free) is one: "5 λεπτά", "μισή ώρα",
     * "μιάμιση ώρα", "1 ώρα και 30 λεπτά", "δυο ώρες και μισή", "ένα τέταρτο".
     */
    internal fun duration(raw: String, bareNumberUnit: Int? = null): Int? {
        var text = " ${digitize(raw.trim())} "
            .replace(Regex(" (?:1 |μια )?μιση ωρα "), " 30 λεπτα ")
            .replace(Regex(" (?:1 |μιαν? )?μισαωρο(?:ακι)? "), " 30 λεπτα ")
            .replace(Regex(" (?:1 )?τεταρτο(?:ακι)?(?: τησ ωρασ)? "), " 15 λεπτα ")
            .replace(Regex(" ($number) και μιση (ωρεσ|ωρα|λεπτα|λεπτο) "), " $1.5 $2 ")
            .replace(Regex(" ($number) (ωρεσ|ωρα|λεπτα|λεπτο) και μιση "), " $1.5 $2 ")
            .replace(Regex("^ ωρα και μιση "), " 1.5 ωρα ")
            .trim()
        if (text.isEmpty()) return null
        if (bareNumberUnit != null && Regex("^$number$").matches(text)) {
            return text.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * bareNumberUnit).toInt() }?.takeIf { it > 0 }
        }
        var total = 0.0
        var matched = 0
        var position = 0
        while (position < text.length) {
            val m = durationPart.find(text, position) ?: break
            val between = text.substring(position, m.range.first).trim()
            if (between.isNotEmpty() && between != "και") return null
            val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            total += amount * unitSeconds(m.groupValues[2])
            matched++
            position = m.range.last + 1
        }
        if (matched == 0 || text.substring(position).isNotBlank()) return null
        return Math.round(total).toInt().takeIf { it > 0 }
    }

    private fun unitSeconds(unit: String): Int = when {
        unit.startsWith("ω") || unit == "h" -> 3600
        unit.startsWith("λ") || unit.startsWith("min") -> 60
        else -> 1
    }

    private val clockPattern = Regex(
        "^(?:η |τισ |στισ )?(\\d{1,2})(?:[:.](\\d{2}))?" +
            "(?: (και|παρα) (μιση|τεταρτο|\\d{1,2})(?: λεπτα)?)?" +
            "(?: η ωρα)?" +
            "(?: (το πρωι|πρωι|τα ξημερωματα|ξημερωματα|το απογευμα|απογευμα|το βραδυ|βραδυ|το βραδακι|" +
            "το μεσημερι|μεσημερι|τη νυχτα|π\\.? ?μ\\.?|μ\\.? ?μ\\.?|am|pm))?$",
    )

    /** "7", "7:30", "7 και μισή", "7 παρά τέταρτο", "9 το βράδυ", "6 π.μ.", "μεσημέρι". Accent-free input. */
    internal fun timeOfDay(raw: String): Spoken.ClockTime? {
        val text = digitize(raw.trim())
        when (text) {
            "μεσημερι", "το μεσημερι" -> return Spoken.ClockTime(12, 0, Spoken.Meridiem.PM)
            "μεσανυχτα", "τα μεσανυχτα" -> return Spoken.ClockTime(0, 0, null)
        }
        val m = clockPattern.matchEntire(text) ?: return null
        var hour = m.groupValues[1].toInt()
        var minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val explicit24 = m.groupValues[1].length == 2 && m.groupValues[1].startsWith("0")
        val offset = when (val amount = m.groupValues[4]) {
            "" -> 0
            "μιση" -> 30
            "τεταρτο" -> 15
            else -> amount.toInt()
        }
        when (m.groupValues[3]) {
            "και" -> minute += offset
            "παρα" -> {
                if (offset !in 1..59) return null
                hour = if (hour == 0) 23 else hour - 1
                minute = 60 - offset
            }
        }
        if (minute !in 0..59) return null
        val meridiem = when (m.groupValues[5].replace(Regex("[ .]"), "")) {
            "" -> null
            "τοπρωι", "πρωι", "ταξημερωματα", "ξημερωματα", "πμ", "am" -> Spoken.Meridiem.AM
            else -> Spoken.Meridiem.PM
        }
        return when {
            meridiem != null -> if (hour in 1..12) Spoken.ClockTime(hour, minute, meridiem) else if (hour in 13..23) {
                Spoken.ClockTime(hour, minute, Spoken.Meridiem.PM)
            } else {
                null
            }
            hour !in 0..23 -> null
            explicit24 || hour == 0 || hour > 12 -> Spoken.ClockTime(hour, minute, if (hour >= 12) Spoken.Meridiem.PM else Spoken.Meridiem.AM)
            else -> Spoken.ClockTime(hour, minute, null)
        }
    }

    private fun isNumberWord(key: String) = key in units || key in tens || key in andAHalf || key.any(Char::isDigit)

    /** Words left once fillers and numbers are gone, cut from the original text: the label of an alarm or timer. */
    private fun label(t: Text, fillers: Set<String>): String? {
        val kept = t.keys.indices.filter { i ->
            val k = t.keys[i].trim('.', ':')
            k.isNotEmpty() && k !in fillers && !isNumberWord(k) && k !in timeWords
        }
        return kept.joinToString(" ") { t.words[it] }.trim().takeIf { it.isNotEmpty() }
    }

    private val timeWords = setOf(
        "και", "παρα", "μιση", "τεταρτο", "λεπτα", "λεπτο", "λεπτων", "ωρα", "ωρεσ", "ωρων", "δευτερολεπτα",
        "δευτερολεπτο", "π.μ", "μ.μ", "πμ", "μμ", "π", "μ", "πρωι", "βραδυ", "απογευμα", "μεσημερι", "βραδακι",
        "ξημερωματα", "νυχτα", "μεσανυχτα", "αποψε", "αυριο", "σημερα", "μισαωρο", "λεπ", "ω", "min", "h", "s",
    )

    // endregion

    // region Clock app

    private val timerWord = Regex("χρονομετρ|χρονοδιακοπτ|αντιστροφη μετρηση|αντιστροφη μετρησ|\\btimer")
    private val showTimers = Regex("^(?:δειξε |ανοιξε |δειξε μου )?(?:τα |ολα τα )?(?:χρονομετρα|χρονοδιακοπτεσ)(?: μου)?$")
    private val timerFillers = setOf(
        "βαλε", "βαλτε", "βαλεισ", "ορισε", "ορισεισ", "ρυθμισε", "ρυθμισεισ", "φτιαξε", "φτιαξεισ", "κανε", "ξεκινα",
        "ξεκινησε", "ξεκινησεισ", "ενα", "εναν", "μια", "ενασ", "χρονομετρο", "χρονομετρου", "χρονοδιακοπτη",
        "χρονοδιακοπτησ", "αντιστροφη", "μετρηση", "timer", "για", "σε", "στα", "στο", "τα", "το", "του", "τησ", "με",
        "ονομα", "και", "νεο", "μου", "να", "τη", "την", "ετικετα", "που", "λεγεται",
    )

    private fun timer(t: Text): Command? {
        if (showTimers.matches(t.key)) return Command.ShowTimers
        if (!timerWord.containsMatchIn(t.key)) return null
        val seconds = longestDuration(t.keys) ?: return null
        if (seconds > MAX_TIMER_SECONDS) return null
        return Command.SetTimer(seconds, label(t, timerFillers))
    }

    /** The longest run of words that reads as a duration. A lone number means minutes. */
    private fun longestDuration(keys: List<String>): Int? {
        for (length in keys.size downTo 1) {
            for (start in 0..keys.size - length) {
                val span = keys.subList(start, start + length).joinToString(" ")
                // A lone number means minutes, but "ένα"/"μια" are usually just "a".
                val bare = length == 1 && span !in articleNumbers
                val seconds = duration(span, bareNumberUnit = if (bare) 60 else null) ?: continue
                return seconds
            }
        }
        return null
    }

    private val articleNumbers = setOf("ενα", "εναν", "ενασ", "μια", "μιαν")

    private val alarmWord = Regex("ξυπνητηρ|ξυπνα με|ξυπνησε με|με ξυπνησεισ|με ξυπνησει|ξυπνημα|\\balarm")
    private val showAlarms = Regex("^(?:δειξε |ανοιξε |δειξε μου )?(?:τα |ολα τα |ποια )?ξυπνητηρια(?: μου| εχω)?$")
    private val relativeAlarm = Regex("(?:^| )σε (.+)$")
    // Explicit space boundaries: "\b" around Greek letters differs between the JVM and Android.
    private val morning = Regex("(?:^| )(?:το πρωι|πρωι|ξημερωματα|τα ξημερωματα|πρωινο)(?= |$)")
    private val evening = Regex("(?:^| )(?:το απογευμα|απογευμα|το βραδυ|βραδυ|αποψε|βραδακι|τη νυχτα)(?= |$)")
    private val tomorrow = Regex("(?:^| )αυριο(?: |$)")
    private val alarmFillers = setOf(
        "βαλε", "βαλτε", "βαλεισ", "ορισε", "ορισεισ", "ρυθμισε", "ρυθμισεισ", "φτιαξε", "κανε", "ενα", "μια", "εναν",
        "ξυπνητηρι", "ξυπνητηρια", "ξυπνητηριου", "ξυπνα", "ξυπνησε", "ξυπνησεισ", "ξυπνησει", "ξυπνημα", "με", "να",
        "για", "στισ", "τισ", "τη", "την", "το", "του", "τησ", "η", "και", "νεο", "ονομα", "ετικετα", "alarm", "μου",
        "στη", "στο", "στην", "που", "λεγεται", "αυριο", "σημερα",
    )

    private fun alarm(t: Text, now: LocalDateTime): Command? {
        if (showAlarms.matches(t.key)) return Command.ShowAlarms
        if (!alarmWord.containsMatchIn(t.key)) return null

        relativeAlarm.find(t.key)?.let { m ->
            duration(m.groupValues[1])?.let { seconds ->
                val at = now.plusSeconds(seconds.toLong())
                return Command.SetAlarm(at.hour, at.minute)
            }
        }

        val hint = when {
            morning.containsMatchIn(t.key) -> Spoken.Meridiem.AM
            evening.containsMatchIn(t.key) -> Spoken.Meridiem.PM
            // "Tomorrow at 6:30" is a morning alarm.
            tomorrow.containsMatchIn(t.key) -> Spoken.Meridiem.AM
            else -> null
        }
        val keys = t.keys
        for (length in minOf(keys.size, 7) downTo 1) {
            for (start in 0..keys.size - length) {
                val time = timeOfDay(keys.subList(start, start + length).joinToString(" ")) ?: continue
                val withHint = if (time.meridiem == null && hint != null && time.hour in 1..12) time.copy(meridiem = hint) else time
                val resolved = withHint.resolve(now.toLocalTime())
                return Command.SetAlarm(resolved.hour, resolved.minute, label(t, alarmFillers))
            }
        }
        return null
    }

    // endregion

    // region Device controls

    private val torchMention = Regex("(?:^| )(?:φακο|φακοσ|φακου|φακακι|φλασ|flashlight|torch)(?: |$)")
    private val torchWords = setOf(
        "αναψε", "αναψεισ", "ανοιξε", "ανοιξεισ", "σβησε", "σβησεισ", "κλεισε", "κλεισεισ", "ενεργοποιησε",
        "απενεργοποιησε", "αναμμα", "σβησιμο", "τον", "το", "ο", "φακο", "φακοσ", "φακου", "φακακι", "φλασ", "μου",
        "του", "κινητου", "τηλεφωνου", "βαλε", "βγαλε", "να", "flashlight", "torch", "on", "off",
    )

    private fun flashlight(t: Text): Command? {
        if (!torchMention.containsMatchIn(t.key) || !t.keys.all { it in torchWords }) return null
        val words = t.keys
        val state = when {
            words.any { it in setOf("αναψε", "αναψεισ", "ανοιξε", "ανοιξεισ", "ενεργοποιησε", "αναμμα", "βαλε", "on") } -> Switch.ON
            words.any { it in setOf("σβησε", "σβησεισ", "κλεισε", "κλεισεισ", "απενεργοποιησε", "σβησιμο", "βγαλε", "off") } -> Switch.OFF
            else -> Switch.TOGGLE
        }
        return Command.Flashlight(state)
    }

    private val volumeSet = Regex(
        "^(?:βαλε |ρυθμισε |αλλαξε |γυρισε |κανε )?(?:την |τη )?ενταση(?: του ηχου| πολυμεσων| τησ μουσικησ)? " +
            "(?:στο |στα |σε |στον )?(\\d{1,3})(?: ?%| τοισ εκατο| τοισ εκατον| στα εκατο)?$",
    )
    private val volumeWord = Regex(
        "^(?:βαλε |ρυθμισε |κανε )?(?:την |τη )?ενταση (?:στο |στη |στην |σε )?(μεγιστο|τερμα|φουλ|τερματα|μεση|μισο|μιση|μηδεν|ελαχιστο)$",
    )
    private val volumeUp = setOf(
        "δυναμωσε", "δυναμωσε το", "δυναμωσε τον ηχο", "δυναμωσε την ενταση", "δυναμωσε τη μουσικη", "δυναμωσε λιγο",
        "ανεβασε την ενταση", "ανεβασε τον ηχο", "ανεβασε το", "ανεβασε λιγο", "πιο δυνατα", "πιο δυνατα τη μουσικη",
        "ενταση πανω", "αυξησε την ενταση", "αυξηση εντασησ", "περισσοτερη ενταση", "πιο πολυ ενταση",
    )
    private val volumeDown = setOf(
        "χαμηλωσε", "χαμηλωσε το", "χαμηλωσε τον ηχο", "χαμηλωσε την ενταση", "χαμηλωσε τη μουσικη", "χαμηλωσε λιγο",
        "κατεβασε την ενταση", "κατεβασε τον ηχο", "κατεβασε το", "κατεβασε λιγο", "πιο σιγα", "πιο χαμηλα",
        "ενταση κατω", "μειωσε την ενταση", "μειωση εντασησ", "λιγοτερη ενταση", "πιο λιγη ενταση",
    )
    private val mute = setOf(
        "σιγαση", "βουβο", "κλεισε τον ηχο", "βαλε σιγαση", "σιγαση ηχου", "κανε σιγαση", "βουβανε", "σιγαση πολυμεσων",
        "κλεισε την ενταση",
    )
    private val unmute = setOf(
        "ανοιξε τον ηχο", "αρση σιγασησ", "βγαλε τη σιγαση", "βγαλε την σιγαση", "ξεβουβανε", "επαναφορα ηχου",
        "ανοιξε την ενταση",
    )

    private fun volume(t: Text): Command? {
        val digits = digitize(t.key)
        volumeSet.matchEntire(digits)?.let { m ->
            val percent = m.groupValues[1].toInt()
            return if (percent in 0..100) Command.Volume(VolumeChange.Set(percent)) else null
        }
        volumeWord.matchEntire(t.key)?.let { m ->
            val percent = when (m.groupValues[1]) {
                "μεγιστο", "τερμα", "φουλ", "τερματα" -> 100
                "μεση", "μισο", "μιση" -> 50
                else -> 0
            }
            return Command.Volume(VolumeChange.Set(percent))
        }
        val change = when (t.key) {
            in volumeUp -> VolumeChange.Up
            in volumeDown -> VolumeChange.Down
            in mute -> VolumeChange.Mute
            in unmute -> VolumeChange.Unmute
            "τερμα την ενταση", "φουλ την ενταση", "ενταση στο φουλ", "στο φουλ" -> VolumeChange.Set(100)
            else -> return null
        }
        return Command.Volume(change)
    }

    private val pause = setOf(
        "παυση", "σταματα", "σταματα τη μουσικη", "σταματα το τραγουδι", "σταματησε τη μουσικη", "σταματησε",
        "κανε παυση", "βαλε παυση", "παυση μουσικησ", "σταματα την αναπαραγωγη", "κλεισε τη μουσικη", "pause",
        "σταματα το", "παυση στη μουσικη",
    )
    private val resume = setOf(
        "συνεχισε", "συνεχεια", "συνεχισε τη μουσικη", "συνεχισε το τραγουδι", "συνεχισε την αναπαραγωγη", "παιξε",
        "παιξε μουσικη", "βαλε μουσικη", "αναπαραγωγη", "ξεκινα τη μουσικη", "play", "βαλε παλι μουσικη", "παιξε κατι",
    )
    private val next = setOf(
        "επομενο", "επομενο τραγουδι", "επομενο κομματι", "αλλο τραγουδι", "αλλαξε τραγουδι", "αλλαξε κομματι",
        "αλλαξε το τραγουδι", "προσπερασε", "προσπερνα", "προσπερασε το τραγουδι", "παραλειψη", "βαλε το επομενο",
        "παιξε το επομενο", "το επομενο", "next", "επομενο κομματι παρακαλω",
    )
    private val previous = setOf(
        "προηγουμενο", "προηγουμενο τραγουδι", "προηγουμενο κομματι", "βαλε το προηγουμενο", "παιξε το προηγουμενο",
        "το προηγουμενο", "γυρνα πισω", "πισω ενα τραγουδι", "ξανα το προηγουμενο",
    )
    private val nowPlaying = setOf(
        "τι παιζει", "τι παιζει τωρα", "ποιο τραγουδι ειναι αυτο", "ποιο ειναι αυτο το τραγουδι", "τι τραγουδι ειναι αυτο",
        "τι τραγουδι παιζει", "ποιοσ τραγουδαει", "ποιοσ το τραγουδαει", "ποιοσ τραγουδαει αυτο", "τι ακουω",
        "τι ακουμε", "πωσ λεγεται αυτο το τραγουδι", "πωσ λεγεται το τραγουδι", "τι κομματι ειναι αυτο",
        "ποιο κομματι ειναι αυτο", "ποιο τραγουδι παιζει",
    )
    private val like = setOf(
        "μου αρεσει αυτο το τραγουδι", "μου αρεσει το τραγουδι", "μου αρεσει", "λαικ", "κανε λαικ", "βαλε καρδια",
        "αποθηκευσε το τραγουδι", "αποθηκευσε αυτο το τραγουδι", "προσθεσε στα αγαπημενα", "βαλε το στα αγαπημενα",
        "προσθεσε το στα αγαπημενα", "προσθεσε το τραγουδι στα αγαπημενα", "στα αγαπημενα", "like",
    )
    private val shuffleOn = setOf(
        "τυχαια αναπαραγωγη", "ανακατεμα", "ανακατεψε", "ανακατεψε τα τραγουδια", "βαλε τυχαια σειρα",
        "ενεργοποιησε την τυχαια αναπαραγωγη", "ανοιξε την τυχαια αναπαραγωγη", "βαλε τυχαια αναπαραγωγη", "shuffle",
    )
    private val shuffleOff = setOf(
        "σταματα την τυχαια αναπαραγωγη", "απενεργοποιησε την τυχαια αναπαραγωγη", "κλεισε την τυχαια αναπαραγωγη",
        "χωρισ ανακατεμα", "οχι τυχαια", "βγαλε την τυχαια αναπαραγωγη",
    )
    private val repeatOn = setOf(
        "επαναληψη", "βαλε επαναληψη", "ενεργοποιησε την επαναληψη", "επαναλαβε το τραγουδι", "επαναλαβε αυτο το τραγουδι",
        "ξανα και ξανα", "repeat", "βαλε το σε επαναληψη", "ανοιξε την επαναληψη",
    )
    private val repeatOff = setOf(
        "σταματα την επαναληψη", "απενεργοποιησε την επαναληψη", "κλεισε την επαναληψη", "χωρισ επαναληψη",
        "βγαλε την επαναληψη",
    )

    private fun media(t: Text): Command? = when (t.key) {
        in nowPlaying -> Command.NowPlaying
        in like -> Command.LikeSong
        in shuffleOn -> Command.Shuffle(true)
        in shuffleOff -> Command.Shuffle(false)
        in repeatOn -> Command.Repeat(true)
        in repeatOff -> Command.Repeat(false)
        in pause -> Command.Media(MediaAction.PAUSE)
        in resume -> Command.Media(MediaAction.PLAY)
        in next -> Command.Media(MediaAction.NEXT)
        in previous -> Command.Media(MediaAction.PREVIOUS)
        else -> null
    }

    private val musicApps = listOf(
        "youtube music" to MusicApp.YOUTUBE_MUSIC, "yt music" to MusicApp.YOUTUBE_MUSIC,
        "γιουτιουμπ μιουζικ" to MusicApp.YOUTUBE_MUSIC, "spotify" to MusicApp.SPOTIFY, "σποτιφαι" to MusicApp.SPOTIFY,
        "σποτιφι" to MusicApp.SPOTIFY, "youtube" to MusicApp.YOUTUBE, "γιουτιουμπ" to MusicApp.YOUTUBE,
        "deezer" to MusicApp.DEEZER, "ντιζερ" to MusicApp.DEEZER, "apple music" to MusicApp.APPLE_MUSIC,
        "soundcloud" to MusicApp.SOUNDCLOUD, "tidal" to MusicApp.TIDAL, "amazon music" to MusicApp.AMAZON_MUSIC,
    )
    private val musicLead = Regex(
        "^(?:παιξε|παιξεισ|παιξτε|βαλε|βαλεισ|βαλτε|ακουσω|να ακουσω|ν ακουσω|ακουσε|ξεκινα|ανακατεψε)" +
            "(?: μου| μασ)?(?: (λιγο|λιγη|λιγα|κατι απο|μουσικη απο|μουσικη του|μουσικη τησ|μουσικη των|μουσικη" +
            "|τραγουδια απο|τραγουδια του|τραγουδια τησ|τραγουδια των|τραγουδια|το τραγουδι|το κομματι|το αλμπουμ|τον δισκο" +
            "|τη λιστα μου|την λιστα μου|τη λιστα|την λιστα|την playlist μου|το playlist μου|την playlist|το playlist))? (.+)$",
    )

    /** What the words before the name say it is: "το τραγούδι ...", "τη λίστα μου ...", "τραγούδια του ...". */
    private fun musicKind(qualifier: String): MusicKind = when {
        qualifier == "το τραγουδι" || qualifier == "το κομματι" -> MusicKind.SONG
        qualifier == "το αλμπουμ" || qualifier == "τον δισκο" -> MusicKind.ALBUM
        "λιστα" in qualifier || "playlist" in qualifier -> MusicKind.PLAYLIST
        qualifier.endsWith(" απο") || qualifier.endsWith(" του") || qualifier.endsWith(" τησ") || qualifier.endsWith(" των") -> MusicKind.ARTIST
        else -> MusicKind.ANY
    }
    private val likedSongs = setOf(
        "τα αγαπημενα μου", "τα αγαπημενα μου τραγουδια", "αγαπημενα τραγουδια", "τα αγαπημενα τραγουδια", "τα αγαπημενα",
        "τα τραγουδια που μου αρεσουν", "τα likes μου", "τα liked songs", "liked songs",
    )

    /** "... των Iron Maiden" after a song or album title: the last one counts. */
    private val byArtist = Regex(" (?:των|του|τησ|απο τουσ|απο τον|απο την|απο) ")
    private val musicAppTail: Regex by lazy {
        Regex(" (?:στο|στην|απο το|απο|με το|μεσω|μεσω του) (${musicApps.joinToString("|") { Regex.escape(it.first) }})$")
    }
    private val notMusic = setOf(
        "μουσικη", "τραγουδι", "κατι", "οτιδηποτε", "το επομενο", "επομενο", "προηγουμενο", "ξανα", "παλι", "ενταση",
        "ξυπνητηρι", "χρονομετρο", "ηχο", "τον ηχο", "φακο", "τον φακο", "παυση", "σιγαση", "το", "μου", "αυτο",
        "υπενθυμιση", "σημειωση", "ραντεβου", "ημερολογιο", "τελοσ", "στοπ", "πλυντηριο",
    )

    private fun music(t: Text): Command? {
        // "βάλε" also sets things: "βάλε ένα χρονόμετρο" without a duration isn't a song.
        if (timerWord.containsMatchIn(t.key) || alarmWord.containsMatchIn(t.key)) return null
        val lead = musicLead.matchEntire(t.key) ?: return null
        val qualifier = lead.groupValues[1]
        val payload = lead.groups[2] ?: return null
        val app = musicAppTail.find(t.key, payload.range.first)
        val queryRange = if (app != null) payload.range.first until app.range.first else payload.range
        val queryKey = t.key.substring(queryRange).trim()
        if (queryKey.isEmpty() || queryKey in notMusic) return null
        val query = t.cut(queryRange)
        val musicApp = app?.groupValues?.get(1)?.let { name -> musicApps.first { it.first == name }.second }
        // "παίξε spotify": open the app rather than search it for its own name.
        if (musicApp == null && qualifier.isEmpty()) {
            musicApps.firstOrNull { it.first == queryKey }?.let { return Command.OpenApp(it.second.label) }
        }
        if (queryKey in likedSongs) return Command.PlayMusic(query, musicApp, MusicKind.LIKED)
        val kind = musicKind(qualifier)
        // "παίξε τη γυμναστική playlist"
        if (kind == MusicKind.ANY && queryKey.endsWith(" playlist")) {
            val name = query.removeSuffix("playlist").trim().replace(Regex("^(?:τη|την|το|η|μου) "), "")
            return Command.PlayMusic(name, musicApp, MusicKind.PLAYLIST)
        }
        if (kind == MusicKind.SONG || kind == MusicKind.ALBUM) {
            byArtist.findAll(queryKey).lastOrNull()?.let { by ->
                // queryKey is the trimmed query: find where it starts in the full text.
                val start = queryRange.first + t.key.substring(queryRange).indexOf(queryKey)
                val title = t.cut(start until start + by.range.first)
                val artist = t.cut(start + by.range.last + 1..queryRange.last)
                if (title.isNotEmpty() && artist.isNotEmpty()) return Command.PlayMusic(title, musicApp, kind, artist)
            }
        }
        return Command.PlayMusic(query, musicApp, kind)
    }

    // endregion

    // region Weather & time

    private val weatherLead = Regex(
        "^(?:πωσ (?:ειναι|θα ειναι|ειν) |τι (?:λεει |θα κανει |κανει )?|πεσ μου |δειξε μου |ποιοσ ειναι |ποια ειναι |" +
            "ποια θα ειναι |πωσ παει |θελω )?(?:ο |τον |η |την |του |τη )?" +
            "(?:καιροσ|καιρο|καιρου|προγνωση(?: του)? καιρου|προγνωση|θερμοκρασια|καιρικεσ συνθηκεσ)(?= |$)",
    )
    private val weatherQuestion = Regex(
        "^(?:θα |μηπωσ |λεσ να |θα ειναι |ειναι )?(?:βρεξει|βρεχει|χιονισει|χιονιζει|κανει κρυο|κανει ζεστη|" +
            "θα κανει κρυο|θα κανει ζεστη|εχει ηλιο|εχει λιακαδα|εχει αερα|φυσαει|θα φυσαει|εχει συννεφα|" +
            "ειναι συννεφιασμενοσ|ριχνει)(?= |$)",
    )
    private val howHot = Regex("^(?:ποσουσ βαθμουσ|ποση ζεστη|ποσο κρυο|ποση θερμοκρασια) (?:εχει|κανει|θα εχει|θα κανει|ειναι|εχουμε)(?= |$)")
    private val umbrella = Regex("^(?:χρειαζομαι|θα χρειαστω|να παρω|θελει) (?:ομπρελα|μπουφαν|ζακετα|αντηλιακο)(?= |$)")
    private val placeWeather = Regex("^(.+?) (?:καιροσ|προγνωση|θερμοκρασια)$")
    private val weatherDays = listOf(
        "αυτη την εβδομαδα" to WeatherDay.WEEK, "αυτη τη βδομαδα" to WeatherDay.WEEK, "την εβδομαδα" to WeatherDay.WEEK,
        "τη βδομαδα" to WeatherDay.WEEK, "εβδομαδα" to WeatherDay.WEEK, "βδομαδα" to WeatherDay.WEEK,
        "το σαββατοκυριακο" to WeatherDay.WEEK, "σαββατοκυριακο" to WeatherDay.WEEK, "τισ επομενεσ μερεσ" to WeatherDay.WEEK,
        "τισ επομενεσ ημερεσ" to WeatherDay.WEEK, "μεθαυριο" to WeatherDay.WEEK, "αυριο" to WeatherDay.TOMORROW,
        "σημερα" to WeatherDay.TODAY, "αποψε" to WeatherDay.TODAY, "τωρα" to WeatherDay.NOW,
    )
    private val weatherFillers = setOf(
        "κανει", "θα", "εχει", "ειναι", "ειν", "εξω", "εκει", "λεει", "εχουμε", "εκανε", "ο", "η", "μου", "για",
        "στην", "στη", "στο", "στον", "στα", "στισ", "στουσ", "σε", "την", "τη", "το", "τον", "του", "τησ", "και",
        "πωσ", "τι", "καιρο", "καιροσ", "να", "βρεξει", "βρεχει", "χιονισει", "κρυο", "ζεστη", "ηλιο", "αερα",
        "ομπρελα", "θερμοκρασια", "βαθμουσ", "προγνωση", "καιρου", "πανω", "μερια",
    )
    private val herePlaces = setOf("εδω", "εδω γυρω", "περιοχη", "κοντα", "γυρω", "τοποθεσια")
    private val notPlaces = setOf(
        "εφαρμογη", "app", "καναλι", "χαρτησ", "ρανταρ", "ειδησεισ", "δελτιο", "widget", "σταθμοσ", "ρυθμισεισ", "κακοσ", "καλοσ",
    )

    private fun weather(t: Text): Command? {
        val lead = weatherLead.find(t.key) ?: weatherQuestion.find(t.key) ?: howHot.find(t.key) ?: umbrella.find(t.key)
        if (lead != null) return weatherTail(t, lead.range.last + 1)
        placeWeather.matchEntire(t.key)?.let { m ->
            val subject = m.groups[1] ?: return null
            if (subject.value.split(' ').size > 3) return null
            return weatherTail(t, 0, until = subject.range.last + 1)
        }
        return null
    }

    /** Day words and a place after the weather words; the place is cut from the original text. */
    private fun weatherTail(t: Text, from: Int, until: Int = t.key.length): Command.Weather? {
        var day = WeatherDay.NOW
        val used = BooleanArray(t.key.length)
        for ((phrase, value) in weatherDays) {
            val m = Regex("(?:^| )${Regex.escape(phrase)}(?= |$)").find(t.key.substring(0, until), from) ?: continue
            if (used[m.range.last]) continue
            if (day == WeatherDay.NOW || value == WeatherDay.WEEK) day = value
            for (i in m.range) used[i] = true
        }
        // The remaining words, by position, so the place can be cut from the original.
        val place = ArrayList<IntRange>()
        var start = from
        for (word in Regex("\\S+").findAll(t.key.substring(0, until), from)) {
            if (word.range.first < start) continue
            if (used[word.range.first] || word.value in weatherFillers) {
                if (place.isNotEmpty() && word.value !in setOf("και")) break
                continue
            }
            place += word.range
            start = word.range.last + 1
        }
        if (place.isEmpty()) return Command.Weather(null, day)
        if (place.size > 3) return null
        val range = place.first().first..place.last().last
        val placeKey = t.key.substring(range)
        if (placeKey in herePlaces || placeKey.split(' ').any { it in notPlaces }) {
            return if (placeKey in herePlaces) Command.Weather(null, day) else null
        }
        return Command.Weather(t.cut(range), day)
    }

    private val localTime = setOf(
        "τι ωρα ειναι", "τι ωρα εχουμε", "ωρα", "η ωρα", "πεσ μου την ωρα", "πεσ μου τι ωρα ειναι", "ποια ειναι η ωρα",
        "τι ωρα ειναι τωρα", "ημερομηνια", "η ημερομηνια", "τι ημερομηνια ειναι", "τι ημερομηνια εχουμε",
        "τι ημερομηνια εχουμε σημερα", "τι ημερομηνια ειναι σημερα", "τι μερα ειναι", "τι μερα ειναι σημερα",
        "τι μερα εχουμε", "τι μερα εχουμε σημερα", "ποια μερα ειναι σημερα", "τι ημερα ειναι σημερα",
        "τι ημερα εχουμε σημερα", "σημερινη ημερομηνια", "ποια ειναι η ημερομηνια", "τι ημερομηνια",
    )
    private val timeIn = Regex(
        "^(?:τι ωρα (?:ειναι|εχει|εχουν|ειναι τωρα)|η ωρα|ωρα|ποια ειναι η ωρα|τοπικη ωρα|η τοπικη ωρα) " +
            "(?:τωρα )?(?:στην |στη |στο |στον |στα |στισ |στουσ |σε |για |τησ |του )?(.+)$",
    )
    private val placeTime = Regex("^(.+?) (?:τοπικη )?ωρα$")

    private fun clock(t: Text): Command? {
        if (t.key in localTime) return Command.Clock(null, null)
        val m = timeIn.matchEntire(t.key) ?: placeTime.matchEntire(t.key) ?: return null
        val group = m.groups[1] ?: return null
        val zone = zoneFor(group.value) ?: WorldClock.lookup(group.value)?.zone ?: return null
        val name = t.cut(group)?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(greek) } }
        return Command.Clock(zone, name)
    }

    /** Greek names of big cities and countries, accent-free. */
    private val zones = mapOf(
        "αθηνα" to "Europe/Athens", "ελλαδα" to "Europe/Athens", "θεσσαλονικη" to "Europe/Athens",
        "λευκωσια" to "Asia/Nicosia", "κυπρο" to "Asia/Nicosia", "κυπροσ" to "Asia/Nicosia", "λεμεσο" to "Asia/Nicosia",
        "λονδινο" to "Europe/London", "αγγλια" to "Europe/London", "βρετανια" to "Europe/London",
        "ηνωμενο βασιλειο" to "Europe/London", "ιρλανδια" to "Europe/Dublin", "δουβλινο" to "Europe/Dublin",
        "παρισι" to "Europe/Paris", "γαλλια" to "Europe/Paris", "βερολινο" to "Europe/Berlin", "γερμανια" to "Europe/Berlin",
        "μοναχο" to "Europe/Berlin", "ρωμη" to "Europe/Rome", "ιταλια" to "Europe/Rome", "μιλανο" to "Europe/Rome",
        "μαδριτη" to "Europe/Madrid", "ισπανια" to "Europe/Madrid", "βαρκελωνη" to "Europe/Madrid",
        "λισαβονα" to "Europe/Lisbon", "πορτογαλια" to "Europe/Lisbon", "αμστερνταμ" to "Europe/Amsterdam",
        "ολλανδια" to "Europe/Amsterdam", "βρυξελλεσ" to "Europe/Brussels", "βελγιο" to "Europe/Brussels",
        "βιεννη" to "Europe/Vienna", "αυστρια" to "Europe/Vienna", "ζυριχη" to "Europe/Zurich", "ελβετια" to "Europe/Zurich",
        "στοκχολμη" to "Europe/Stockholm", "σουηδια" to "Europe/Stockholm", "οσλο" to "Europe/Oslo", "νορβηγια" to "Europe/Oslo",
        "κοπεγχαγη" to "Europe/Copenhagen", "δανια" to "Europe/Copenhagen", "ελσινκι" to "Europe/Helsinki",
        "φινλανδια" to "Europe/Helsinki", "βαρσοβια" to "Europe/Warsaw", "πολωνια" to "Europe/Warsaw",
        "πραγα" to "Europe/Prague", "βουδαπεστη" to "Europe/Budapest", "βουκουρεστι" to "Europe/Bucharest",
        "σοφια" to "Europe/Sofia", "βουλγαρια" to "Europe/Sofia", "βελιγραδι" to "Europe/Belgrade", "τιρανα" to "Europe/Tirane",
        "κωνσταντινουπολη" to "Europe/Istanbul", "πολη" to "Europe/Istanbul", "ιστανμπουλ" to "Europe/Istanbul",
        "τουρκια" to "Europe/Istanbul", "αγκυρα" to "Europe/Istanbul", "μοσχα" to "Europe/Moscow", "ρωσια" to "Europe/Moscow",
        "κιεβο" to "Europe/Kyiv", "ουκρανια" to "Europe/Kyiv", "ισραηλ" to "Asia/Jerusalem", "τελ αβιβ" to "Asia/Jerusalem",
        "ιερουσαλημ" to "Asia/Jerusalem", "καιρο" to "Africa/Cairo", "αιγυπτο" to "Africa/Cairo", "αιγυπτοσ" to "Africa/Cairo",
        "ντουμπαι" to "Asia/Dubai", "αραβικα εμιρατα" to "Asia/Dubai", "τοκιο" to "Asia/Tokyo", "ιαπωνια" to "Asia/Tokyo",
        "πεκινο" to "Asia/Shanghai", "σαγκαη" to "Asia/Shanghai", "κινα" to "Asia/Shanghai", "χονγκ κονγκ" to "Asia/Hong_Kong",
        "σιγκαπουρη" to "Asia/Singapore", "σεουλ" to "Asia/Seoul", "κορεα" to "Asia/Seoul", "μπανγκοκ" to "Asia/Bangkok",
        "ταιλανδη" to "Asia/Bangkok", "ινδια" to "Asia/Kolkata", "νεο δελχι" to "Asia/Kolkata", "μουμπαι" to "Asia/Kolkata",
        "σιδνει" to "Australia/Sydney", "αυστραλια" to "Australia/Sydney", "μελβουρνη" to "Australia/Melbourne",
        "νεα ζηλανδια" to "Pacific/Auckland", "ωκλαντ" to "Pacific/Auckland", "χαβαη" to "Pacific/Honolulu",
        "νεα υορκη" to "America/New_York", "αμερικη" to "America/New_York", "ηπα" to "America/New_York",
        "ουασινγκτον" to "America/New_York", "βοστωνη" to "America/New_York", "μαιαμι" to "America/New_York",
        "σικαγο" to "America/Chicago", "τεξασ" to "America/Chicago", "λοσ αντζελεσ" to "America/Los_Angeles",
        "σαν φρανσισκο" to "America/Los_Angeles", "καλιφορνια" to "America/Los_Angeles", "σιατλ" to "America/Los_Angeles",
        "λασ βεγκασ" to "America/Los_Angeles", "τοροντο" to "America/Toronto",
        "καναδα" to "America/Toronto", "καναδασ" to "America/Toronto", "μοντρεαλ" to "America/Toronto",
        "βανκουβερ" to "America/Vancouver", "μεξικο" to "America/Mexico_City", "βραζιλια" to "America/Sao_Paulo",
        "σαο παολο" to "America/Sao_Paulo", "ριο" to "America/Sao_Paulo", "αργεντινη" to "America/Argentina/Buenos_Aires",
        "μπουενοσ αιρεσ" to "America/Argentina/Buenos_Aires", "μελβουρνη αυστραλια" to "Australia/Melbourne",
    )

    private fun zoneFor(key: String): ZoneId? {
        val id = zones[key.trim()] ?: return null
        return runCatching { ZoneId.of(id) }.getOrNull()
    }

    // endregion

    // region Apps & people

    private val photo = Regex(
        "^(?:βγαλε|βγαλεισ|τραβα|τραβηξε|τραβηξεισ|κανε)(?: μου| μια| μιαν| ενα| μασ)* (?:φωτογραφια|φωτογραφιεσ|φωτο|σελφι|selfie)$",
    )
    private val video = Regex(
        "^(?:τραβα|τραβηξε|τραβηξεισ|γυρισε|ξεκινα|ξεκινησε|κανε)(?: μου| ενα)* (?:βιντεο|βιντεακι|εγγραφη βιντεο)$|^βιντεοσκοπηση$",
    )

    private fun camera(t: Text): Command? = when {
        photo.matches(t.key) -> Command.Camera(video = false)
        video.matches(t.key) -> Command.Camera(video = true)
        else -> null
    }

    private val articles = "(?:στην |στη |στο |στον |στα |στισ |στουσ |σε |την |τη |το |τον |τα |τισ |τουσ |του |τησ )?"
    private val navigation = Regex(
        "^(?:πλοηγηση|οδηγιεσ|δρομολογιο|διαδρομη|πωσ (?:θα )?(?:παω|πηγαινω|φτανω)|πηγαινε με|παρε με|οδηγησε με|" +
            "πανε με|δειξε μου (?:το δρομο|τον δρομο|πωσ να παω|πωσ παω)|(?:να )?παω|θελω να παω)" +
            " (?:για |προσ |μεχρι )?$articles(.+)$",
    )
    private val onMap = Regex("^(?:δειξε μου |βρεσ )?$articles(.+?) στο(?:ν)? χαρτη$")
    private val mapOf = Regex("^(?:χαρτησ|ανοιξε χαρτη|χαρτη) (?:για |τησ |του |των )?$articles(.+)$")

    private fun navigate(t: Text): Command? {
        val m = navigation.matchEntire(t.key) ?: onMap.matchEntire(t.key) ?: mapOf.matchEntire(t.key) ?: return null
        val destination = t.cut(m.groups[1]) ?: return null
        return Command.Navigate(destination)
    }

    private val callPattern = Regex(
        "^(?:καλεσε|καλεσεισ|καλεσετε|παρε|παρεισ|παρτε|τηλεφωνησε|τηλεφωνησεισ|κληση|καλεσμα|βιντεοκληση)" +
            "(?: τηλεφωνο| τηλεφωνικα)?(?: σε)? $articles(.+?)(?: στο κινητο| στο σπιτι| στη δουλεια| στο σταθερο)?$",
    )
    private val phoneNumber = Regex("^\\+?[\\d][\\d\\s().-]{2,}$")
    private val notPeople = setOf("με", "μου", "πισω", "τον", "την", "το", "αυτον", "αυτην", "κατι", "ομπρελα", "μια αποφαση")

    private fun call(t: Text): Command? {
        val m = callPattern.matchEntire(t.key) ?: return null
        val group = m.groups[1] ?: return null
        val whoKey = group.value.trim()
        if (whoKey.isEmpty() || whoKey in notPeople || whoKey.startsWith("με ")) return null
        if (phoneNumber.matches(whoKey)) return Command.DialNumber(whoKey.filter { it.isDigit() || it == '+' })
        return Command.Call(t.cut(group) ?: return null)
    }

    private val messagePattern = Regex(
        "^(?:στειλε|στειλεισ|στειλτε|γραψε|γραψεισ)(?: (?:ενα|μια))?(?: (?:μηνυμα|sms|μνμ|γραπτο|μυνημα|μεσατζ))?" +
            " (?:στην |στη |στο |στον |στουσ |στισ |σε )(.+?)(?: (?:οτι|πωσ|που να λεει|που λεει|λεγοντασ|με κειμενο|:) (.+))?$",
    )
    private val messageNoun = Regex(
        "^(?:μηνυμα|sms|γραπτο μηνυμα) (?:στην |στη |στο |στον |σε )(.+?)(?: (?:οτι|πωσ|που λεει|:) (.+))?$",
    )

    private fun message(t: Text): Command? {
        val m = messagePattern.matchEntire(t.key) ?: messageNoun.matchEntire(t.key) ?: return null
        val hasNoun = Regex("(?:^| )(?:μηνυμα|sms|μνμ|γραπτο|μυνημα|μεσατζ)(?= |$)").containsMatchIn(t.key.substring(0, m.groups[1]!!.range.first))
        // "στείλε στη μαμά" alone is too vague; with a message word or a body it's a message.
        if (!hasNoun && m.groups[2] == null) return null
        val who = t.cut(m.groups[1]) ?: return null
        return Command.Message(who, t.cut(m.groups[2]))
    }

    private val panelVerbs = setOf(
        "ανοιξε", "ανοιξεισ", "κλεισε", "κλεισεισ", "ενεργοποιησε", "ενεργοποιησεισ", "απενεργοποιησε",
        "απενεργοποιησεισ", "αναψε", "σβησε", "βαλε", "βγαλε", "ρυθμισεισ", "ρυθμισεων", "ρυθμιση", "τισ", "τα", "το",
        "τη", "την", "τον", "του", "τησ", "η", "ο", "οι", "δειξε", "μου", "εμφανισε", "πηγαινε", "στισ", "στο", "στη",
        "στην", "συνδεσε", "συνδεση", "λειτουργια", "λειτουργιασ", "on", "off", "settings", "ενεργοποιηση",
        "απενεργοποιηση", "διακοπτη", "διακοπτησ",
    )
    private val panelNames = listOf(
        "wi-fi" to SettingsPanel.WIFI, "wifi" to SettingsPanel.WIFI, "wi fi" to SettingsPanel.WIFI,
        "γουαιφαι" to SettingsPanel.WIFI, "ασυρματο δικτυο" to SettingsPanel.WIFI,
        "bluetooth" to SettingsPanel.BLUETOOTH, "μπλουτουθ" to SettingsPanel.BLUETOOTH,
        "δεδομενα κινητησ τηλεφωνιασ" to SettingsPanel.INTERNET, "δεδομενα κινητησ" to SettingsPanel.INTERNET,
        "κινητα δεδομενα" to SettingsPanel.INTERNET, "δεδομενα" to SettingsPanel.INTERNET,
        "ιντερνετ" to SettingsPanel.INTERNET, "internet" to SettingsPanel.INTERNET, "data" to SettingsPanel.INTERNET,
        "λειτουργια πτησησ" to SettingsPanel.AIRPLANE, "λειτουργια αεροπλανου" to SettingsPanel.AIRPLANE,
        "πτησησ" to SettingsPanel.AIRPLANE, "αεροπλανου" to SettingsPanel.AIRPLANE, "airplane mode" to SettingsPanel.AIRPLANE,
        "nfc" to SettingsPanel.NFC,
        "σημειο προσβασησ" to SettingsPanel.HOTSPOT, "hotspot" to SettingsPanel.HOTSPOT, "hot spot" to SettingsPanel.HOTSPOT,
        "ηχου" to SettingsPanel.VOLUME, "ηχοσ" to SettingsPanel.VOLUME, "ηχων" to SettingsPanel.VOLUME,
        "εξοικονομηση ενεργειασ" to SettingsPanel.BATTERY, "εξοικονομηση μπαταριασ" to SettingsPanel.BATTERY,
        "μπαταριασ" to SettingsPanel.BATTERY, "μπαταρια" to SettingsPanel.BATTERY,
        "φωτεινοτητα" to SettingsPanel.DISPLAY, "φωτεινοτητασ" to SettingsPanel.DISPLAY, "οθονησ" to SettingsPanel.DISPLAY,
        "οθονη" to SettingsPanel.DISPLAY, "σκουρο θεμα" to SettingsPanel.DISPLAY, "σκοτεινο θεμα" to SettingsPanel.DISPLAY,
        "dark mode" to SettingsPanel.DISPLAY,
        "τοποθεσιασ" to SettingsPanel.LOCATION, "τοποθεσια" to SettingsPanel.LOCATION, "gps" to SettingsPanel.LOCATION,
        "μην ενοχλειτε" to SettingsPanel.DND, "μην ενοχλεισ" to SettingsPanel.DND, "μη με ενοχλεισ" to SettingsPanel.DND,
        "dnd" to SettingsPanel.DND,
    ).sortedByDescending { it.first.length }
    private val barePanelNames = setOf("δεδομενα", "οθονη", "οθονησ", "τοποθεσια", "μπαταρια", "ηχοσ", "ηχου", "data", "πτησησ", "αεροπλανου")

    private fun panel(t: Text): Command? {
        for ((name, panel) in panelNames) {
            val pattern = Regex("(?:^| )${Regex.escape(name)}(?: |$)")
            if (!pattern.containsMatchIn(t.key)) continue
            val rest = t.key.replace(pattern, " ").trim()
            val words = rest.split(' ').filter { it.isNotEmpty() }
            if (!words.all { it in panelVerbs }) return null
            if (words.isEmpty() && name in barePanelNames) return null
            val requested = when {
                words.any { it in setOf("ανοιξε", "ανοιξεισ", "ενεργοποιησε", "ενεργοποιησεισ", "αναψε", "βαλε", "συνδεσε", "ενεργοποιηση", "on") } -> Switch.ON
                words.any { it in setOf("κλεισε", "κλεισεισ", "απενεργοποιησε", "απενεργοποιησεισ", "σβησε", "βγαλε", "απενεργοποιηση", "off") } -> Switch.OFF
                else -> null
            }
            return Command.OpenPanel(panel, requested)
        }
        return null
    }

    private val whatIs = Regex("^(?:τι|ποιοσ|ποια|ποιο|ποιοι|ποιεσ|ποιανου) (?:ειναι|ηταν|ειν) (?:ο |η |το |οι |τα |ενασ |μια |ενα |μιαν )?(.+)$")
    private val tellMe = Regex(
        "^(?:πεσ μου|μιλα μου|μιλησε μου|θελω να μαθω|ενημερωσε με) (?:για |σχετικα με |κατι για |λιγα για )" +
            "(?:τον |την |τη |το |τα |τουσ |τισ |τo )?(.+)$",
    )
    private val meaning = Regex("^τι σημαινει (?:η λεξη |η φραση |ο ορος )?(.+)$")
    private val definition = Regex("^(?:ορισμοσ|ορισμο|εννοια|σημασια) (?:του |τησ |των )?(?:λεξησ |ορου )?(.+)$")
    private val searchWiki = Regex("^(?:ψαξε|αναζητησε|βρεσ) (?:στη |στην )?(?:wikipedia|βικιπαιδεια) (?:για )?(?:τον |την |τη |το )?(.+)$")
    private val onWiki = Regex("^(.+?) (?:στη |στην )?(?:wikipedia|βικιπαιδεια)$")
    private val notTopics = setOf(
        "αυτο", "αυτοσ", "αυτη", "εκεινο", "εσυ", "το ονομα σου", "το ονομα μου", "νεο", "καινουργιο", "σωστο", "λαθοσ",
        "εδω", "εκει", "η ωρα", "ωρα", "ο καιροσ", "καιροσ", "αυτο το τραγουδι", "το τραγουδι", "τραγουδι",
        "η θερμοκρασια", "θερμοκρασια", "η ημερομηνια", "ημερομηνια",
    )

    private fun lookup(t: Text): Command? {
        val m = meaning.matchEntire(t.key) ?: whatIs.matchEntire(t.key) ?: tellMe.matchEntire(t.key)
            ?: definition.matchEntire(t.key) ?: searchWiki.matchEntire(t.key) ?: onWiki.matchEntire(t.key) ?: return null
        val group = m.groups[1] ?: return null
        val key = group.value.trim()
        if (key.isEmpty() || key in notTopics) return null
        val topic = t.cut(group) ?: return null
        if (com.fastsearch.search.Calculator.evaluate(topic) != null || arithmetic(topic) != null) return null
        return Command.Lookup(topic)
    }

    private val openPattern = Regex(
        "^(?:ανοιξε|ανοιξεισ|ανοιξτε|ξεκινα|ξεκινησε|τρεξε|εκκινησε|ανοιγμα|πηγαινε στο|πηγαινε στην|πηγαινε σε|δειξε μου)" +
            "(?: μου)? (?:την |τη |το |τον |τα |τισ )?(?:εφαρμογη |app )?(.+?)(?: app| εφαρμογη)?$",
    )

    private fun openApp(t: Text): Command? {
        val m = openPattern.matchEntire(t.key) ?: return null
        val group = m.groups[1] ?: return null
        val name = t.cut(group) ?: return null
        panel(Text(t.shown.substring(group.range), t.key.substring(group.range)))?.let { return it }
        return Command.OpenApp(name)
    }

    // endregion

    // region Conversions and sums

    private val unitNames = listOf(
        "χιλιομετρα την ωρα" to "km/h", "χιλιομετρα ανα ωρα" to "km/h", "χιλιομετρων την ωρα" to "km/h", "χλμ/ω" to "km/h",
        "μιλια την ωρα" to "mph", "μιλια ανα ωρα" to "mph", "κομβοι" to "kn", "κομβουσ" to "kn", "κομβο" to "kn",
        "τετραγωνικα μετρα" to "m²", "τετραγωνικο μετρο" to "m²",
        "χιλιομετρα" to "km", "χιλιομετρο" to "km", "χιλιομετρου" to "km", "χιλιομετρων" to "km", "χλμ" to "km",
        "εκατοστα" to "cm", "εκατοστο" to "cm", "εκατοστων" to "cm", "εκατοστομετρα" to "cm",
        "χιλιοστα" to "mm", "χιλιοστο" to "mm", "χιλιοστων" to "mm",
        "μετρα" to "m", "μετρο" to "m", "μετρου" to "m", "μετρων" to "m",
        "μιλια" to "mi", "μιλι" to "mi", "μιλιου" to "mi", "μιλιων" to "mi", "ναυτικα μιλια" to "nmi",
        "ποδια" to "ft", "ποδι" to "ft", "ποδιων" to "ft", "ιντσεσ" to "in", "ιντσα" to "in", "ιντσων" to "in",
        "γιαρδεσ" to "yd", "γιαρδα" to "yd", "γιαρδων" to "yd",
        "χιλιογραμμα" to "kg", "κιλα" to "kg", "κιλο" to "kg", "κιλου" to "kg", "κιλων" to "kg",
        "γραμμαρια" to "g", "γραμμαριο" to "g", "γραμμαριων" to "g", "γρ" to "g",
        "τονοι" to "t", "τονο" to "t", "τονουσ" to "t", "τονων" to "t",
        "λιβρεσ" to "lb", "λιβρα" to "lb", "λιβρων" to "lb", "ουγγιεσ" to "oz", "ουγγια" to "oz", "ουγγιων" to "oz",
        "χιλιοστολιτρα" to "ml", "μιλιλιτρ" to "ml", "λιτρα" to "L", "λιτρο" to "L", "λιτρου" to "L", "λιτρων" to "L",
        "γαλονια" to "gal", "γαλονι" to "gal", "φλιτζανια" to "cup", "φλιτζανι" to "cup",
        "κουταλιεσ τησ σουπασ" to "tbsp", "κουταλια τησ σουπασ" to "tbsp", "κουταλακια" to "tsp", "κουταλακι" to "tsp",
        "βαθμοι κελσιου" to "c", "βαθμουσ κελσιου" to "c", "βαθμων κελσιου" to "c", "κελσιου" to "c", "κελσιοσ" to "c",
        "βαθμοι φαρεναιτ" to "f", "βαθμουσ φαρεναιτ" to "f", "φαρεναιτ" to "f", "κελβιν" to "k",
        "δευτερολεπτα" to "s", "δευτερολεπτο" to "s", "λεπτα" to "min", "λεπτο" to "min", "ωρεσ" to "h", "ωρα" to "h",
        "μερεσ" to "days", "ημερεσ" to "days", "μερα" to "days", "εβδομαδεσ" to "weeks", "εβδομαδα" to "weeks",
        "χρονια" to "years", "χρονο" to "years", "ετη" to "years",
        "κιλομπαιτ" to "kB", "μεγκαμπαιτ" to "MB", "μεγαμπαιτ" to "MB", "γιγκαμπαιτ" to "GB", "γιγαμπαιτ" to "GB",
        "τεραμπαιτ" to "TB", "θερμιδεσ" to "kcal", "θερμιδα" to "kcal",
    ).sortedByDescending { it.first.length }
    private val howMany = Regex("^(?:ποσα|ποσεσ|ποσοι|ποσουσ) (.+?) (?:ειναι|κανουν|κανει|εχει|εχουν|βγαζει|βγαζουν) (.+)$")

    private fun conversion(t: Text): Command? {
        var text = digitize(t.key)
            .replace(Regex("(\\d),(\\d)"), "$1.$2")
            .replace(Regex("^(?:μετατροπη|μετατρεψε|μετατρεψεισ|κανε|ποσο κανει|ποσο ειναι|ποσα ειναι) "), "")
        howMany.matchEntire(text)?.let { m -> text = "${m.groupValues[2]} σε ${m.groupValues[1]}" }
        for ((name, symbol) in unitNames) text = text.replace(Regex("(?<!\\p{L})${Regex.escape(name)}(?!\\p{L})"), symbol)
        text = text.replace(" σε ", " to ").replace(" στα ", " to ").replace(" στο ", " to ").replace(" στουσ ", " to ")
            .replace(Regex("^1 (?=\\D)"), "1 ").trim()
        if (isGreek(text)) return null
        return UnitConverter.parse(text)?.let(Command::Convert)
    }

    /**
     * Greek arithmetic in calculator syntax: "πόσο κάνει 12 επί 7" -> "12*7", "20 τοις εκατό του 80" -> "20%*80".
     * Null when there's nothing to calculate.
     */
    fun arithmetic(input: String): String? {
        if (!isGreek(input)) return null
        var text = " " + digitize(fold(input.trim().lowercase(greek)).trimEnd('?', ';', '.', ';')) + " "
        text = text.replace(Regex(" (?:ποσο κανει|ποσο κανουν|ποσο ειναι|ποσο βγαινει|ποσο βγαζει|υπολογισε|τι κανει|ποσα κανουν|ποσα ειναι) "), " ")
            .replace(Regex("(\\d),(\\d)"), "$1.$2")
            .replace(Regex(" τοισ εκατο (?:του |τησ |των )?"), "% * ")
            .replace(" επι ", " * ").replace(" φορεσ ", " * ").replace(" πολλαπλασιασμενο με ", " * ")
            .replace(" δια ", " / ").replace(" διαιρεμενο με ", " / ")
            .replace(" συν ", " + ").replace(" και ", " + ")
            .replace(" μειον ", " - ").replace(" πλην ", " - ")
            .replace(" στο τετραγωνο ", "^2 ").replace(" στον κυβο ", "^3 ")
            .replace(Regex(" (?:τετραγωνικη ριζα|ριζα) (?:του |τησ )?"), " sqrt ")
            .trim()
        if (isGreek(text) || text.none { it.isDigit() }) return null
        return text
    }

    // endregion

    private const val MAX_LENGTH = 160
    private const val MAX_TIMER_SECONDS = 24 * 3600
}
