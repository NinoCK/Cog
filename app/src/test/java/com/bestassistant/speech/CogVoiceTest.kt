package com.bestassistant.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CogVoiceTest {

    @Test
    fun `model samples become 16-bit pcm, clipped to full scale`() {
        val pcm = CogVoice.toPcm16(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 1.7f, -3f))
        assertArrayEquals(shortArrayOf(0, 16383, -16383, 32767, -32767, 32767, -32767), pcm)
    }

    @Test
    fun `cog falls back to the robot voice, other styles to themselves`() {
        assertEquals(VoiceStyle.ROBOT_COG, VoiceStyle.COG.fallback)
        VoiceStyle.entries.filterNot { it.usesModel }.forEach { assertEquals(it, it.fallback) }
    }

    @Test
    fun `an unknown or missing setting means the cog voice`() {
        assertEquals(VoiceStyle.COG, VoiceStyle.fromId("COG"))
        assertEquals(VoiceStyle.COG, VoiceStyle.fromId("NO_SUCH_STYLE"))
        assertEquals(VoiceStyle.COG, VoiceStyle.fromId(null))
    }
}
