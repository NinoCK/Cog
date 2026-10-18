package com.bestassistant.integrations.jellyfin

import java.text.Normalizer

/**
 * Names the way they're matched against what was said: "Spider-Man: No Way Home" is
 * "spider man no way home", "The Office" is "office", "Amélie" is "amelie".
 */
object Titles {

    private val marks = Regex("\\p{Mn}+")
    private val apostrophes = Regex("['’`]")
    private val notWords = Regex("[^\\p{L}\\p{N}]+")

    fun key(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase()
        val plain = folded.replace("&", " and ").replace(apostrophes, "").replace(notWords, " ").trim()
        return plain.removePrefix("the ").removePrefix("a ").trim()
    }

    /**
     * How well [said] names [name] (or its [original] title): 3 exactly, 2 when one starts the
     * other ("dune" for "Dune: Part Two"), 1 when every word said is in it, 0 not at all.
     */
    fun score(said: String, name: String, original: String? = null): Int {
        val wanted = key(said)
        if (wanted.isEmpty()) return 0
        return listOfNotNull(name, original).maxOf { title ->
            val have = key(title)
            when {
                have == wanted -> 3
                have.startsWith("$wanted ") || wanted.startsWith("$have ") -> 2
                wanted.split(' ').all { it in have.split(' ') } -> 1
                else -> 0
            }
        }
    }
}

/**
 * The movies and shows on the server, by name, so "play inception" can be told apart from a song
 * without asking the server first. Kept on the phone between runs and refreshed now and then.
 */
class Library {

    /** Each name as matched, and as the server spells it. */
    @Volatile
    private var names: Map<String, String> = emptyMap()

    /** Whether [said] is the name of a movie or show on the server. */
    fun has(said: String): Boolean = name(said) != null

    /** The server's own spelling of [said] ("Spider-Man: No Way Home"), for searching by it. */
    fun name(said: String): String? = Titles.key(said).takeIf { it.isNotEmpty() }?.let { names[it] }

    fun set(titles: Collection<String>) {
        names = titles.associateBy(Titles::key).filterKeys { it.length >= MIN_LENGTH && it !in COMMON }
    }

    val size: Int get() = names.size

    private companion object {
        const val MIN_LENGTH = 3

        /**
         * Titles that are also everyday words after "play": "play it", "play something", "play
         * music". Said like that they're never the movie; "play the movie it" still is.
         */
        val COMMON = setOf(
            "music", "something", "anything", "next", "again", "her", "him", "them", "you", "me", "now", "more",
            "games", "movies", "radio", "the news", "news", "songs", "playlist", "videos", "media", "pause", "stop",
        )
    }
}

/** Spoken numbers, lengths of time, languages and genres, the way speech recognition writes them. */
object Spoken {

