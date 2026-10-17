package com.bestassistant.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupertonicVoiceTest {

    @Test
    fun picksM2AndF2() {
        // voice.bin: F1–F5 are speakers 0–4, M1–M5 are 5–9.
        assertEquals(6, SupertonicVoice.speakerFor(male = true))
        assertEquals(1, SupertonicVoice.speakerFor(male = false))
    }

    @Test
    fun convertsAndClipsSamples() {
        val pcm = SupertonicVoice.toPcm16(floatArrayOf(0f, 1f, -1f, 1.5f, -2f))
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 32767, -32767), pcm)
    }

    @Test
    fun everyFileIsPinned() {
        val files = SupertonicVoice.FILES
        assertEquals(files.size, files.map { it.name }.toSet().size)
        assertTrue(files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size > 0 })
        assertEquals(145_295_768L, SupertonicVoice.SIZE_BYTES)
    }
}
