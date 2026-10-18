package com.bestassistant.integrations.homeassistant

import com.bestassistant.integrations.homeassistant.HomeAssistant.Area
import com.bestassistant.integrations.homeassistant.HomeAssistant.Entity
import com.bestassistant.integrations.homeassistant.QueryPlanner.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryPlannerTest {

    private val areas = listOf(Area("bedroom", "Bedroom"), Area("kitchen", "Kitchen"), Area("living_room", "Living Room"))

    private fun sensor(id: String, name: String, area: String?, state: String, cls: String, unit: String?, device: String? = null) =
        Entity("sensor.$id", name, area, state, device = device, attributes = mapOf("device_class" to cls, "unit_of_measurement" to unit))

    private fun contact(id: String, name: String, area: String?, state: String, cls: String) =
        Entity("binary_sensor.$id", name, area, state, attributes = mapOf("device_class" to cls))

    private val entities = listOf(
        sensor("bedroom_temp", "Bedroom temperature", "bedroom", "21.46", "temperature", "°C"),
        sensor("bedroom_humidity", "Bedroom humidity", "bedroom", "48", "humidity", "%"),
        sensor("kitchen_temp", "Kitchen temperature", "kitchen", "23.1", "temperature", "°C"),
        sensor("roborock_battery", "Roborock Battery", "living_room", "87", "battery", "%", device = "robo"),
        Entity("vacuum.roborock", "Roborock", "living_room", "docked", device = "robo"),
        Entity("climate.living_ac", "Living room AC", "living_room", "cool", attributes = mapOf("current_temperature" to 25.0)),
        Entity("lock.front_door", "Front door", null, "locked"),
        contact("front_door_contact", "Front door contact", null, "off", "door"),
        contact("kitchen_window", "Kitchen window", "kitchen", "on", "window"),
        contact("bedroom_window", "Bedroom window", "bedroom", "off", "window"),
        Entity("light.kitchen_ceiling", "Kitchen ceiling", "kitchen", "on"),
        Entity("light.kitchen_strip", "Kitchen strip", "kitchen", "off"),
        Entity("person.anna", "Anna", null, "home"),
        Entity("person.nick", "Nick", null, "not_home"),
        Entity("alarm_control_panel.house", "House alarm", null, "armed_night"),
    )

    private fun ask(target: String, asked: Asked) = QueryPlanner.answer(DeviceQuery(target, asked), entities, areas)

    private fun measure(target: String, quantity: Quantity) = ask(target, Asked.Measure(quantity))

    private fun check(target: String, condition: Condition) = ask(target, Asked.State(condition)) as Answer.Checked

    @Test
    fun readings() {
        val bedroom = measure("the bedroom", Quantity.TEMPERATURE) as Answer.Measured
        assertEquals(listOf(QueryPlanner.Reading("Bedroom temperature", 21.46, "21.46", "°C")), bedroom.readings)
        assertEquals("Bedroom", bedroom.area?.name)
        assertEquals(listOf("Bedroom humidity"), (measure("bedroom", Quantity.HUMIDITY) as Answer.Measured).readings.map { it.device })
        // The vacuum's battery is the battery sensor on the vacuum.
        assertEquals(listOf("Roborock Battery"), (measure("the vacuum", Quantity.BATTERY) as Answer.Measured).readings.map { it.device })
        // No thermometer in the living room but the AC's own.
        assertEquals(listOf(25.0), (measure("the living room", Quantity.TEMPERATURE) as Answer.Measured).readings.map { it.value })
        // The whole house: every thermometer.
        assertEquals(2, (measure("inside", Quantity.TEMPERATURE) as Answer.Measured).readings.size)
        assertEquals(Answer.NotMine, measure("the garage", Quantity.TEMPERATURE))
    }

    @Test
    fun yesOrNo() {
        // The door's contact, before its lock.
        val open = check("the front door", Condition.OPEN)
        assertEquals(listOf("Front door contact"), open.no.map { it.name })
        assertEquals(listOf("Front door"), check("the front door", Condition.LOCKED).yes.map { it.name })
        val windows = check("windows", Condition.OPEN)
        assertEquals(listOf("Kitchen window"), windows.yes.map { it.name })
        assertEquals(listOf("Bedroom window"), windows.no.map { it.name })
        val kitchen = check("lights in the kitchen", Condition.ON)
        assertEquals(listOf("Kitchen ceiling"), kitchen.yes.map { it.name })
        assertEquals(listOf("Kitchen strip"), kitchen.no.map { it.name })
        assertEquals(listOf("Anna"), check("anna", Condition.HOME).yes.map { it.name })
        assertEquals(listOf("Nick"), check("nick", Condition.HOME).no.map { it.name })
        assertEquals(2, check("anyone", Condition.HOME).let { it.yes.size + it.no.size })
        assertEquals(listOf("House alarm"), check("the alarm", Condition.ARMED).yes.map { it.name })
        assertEquals(listOf("Roborock"), check("the vacuum", Condition.DOCKED).yes.map { it.name })
    }

    @Test
    fun status() {
        val robot = ask("the vacuum", Asked.Status) as Answer.Status
        assertEquals("Roborock", robot.device.name)
        assertEquals("docked", robot.device.state)
        assertEquals(87.0, robot.battery?.value)
    }

    @Test
    fun fromSentenceToAnswer() {
        fun query(text: String) = HomeRequests.find(text)!!.query!!
        assertEquals(DeviceQuery("bedroom", Asked.Measure(Quantity.TEMPERATURE)), query("what's the temperature in the bedroom?"))
        assertEquals(DeviceQuery("bedroom", Asked.Measure(Quantity.TEMPERATURE)), query("what is the bedroom temperature"))
        assertEquals(DeviceQuery("bathroom", Asked.Measure(Quantity.HUMIDITY)), query("how humid is the bathroom"))
        assertEquals(DeviceQuery("kitchen", Asked.Measure(Quantity.TEMPERATURE)), query("how warm is it in the kitchen"))
        assertEquals(DeviceQuery("vacuum", Asked.Measure(Quantity.BATTERY)), query("what's the vacuum's battery"))
        assertEquals(DeviceQuery("vacuum", Asked.Measure(Quantity.BATTERY)), query("how much battery does the vacuum have left"))
        assertEquals(DeviceQuery("washing machine", Asked.Measure(Quantity.POWER)), query("how much power is the washing machine using"))
        assertEquals(DeviceQuery("front door", Asked.State(Condition.LOCKED)), query("is the front door locked"))
        assertEquals(DeviceQuery("garage door", Asked.State(Condition.CLOSED)), query("is the garage door closed?"))
        assertEquals(DeviceQuery("lights in the kitchen", Asked.State(Condition.ON)), query("are the lights on in the kitchen"))
        assertEquals(DeviceQuery("windows", Asked.State(Condition.OPEN)), query("are there any windows open"))
        assertEquals(DeviceQuery("anna", Asked.State(Condition.HOME)), query("is anna home"))
        assertEquals(DeviceQuery("anyone", Asked.State(Condition.HOME)), query("is anyone home"))
        assertEquals(DeviceQuery("washing machine", Asked.Status), query("what's the washing machine doing"))
        assertEquals(DeviceQuery("vacuum", Asked.Status), query("what is the status of the vacuum"))
        assertTrue(QueryPlanner.answer(query("is the front door locked"), entities, areas) is Answer.Checked)
    }
}
