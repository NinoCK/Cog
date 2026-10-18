package com.bestassistant.integrations.homeassistant

import com.bestassistant.integration.Request
import com.bestassistant.integration.RequestParser
import com.bestassistant.integration.Slot

/**
 * "turn on the kitchen light": [text] is handed to Home Assistant's own (rule-based) Assist,
 * which knows the user's devices and areas, and answers with its own sentence. [group] is set
 * for "turn off all the lights" and the like, which Cog does itself across every such device;
 * [setting] for "set the air conditioner to cooling" or "start the vacuum", which Cog matches
 * against what the device itself says it can do; [query] for "is the front door locked?",
 * answered from what Home Assistant says about its devices and sensors.
 */
data class HomeRequest(
    val text: String,
    val group: HomeGroup? = null,
    val setting: DeviceSetting? = null,
    val query: DeviceQuery? = null,
) : Request

/**
 * Finds requests for the house in what was said. Questions about the house and device settings
 * come early ("what's the temperature in the bedroom" before the weather takes it, "put the AC on
 * cool" before music takes "put ... on" as a song); switching devices on and off comes late, after
 * the phone's own switches and everything else Cog does itself.
 */
object HomeRequests : RequestParser {

    override fun parse(text: String, slot: Slot): HomeRequest? = when (slot) {
        Slot.EARLY -> question(text) ?: deviceSetting(text)
        Slot.LATE -> smartHome(text)
    }

    /** Both, the way Cog asks: for tests, with [said] lowercase and without its final punctuation. */
    internal fun find(said: String): HomeRequest? {
        val text = said.trim().lowercase().trimEnd('?', '!', '.')
        return parse(text, Slot.EARLY) ?: parse(text, Slot.LATE)
    }

    /** "ask home assistant to ...", "... in home assistant": whatever follows is for Home Assistant. */
    private val toHomeAssistant = Regex("^(?:ask |tell |hey )?(?:home assistant|homeassistant|home-assistant)(?: to| please)? (.+)$")
    private val inHomeAssistant = Regex("^(.+?) (?:in|on|with|using|via|through) (?:home assistant|homeassistant)$")

    /** Device control that Home Assistant's Assist understands; the phone's own switches are matched before this. */
    private val switchDevice = Regex("^(?:turn|switch|power) (?:on|off) (?:the |my |all |all the )?(.+)$")
    private val switchDeviceAfter = Regex("^(?:turn|switch|power) (?:the |my |all |all the )?(.+?) (?:on|off)$")
    private val dimDevice = Regex("^(?:dim|brighten|lock|unlock)(?: the| my| all| all the)? (.+)$")
    private val setDevice = Regex("^(?:set|turn|put|change) (?:the |my )?(.+?) (?:to|at) (\\d{1,3})(?: ?%| percent| degrees?)?$")
    private val scene = Regex("^(?:activate|run|start|turn on|set|enable)(?: the| my)? (.+?) (?:scene|mode)$")
    private val openCover = Regex("^(?:open|close|raise|lower|shut)(?: the| my| all the| all)? (.+)$")
    private val coverWords = setOf(
        "garage", "blinds", "blind", "curtains", "curtain", "shades", "shade", "shutters", "shutter", "gate", "awning",
        "door", "garage door", "front door", "back door", "valve", "window", "windows", "roller", "rollers",
    )
    private val notDevices = setOf(
        "it", "this", "that", "everything", "something", "music", "media", "sound", "phone", "screen", "volume", "the volume",
        "alarm", "the alarm", "timer", "the timer", "song", "the song", "brightness", "the brightness", "screen brightness",
        // The phone's own modes, which only its settings can change.
        "dark", "light", "airplane", "aeroplane", "flight", "silent", "vibrate", "focus", "driving", "battery saver",
        "power saving", "low power", "reading",
    )

    private fun words(text: String) = text.split(' ').filter { it.isNotEmpty() }

    /** "Is the front door locked?", "what's the temperature in the bedroom?" before the weather takes it. */
    private fun question(text: String): HomeRequest? = HomeQuestions.parse(text)?.let { HomeRequest(text, query = it) }

    /** "Put the AC on cool" before music takes "put ... on" as a song. */
    private fun deviceSetting(text: String): HomeRequest? {
        val setting = HomeSettings.parse(text) ?: return null
        if (setting.value.let { it is SettingValue.Word && it.text in setOf("on", "off") }) return null
        return smartHome(text)
    }

