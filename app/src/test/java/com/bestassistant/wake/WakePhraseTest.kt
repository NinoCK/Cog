package com.bestassistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakePhraseTest {

    private val pocket = WakePhrase.words("ok Pocket")
    private val cog = WakePhrase.words("ok Cog")
    private val computer = WakePhrase.words("hey computer")

    @Test
    fun phraseWordsMatchHowTheRecognizerWrites() {
        assertEquals(listOf("okay", "pocket"), WakePhrase.words("OK Pocket"))
        assertEquals(listOf("okay", "cog"), WakePhrase.words("O.K. cog!"))
        assertEquals(listOf("hey", "computer"), WakePhrase.words("Hey, computer"))
    }

    @Test
    fun findsThePhrase() {
        assertNotNull(WakePhrase.find("okay pocket", pocket))
        assertNotNull(WakePhrase.find("hey okay pocket", pocket))
        assertEquals("set a timer for five minutes", WakePhrase.find("okay pocket set a timer for five minutes", pocket)?.rest)
        // The recognizer sometimes adds or swaps a letter.
        assertNotNull(WakePhrase.find("okay pockets", pocket))
        // One wrong letter in a longer word is tolerated.
        assertNotNull(WakePhrase.find("hey computor", computer))
    }

    @Test
    fun ignoresLookalikes() {
        // A short name must be heard exactly: one letter off is another word.
        listOf("okay cool", "okay dog", "okay god", "okay clog", "okay cop", "okay thanks", "oh cog",
            "the cog is broken", "okay let's go to the store", "okay").forEach {
            assertNull("'$it' should not trigger", WakePhrase.find(it, cog))
        }
        // A different first sound is a different word.
        listOf("okay rocket", "okay locket", "okay socket", "oh pocket", "my pocket is empty", "okay").forEach {
            assertNull("'$it' should not trigger", WakePhrase.find(it, pocket))
        }
        listOf("hey competitor", "hey company", "hey commute").forEach {
            assertNull("'$it' should not trigger", WakePhrase.find(it, computer))
        }
    }

    @Test
    fun validatesPhrases() {
        assertNull(WakePhrase.validate("ok Pocket"))
        assertNull(WakePhrase.validate("jarvis"))
        assertEquals(WakePhrase.Problem.EMPTY, WakePhrase.validate("  "))
        assertEquals(WakePhrase.Problem.TOO_SHORT, WakePhrase.validate("hey"))
        assertEquals(WakePhrase.Problem.TOO_SHORT, WakePhrase.validate("cog"))
        assertEquals(WakePhrase.Problem.TOO_LONG, WakePhrase.validate("ok my dear little robot friend"))
        assertEquals(WakePhrase.Problem.NOT_ENGLISH, WakePhrase.validate("γεια σου Κογκ"))
    }

    @Test
    fun editDistance() {
        assertEquals(0, WakePhrase.distance("jarvis", "jarvis"))
        assertEquals(1, WakePhrase.distance("jarvis", "jervis"))
        assertEquals(2, WakePhrase.distance("cog", "cool"))
    }
}
