package com.bestassistant.automation

import com.bestassistant.automation.GroupPlanner.Call
import com.bestassistant.automation.HomeAssistant.Area
import com.bestassistant.automation.HomeAssistant.Entity
import com.bestassistant.automation.SettingPlanner.Aspect
import com.bestassistant.automation.SettingPlanner.Plan
import com.bestassistant.automation.SettingPlanner.Shown
import com.bestassistant.commands.Command
import com.bestassistant.commands.CommandParser
import com.bestassistant.commands.DeviceSetting
import com.bestassistant.commands.DeviceVerb
import com.bestassistant.commands.NumberUnit
import com.bestassistant.commands.SettingValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Light colours, TV volume and inputs, and devices that are told what to do: vacuums, locks, alarms, valves, buttons, mowers. */
class DeviceActionsTest {

    private val areas = listOf(Area("kitchen", "Kitchen"), Area("living_room", "Living Room"), Area("entrance", "Entrance"))

    private val bulb = Entity(
        "light.kitchen", "Kitchen light", "kitchen", "on",
        attributes = mapOf(
            "supported_color_modes" to listOf("color_temp", "hs"), "min_color_temp_kelvin" to 2200.0, "max_color_temp_kelvin" to 6500.0,
            "color_temp_kelvin" to 4000.0, "effect_list" to listOf("None", "Rainbow", "Candle"),
        ),
    )
    private val whiteOnly = Entity(
        "light.hall", "Hall lamp", null, "on",
        attributes = mapOf("supported_color_modes" to listOf("color_temp"), "min_color_temp_kelvin" to 2700.0, "max_color_temp_kelvin" to 5000.0),
    )
    private val tv = Entity(
        "media_player.tv", "TV", "living_room", "on",
        attributes = mapOf(
            "volume_level" to 0.3, "is_volume_muted" to false, "source_list" to listOf("Live TV", "HDMI 1", "HDMI 2", "Netflix"), "source" to "HDMI 1",
        ),
    )
    private val vacuum = Entity(
        "vacuum.roborock", "Roborock", "living_room", "docked",
        attributes = mapOf("fan_speed_list" to listOf("Silent", "Standard", "Turbo", "Max"), "fan_speed" to "Standard"),
    )
    private val frontDoor = Entity("lock.front_door", "Front door", "entrance", "locked")
    private val codedLock = Entity("lock.safe", "Safe", null, "locked", attributes = mapOf("code_format" to "^\\d{4}$"))
    private val alarm = Entity(
        "alarm_control_panel.house", "House alarm", null, "disarmed",
        attributes = mapOf("supported_features" to 7.0, "code_format" to null, "code_arm_required" to false),
    )
    private val valve = Entity("valve.garden", "Garden sprinkler", null, "closed", attributes = mapOf("current_position" to 0.0))
    private val doorbell = Entity("button.doorbell_chime", "Doorbell chime", "entrance", "unknown")
    private val mower = Entity("lawn_mower.automower", "Automower", null, "docked")
    private val garage = Entity("cover.garage", "Garage door", null, "closed")

    private val entities = listOf(bulb, whiteOnly, tv, vacuum, frontDoor, codedLock, alarm, valve, doorbell, mower, garage)

    private fun plan(target: String, value: SettingValue, list: List<Entity> = entities) = SettingPlanner.plan(DeviceSetting(target, value), list, areas)

    private fun act(call: Call, device: String, aspect: Aspect?, shown: Shown) = Plan.Act(listOf(call), listOf(device), aspect, null, shown)

    private fun did(target: String, verb: DeviceVerb, mode: String? = null) = plan(target, SettingValue.Action(verb, mode))

    private val word = SettingValue::Word

    @Test
    fun lightColours() {
        assertEquals(
            act(Call("light", "turn_on", listOf("light.kitchen"), mapOf("color_name" to "red")), "Kitchen light", Aspect.COLOR, Shown.Option("red")),
            plan("the kitchen light", word("red")),
        )
        assertEquals(
            act(Call("light", "turn_on", listOf("light.kitchen"), mapOf("color_temp_kelvin" to 2700)), "Kitchen light", Aspect.COLOR, Shown.Option("warm white")),
            plan("the kitchen light", word("warm white")),
        )
        // A light that only does whites has no red, and its whites stay within its range.
        assertTrue(plan("the hall lamp", word("red")) is Plan.NoSuchSetting)
        assertEquals(
            act(Call("light", "turn_on", listOf("light.hall"), mapOf("color_temp_kelvin" to 5000)), "Hall lamp", Aspect.COLOR, Shown.Option("daylight")),
            plan("the hall lamp", word("daylight")),
        )
    }

