package com.bestassistant.wake

import java.util.Locale

/**
 * Finds the wake phrase ("ok Pocket") in what the offline recognizer heard.
 *
 * The recognizer runs unrestricted and this matcher looks for the phrase in its transcript:
 * forcing the recognizer to choose between the phrase and "something else" makes it hear the
 * phrase in "ok cool" or "oh god". Longer words tolerate one wrong letter, because the
 * recognizer sometimes spells an unusual name like a common word ("jervis" for "jarvis").
 */
object WakePhrase {

    data class Match(
        /** Words said right after the phrase, in the same breath ("set a timer"), or empty. */
        val rest: String,
    )

    /** The phrase as recognizer words: lowercase, letters only, "ok" spelled the way Vosk writes it. */
    fun words(phrase: String): List<String> {
        val raw = phrase.lowercase(Locale.ROOT)
            .replace(Regex("\\bo\\.?\\s?k\\.?(?=\\s|$)"), "okay")
            .replace(Regex("[^a-z' ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
        return raw.map { if (it == "ok") "okay" else it }
    }

    /** Problems with a phrase the user typed, or null when it can be used. */
    fun validate(phrase: String): Problem? {
        if (phrase.any { it.isLetter() && it.lowercaseChar() !in 'a'..'z' }) return Problem.NOT_ENGLISH
        val words = words(phrase)
        return when {
            words.isEmpty() -> Problem.EMPTY
            words.size > MAX_WORDS -> Problem.TOO_LONG
            // One short word fires on ordinary conversation.
            words.size == 1 && words[0].length < 5 -> Problem.TOO_SHORT
            else -> null
        }
    }

    enum class Problem { EMPTY, TOO_SHORT, TOO_LONG, NOT_ENGLISH }

    fun find(transcript: String, phrase: List<String>): Match? {
        if (phrase.isEmpty()) return null
        val heard = words(transcript)
        if (heard.size < phrase.size) return null
        for (start in 0..heard.size - phrase.size) {
            val matches = phrase.indices.all { i -> similar(heard[start + i], phrase[i]) }
            if (matches) return Match(heard.drop(start + phrase.size).joinToString(" "))
        }
        return null
    }

    private fun similar(heard: String, wanted: String): Boolean {
        if (heard == wanted) return true
        if (wanted.length < MIN_FUZZY_LENGTH || heard.length < MIN_FUZZY_LENGTH) return false
        // Same first letter: a different initial sound is a different word ("plank", "blank").
        return heard[0] == wanted[0] && distance(heard, wanted) <= 1
    }

    /** Levenshtein distance. */
    internal fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val MAX_WORDS = 4
    private const val MIN_FUZZY_LENGTH = 5
}
