package com.bestassistant.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ExchangeRatesTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
            <gesmes:subject>Reference rates</gesmes:subject>
            <Cube>
                <Cube time='2026-10-16'>
                    <Cube currency='USD' rate='1.1253'/>
                    <Cube currency='JPY' rate='177.82'/>
                    <Cube currency='GBP' rate='0.84385'/>
                </Cube>
            </Cube>
        </gesmes:Envelope>
    """.trimIndent()

    @Test
    fun parsesTheDailyFile() {
        val rates = ExchangeRates.parse(xml)!!
        assertEquals(LocalDate.of(2026, 10, 16), rates.date)
        assertEquals(mapOf("USD" to 1.1253, "JPY" to 177.82, "GBP" to 0.84385), rates.perEuro)
        assertNull(ExchangeRates.parse("<html>maintenance</html>"))
    }

    @Test
    fun convertsThroughTheEuro() {
        val rates = ExchangeRates.parse(xml)!!
        assertEquals(112.53, rates.convert(100.0, "EUR", "USD")!!, 1e-9)
        assertEquals(100.0 / 1.1253, rates.convert(100.0, "USD", "EUR")!!, 1e-9)
        assertEquals(100.0 / 1.1253 * 0.84385, rates.convert(100.0, "USD", "GBP")!!, 1e-9)
        assertNull(rates.convert(1.0, "USD", "BGN"))
    }
}