    @Test
    fun lightWhitesAndEffects() {
        assertEquals(
            act(Call("light", "turn_on", listOf("light.kitchen"), mapOf("color_temp_kelvin" to 3000)), "Kitchen light", Aspect.COLOR_TEMPERATURE,
                Shown.Number(3000.0, NumberUnit.KELVIN)),
            plan("the kitchen light", SettingValue.Number(3000.0, NumberUnit.KELVIN)),
        )
        assertEquals(
            Plan.OutOfRange("Hall lamp", Aspect.COLOR_TEMPERATURE, null, 2700.0, 5000.0, NumberUnit.KELVIN),
            plan("the hall lamp", SettingValue.Number(6500.0, NumberUnit.KELVIN)),
        )
        // "Make the kitchen light warmer": a lower colour temperature.
        assertEquals(
            act(Call("light", "turn_on", listOf("light.kitchen"), mapOf("color_temp_kelvin" to 3500)), "Kitchen light", Aspect.COLOR_TEMPERATURE,
                Shown.Number(3500.0, NumberUnit.KELVIN)),
            plan("the kitchen light warmth", SettingValue.Step(false)),
        )
        assertEquals(
            act(Call("light", "turn_on", listOf("light.kitchen"), mapOf("effect" to "Rainbow")), "Kitchen light", Aspect.EFFECT, Shown.Option("Rainbow")),
            plan("the kitchen light", word("rainbow")),
        )
        assertEquals(
            act(Call("light", "turn_on", listOf("light.kitchen"), mapOf("effect" to "Candle")), "Kitchen light", Aspect.EFFECT, Shown.Option("Candle")),
            plan("the kitchen light effect", word("candle")),
        )
    }

    @Test
    fun televisions() {
        assertEquals(
            act(Call("media_player", "volume_set", listOf("media_player.tv"), mapOf("volume_level" to 0.2)), "TV", Aspect.VOLUME,
                Shown.Number(20.0, NumberUnit.PERCENT)),
            plan("the tv volume", SettingValue.Number(20.0)),
        )
        // "Turn up the TV": its volume, from where it is.
        assertEquals(
            act(Call("media_player", "volume_set", listOf("media_player.tv"), mapOf("volume_level" to 0.4)), "TV", Aspect.VOLUME,
                Shown.Number(40.0, NumberUnit.PERCENT)),
            plan("the tv", SettingValue.Step(true)),
        )
        assertEquals(
            act(Call("media_player", "select_source", listOf("media_player.tv"), mapOf("source" to "HDMI 2")), "TV", Aspect.SOURCE, Shown.Option("HDMI 2")),
            plan("the tv", word("hdmi 2")),
        )
        assertEquals(
            act(Call("media_player", "select_source", listOf("media_player.tv"), mapOf("source" to "Netflix")), "TV", Aspect.SOURCE, Shown.Option("Netflix")),
            plan("the tv input", word("netflix")),
        )
        assertEquals(
            act(Call("media_player", "volume_mute", listOf("media_player.tv"), mapOf("is_volume_muted" to true)), "TV", Aspect.MUTE, Shown.Switch(true)),
            plan("the tv mute", word("on")),
        )
        assertEquals(
            act(Call("media_player", "media_pause", listOf("media_player.tv")), "TV", null, Shown.Did(DeviceVerb.PAUSE)),
            did("the tv", DeviceVerb.PAUSE),
        )
    }

    @Test
    fun vacuumsAndMowers() {
        assertEquals(act(Call("vacuum", "start", listOf("vacuum.roborock")), "Roborock", null, Shown.Did(DeviceVerb.START)), did("the vacuum", DeviceVerb.START))
        assertEquals(act(Call("vacuum", "return_to_base", listOf("vacuum.roborock")), "Roborock", null, Shown.Did(DeviceVerb.DOCK)), did("the robot vacuum", DeviceVerb.DOCK))
        assertEquals(act(Call("vacuum", "locate", listOf("vacuum.roborock")), "Roborock", null, Shown.Did(DeviceVerb.LOCATE)), did("the vacuum", DeviceVerb.LOCATE))
        assertEquals(act(Call("vacuum", "start", listOf("vacuum.roborock")), "Roborock", null, Shown.Did(DeviceVerb.START)), plan("the roborock", word("on")))
        assertEquals(
            act(Call("vacuum", "set_fan_speed", listOf("vacuum.roborock"), mapOf("fan_speed" to "Max")), "Roborock", Aspect.SUCTION, Shown.Option("Max")),
            plan("the vacuum suction", word("max")),
        )
        assertEquals(
            act(Call("lawn_mower", "start_mowing", listOf("lawn_mower.automower")), "Automower", null, Shown.Did(DeviceVerb.START)),
            did("mower", DeviceVerb.START),
        )
        assertEquals(act(Call("lawn_mower", "dock", listOf("lawn_mower.automower")), "Automower", null, Shown.Did(DeviceVerb.DOCK)), did("the mower", DeviceVerb.DOCK))
        // A light can't be started.
        assertEquals(Plan.CantDo("Kitchen light"), did("the kitchen light", DeviceVerb.START))
    }

