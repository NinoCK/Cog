package com.fastsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {

    private fun score(query: String, label: String, pkg: String? = null) =
        FuzzyMatcher.score(FuzzyMatcher.normalize(query), SearchKey.of(label, pkg))

    @Test
    fun normalizeStripsAccentsAndPunctuation() {
        assertEquals("cafe creme", FuzzyMatcher.normalize("  Café-Crème! "))
    }

    @Test
    fun tiersAreOrdered() {
        val exact = score("maps", "Maps")
        val prefix = score("map", "Maps")
        val word = score("maps", "Google Maps")
        val initials = score("gm", "Google Maps")
        val substring = score("ogle", "Google Maps")
        val typo = score("whtasapp", "WhatsApp")
        assertTrue(exact > prefix)
        assertTrue(prefix > word)
        assertTrue(word > initials)
        assertTrue(initials > substring)
        assertTrue(substring > typo)
        assertTrue(typo > 0)
    }

    @Test
    fun camelCaseInitials() {
        assertTrue(score("ytm", "YouTube Music") >= FuzzyMatcher.INITIALS)
        assertTrue(score("ym", "YouTube Music") >= FuzzyMatcher.INITIALS)
    }

    @Test
    fun multiWordQuery() {
        assertTrue(score("goo ma", "Google Maps") >= FuzzyMatcher.WORD_PREFIX - 20)
    }

    @Test
    fun packageNameFallback() {
        assertEquals(FuzzyMatcher.PACKAGE, score("sbrowser", "Samsung Internet", "com.sec.android.app.sbrowser"))
    }

    @Test
    fun shorterLabelWinsPrefixTies() {
        assertTrue(score("map", "Maps") > score("map", "Maps Go"))
    }

    @Test
    fun shortQueriesDontMatchMidWord() {
        assertEquals(0, score("ch", "FastSearch"))
        assertTrue(score("arch", "FastSearch") >= FuzzyMatcher.SUBSTRING - 50)
        assertTrue(score("ch", "Chrome") >= FuzzyMatcher.PREFIX)
    }

    @Test
    fun unrelatedDoesNotMatch() {
        assertEquals(0, score("zebra", "Calculator"))
        assertEquals(0, score("", "Calculator"))
    }

    @Test
    fun osaDistanceCountsTranspositionsOnce() {
        assertEquals(1, FuzzyMatcher.osaDistance("ab", "ba"))
        assertEquals(1, FuzzyMatcher.osaDistance("kitten", "sitten"))
        assertEquals(3, FuzzyMatcher.osaDistance("kitten", "sitting"))
    }
}
