package com.fastsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CalculatorTest {

    private fun eval(input: String, locale: Locale = Locale.US) = Calculator.evaluate(input, locale)

    private fun value(input: String): Double {
        val result = eval(input)
        assertNotNull("expected a result for '$input'", result)
        return result!!.value
    }

    @Test
    fun basicArithmetic() {
        assertEquals(7.0, value("1+2*3"), 1e-9)
        assertEquals(9.0, value("(1+2)*3"), 1e-9)
        assertEquals(2.5, value("5/2"), 1e-9)
        assertEquals(-4.0, value("-2^2"), 1e-9)
        assertEquals(512.0, value("2^3^2"), 1e-9)
        assertEquals(12.0, value("3x4"), 1e-9)
        assertEquals(12.0, value("3 × 4"), 1e-9)
        assertEquals(2.0, value("8 ÷ 4"), 1e-9)
    }

    @Test
    fun implicitMultiplicationAndConstants() {
        assertEquals(14.0, value("2(3+4)"), 1e-9)
        assertEquals(2 * Math.PI, value("2pi"), 1e-9)
        assertEquals(21.0, value("(1+2)(3+4)"), 1e-9)
    }

    @Test
    fun percentages() {
        assertEquals(220.0, value("200 + 10%"), 1e-9)
        assertEquals(180.0, value("200 - 10%"), 1e-9)
        assertEquals(20.0, value("200 * 10%"), 1e-9)
        assertEquals(0.5, value("50%"), 1e-9)
    }

    @Test
    fun functionsUseDegrees() {
        assertEquals(0.5, value("sin 30"), 1e-9)
        assertEquals(0.0, value("sin(180)"), 0.0)
        assertEquals(4.0, value("sqrt(16)"), 1e-9)
        assertEquals(3.0, value("√9"), 1e-9)
        assertEquals(2.0, value("log 100"), 1e-9)
        assertEquals(120.0, value("5!"), 1e-9)
        assertTrue(eval("sin 30")!!.usesDegrees)
        assertNull(eval("tan 90"))
    }

    @Test
    fun floatingPointNoiseIsHidden() {
        assertEquals("0.3", eval("0.1+0.2")!!.formatted)
        assertEquals("1,234,567", eval("1234567*1")!!.formatted)
        assertEquals("0.3333333333", eval("1/3")!!.plain.take(12))
    }

    @Test
    fun localeDecimalComma() {
        val result = eval("1,5*2", Locale.GERMANY)
        assertNotNull(result)
        assertEquals(3.0, result!!.value, 1e-9)
        assertEquals("3", result.formatted)
    }

    @Test
    fun nonCalculationsAreIgnored() {
        assertNull(eval(""))
        assertNull(eval("42"))
        assertNull(eval("pi"))
        assertNull(eval("e"))
        assertNull(eval("chrome"))
        assertNull(eval("2 apples"))
        assertNull(eval("1/0"))
        assertNull(eval("555-1234"))
        assertNull(eval("+1 555 123 4567"))
        assertNull(eval("covid 19"))
    }

    @Test
    fun toleratesUnclosedParenthesisWhileTyping() {
        assertEquals(9.0, value("(1+2)*(1+2"), 1e-9)
    }
}
