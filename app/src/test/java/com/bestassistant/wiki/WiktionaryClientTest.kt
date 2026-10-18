package com.bestassistant.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WiktionaryClientTest {

    @Test
    fun meaningsInPlainText() {
        val json = """
            {"en":[
              {"partOfSpeech":"Symbol","language":"English","definitions":[{"definition":"ISO 639-3 code"}]},
              {"partOfSpeech":"Verb","language":"English","definitions":[
                {"definition":"To move <a href=\"/wiki/swiftly\">swiftly</a>.\n<ol><li>To run a race.</li></ol>"},
                {"definition":"To flee."}
              ]},
              {"partOfSpeech":"Noun","language":"English","definitions":[{"definition":"Act of running &amp; moving fast."}]}
            ],
            "nl":[{"partOfSpeech":"Verb","language":"Dutch","definitions":[{"definition":"rennen"}]}]}
        """.trimIndent()
        val senses = WiktionaryClient.parse(json)
        assertEquals(
            listOf(Sense("Verb", "To move swiftly."), Sense("Noun", "Act of running & moving fast."), Sense("Verb", "To flee.")),
            senses,
        )
    }

    @Test
    fun nothingForOtherLanguagesOrBadJson() {
        assertTrue(WiktionaryClient.parse("""{"fr":[{"partOfSpeech":"Noun","definitions":[{"definition":"chat"}]}]}""").isEmpty())
        assertTrue(WiktionaryClient.parse("not json").isEmpty())
    }
}
