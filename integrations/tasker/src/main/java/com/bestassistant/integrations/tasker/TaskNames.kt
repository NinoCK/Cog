package com.bestassistant.integrations.tasker

import java.text.Normalizer
import kotlin.math.min

/**
 * Which of the user's task names they meant ("lights out" -> "Lights Out", "bedtime" -> "Bedtime
 * Routine"), with the kinds of match PocketSearch's own search counts as good ones, best first: the whole
 * name, its start, a word's start, its words in order, its initials, then part of it.
 */
internal object TaskNames {

    /** The name in [names] that best matches [spoken]; null when none does well enough. */
    fun best(spoken: String, names: List<String>): String? {
        val query = normalize(spoken)
        return names.map { it to score(query, it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    private val diacritics = Regex("\\p{Mn}+")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, without accents, punctuation collapsed into single spaces. */
    fun normalize(text: String): String =
        nonAlphanumeric.replace(diacritics.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(), " ").trim()

    /** How well [query] (already [normalize]d) matches [name]: 0 for not at all, higher is better. */
    private fun score(query: String, name: String): Int {
        val normalized = normalize(name)
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        val compact = words.joinToString("")
        val qc = query.replace(" ", "")
        if (qc.isEmpty() || compact.isEmpty()) return 0

        if (normalized == query || compact == qc) return EXACT
        if (normalized.startsWith(query) || compact.startsWith(qc)) return PREFIX + 20 * qc.length / compact.length

        val wordIndex = words.indexOfFirst { it.startsWith(qc) }
        if (wordIndex >= 0) return WORD_PREFIX - min(wordIndex, 5) * 10

        val queryWords = query.split(' ').filter { it.isNotEmpty() }
        if (queryWords.size > 1 && inOrder(queryWords, words)) return WORD_PREFIX - 20

        if (qc.length >= 2 && words.size >= 2 && words.joinToString("") { it.take(1) }.startsWith(qc)) return INITIALS

        // Mid-word matches on one or two letters are mostly noise.
        if (qc.length >= 3) {
            val index = compact.indexOf(qc)
            if (index >= 0) return SUBSTRING - min(index, 50)
        }

        return 0
    }

    private fun inOrder(queryWords: List<String>, words: List<String>): Boolean {
        var w = 0
        for (qw in queryWords) {
            while (w < words.size && !words[w].startsWith(qw)) w++
            if (w == words.size) return false
            w++
        }
        return true
    }

    private const val EXACT = 1000
    private const val PREFIX = 900
    private const val WORD_PREFIX = 800
    private const val INITIALS = 700
    private const val SUBSTRING = 600
}
