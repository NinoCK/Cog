package com.bestassistant.namedays

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.util.Locale

/**
 * The Greek name-day calendar (εορτολόγιο), from a table in the app: no network. Fixed feasts are
 * by date; the movable ones (Λάζαρος, Βάιος, Αναστάσιος, Θωμάς, Φωτεινή, ...) are counted from
 * Orthodox Easter, and Saint George moves to Easter Monday when the 23rd of April falls before it.
 */
object NameDayCalendar {

    private val greekLetter = Regex("[\u0370-\u03FF\u1F00-\u1FFF]")

    private fun isGreek(text: String) = greekLetter.containsMatchIn(text)

    /** Accent-free Greek, final sigma as plain sigma: "Ελένης" -> "ελενησ" (lowercase first). */
    private fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when {
                    c == 'ς' -> 'σ'
                    c in 'Ͱ'..'Ͽ' || c in 'ἀ'..'῿' -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
                    else -> c
                },
            )
        }
    }


    /** A name as it's usually written, and the shorter forms that celebrate with it ("Γιώργος"). */
    class Name(val name: String, val also: List<String>)

    /** A name someone asked about, as found in the table: [name] is the form that matched. */
    class Found(val name: String, val main: String)

    // Each date: names separated by "|", each with its short forms after "/".
    private val fixed: Map<MonthDay, String> = mapOf(
        day(1, 1) to "Βασίλειος/Βασίλης/Βάσος|Βασιλική/Βάσω/Βασιλεία",
        day(1, 2) to "Σεραφείμ|Σίλβεστρος",
        day(1, 6) to "Φώτιος/Φώτης|Φωτεινή/Φωτούλα/Φώφη|Θεοφάνης/Φάνης|Θεοφανώ/Θεοφανία/Φανή|Ιορδάνης|Ιορδάνα",
        day(1, 7) to "Ιωάννης/Γιάννης/Γιαννάκης/Γιάγκος|Ιωάννα/Γιάννα|Πρόδρομος",
        day(1, 11) to "Θεοδόσιος/Θεοδόσης|Θεοδοσία",
        day(1, 12) to "Τατιανή/Τατιάνα",
        day(1, 17) to "Αντώνιος/Αντώνης|Αντωνία",
        day(1, 18) to "Αθανάσιος/Θανάσης/Νάσος|Αθανασία/Νάσια|Κύριλλος",
        day(1, 20) to "Ευθύμιος/Θύμιος/Ευθύμης",
        day(1, 21) to "Μάξιμος|Νεόφυτος",
        day(1, 22) to "Τιμόθεος|Αναστάσιος/Τάσος",
        day(1, 24) to "Ξένη/Ξένια/Ξενία",
        day(1, 25) to "Γρηγόριος/Γρηγόρης|Γρηγορία",
        day(1, 26) to "Ξενοφών/Ξενοφώντας",
        day(1, 27) to "Χρυσόστομος",
        day(2, 1) to "Τρύφων/Τρύφωνας",
        day(2, 2) to "Υπαπαντή",
        day(2, 3) to "Συμεών/Σίμος",
        day(2, 6) to "Φώτιος/Φώτης",
        day(2, 9) to "Νικηφόρος",
        day(2, 10) to "Χαράλαμπος/Χάρης/Μπάμπης/Λάμπης|Χαραλαμπία",
        day(2, 11) to "Βλάσιος/Βλάσης|Θεοδώρα/Δώρα",
        day(2, 12) to "Μελέτιος/Μελέτης",
        day(2, 26) to "Φωτεινή/Φωτούλα/Φώφη|Πορφύριος",
        day(3, 1) to "Ευδοκία",
        day(3, 17) to "Αλέξιος/Αλέξης",
        day(3, 19) to "Χρύσανθος|Χρυσάνθη",
        day(3, 25) to "Ευάγγελος/Βαγγέλης|Ευαγγελία/Βαγγελιώ/Ευαγγελή",
        day(3, 26) to "Γαβριήλ|Γαβριέλα",
        day(4, 25) to "Μάρκος",
        day(4, 29) to "Ιάσων/Ιάσονας",
        day(4, 30) to "Ιάκωβος/Γιάκωβος/Γιάκουμος",
        day(5, 2) to "Αθανάσιος/Θανάσης/Νάσος|Αθανασία/Νάσια",
        day(5, 5) to "Ειρήνη/Ρένα/Ρένια",
        day(5, 12) to "Επιφάνιος",
        day(5, 15) to "Αχιλλέας/Αχιλλεύς/Αχίλλειος",
        day(5, 21) to "Κωνσταντίνος/Κώστας/Ντίνος/Κωστής/Κωνσταντής|Κωνσταντίνα/Ντίνα/Κωνσταντία|Ελένη/Λένα/Λενιώ/Έλενα",
        day(6, 1) to "Ιουστίνος",
        day(6, 11) to "Βαρνάβας|Βαρθολομαίος",
        day(6, 29) to "Πέτρος|Παύλος|Πετρούλα/Πέτρα|Παυλίνα/Παύλα",
        day(6, 30) to "Απόστολος/Τόλης/Αποστόλης|Αποστολία",
        day(7, 1) to "Κοσμάς|Δαμιανός|Ανάργυρος/Αργύρης|Αργυρώ",
        day(7, 7) to "Κυριακή/Κική",
        day(7, 8) to "Προκόπιος/Προκόπης",
        day(7, 11) to "Ευφημία/Έφη",
        day(7, 12) to "Παΐσιος",
        day(7, 14) to "Νικόδημος",
        day(7, 15) to "Κήρυκος",
        day(7, 17) to "Μαρίνα",
        day(7, 20) to "Ηλίας/Λιάς|Ηλιάνα",
        day(7, 22) to "Μαγδαληνή/Μάγδα",
        day(7, 24) to "Χριστίνα",
        day(7, 25) to "Άννα/Αννούλα/Αννέτα",
        day(7, 26) to "Παρασκευή/Βούλα|Παρασκευάς",
        day(7, 27) to "Παντελεήμων/Παντελής",
        day(8, 6) to "Σωτήριος/Σωτήρης|Σωτηρία",
        day(8, 8) to "Τριαντάφυλλος|Τριανταφυλλιά",
        day(8, 15) to "Μαρία/Μαίρη/Μαρούλα/Μαρίκα/Μαριώ/Μαριάννα|Παναγιώτης/Πάνος/Γιώτης/Παναγής|Παναγιώτα/Γιώτα|Δέσποινα",
        day(8, 16) to "Γεράσιμος",
        day(8, 26) to "Αδριανός|Ναταλία",
        day(8, 27) to "Φανούριος/Φανούρης",
        day(8, 30) to "Αλέξανδρος/Αλέκος|Αλεξάνδρα/Σάντρα",
        day(9, 5) to "Ζαχαρίας|Ελισάβετ/Λίζα",
        day(9, 14) to "Σταύρος|Σταυρούλα/Σταυριανή",
        day(9, 15) to "Νικήτας",
        day(9, 16) to "Ευφημία/Έφη",
        day(9, 17) to "Σοφία/Σόφη|Πίστη|Ελπίδα/Ελπίς|Αγάπη",
        day(9, 20) to "Ευστάθιος/Στάθης|Ευσταθία",
        day(9, 23) to "Πολυξένη|Ξανθίππη",
        day(10, 3) to "Διονύσιος/Διονύσης",
        day(10, 6) to "Θωμάς",
        day(10, 8) to "Πελαγία",
        day(10, 18) to "Λουκάς",
        day(10, 20) to "Γεράσιμος|Αρτέμιος",
        day(10, 21) to "Ιλαρίων",
        day(10, 23) to "Ιάκωβος/Γιάκωβος/Γιάκουμος",
        day(10, 26) to "Δημήτριος/Δημήτρης/Μήτσος/Μίμης|Δήμητρα/Δημητρούλα",
        day(11, 1) to "Κοσμάς|Δαμιανός|Ανάργυρος/Αργύρης|Αργυρώ",
        day(11, 8) to "Μιχαήλ/Μιχάλης|Μιχαέλα|Γαβριήλ|Γαβριέλα|Άγγελος|Αγγελική/Αγγελίνα|Ταξιάρχης",
        day(11, 9) to "Νεκτάριος",
        day(11, 11) to "Μηνάς",
        day(11, 13) to "Χρυσόστομος",
        day(11, 14) to "Φίλιππος",
        day(11, 16) to "Ματθαίος",
        day(11, 25) to "Αικατερίνη/Κατερίνα/Κατίνα/Καίτη|Μερκούριος",
        day(11, 26) to "Στυλιανός/Στέλιος|Στυλιανή/Στέλλα",
        day(11, 30) to "Ανδρέας",
        day(12, 4) to "Βαρβάρα",
        day(12, 5) to "Σάββας",
        day(12, 6) to "Νικόλαος/Νίκος/Νικολής|Νικολέτα",
        day(12, 7) to "Αμβρόσιος",
        day(12, 9) to "Άννα/Αννούλα/Αννέτα",
        day(12, 12) to "Σπυρίδων/Σπύρος|Σπυριδούλα",
        day(12, 13) to "Ευστράτιος/Στράτος|Λουκία",
        day(12, 15) to "Ελευθέριος/Λευτέρης|Ελευθερία",
        day(12, 17) to "Διονύσιος/Διονύσης|Δανιήλ",
        day(12, 20) to "Ιγνάτιος",
        day(12, 22) to "Αναστασία/Τασούλα/Νατάσα",
        day(12, 24) to "Ευγενία/Τζένη",
        day(12, 25) to "Χρήστος/Χρίστος|Εμμανουήλ/Μανώλης/Μάνος|Εμμανουέλα/Μανουέλα",
        day(12, 27) to "Στέφανος|Στεφανία/Στέφη",
    )

    /** Days counted from Orthodox Easter Sunday. */
    private val movable: Map<Int, String> = mapOf(
        // Saturday and Sunday of the first week of Lent.
        -43 to "Θεόδωρος/Θοδωρής/Θόδωρος",
        -42 to "Θεοδώρα/Δώρα",
        -8 to "Λάζαρος",
        -7 to "Βάιος|Βάια/Βάγια",
        0 to "Αναστάσιος/Τάσος|Αναστασία/Τασούλα|Λάμπρος|Λαμπρινή|Πασχάλης|Πασχαλία",
        2 to "Ραφαήλ|Ραφαέλα",
        5 to "Ζωή|Πηγή",
        7 to "Θωμάς|Θωμαΐς",
        28 to "Φωτεινή/Φωτούλα/Φώφη",
    )

    private const val GEORGE = "Γεώργιος/Γιώργος/Γιώργης/Γιωργής|Γεωργία/Γεωργούλα"

    /** Joseph, David and James, the Sunday after Christmas. */
    private const val JOSEPH = "Ιωσήφ/Σήφης|Δαβίδ"

    private fun day(month: Int, day: Int) = MonthDay.of(month, day)

    private fun names(spec: String): List<Name> = spec.split('|').map { group ->
        val forms = group.split('/')
        Name(forms.first(), forms.drop(1))
    }

    /**
     * Orthodox Easter Sunday (Gregorian date), by Meeus' Julian algorithm. The 13-day gap
     * between the calendars holds from 1900 to 2099.
     */
    fun orthodoxEaster(year: Int): LocalDate {
        val a = year % 4
        val b = year % 7
        val c = year % 19
        val d = (19 * c + 15) % 30
        val e = (2 * a + 4 * b - d + 34) % 7
        val month = (d + e + 114) / 31
        val dayOfMonth = (d + e + 114) % 31 + 1
        return LocalDate.of(year, month, dayOfMonth).plusDays(13)
    }

    /** Saint George: the 23rd of April, or Easter Monday when that's still in Lent or Holy Week. */
    private fun george(year: Int): LocalDate {
        val easter = orthodoxEaster(year)
        val fixedDay = LocalDate.of(year, 4, 23)
        return if (fixedDay.isAfter(easter)) fixedDay else easter.plusDays(1)
    }

    private fun joseph(year: Int): LocalDate {
        val christmas = LocalDate.of(year, 12, 25)
        val sunday = (1..6L).map(christmas::plusDays).firstOrNull { it.dayOfWeek == DayOfWeek.SUNDAY }
        return sunday ?: christmas.plusDays(1)
    }

    /** Every feast of [year], by date. */
    private fun feasts(year: Int): Map<LocalDate, List<Name>> {
        val all = LinkedHashMap<LocalDate, MutableList<Name>>()
        fun add(date: LocalDate, spec: String) = all.getOrPut(date) { mutableListOf() }.addAll(names(spec))
        for ((monthDay, spec) in fixed) if (monthDay.isValidYear(year)) add(monthDay.atYear(year), spec)
        val easter = orthodoxEaster(year)
        for ((offset, spec) in movable) add(easter.plusDays(offset.toLong()), spec)
        add(george(year), GEORGE)
        add(joseph(year), JOSEPH)
        return all
    }

    /** Who celebrates on [date]. */
    fun on(date: LocalDate): List<Name> = feasts(date.year)[date].orEmpty()

    /** The next days on or after [from] that [found] celebrates, soonest first (a year ahead at most). */
    fun next(found: Found, from: LocalDate): List<LocalDate> {
        val key = fold(found.main.lowercase(GREEK))
        val days = (from.year..from.year + 1).flatMap { year ->
            feasts(year).filter { (_, names) -> names.any { fold(it.name.lowercase(GREEK)) == key } }.keys
        }
        // Each feast once: its next occurrence.
        return days.filter { !it.isBefore(from) && it.isBefore(from.plusYears(1)) }.sorted()
    }

    /** Every name and short form, accent-free, to the name it's under in the table. */
    private val index: Map<String, Found> by lazy {
        val all = LinkedHashMap<String, Found>()
        val specs = fixed.values + movable.values + GEORGE + JOSEPH
        for (spec in specs) for (name in names(spec)) {
            for (form in listOf(name.name) + name.also) {
                all.putIfAbsent(fold(form.lowercase(GREEK)), Found(form, name.name))
            }
        }
        all
    }

    /** The same names in Latin letters ("eleni", "giorgos"), as loosely spelled keys. */
    private val latinIndex: Map<String, Found> by lazy {
        val all = LinkedHashMap<String, Found>()
        for ((key, found) in index) all.putIfAbsent(loose(latin(key)), found)
        for ((english, greek) in englishNames) index[fold(greek.lowercase(GREEK))]?.let { all[loose(english)] = it }
        all
    }

    /**
     * The name in the table that [spoken] is, in Greek in any case ("της Ελένης", "τον Γιώργο") or
     * in Latin letters ("Eleni", "George"). Null when it isn't in the calendar.
     */
    fun find(spoken: String): Found? {
        val text = spoken.trim().lowercase(GREEK).replace(Regex("[^\\p{L} ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        val words = text.split(' ')
        val tries = listOf(text, words.first(), words.last()).distinct()
        for (attempt in tries) {
            if (isGreek(attempt)) {
                for (form in caseForms(fold(attempt))) index[form]?.let { return it }
            } else {
                // "eleni's" arrives as "eleni s".
                for (form in listOf(attempt, attempt.removeSuffix("s"))) {
                    val key = loose(form)
                    if (key.length > 1) latinIndex[key]?.let { return it }
                }
            }
        }
        return null
    }

    /** The name in the nominative for genitive and accusative forms: "ελενησ" -> "ελενη", "γιωργου" -> "γιωργοσ". */
    private fun caseForms(word: String): List<String> = buildList {
        add(word)
        if (word.endsWith("ου")) add(word.dropLast(2) + "οσ")
        if (word.endsWith("σ")) add(word.dropLast(1))
        if (word.endsWith("η") || word.endsWith("α") || word.endsWith("ο")) add(word + "σ")
        if (word.endsWith("ων")) add(word.dropLast(2) + "ωνασ")
        // "Σπυρίδωνα", "Σπυρίδωνος", "Παντελεήμονα": the -ων names.
        if (word.endsWith("ωνα")) add(word.dropLast(1))
        if (word.endsWith("ωνοσ")) add(word.dropLast(2))
        if (word.endsWith("ονα")) add(word.dropLast(3) + "ων")
        if (word.endsWith("ονοσ")) add(word.dropLast(4) + "ων")
    }.distinct()

    /** Greek letters in Latin ones, the way Greek names are usually written: "κωνσταντινοσ" -> "konstantinos". */
    fun latin(greek: String): String {
        val text = fold(greek.lowercase(GREEK))
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val pair = text.substring(i, minOf(i + 2, text.length))
            val start = i == 0 || !text[i - 1].isLetter()
            val two = when (pair) {
                "ου" -> "ou"
                "αι" -> "ai"
                "ει" -> "ei"
                "οι" -> "oi"
                "αυ" -> "av"
                "ευ" -> "ev"
                "μπ" -> if (start) "b" else "mp"
                "ντ" -> if (start) "d" else "nt"
                "γκ" -> if (start) "g" else "gk"
                "γγ" -> "ng"
                else -> null
            }
            if (two != null) {
                out.append(two)
                i += 2
                continue
            }
            out.append(LATIN[text[i]] ?: text[i].toString())
            i++
        }
        return out.toString()
    }

    /** Spelling-insensitive key for Latin names: "Yiorgos", "Giorgos", "Konstantinos", "Constantinos". */
    private fun loose(text: String): String {
        var key = text.lowercase(Locale.ROOT).filter { it.isLetter() }
        for ((from, to) in LOOSE) key = key.replace(from, to)
        return key.replace(Regex("(.)\\1+"), "$1")
    }

    private val GREEK = Locale.forLanguageTag("el")

    private val LATIN = mapOf(
        'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th", 'ι' to "i",
        'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s",
        'τ' to "t", 'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps", 'ω' to "o",
    )

    private val LOOSE = listOf(
        "ch" to "h", "kh" to "h", "ph" to "f", "th" to "t", "ou" to "u", "oo" to "u", "ei" to "i", "oi" to "i",
        "ee" to "i", "y" to "i", "ai" to "e", "mp" to "b", "nt" to "d", "gk" to "g", "c" to "k", "w" to "v",
    )

    /** Names as English speakers write them, to the Greek name they celebrate with. */
    private val englishNames = mapOf(
        "george" to "Γεώργιος", "yiorgos" to "Γιώργος", "helen" to "Ελένη", "helena" to "Ελένη", "elena" to "Ελένη",
        "mary" to "Μαρία", "marie" to "Μαρία", "john" to "Ιωάννης", "yiannis" to "Γιάννης", "joanna" to "Ιωάννα",
        "nick" to "Νικόλαος", "nicholas" to "Νικόλαος", "nicolas" to "Νικόλαος", "peter" to "Πέτρος", "paul" to "Παύλος",
        "michael" to "Μιχαήλ", "mike" to "Μιχαήλ", "michalis" to "Μιχάλης", "catherine" to "Αικατερίνη",
        "katherine" to "Αικατερίνη", "kate" to "Αικατερίνη", "katerina" to "Κατερίνα", "demetrius" to "Δημήτριος",
        "dimitri" to "Δημήτριος", "demetra" to "Δήμητρα", "constantine" to "Κωνσταντίνος", "costas" to "Κώστας",
        "alexander" to "Αλέξανδρος", "alex" to "Αλέξανδρος", "alexandra" to "Αλεξάνδρα", "anna" to "Άννα",
        "anne" to "Άννα", "ann" to "Άννα", "sophia" to "Σοφία", "sophie" to "Σοφία", "irene" to "Ειρήνη",
        "christine" to "Χριστίνα", "chris" to "Χρήστος", "christos" to "Χρήστος", "andrew" to "Ανδρέας",
        "stephen" to "Στέφανος", "steven" to "Στέφανος", "stephanie" to "Στεφανία", "thomas" to "Θωμάς", "tom" to "Θωμάς",
        "basil" to "Βασίλειος", "bill" to "Βασίλειος", "gregory" to "Γρηγόριος", "greg" to "Γρηγόριος",
        "theodore" to "Θεόδωρος", "anthony" to "Αντώνιος", "tony" to "Αντώνιος", "elias" to "Ηλίας", "elijah" to "Ηλίας",
        "luke" to "Λουκάς", "mark" to "Μάρκος", "matthew" to "Ματθαίος", "james" to "Ιάκωβος", "jacob" to "Ιάκωβος",
        "philip" to "Φίλιππος", "angela" to "Αγγελική", "angel" to "Άγγελος", "evangelos" to "Ευάγγελος",
        "stella" to "Στυλιανή", "lazarus" to "Λάζαρος", "daniel" to "Δανιήλ", "joseph" to "Ιωσήφ", "david" to "Δαβίδ",
        "zachary" to "Ζαχαρίας", "elizabeth" to "Ελισάβετ", "lucy" to "Λουκία", "lucia" to "Λουκία",
        "eugenia" to "Ευγενία", "natalie" to "Ναταλία", "dennis" to "Διονύσιος", "harry" to "Χαράλαμπος",
        "emmanuel" to "Εμμανουήλ", "manos" to "Μάνος", "raphael" to "Ραφαήλ", "gabriel" to "Γαβριήλ", "zoe" to "Ζωή",
        "timothy" to "Τιμόθεος", "simeon" to "Συμεών", "jason" to "Ιάσων", "achilles" to "Αχιλλέας", "barbara" to "Βαρβάρα",
        "theodora" to "Θεοδώρα", "dora" to "Θεοδώρα", "panos" to "Πάνος", "kiki" to "Κική",
    )
}