    @Test
    fun locks() {
        assertEquals(
            act(Call("lock", "unlock", listOf("lock.front_door")), "Front door", null, Shown.Did(DeviceVerb.UNLOCK)),
            did("the front door", DeviceVerb.UNLOCK),
        )
        // In the entrance, though it's not called that: the only lock there.
        assertEquals(listOf("lock.front_door"), (did("the entrance door", DeviceVerb.LOCK) as Plan.Act).calls.single().entityIds)
        // A lock that takes a code isn't opened by voice.
        assertEquals(Plan.NeedsCode("Safe"), did("the safe", DeviceVerb.UNLOCK))
        // "Lock" alone is still a purifier's child lock, not a kind of device.
        val purifier = Entity("fan.purifier", "Air Purifier", null, "on", device = "p", deviceName = "Air Purifier")
        val childLock = Entity("switch.purifier_child_lock", "Air Purifier Child Lock", null, "off", device = "p", deviceName = "Air Purifier")
        assertEquals(listOf("switch.purifier_child_lock"), (plan("the child lock", word("on"), listOf(purifier, childLock, frontDoor)) as Plan.Act).calls.single().entityIds)
    }

    @Test
    fun alarms() {
        assertEquals(
            act(Call("alarm_control_panel", "alarm_arm_away", listOf("alarm_control_panel.house")), "House alarm", null, Shown.Did(DeviceVerb.ARM, "away")),
            did("the alarm", DeviceVerb.ARM),
        )
        assertEquals(
            act(Call("alarm_control_panel", "alarm_arm_night", listOf("alarm_control_panel.house")), "House alarm", null, Shown.Did(DeviceVerb.ARM, "night")),
            did("the alarm", DeviceVerb.ARM, "night"),
        )
        assertEquals(
            act(Call("alarm_control_panel", "alarm_arm_home", listOf("alarm_control_panel.house")), "House alarm", null, Shown.Did(DeviceVerb.ARM, "home")),
            plan("the alarm", word("home mode")),
        )
        // Vacation isn't one of its features (7: home, away, night).
        assertEquals(Plan.CantDo("House alarm"), did("the alarm", DeviceVerb.ARM, "vacation"))
        assertEquals(
            act(Call("alarm_control_panel", "alarm_disarm", listOf("alarm_control_panel.house")), "House alarm", null, Shown.Did(DeviceVerb.DISARM)),
            did("the security system", DeviceVerb.DISARM),
        )
        val coded = alarm.copy(attributes = alarm.attributes + ("code_format" to "number"))
        assertEquals(Plan.NeedsCode("House alarm"), plan("the alarm", SettingValue.Action(DeviceVerb.DISARM), listOf(coded)))
    }

    @Test
    fun valvesAndButtons() {
        assertEquals(
            act(Call("valve", "open_valve", listOf("valve.garden")), "Garden sprinkler", null, Shown.Did(DeviceVerb.OPEN)),
            did("the sprinkler", DeviceVerb.OPEN),
        )
        assertEquals(
            act(Call("valve", "open_valve", listOf("valve.garden")), "Garden sprinkler", null, Shown.Did(DeviceVerb.OPEN)),
            plan("the garden sprinkler", word("on")),
        )
        assertEquals(
            act(Call("valve", "set_valve_position", listOf("valve.garden"), mapOf("position" to 50)), "Garden sprinkler", Aspect.POSITION,
                Shown.Number(50.0, NumberUnit.PERCENT)),
            plan("the sprinkler", SettingValue.Number(50.0, NumberUnit.PERCENT)),
        )
        assertEquals(
            act(Call("button", "press", listOf("button.doorbell_chime")), "Doorbell chime", null, Shown.Did(DeviceVerb.PRESS)),
            did("the doorbell chime", DeviceVerb.PRESS),
        )
    }

    @Test
    fun fromSentenceToCall() {
        fun said(text: String) = (CommandParser.parse(text) as Command.SmartHome).setting!!
        fun value(text: String) = (SettingPlanner.plan(said(text), entities, areas) as Plan.Act).value
        assertEquals(Shown.Option("blue"), value("turn the kitchen light blue"))
        assertEquals(Shown.Option("warm white"), value("set the kitchen light to warm white"))
        assertEquals(Shown.Number(2700.0, NumberUnit.KELVIN), value("set the hall lamp to 2700k"))
        assertEquals(Shown.Option("Rainbow"), value("set the kitchen light effect to rainbow"))
        assertEquals(Shown.Number(25.0, NumberUnit.PERCENT), value("set the tv volume to 25"))
        assertEquals(Shown.Option("HDMI 1"), value("switch the tv to hdmi 1"))
        assertEquals(Shown.Switch(true), value("mute the tv"))
        assertEquals(Shown.Did(DeviceVerb.START), value("vacuum the house"))
        assertEquals(Shown.Did(DeviceVerb.DOCK), value("send the vacuum home"))
        assertEquals(Shown.Did(DeviceVerb.UNLOCK), value("unlock the front door"))
        assertEquals(Shown.Did(DeviceVerb.ARM, "night"), value("arm the alarm in night mode"))
        assertEquals(Shown.Did(DeviceVerb.DISARM), value("disarm the alarm"))
        assertEquals(Shown.Did(DeviceVerb.OPEN), value("open the garden sprinkler"))
        assertEquals(Shown.Did(DeviceVerb.PRESS), value("press the doorbell chime"))
        assertEquals(Shown.Did(DeviceVerb.START), value("mow the lawn"))
    }
}
