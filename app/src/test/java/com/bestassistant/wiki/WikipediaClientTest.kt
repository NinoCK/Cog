package com.bestassistant.wiki

import com.bestassistant.device.TimeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WikipediaClientTest {

    @Test
    fun search() {
        val json = """{"pages":[{"id":1,"key":"Quasar","title":"Quasar"},{"id":2,"key":"Quasar_(disambiguation)"},{"id":3}]}"""
        assertEquals(listOf("Quasar", "Quasar_(disambiguation)"), WikipediaClient.parseSearch(json))
        assertEquals(emptyList<String>(), WikipediaClient.parseSearch("not json"))
    }

    @Test
    fun summary() {
        val json = """
            {"type":"standard","title":"Quasar","description":"Active galactic nucleus of very high luminosity",
             "extract":"A quasar is an extremely luminous active galactic nucleus. It is powered by a supermassive black hole.",
             "thumbnail":{"source":"https://upload.wikimedia.org/q.jpg"},
             "content_urls":{"mobile":{"page":"https://en.m.wikipedia.org/wiki/Quasar"}}}
        """.trimIndent()
        val article = WikipediaClient.parseSummary(json)!!
        assertEquals("Quasar", article.title)
        assertEquals("Active galactic nucleus of very high luminosity", article.description)
        assertEquals("https://upload.wikimedia.org/q.jpg", article.imageUrl)
        assertEquals("https://en.m.wikipedia.org/wiki/Quasar", article.pageUrl)
        assertNull(WikipediaClient.parseSummary("""{"type":"disambiguation","title":"Mercury","extract":"Mercury may refer to:"}"""))
        assertNull(WikipediaClient.parseSummary("""{"type":"standard","title":"Empty","extract":""}"""))
    }

    @Test
    fun spokenSummaryIsShortAndSkipsBrackets() {
        val extract = "Ada Lovelace (10 December 1815 – 27 November 1852) was an English mathematician. " +
            "She is chiefly known for her work on Charles Babbage's Analytical Engine. " +
            "She was the first to recognise that the machine had applications beyond pure calculation, and to publish an algorithm."
        assertEquals(
            "Ada Lovelace was an English mathematician. She is chiefly known for her work on Charles Babbage's Analytical Engine.",
            WikipediaClient.spokenSummary(extract, maxChars = 140),
        )
    }

    private fun english(seconds: Int) = TimeText.spokenDuration(
        seconds,
        and = "and",
        hours = { if (it == 1) "1 hour" else "$it hours" },
        minutes = { if (it == 1) "1 minute" else "$it minutes" },
        seconds = { if (it == 1) "1 second" else "$it seconds" },
    )

    @Test
    fun spokenDurations() {
        assertEquals("5 minutes", english(300))
        assertEquals("1 hour and 30 minutes", english(5400))
        assertEquals("2 hours, 1 minute and 5 seconds", english(7265))
        assertEquals("1 second", english(1))
    }
}