    private fun smartHome(text: String): HomeRequest? {
        (toHomeAssistant.matchEntire(text) ?: inHomeAssistant.matchEntire(text))?.let { m ->
            val request = m.groupValues[1].trim()
            HomeQuestions.parse(request)?.let { return HomeRequest(request, query = it) }
            val group = HomeGroups.parse(request)
            return HomeRequest(request, group, if (group == null) HomeSettings.parse(request, strict = false) else null)
        }
        HomeGroups.parse(text)?.let { return HomeRequest(text, it) }
        HomeSettings.parse(text)?.let { setting ->
            // "Arm the alarm" is the house's alarm; "set the alarm to ..." the phone's.
            if (setting.value is SettingValue.Action || setting.target.trim() !in notDevices) return HomeRequest(text, setting = setting)
        }
        val device = switchDevice.matchEntire(text)?.groupValues?.get(1)
            ?: switchDeviceAfter.matchEntire(text)?.groupValues?.get(1)
            ?: dimDevice.matchEntire(text)?.groupValues?.get(1)
            ?: setDevice.matchEntire(text)?.groupValues?.get(1)
            ?: scene.matchEntire(text)?.groupValues?.get(1)
            ?: openCover.matchEntire(text)?.groupValues?.get(1)?.takeIf { rest -> words(rest).any { it in coverWords } }
            ?: return null
        if (device.trim() in notDevices) return null
        return HomeRequest(text)
    }
}

/**
 * "turn off all the lights", "close the blinds in the bedroom": one [action] on every device of a
 * [kind] (null: everything that is on, i.e. lights, fans and media players), in the house or in the
 * [area] named, as the user said it.
 */
data class HomeGroup(val action: HomeAction, val kind: DeviceKind?, val area: String? = null)

enum class HomeAction { ON, OFF, OPEN, CLOSE, LOCK }

/**
 * "set the bedroom AC to 22", "put the air purifier in sleep mode", "turn up the AC fan":
 * [target] is the device and, maybe, which of its settings, as said ("the bedroom ac fan"); which
 * device and setting that is only becomes clear against what Home Assistant lists.
 */
data class DeviceSetting(val target: String, val value: SettingValue)

sealed interface SettingValue {
    /** "22 degrees", "40%", "3". */
    data class Number(val value: Double, val unit: NumberUnit? = null) : SettingValue

    /** "cooling", "sleep mode", "high", "on". */
    data class Word(val text: String) : SettingValue

    /** "turn up", "warmer", "lower". */
    data class Step(val up: Boolean) : SettingValue

    /** "start the vacuum", "press the doorbell button", "arm the alarm in night mode" ([mode]: "night"). */
    data class Action(val verb: DeviceVerb, val mode: String? = null) : SettingValue
}

enum class NumberUnit { DEGREES, PERCENT, KELVIN }

/** What a device can be told to do, beyond on and off. */
enum class DeviceVerb { START, STOP, PAUSE, DOCK, LOCATE, PRESS, LOCK, UNLOCK, OPEN, CLOSE, ARM, DISARM }

/**
 * "what's the temperature in the bedroom", "is the front door locked", "what's the vacuum doing":
 * [target] is the device or room as said; [asked] is what about it.
 */
data class DeviceQuery(val target: String, val asked: Asked)

sealed interface Asked {
    /** A reading: "the temperature", "the battery". */
    data class Measure(val quantity: Quantity) : Asked

    /** A yes-or-no question about its state: "is it open?". */
    data class State(val condition: Condition) : Asked

    /** "What's it doing?", "its status". */
    data object Status : Asked
}

/** What sensors measure, by Home Assistant's device classes, with the words people use. */
enum class Quantity(val deviceClasses: Set<String>, val words: List<String>) {
    TEMPERATURE(setOf("temperature"), listOf("temperature", "temp")),
    HUMIDITY(setOf("humidity", "moisture"), listOf("humidity", "moisture")),
    BATTERY(setOf("battery"), listOf("battery", "battery level", "charge")),
    POWER(setOf("power"), listOf("power", "power usage", "power consumption", "power draw", "wattage", "consumption")),
    ENERGY(setOf("energy"), listOf("energy", "energy usage", "energy consumption")),
    CO2(setOf("carbon_dioxide"), listOf("co2", "carbon dioxide")),
    AIR_QUALITY(setOf("pm25", "aqi", "pm10", "volatile_organic_compounds"), listOf("air quality", "pm2.5", "pm 2.5", "pollution")),
    PRESSURE(setOf("pressure", "atmospheric_pressure"), listOf("pressure", "air pressure")),
    ILLUMINANCE(setOf("illuminance"), listOf("light level", "illuminance", "lux")),
}

/** The states a yes-or-no question asks about. */
enum class Condition { ON, OFF, OPEN, CLOSED, LOCKED, UNLOCKED, ARMED, DISARMED, RUNNING, PLAYING, CHARGING, DOCKED, HOME }

/** The kinds of Home Assistant device a whole group of can be switched at once, by their domain. */
enum class DeviceKind(val domain: String) {
    LIGHT("light"), SWITCH("switch"), FAN("fan"), COVER("cover"), MEDIA("media_player"), CLIMATE("climate"), LOCK("lock"), VALVE("valve"),
}
