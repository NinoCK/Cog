package com.bestassistant.namedays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NameDayCalendarTest {

    private fun names(year: Int, month: Int, day: Int) = NameDayCalendar.on(LocalDate.of(year, month, day)).map { it.name }

    private fun next(name: String, from: LocalDate = LocalDate.of(2026, 10, 18)) =
        NameDayCalendar.next(NameDayCalendar.find(name)!!, from)

    @Test
    fun orthodoxEaster() {
        assertEquals(LocalDate.of(2023, 4, 16), NameDayCalendar.orthodoxEaster(2023))
        assertEquals(LocalDate.of(2024, 5, 5), NameDayCalendar.orthodoxEaster(2024))
        assertEquals(LocalDate.of(2025, 4, 20), NameDayCalendar.orthodoxEaster(2025))
        assertEquals(LocalDate.of(2026, 4, 12), NameDayCalendar.orthodoxEaster(2026))
        assertEquals(LocalDate.of(2027, 5, 2), NameDayCalendar.orthodoxEaster(2027))
        assertEquals(LocalDate.of(2028, 4, 16), NameDayCalendar.orthodoxEaster(2028))
    }

    @Test
    fun fixedFeasts() {
        assertTrue("Κωνσταντίνος" in names(2026, 5, 21))
        assertTrue("Ελένη" in names(2026, 5, 21))
        assertTrue("Δημήτριος" in names(2026, 10, 26))
        assertTrue("Νικόλαος" in names(2026, 12, 6))
        assertTrue("Μαρία" in names(2026, 8, 15))
        assertTrue("Παναγιώτης" in names(2026, 8, 15))
    }

    @Test
    fun movableFeasts() {
        // Easter 2026 is the 12th of April.
        assertTrue("Θεόδωρος" in names(2026, 2, 28))
        assertTrue("Λάζαρος" in names(2026, 4, 4))
        assertTrue("Αναστάσιος" in names(2026, 4, 12))
        assertTrue("Θωμάς" in names(2026, 4, 19))
        assertTrue("Φωτεινή" in names(2026, 5, 10))
        // Saint George after Easter stays on the 23rd; in Lent it moves to Easter Monday (2024: 6 May).
        assertTrue("Γεώργιος" in names(2026, 4, 23))
        assertTrue("Γεώργιος" in names(2024, 5, 6))
        assertFalse("Γεώργιος" in names(2024, 4, 23))
    }

    @Test
    fun findsNamesInAnyCase() {
        assertEquals("Ελένη", NameDayCalendar.find("Ελένη")?.main)
        assertEquals("Ελένη", NameDayCalendar.find("της Ελένης")?.main)
        assertEquals("Γεώργιος", NameDayCalendar.find("Γιώργου")?.main)
        assertEquals("Γιώργος", NameDayCalendar.find("τον Γιώργο")?.name)
        assertEquals("Κωνσταντίνος", NameDayCalendar.find("Κώστα")?.main)
        assertEquals("Σπυρίδων", NameDayCalendar.find("Σπύρο")?.main)
        assertEquals("Σπυρίδων", NameDayCalendar.find("Σπυρίδωνα")?.main)
        assertEquals("Ελένη", NameDayCalendar.find("Eleni")?.main)
        assertEquals("Γεώργιος", NameDayCalendar.find("George")?.main)
        assertEquals("Κωνσταντίνος", NameDayCalendar.find("Konstantinos")?.main)
        assertNull(NameDayCalendar.find("Ζγκρχ"))
    }

    @Test
    fun nextDays() {
        assertEquals(listOf(LocalDate.of(2027, 5, 21)), next("Ελένη"))
        assertEquals(listOf(LocalDate.of(2026, 12, 9), LocalDate.of(2027, 7, 25)), next("Άννα"))
        // In 2027 Easter is the 2nd of May, so Saint George moves to the 3rd.
        assertEquals(listOf(LocalDate.of(2027, 5, 3)), next("Γιώργος"))
        assertEquals(listOf(LocalDate.of(2026, 10, 26)), next("Δημήτρης"))
    }

    @Test
    fun latinSpelling() {
        assertEquals("konstantinos", NameDayCalendar.latin("Κωνσταντίνος"))
        assertEquals("eleni", NameDayCalendar.latin("Ελένη"))
        assertEquals("evangelos", NameDayCalendar.latin("Ευάγγελος"))
        assertEquals("dimitrios", NameDayCalendar.latin("Δημήτριος"))
    }
}
