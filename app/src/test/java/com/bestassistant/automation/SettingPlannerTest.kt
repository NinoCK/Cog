package com.bestassistant.automation

import com.bestassistant.automation.GroupPlanner.Call
import com.bestassistant.automation.HomeAssistant.Area
import com.bestassistant.automation.HomeAssistant.Entity
import com.bestassistant.automation.SettingPlanner.Aspect
import com.bestassistant.automation.SettingPlanner.Plan
import com.bestassistant.automation.SettingPlanner.Shown
import com.bestassistant.commands.CommandParser
import com.bestassistant.commands.DeviceSetting
import com.bestassistant.commands.NumberUnit
import com.bestassistant.commands.SettingValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingPlannerTest {

    private val areas = listOf(Area("living_room", "Living Room"), Area("bedroom", "Bedroom"))

    private val livingAc = Entity(
        "climate.living_room_ac", "Living room AC", "living_room", "cool", device = "dev_ac1", deviceName = "Living room AC",
        attributes = mapOf(
            "hvac_modes" to listOf("off", "cool", "heat", "dry", "fan_only", "auto"),
            "fan_modes" to listOf("auto", "low", "medium", "high"), "fan_mode" to "low",
            "swing_modes" to listOf("off", "vertical", "horizontal", "both"), "swing_mode" to "off",
            "min_temp" to 16.0, "max_temp" to 30.0, "target_temp_step" to 1.0, "temperature" to 24.0,
        ),
    )
    private val bedroomAc = livingAc.copy(id = "climate.bedroom_ac", name = "Bedroom AC", area = "bedroom", device = "dev_ac2", deviceName = "Bedroom AC")
    private val acDisplay = Entity("switch.living_room_ac_display", "Living room AC Display", "living_room", "on", device = "dev_ac1", deviceName = "Living room AC")
    private val purifier = Entity(
        "fan.air_purifier", "Air Purifier", "bedroom", "on", device = "dev_p", deviceName = "Air Purifier",
        attributes = mapOf(
            "preset_modes" to listOf("Auto", "Silent", "Favorite"), "preset_mode" to "Auto",
            "percentage" to 40.0, "percentage_step" to 25.0, "oscillating" to false,
        ),
    )
    private val childLock = Entity("switch.air_purifier_child_lock", "Air Purifier Child Lock", "bedroom", "off", device = "dev_p", deviceName = "Air Purifier")
    private val favoriteLevel = Entity(
        "number.air_purifier_favorite_level", "Air Purifier Favorite Level", "bedroom", "5", device = "dev_p", deviceName = "Air Purifier",
        attributes = mapOf("min" to 0.0, "max" to 14.0, "step" to 1.0),
    )
    private val ceiling = Entity("light.ceiling", "Ceiling", "living_room", "on", attributes = mapOf("supported_color_modes" to listOf("brightness")))

    private val entities = listOf(livingAc, bedroomAc, acDisplay, purifier, childLock, favoriteLevel, ceiling)

    private fun plan(target: String, value: SettingValue, list: List<Entity> = entities) = SettingPlanner.plan(DeviceSetting(target, value), list, areas)

    private fun act(call: Call, device: String, aspect: Aspect?, shown: Shown, label: String? = null) =
        Plan.Act(listOf(call), listOf(device), aspect, label, shown)

    private val word = SettingValue::Word

    @Test
    fun airConditioner() {
        assertEquals(
            act(Call("climate", "set_temperature", listOf("climate.bedroom_ac"), mapOf("temperature" to 22.0)), "Bedroom AC", Aspect.TEMPERATURE,
                Shown.Number(22.0, NumberUnit.DEGREES)),
            plan("the bedroom ac", SettingValue.Number(22.0)),
        )
        assertEquals(
            act(Call("climate", "set_hvac_mode", listOf("climate.living_room_ac"), mapOf("hvac_mode" to "cool")), "Living room AC", Aspect.MODE,
                Shown.Option("cool")),
            plan("the living room ac", word("cooling")),
        )
        assertEquals(
            act(Call("climate", "set_hvac_mode", listOf("climate.living_room_ac"), mapOf("hvac_mode" to "fan_only")), "Living room AC", Aspect.MODE,
                Shown.Option("fan_only")),
            plan("the living room ac", word("fan mode")),
        )
        assertEquals(
            act(Call("climate", "set_fan_mode", listOf("climate.living_room_ac"), mapOf("fan_mode" to "high")), "Living room AC", Aspect.FAN,
                Shown.Option("high")),
            plan("the ac fan in the living room", word("high")),
        )
        assertEquals(
            act(Call("climate", "set_swing_mode", listOf("climate.bedroom_ac"), mapOf("swing_mode" to "vertical")), "Bedroom AC", Aspect.SWING,
                Shown.Option("vertical")),
            plan("bedroom ac swing", word("on")),
        )
        assertEquals(
            act(Call("climate", "set_temperature", listOf("climate.bedroom_ac"), mapOf("temperature" to 25.0)), "Bedroom AC", Aspect.TEMPERATURE,
                Shown.Number(25.0, NumberUnit.DEGREES)),
            plan("the temperature in the bedroom", SettingValue.Step(true)),
        )
        // The fan speed after "low" in the device's own order.
        assertEquals(
            act(Call("climate", "set_fan_mode", listOf("climate.living_room_ac"), mapOf("fan_mode" to "medium")), "Living room AC", Aspect.FAN,
                Shown.Option("medium")),
            plan("the living room ac fan", SettingValue.Step(true)),
        )
        assertEquals(
            act(Call("switch", "turn_off", listOf("switch.living_room_ac_display")), "Living room AC Display", null, Shown.Power(false)),
            plan("the living room ac display", word("off")),
        )
    }

    @Test
    fun twoAirConditioners() {
        val which = Plan.Ambiguous(listOf("Living room AC", "Bedroom AC"), listOf("Living Room", "Bedroom"))
        assertEquals(which, plan("the ac", SettingValue.Number(22.0)))
        assertEquals(which, plan("the temperature", SettingValue.Number(22.0)))
        // The answer to "which one?" is added to what was said.
        assertEquals(listOf("Living room AC"), (plan("the ac in the living room", SettingValue.Number(22.0)) as Plan.Act).devices)
        assertEquals(listOf("Bedroom AC"), (plan("the ac bedroom", SettingValue.Number(22.0)) as Plan.Act).devices)
        assertEquals(2, (plan("both acs", SettingValue.Number(22.0)) as Plan.Act).devices.size)
        // Only one is running: that's the one.
        val running = listOf(livingAc.copy(state = "off"), bedroomAc, purifier)
        assertEquals(listOf("Bedroom AC"), (plan("the ac", SettingValue.Number(22.0), running) as Plan.Act).devices)
        // A heating-only thermostat isn't an AC.
        val boiler = Entity("climate.boiler", "Boiler", null, "heat", attributes = mapOf("hvac_modes" to listOf("off", "heat"), "temperature" to 20.0))
        assertEquals(listOf("Living room AC"), (plan("the ac", SettingValue.Number(22.0), listOf(livingAc, boiler)) as Plan.Act).devices)
        assertEquals(listOf("Boiler"), (plan("the heating", SettingValue.Step(true), listOf(livingAc.copy(attributes = mapOf()), boiler)) as Plan.Act).devices)
        val both = plan("all the acs", word("cool")) as Plan.Act
        assertEquals(listOf("climate.living_room_ac", "climate.bedroom_ac"), both.calls.flatMap { it.entityIds })
        // With one AC, "the temperature" is that one.
        assertTrue(plan("the temperature", SettingValue.Number(22.0), listOf(livingAc, purifier)) is Plan.Act)
    }

    @Test
    fun airPurifier() {
        assertEquals(
            act(Call("fan", "set_preset_mode", listOf("fan.air_purifier"), mapOf("preset_mode" to "Silent")), "Air Purifier", Aspect.PRESET,
                Shown.Option("Silent")),
            plan("the air purifier", word("sleep mode")),
        )
        assertEquals(
            act(Call("fan", "set_preset_mode", listOf("fan.air_purifier"), mapOf("preset_mode" to "Favorite")), "Air Purifier", Aspect.PRESET,
                Shown.Option("Favorite")),
            plan("the purifier", word("favorite")),
        )
        assertEquals(
            act(Call("fan", "set_percentage", listOf("fan.air_purifier"), mapOf("percentage" to 60)), "Air Purifier", Aspect.SPEED,
                Shown.Number(60.0, NumberUnit.PERCENT)),
            plan("the purifier speed", SettingValue.Number(60.0, NumberUnit.PERCENT)),
        )
        assertEquals(
            act(Call("fan", "set_percentage", listOf("fan.air_purifier"), mapOf("percentage" to 100)), "Air Purifier", Aspect.SPEED,
                Shown.Number(100.0, NumberUnit.PERCENT)),
            plan("the air purifier", word("high")),
        )
        assertEquals(
            act(Call("fan", "set_percentage", listOf("fan.air_purifier"), mapOf("percentage" to 15)), "Air Purifier", Aspect.SPEED,
                Shown.Number(15.0, NumberUnit.PERCENT)),
            plan("the purifier", SettingValue.Step(false)),
        )
        // The integration's own extras on the same device.
        assertEquals(
            act(Call("switch", "turn_on", listOf("switch.air_purifier_child_lock")), "Air Purifier Child Lock", null, Shown.Power(true)),
            plan("the purifier child lock", word("on")),
        )
        assertEquals(
            act(Call("number", "set_value", listOf("number.air_purifier_favorite_level"), mapOf("value" to 10.0)), "Air Purifier", null,
                Shown.Number(10.0, null), "Favorite Level"),
            plan("the purifier favorite level", SettingValue.Number(10.0)),
        )
        assertEquals(
            act(Call("fan", "oscillate", listOf("fan.air_purifier"), mapOf("oscillating" to true)), "Air Purifier", Aspect.OSCILLATION,
                Shown.Switch(true)),
            plan("the purifier oscillation", word("on")),
        )
    }

    @Test
    fun deskAcIsNotTheDeskLamp() {
        val deskLight = Entity("light.desk_light", "Desk light", "office", "off", attributes = mapOf("supported_color_modes" to listOf("brightness")))
        val deskAc = livingAc.copy(id = "climate.desk_ac", name = "Desk AC", area = "office", device = "dev_desk", deviceName = "Desk AC", state = "off")
        val all = listOf(deskLight, deskAc, livingAc, bedroomAc, purifier)
        val withOffice = areas + Area("office", "Office")
        fun plan(target: String, value: SettingValue, list: List<Entity> = all) = SettingPlanner.plan(DeviceSetting(target, value), list, withOffice)

        assertEquals(act(Call("climate", "turn_on", listOf("climate.desk_ac")), "Desk AC", null, Shown.Power(true)), plan("desk ac", word("on")))
        assertEquals(listOf("climate.desk_ac"), (plan("the desk ac", SettingValue.Number(22.0, NumberUnit.DEGREES)) as Plan.Act).calls.single().entityIds)
        assertEquals(listOf("climate.desk_ac"), (plan("the office ac", SettingValue.Number(22.0)) as Plan.Act).calls.single().entityIds)
        assertEquals(act(Call("light", "turn_on", listOf("light.desk_light")), "Desk light", null, Shown.Power(true)), plan("desk light", word("on")))
        assertEquals(act(Call("light", "turn_on", listOf("light.desk_light")), "Desk light", null, Shown.Power(true)), plan("desk lamp", word("on")))
        // An AC not called "desk", but in the office ("desk" is another word for it).
        val midea = deskAc.copy(name = "Midea 1", deviceName = "Midea 1")
        assertEquals(listOf("climate.desk_ac"), (plan("desk ac", word("on"), listOf(deskLight, midea, livingAc)) as Plan.Act).calls.single().entityIds)
        // A name that fits no AC: ask which AC, never the lamp.
        val which = plan("study room ac", word("on"), listOf(deskLight, livingAc, bedroomAc))
        assertEquals(Plan.Ambiguous(listOf("Living room AC", "Bedroom AC"), listOf("Living Room", "Bedroom")), which)
        // Just "desk": the AC or the lamp?
        assertTrue(plan("the desk", word("on")) is Plan.Ambiguous)
        // No AC at all that Cog can see: say so, never hand "desk ac" to Assist (which switched the lamp).
        assertEquals(Plan.NotFound("desk ac"), plan("desk ac", word("on"), listOf(deskLight, purifier)))
        // "Turn on the AC" with one running and one off: the one that's off.
        assertEquals(listOf("climate.bedroom_ac"), (plan("the ac", word("on"), listOf(livingAc, bedroomAc.copy(state = "off"))) as Plan.Act).calls.single().entityIds)
    }

    @Test
    fun answeringWhichOne() {
        val request = DeviceSetting("the ac", SettingValue.Number(22.0))
        val offered = listOf("Living room AC", "Bedroom AC")
        fun answer(text: String) = (SettingPlanner.choose(request, offered, text, entities, areas) as Plan.Act).calls.flatMap { it.entityIds }
        assertEquals(listOf("climate.living_room_ac"), answer("in the living room"))
        assertEquals(listOf("climate.bedroom_ac"), answer("the bedroom"))
        assertEquals(listOf("climate.bedroom_ac"), answer("the second one"))
        assertEquals(listOf("climate.living_room_ac", "climate.bedroom_ac"), answer("both"))
        assertEquals(listOf("climate.living_room_ac", "climate.bedroom_ac"), answer("all of them"))
        assertEquals(Plan.NotMine, SettingPlanner.choose(request, offered, "the garage", entities, areas))
    }

    @Test
    fun mideaFanSpeeds() {
        val midea = livingAc.copy(attributes = livingAc.attributes + mapOf(
            "fan_modes" to listOf("Silent", "Low", "Medium", "High", "Full", "Auto"), "fan_mode" to "High",
            "preset_modes" to listOf("none", "comfort", "eco", "boost", "sleep", "away"),
        ))
        fun value(target: String, v: SettingValue) = (plan(target, v, listOf(midea)) as Plan.Act).value
        assertEquals(Shown.Option("Full"), value("the ac fan", word("full")))
        assertEquals(Shown.Option("Silent"), value("the ac fan", word("quiet")))
        assertEquals(Shown.Option("Full"), value("the ac fan", SettingValue.Step(true)))
        assertEquals(Shown.Option("boost"), value("the ac", word("turbo")))
        assertEquals(Shown.Option("sleep"), value("the ac", word("sleep mode")))
    }

    @Test
    fun whatTheDeviceCantDo() {
        val missing = plan("the purifier", word("turbo")) as Plan.NoSuchSetting
        assertEquals("Air Purifier", missing.device)
        assertTrue(missing.offers.any { it.options == listOf("Auto", "Silent", "Favorite") })
        assertEquals(
            Plan.OutOfRange("Bedroom AC", Aspect.TEMPERATURE, null, 16.0, 30.0, NumberUnit.DEGREES),
            plan("the bedroom ac", SettingValue.Number(35.0)),
        )
        // A device Cog can't find is Assist's.
        assertEquals(Plan.NotMine, plan("the garage", SettingValue.Number(3.0)))
    }

    @Test
    fun lights() {
        assertEquals(
            act(Call("light", "turn_on", listOf("light.ceiling"), mapOf("brightness_pct" to 40)), "Ceiling", Aspect.BRIGHTNESS,
                Shown.Number(40.0, NumberUnit.PERCENT)),
            plan("the ceiling light", SettingValue.Number(40.0, NumberUnit.PERCENT)),
        )
        assertEquals(
            act(Call("light", "turn_on", listOf("light.ceiling"), mapOf("brightness_step_pct" to -10)), "Ceiling", Aspect.BRIGHTNESS, Shown.Step(false)),
            plan("the ceiling", SettingValue.Step(false)),
        )
    }

    @Test
    fun fromSentenceToCall() {
        fun said(text: String) = (CommandParser.parse(text) as com.bestassistant.commands.Command.SmartHome).setting!!
        assertEquals(Shown.Option("dry"), (SettingPlanner.plan(said("put the bedroom ac on dry"), entities, areas) as Plan.Act).value)
        assertEquals(Shown.Option("Silent"), (SettingPlanner.plan(said("put the air purifier in silent mode"), entities, areas) as Plan.Act).value)
        assertEquals(Shown.Number(21.0, NumberUnit.DEGREES), (SettingPlanner.plan(said("set the bedroom ac to 21"), entities, areas) as Plan.Act).value)
    }
}
