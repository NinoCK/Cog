package com.fastsearch.search

import java.text.Normalizer
import kotlin.math.min

/**
 * Precomputed, normalized representation of something searchable (an app label, a contact name).
 * Building it once per item keeps per-keystroke matching cheap.
 */
class SearchKey private constructor(
    /** "google maps" */
    val normalized: String,
    /** "googlemaps" */
    val compact: String,
    /** ["google", "maps"] */
    val words: List<String>,
    /** Initials of words and camel-case parts, e.g. "YouTube Music" -> ["ym", "ytm"]. */
    val initials: List<String>,
    /** Meaningful package-name segments, e.g. "com.whatsapp" -> ["whatsapp"]. */
    val packageWords: List<String>,
) {
    companion object {
        private val camelBoundary = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])")
        private val ignoredPackageWords = setOf("com", "org", "net", "android", "google", "app", "apps", "mobile", "www")

        fun of(label: String, packageName: String? = null): SearchKey {
            val normalized = FuzzyMatcher.normalize(label)
            val words = normalized.split(' ').filter { it.isNotEmpty() }
            val camelParts = FuzzyMatcher.stripDiacritics(label)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() }
                .flatMap { it.split(camelBoundary) }
                .map { it.lowercase() }
                .filter { it.isNotEmpty() }
            val initials = listOf(
                words.joinToString("") { it.take(1) },
                camelParts.joinToString("") { it.take(1) },
            ).filter { it.length >= 2 }.distinct()
            val packageWords = packageName
                ?.lowercase()
                ?.split('.', '_')
                ?.filter { it.length >= 3 && it !in ignoredPackageWords }
                .orEmpty()
            return SearchKey(normalized, words.joinToString(""), words, initials, packageWords)
        }
    }
}

object FuzzyMatcher {
    const val EXACT = 1000
    const val PREFIX = 900
    const val WORD_PREFIX = 800
    const val INITIALS = 700
    const val SUBSTRING = 600
    const val PACKAGE = 450
    const val TYPO = 350

    private val diacritics = Regex("\\p{Mn}+")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")

    fun stripDiacritics(text: String): String =
        diacritics.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")

    /** Lowercase, strip accents, collapse punctuation into single spaces. */
    fun normalize(text: String): String =
        nonAlphanumeric.replace(stripDiacritics(text).lowercase(), " ").trim()

    /**
     * Scores how well [query] (already passed through [normalize]) matches [key].
     * Returns 0 for no match; higher is better. Tiers are spaced so a better
     * kind of match always beats a worse one, with small in-tier adjustments.
     */
    fun score(query: String, key: SearchKey): Int {
        if (query.isEmpty() || key.compact.isEmpty()) return 0
        val q = query
        val qc = q.replace(" ", "")
        if (qc.isEmpty()) return 0

        if (key.normalized == q || key.compact == qc) return EXACT

        if (key.normalized.startsWith(q) || key.compact.startsWith(qc)) {
            // Prefer shorter labels: "Maps" over "Maps Go" when typing "map".
            return PREFIX + (20 * qc.length / key.compact.length)
        }

        val wordIndex = key.words.indexOfFirst { it.startsWith(qc) }
        if (wordIndex >= 0) return WORD_PREFIX - min(wordIndex, 5) * 10

        val queryWords = q.split(' ').filter { it.isNotEmpty() }
        if (queryWords.size > 1 && matchesWordsInOrder(queryWords, key.words)) return WORD_PREFIX - 20

        if (qc.length >= 2) {
            val initials = key.initials.firstOrNull { it.startsWith(qc) }
            if (initials != null) return INITIALS + if (initials.length == qc.length) 30 else 0
        }

        // Mid-word matches on one or two letters are mostly noise ("ch" in "FastSearch").
        if (qc.length >= 3) {
            val index = key.compact.indexOf(qc)
            if (index >= 0) return SUBSTRING - min(index, 50)
        }

        if (qc.length >= 3 && key.packageWords.any { it.startsWith(qc) }) return PACKAGE

        if (qc.length >= 4) {
            val allowed = if (qc.length >= 7) 2 else 1
            val candidates = buildList {
                add(key.compact)
                addAll(key.words)
            }
            var best = Int.MAX_VALUE
            for (candidate in candidates) {
                // Compare against the candidate's prefix of similar length so "whtasapp" ~ "whatsapp".
                for (len in (qc.length - 1)..(qc.length + 1)) {
                    if (len <= 0 || len > candidate.length) continue
                    best = min(best, osaDistance(qc, candidate.substring(0, len)))
                }
            }
            if (best <= allowed) return TYPO - 50 * best
        }

        if (qc.length >= 3 && key.words.any { it.startsWith(qc[0]) }) {
            val span = subsequenceSpan(qc, key.compact)
            if (span > 0) return 100 + 150 * qc.length / span
        }
        return 0
    }

    private fun matchesWordsInOrder(queryWords: List<String>, words: List<String>): Boolean {
        var w = 0
        for (qw in queryWords) {
            while (w < words.size && !words[w].startsWith(qw)) w++
            if (w == words.size) return false
            w++
        }
        return true
    }

    /** Length of the tightest window of [text] containing [query] as a subsequence (greedy), or 0. */
    private fun subsequenceSpan(query: String, text: String): Int {
        var first = -1
        var qi = 0
        for (ti in text.indices) {
            if (text[ti] == query[qi]) {
                if (qi == 0) first = ti
                qi++
                if (qi == query.length) return ti - first + 1
            }
        }
        return 0
    }

    /** Optimal string alignment distance (Levenshtein + adjacent transpositions). */
    fun osaDistance(a: String, b: String): Int {
        val rows = a.length + 1
        val cols = b.length + 1
        val d = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) d[i][0] = i
        for (j in 0 until cols) d[0][j] = j
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = min(value, d[i - 2][j - 2] + 1)
                }
                d[i][j] = value
            }
        }
        return d[a.length][b.length]
    }
}
