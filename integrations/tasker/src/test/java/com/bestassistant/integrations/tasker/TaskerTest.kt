package com.bestassistant.integrations.tasker

import com.bestassistant.integration.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskerTest {

    private fun task(text: String) = TaskRequests.parse(text, Slot.LATE)?.name

    @Test
    fun findsTasksByTheirName() {
        assertEquals("lights out", task("run the task called lights out"))
        assertEquals("lights out", task("run task lights out"))
        assertEquals("lights out", task("start the lights out task"))
        assertEquals("lights out", task("run lights out in tasker"))
        assertEquals("good night", task("tasker good night"))
        assertEquals("good night", task("trigger the tasker task good night"))
    }

    @Test
    fun leavesTheRestAlone() {
        assertNull(task("tasker"))
        assertNull(task("run spotify"))
        assertNull(task("open tasker"))
        // Only asked late, after everything PocketSearch does itself.
        assertNull(TaskRequests.parse("run the task called lights out", Slot.EARLY))
    }

    @Test
    fun matchesTheUsersOwnTaskNames() {
        val names = listOf("Lights Out", "Good Night Routine", "Work Mode", "Café Wi-Fi")
        assertEquals("Lights Out", TaskNames.best("lights out", names))
        assertEquals("Good Night Routine", TaskNames.best("good night", names))
        assertEquals("Work Mode", TaskNames.best("work", names))
        assertEquals("Café Wi-Fi", TaskNames.best("cafe wifi", names))
        assertEquals("Good Night Routine", TaskNames.best("gnr", names))
        assertNull(TaskNames.best("vacuum", names))
        assertNull(TaskNames.best("lights out", emptyList()))
    }
}
