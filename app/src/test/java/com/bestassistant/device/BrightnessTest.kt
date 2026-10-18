package com.bestassistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BrightnessTest {

    @Test
    fun followsTheSlidersCurve() {
        assertEquals(1, Brightness.toRaw(0))
        assertEquals(255, Brightness.toRaw(100))
        // Half way on Android's slider is far below half the raw maximum.
        assertTrue(Brightness.toRaw(50) in 15..30)
    }

    @Test
    fun roundTrips() {
        // The darkest few percent share the lowest raw values.
        for (percent in 0..100 step 5) {
            assertTrue("$percent", abs(Brightness.toPercent(Brightness.toRaw(percent)) - percent) <= if (percent < 10) 6 else 2)
        }
    }
}
