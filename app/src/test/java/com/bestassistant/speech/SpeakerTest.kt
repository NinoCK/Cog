package com.bestassistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SpeakerTest {

    @Test
    fun chirpIsShortAndAudible() {
        val chirp = Chirp.samples(24_000)
        assertTrue(chirp.size in 24_000 / 10..24_000 / 2)
        assertTrue(chirp.maxOf { abs(it.toInt()) } > 3_000)
    }

    @Test
    fun readsMonoAndStereoWav() {
        val mono = Wav.parse(wav(channels = 1, samples = shortArrayOf(100, -200, 300)))
        assertNotNull(mono)
        assertEquals(16_000, mono!!.rate)
        assertEquals(listOf<Short>(100, -200, 300), mono.samples.toList())

        val stereo = Wav.parse(wav(channels = 2, samples = shortArrayOf(100, 300, -200, -400)))
        assertEquals(listOf<Short>(200, -300), stereo!!.samples.toList())

        // Streaming writers leave the data size at 0: read to the end.
        val streamed = Wav.parse(wav(channels = 1, samples = shortArrayOf(1, 2, 3, 4), declaredSize = 0))
        assertEquals(4, streamed!!.samples.size)

        assertNull(Wav.parse(ByteArray(8)))
    }

    @Test
    fun splitsRepliesIntoSentences() {
        assertEquals(
            listOf("Affirmative. Your alarm is set for 7:00 AM, which is 9 hours from now.", "There is more."),
            Speaker.sentences("Affirmative. Your alarm is set for 7:00 AM, which is 9 hours from now. There is more."),
        )
        assertEquals(listOf("Μάλιστα. Ο φακός άναψε."), Speaker.sentences("Μάλιστα. Ο φακός άναψε."))
    }

    private fun wav(channels: Int, samples: ShortArray, declaredSize: Int? = null): ByteArray {
        val data = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(16_000)
            .putInt(16_000 * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        buffer.put("data".toByteArray()).putInt(declaredSize ?: data)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }
}
