package com.bestassistant.automation

import com.bestassistant.automation.GroupPlanner.Call
import com.bestassistant.automation.GroupPlanner.Plan
import com.bestassistant.automation.HomeAssistant.Area
import com.bestassistant.automation.HomeAssistant.Entity
import com.bestassistant.commands.DeviceKind
import com.bestassistant.commands.HomeAction
import com.bestassistant.commands.HomeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupPlannerTest {

    private val kitchen = Area("kitchen", "Kitchen")
    private val living = Area("living_room", "Living Room")
    private val bedroom = Area("bedroom", "Bedroom")
    private val master = Area("master_bedroom", "Master Bedroom")
    private val areas = listOf(kitchen, living, bedroom, master)

    private val entities = listOf(
        Entity("light.kitchen_ceiling", "Kitchen ceiling", "kitchen", "on"),
        Entity("light.kitchen_strip", "Kitchen strip", "kitchen", "off"),
        Entity("light.sofa", "Sofa lamp", "living_room", "on"),
        Entity("light.hall", "Hall", null, "on"),
        Entity("light.broken", "Broken", null, "unavailable"),
        Entity("light.downstairs", "Downstairs", null, "on", isGroup = true),
        Entity("switch.fridge", "Fridge", "kitchen", "on"),
        Entity("media_player.tv", "TV", "living_room", "playing"),
        Entity("fan.bedroom", "Bedroom fan", "bedroom", "off"),
        Entity("cover.bedroom", "Bedroom blinds", "bedroom", "open"),
        Entity("lock.front", "Front door", null, "unlocked"),
    )

    private fun plan(action: HomeAction, kind: DeviceKind?, area: String? = null, withAreas: List<Area>? = areas) =
        GroupPlanner.plan(HomeGroup(action, kind, area), entities, withAreas)

    @Test
    fun allLights() {
        assertEquals(
            Plan.Act(listOf(Call("light", "turn_off", listOf("light.kitchen_ceiling", "light.sofa", "light.hall"))), 3, null),
            plan(HomeAction.OFF, DeviceKind.LIGHT),
        )
        assertEquals(Plan.Act(listOf(Call("light", "turn_on", listOf("light.kitchen_strip"))), 1, null), plan(HomeAction.ON, DeviceKind.LIGHT))
        // Without areas (a token that isn't an administrator's), the whole house still works.
        assertEquals(3, (plan(HomeAction.OFF, DeviceKind.LIGHT, withAreas = null) as Plan.Act).count)
    }

    @Test
    fun inOneArea() {
        assertEquals(
            Plan.Act(listOf(Call("light", "turn_off", listOf("light.kitchen_ceiling"))), 1, kitchen),
            plan(HomeAction.OFF, DeviceKind.LIGHT, "Kitchen"),
        )
        assertEquals(Plan.AlreadyDone(living), plan(HomeAction.ON, DeviceKind.LIGHT, "living room"))
        assertEquals(Plan.NoDevices(kitchen), plan(HomeAction.CLOSE, DeviceKind.COVER, "kitchen"))
        assertEquals(Plan.UnknownArea, plan(HomeAction.OFF, DeviceKind.LIGHT, "christmas"))
        assertEquals(Plan.UnknownArea, plan(HomeAction.OFF, DeviceKind.LIGHT, "kitchen", withAreas = null))
    }

    @Test
    fun everythingOff() {
        // Lights, fans and media players: never the fridge's switch, never the locks.
        assertEquals(
            Plan.Act(
                listOf(
                    Call("light", "turn_off", listOf("light.kitchen_ceiling", "light.sofa", "light.hall")),
                    Call("media_player", "turn_off", listOf("media_player.tv")),
                ),
                4,
                null,
            ),
            plan(HomeAction.OFF, null),
        )
    }

    @Test
    fun coversAndLocks() {
        assertEquals(Plan.Act(listOf(Call("cover", "close_cover", listOf("cover.bedroom"))), 1, bedroom), plan(HomeAction.CLOSE, DeviceKind.COVER, "bedroom"))
        assertEquals(Plan.Act(listOf(Call("lock", "lock", listOf("lock.front"))), 1, null), plan(HomeAction.LOCK, DeviceKind.LOCK))
    }

    @Test
    fun areas() {
        assertEquals(kitchen, GroupPlanner.findArea("kitchen", areas))
        assertEquals(kitchen, GroupPlanner.findArea("the kitchen", areas))
        assertEquals(living, GroupPlanner.findArea("living room", areas))
        assertEquals(living, GroupPlanner.findArea("the living", areas))
        assertEquals(bedroom, GroupPlanner.findArea("bedroom", areas))
        assertEquals(master, GroupPlanner.findArea("master bedroom", areas))
        // Its id counts as a name too.
        assertEquals(Area("kitchen", "Cucina"), GroupPlanner.findArea("kitchen", listOf(Area("kitchen", "Cucina"), living)))
        assertEquals(master, GroupPlanner.findArea("bedroom", listOf(master, kitchen)))
        assertNull(GroupPlanner.findArea("bed", areas))
        assertNull(GroupPlanner.findArea("garage", areas))
    }
}
