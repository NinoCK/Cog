package com.bestassistant.automation

import com.bestassistant.automation.HomeAssistant.Area
import com.bestassistant.automation.HomeAssistant.Entity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAssistantTest {

    @Test
    fun catalog() {
        val body = """
            {"areas": [["kitchen", "Κουζίνα"], ["living_room", "Living Room"]],
             "entities": [["light.ceiling", "Ceiling", "kitchen", "on", false], ["light.all", "All lights", "", "on", true]]}
        """
        val found = HomeAssistant.parseCatalog(body)!!
        assertEquals(listOf(Area("kitchen", "Κουζίνα"), Area("living_room", "Living Room")), found.areas)
        assertEquals(
            listOf(Entity("light.ceiling", "Ceiling", "kitchen", "on"), Entity("light.all", "All lights", null, "on", isGroup = true)),
            found.entities,
        )
        assertNull(HomeAssistant.parseCatalog("TemplateError: area_name is undefined"))
    }

    @Test
    fun assistSaysWhichDevices() {
        val body = """
            {"response": {"response_type": "action_done", "speech": {"plain": {"speech": "Turned on the light"}},
             "data": {"targets": [], "success": [{"name": "Desk light", "type": "entity", "id": "light.desk_light"}], "failed": []}}}
        """
        assertEquals(HomeAssistant.Reply.Done("Turned on the light", listOf("Desk light")), HomeAssistant.parse(body))
    }

    @Test
    fun whatCogSees() {
        val found = HomeAssistant.Devices.Found(
            listOf(
                Entity("climate.desk_ac", "Desk AC", null, "off", attributes = mapOf("hvac_modes" to listOf("off", "cool", "heat"))),
                Entity("climate.boiler", "Boiler", null, "heat", attributes = mapOf("hvac_modes" to listOf("off", "heat"))),
                Entity("light.desk", "Desk light", null, "on"),
                Entity("light.all", "All lights", null, "on", isGroup = true),
                Entity("light.broken", "Broken", null, "unavailable"),
            ),
            null,
        )
        assertEquals(HomeAssistant.Seen(mapOf("climate" to 2, "light" to 1), listOf("Desk AC"), null), HomeAssistant.seen(found))
    }

    @Test
    fun states() {
        val body = """
            [{"entity_id": "light.ceiling", "state": "off", "attributes": {"friendly_name": "Ceiling"}},
             {"entity_id": "light.group", "state": "on", "attributes": {"friendly_name": "Group", "entity_id": ["light.ceiling"]}},
             {"entity_id": "sensor.temperature", "state": "21", "attributes": {}},
             {"entity_id": "cover.garage_door", "state": "closed", "attributes": {}}]
        """
        assertEquals(
            listOf(
                Entity("light.ceiling", "Ceiling", null, "off"),
                Entity("light.group", "Group", null, "on", isGroup = true),
                Entity("cover.garage_door", "garage door", null, "closed"),
            ),
            HomeAssistant.parseStates(body),
        )
    }
}
