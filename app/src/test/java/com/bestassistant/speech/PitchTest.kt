package com.bestassistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PitchTest {

    private val rate = 22_050

    /** A buzzy "voice" at [f0] Hz: fundamental plus falling harmonics, gently wobbling like speech. */
    private fun voice(f0: Double, seconds: Double = 1.0): ShortArray {
        var phase = 0.0
        return ShortArray((rate * seconds).toInt()) { i ->
            val hz = f0 * (1 + 0.03 * sin(2 * PI * 3 * i / rate))
            phase += 2 * PI * hz / rate
            val v = sin(phase) + 0.6 * sin(2 * phase) + 0.4 * sin(3 * phase) + 0.25 * sin(4 * phase)
            (v * 7_000).toInt().toShort()
        }
    }

    @Test
    fun measuresMaleAndFemaleVoices() {
        val male = Pitch.median(voice(115.0), rate)
        val female = Pitch.median(voice(210.0), rate)
        assertNotNull(male)
        assertNotNull(female)
        assertEquals(115f, male!!, 8f)
        // Not read an octave low, which would make a woman sound like a man.
        assertEquals(210f, female!!, 12f)
        assertTrue(male < Pitch.MALE_BELOW && female > Pitch.MALE_BELOW)
    }

    @Test
    fun silenceHasNoPitch() {
        assertNull(Pitch.median(ShortArray(rate), rate))
    }

    @Test
    fun resamplingChangesLength() {
        val input = voice(200.0, 0.5)
        assertEquals(input.size * 1.22f, Pitch.resample(input, 1.22f).size.toFloat(), 1f)
        assertEquals(input.size, Pitch.resample(input, 1f).size)
        // Stretching a 200 Hz voice by 1.25 lowers it to 160 Hz.
        assertEquals(160f, Pitch.median(Pitch.resample(input, 1.25f), rate)!!, 10f)
    }

    @Test
    fun shiftsOnlyWhenTheVoiceIsTheWrongKind() {
        assertEquals(1f, Pitch.shiftFor(120f, male = true).factor)
        assertEquals(1f, Pitch.shiftFor(210f, male = false).factor)
        val toMale = Pitch.shiftFor(210f, male = true)
        assertTrue(toMale.changesSound && toMale.factor > 1f)
        // The engine pitch and the resampling together land near a typical male pitch.
        assertEquals(Pitch.MALE_TARGET, 210f * toMale.pitch / toMale.factor, 1f)
        val toFemale = Pitch.shiftFor(115f, male = false)
        assertTrue(toFemale.factor < 1f)
        assertEquals(Pitch.FEMALE_TARGET, 115f * toFemale.pitch / toFemale.factor, 1f)
        // Unknown voices are left alone.
        assertEquals(1f, Pitch.shiftFor(null, male = true).factor)
    }
}