    private val units = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
        "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "last" to -1,
    )

    /** "3", "three", "twenty one", "a", "third": a whole number. */
    fun number(text: String): Int? {
        val said = text.trim().removePrefix("number ").trim()
        said.toIntOrNull()?.let { return it }
        said.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull()?.let { return it }
        ordinals[said]?.let { return it }
        if (said == "a" || said == "an") return 1
        val index = units.indexOf(said)
        if (index >= 0) return index
        tens[said]?.let { return it }
        val parts = said.split(' ', '-')
        if (parts.size == 2) {
            val ten = tens[parts[0]] ?: return null
            val unit = units.indexOf(parts[1]).takeIf { it in 1..9 } ?: return null
            return ten + unit
        }
        return null
    }

    /** A number as a pattern matches it: digits, or one or two words. */
    const val NUMBER = "(\\d+|[a-z]+(?:[ -][a-z]+)?)"

    /**
     * "30 seconds", "a minute and a half", "1 hour 20 minutes", "1:20:00", "2 and a half minutes":
     * seconds. [bareUnit] is what a number alone means ("skip ahead 30"), if anything.
     */
    fun duration(text: String, bareUnit: Int? = null): Int? {
        val said = text.trim()
        clock(said)?.let { return it }
        number(said)?.let { n -> return bareUnit?.let { n * it } }
        val words = said.replace(",", " ").split(' ').filter { it.isNotEmpty() && it != "and" }
        var total = 0.0
        var lastUnit = 0
        val pending = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val unit = unitOf(word)
            when {
                unit != null -> {
                    total += (amount(pending) ?: return null) * unit
                    lastUnit = unit
                    pending.clear()
                }
                word == "a" && words.getOrNull(i + 1) == "half" -> {
                    when {
                        // "2 and a half minutes"
                        pending.isNotEmpty() -> pending += HALF
                        // "a minute and a half"
                        lastUnit > 0 && unitOf(words.getOrNull(i + 2)) == null -> total += lastUnit / 2.0
                        else -> pending += "half"
                    }
                    i++
                }
                else -> pending += word
            }
            i++
        }
        if (pending.isNotEmpty() || lastUnit == 0) return null
        return total.toInt().takeIf { it > 0 }
    }

    private const val HALF = "+half"

    private fun unitOf(word: String?): Int? = when (word) {
        "hour", "hours", "hr", "hrs" -> 3600
        "minute", "minutes", "min", "mins" -> 60
        "second", "seconds", "sec", "secs" -> 1
        else -> null
    }

    /** "half an", "2 +half", "twenty one": how many of a unit. */
    private fun amount(words: List<String>): Double? {
        if (words.isEmpty()) return null
        if (words.first() == "half") return 0.5.takeIf { words.drop(1).all { it == "a" || it == "an" } }
        val half = words.last() == HALF
        val n = number(words.filter { it != HALF }.joinToString(" ")) ?: return null
        return n + if (half) 0.5 else 0.0
    }

    private val clockTime = Regex("^(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})$")

    /** "1:20:00", "45:30": seconds. */
    private fun clock(text: String): Int? {
        val m = clockTime.matchEntire(text) ?: return null
        val hours = m.groupValues[1].toIntOrNull() ?: 0
        return hours * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
    }

    /** Languages by name, with the codes video files tag their tracks with. */
    val languages: Map<String, Set<String>> = mapOf(
        "english" to setOf("eng", "en"),
        "greek" to setOf("gre", "ell", "el"),
        "spanish" to setOf("spa", "es"),
        "french" to setOf("fre", "fra", "fr"),
        "german" to setOf("ger", "deu", "de"),
        "italian" to setOf("ita", "it"),
        "portuguese" to setOf("por", "pt"),
        "japanese" to setOf("jpn", "ja"),
        "korean" to setOf("kor", "ko"),
        "chinese" to setOf("chi", "zho", "zh"),
        "mandarin" to setOf("chi", "zho", "zh", "cmn"),
        "cantonese" to setOf("yue"),
        "russian" to setOf("rus", "ru"),
        "dutch" to setOf("dut", "nld", "nl"),
        "swedish" to setOf("swe", "sv"),
        "norwegian" to setOf("nor", "nob", "nno", "no"),
        "danish" to setOf("dan", "da"),
        "finnish" to setOf("fin", "fi"),
        "polish" to setOf("pol", "pl"),
        "turkish" to setOf("tur", "tr"),
        "arabic" to setOf("ara", "ar"),
        "hebrew" to setOf("heb", "he"),
        "hindi" to setOf("hin", "hi"),
        "thai" to setOf("tha", "th"),
        "czech" to setOf("cze", "ces", "cs"),
        "hungarian" to setOf("hun", "hu"),
        "romanian" to setOf("rum", "ron", "ro"),
        "bulgarian" to setOf("bul", "bg"),
        "ukrainian" to setOf("ukr", "uk"),
        "croatian" to setOf("hrv", "hr"),
        "serbian" to setOf("srp", "sr"),
        "indonesian" to setOf("ind", "id"),
        "vietnamese" to setOf("vie", "vi"),
    )

    /** A pattern for any language name. */
    val LANGUAGE = "(${languages.keys.joinToString("|")}|original)"

    /** What people call a genre, and Jellyfin's name for it. */
    val genres: Map<String, String> = mapOf(
        "comedy" to "Comedy", "funny" to "Comedy", "comedies" to "Comedy",
        "horror" to "Horror", "scary" to "Horror",
        "action" to "Action", "thriller" to "Thriller", "drama" to "Drama", "romance" to "Romance", "romantic" to "Romance",
        "romcom" to "Romance", "rom com" to "Romance",
        "sci fi" to "Science Fiction", "sci-fi" to "Science Fiction", "science fiction" to "Science Fiction", "scifi" to "Science Fiction",
        "fantasy" to "Fantasy", "animated" to "Animation", "animation" to "Animation", "cartoon" to "Animation", "anime" to "Animation",
        "kids" to "Family", "family" to "Family", "children's" to "Family",
        "documentary" to "Documentary", "crime" to "Crime", "mystery" to "Mystery", "adventure" to "Adventure",
        "war" to "War", "western" to "Western", "history" to "History", "historical" to "History", "musical" to "Music",
        "christmas" to "Christmas",
    )

    val GENRE = "(${genres.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})"
}
