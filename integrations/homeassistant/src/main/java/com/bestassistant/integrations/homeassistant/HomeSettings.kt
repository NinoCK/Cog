package com.bestassistant.integrations.homeassistant

/**
 * Requests to change one of a device's settings or tell it to do something: "set the AC to 22
 * degrees", "put the air purifier in sleep mode", "turn up the bedroom heating", "turn the kitchen
 * light blue", "switch the TV to HDMI 1", "start the vacuum", "unlock the front door".
 *
 * Only the shape of the sentence is read here: what is being set ([DeviceSetting.target], as
 * said) and to what. Which device that is, which of its settings, and whether "sleep" is one of
 * its modes is decided later, against what Home Assistant says each device can do.
 */
object HomeSettings {

    private const val NUMBER = "\\d+(?:[.,]\\d+)?(?: ?(?:°c|°|degrees|degree|%|percent|kelvin|k))?"

    private val setTo = Regex("^(?:set|put|turn|change|switch|adjust) (.+) to (.+)$")
    private val setIn = Regex("^(?:set|put|switch|change) (.+) (?:on|in|into|at) (.+)$")
    private val setValueFirst = Regex("^(?:set|put) ($NUMBER) (?:on|in|for|at) (.+)$")
    private val bareTo = Regex("^(.+) to ($NUMBER)$")
    private val turnUp = Regex("^(?:turn|crank) (up|down) (.+)$")
    private val turnUpAfter = Regex("^(?:turn|crank) (.+) (up|down)$")
    private val raise = Regex("^(increase|raise|bump up|decrease|reduce|lower) (.+)$")
    private val warmer = Regex("^make (.+) (warmer|hotter|cooler|colder)$")
    private val switchOn = Regex("^(?:turn|switch) (on|off) (.+)$")
    private val switchOnAfter = Regex("^(?:turn|switch) (.+) (on|off)$")
    private val enable = Regex("^(enable|activate|disable|deactivate) (.+)$")
    private val mute = Regex("^(mute|unmute) (.+)$")
    private val number = Regex("^(?:the )?(-?\\d+(?:[.,]\\d+)?) ?(°c|°|degrees celsius|degrees|degree|celsius|%|percent|per cent|kelvin|k)?$")

    /** Colours and shades of white a light can be set to without "to": "turn the lamp red". */
    val colors = listOf(
        "warm white", "soft white", "neutral white", "cool white", "cold white", "daylight", "white",
        "red", "orange", "yellow", "green", "lime", "blue", "cyan", "turquoise", "teal", "purple", "violet", "magenta",
        "pink", "indigo", "gold", "coral", "salmon", "crimson", "lavender",
    )
    private val colorBare = Regex("^(?:turn|make|change|paint) (.+?) (${colors.joinToString("|")})$")

    // Things to do, rather than settings.
    private val lockVerb = Regex("^(lock|unlock) (.+)$")
    private val startVerb = Regex("^(start|stop|pause|resume|continue) (.+?)(?: (?:cleaning|vacuuming|mowing))?$")
    private val sendHome = Regex("^(?:send|return|take) (.+?) (?:home|back|back home|back to (?:the |its )?(?:dock|base|charger)|to (?:the |its )?(?:dock|base|charger|charging station))$")
    private val dock = Regex("^dock (.+)$")
    private val locate = Regex("^(?:locate|find|where is|where's) (.+)$")
    private val press = Regex("^press (.+?)(?: button)?$")
    private val arm = Regex("^arm (.+?)(?: (?:in|to|on|for) (?:the )?(home|away|night|vacation|holiday|stay)(?: mode)?)?$")
    private val armMode = Regex("^arm (home|away|night|vacation|holiday|stay)$")
    private val disarm = Regex("^disarm (.+)$")
    private val openValve = Regex("^(open|close|shut) (.+)$")
    private val vacuumHouse = Regex("^(?:start )?(?:vacuum|vacuuming|hoover|hoovering|clean|cleaning) (?:the |my )?(?:house|home|floors?|flat|apartment|place)$")
    private val mowLawn = Regex("^(?:start )?(?:mow|mowing) (?:the |my )?(?:lawn|grass|garden|yard)$")

    /**
     * [strict]: only when it's clearly about a device in the house. "Turn on/off ..." always is
     * (the phone's own switches are matched before this), whatever the device is called.
     */
    fun parse(text: String, strict: Boolean = true): DeviceSetting? {
        action(text, strict)?.let { return it }
        colorBare.matchEntire(text)?.let { m ->
            if (!strict || mentions(m.groupValues[1], lightWords)) return DeviceSetting(m.groupValues[1], SettingValue.Word(m.groupValues[2]))
        }
        val setting = shape(text) ?: return null
        val switching = switchOn.matches(text) || switchOnAfter.matches(text)
        return setting.takeIf { !strict || switching || mentions(it.target, deviceWords) }
    }

    private fun action(text: String, strict: Boolean): DeviceSetting? {
        fun act(target: String, verb: DeviceVerb, words: Set<String>, mode: String? = null): DeviceSetting? =
            DeviceSetting(target, SettingValue.Action(verb, mode)).takeIf { !strict || mentions(target, words) }

        if (vacuumHouse.matches(text)) return DeviceSetting("vacuum", SettingValue.Action(DeviceVerb.START))
        if (mowLawn.matches(text)) return DeviceSetting("mower", SettingValue.Action(DeviceVerb.START))
        lockVerb.matchEntire(text)?.let { m ->
            return act(m.groupValues[2], if (m.groupValues[1] == "lock") DeviceVerb.LOCK else DeviceVerb.UNLOCK, lockWords)
        }
        startVerb.matchEntire(text)?.let { m ->
            val verb = when (m.groupValues[1]) {
                "stop" -> DeviceVerb.STOP
                "pause" -> DeviceVerb.PAUSE
                else -> DeviceVerb.START
            }
            return act(m.groupValues[2], verb, robotWords + mediaWords)
        }
        (sendHome.matchEntire(text) ?: dock.matchEntire(text))?.let { return act(it.groupValues[1], DeviceVerb.DOCK, robotWords) }
        locate.matchEntire(text)?.let { m -> if (mentions(m.groupValues[1], robotWords)) return act(m.groupValues[1], DeviceVerb.LOCATE, robotWords) }
        press.matchEntire(text)?.let { m -> return DeviceSetting(m.groupValues[1], SettingValue.Action(DeviceVerb.PRESS)) }
        armMode.matchEntire(text)?.let { return DeviceSetting("alarm", SettingValue.Action(DeviceVerb.ARM, armedMode(it.groupValues[1]))) }
        arm.matchEntire(text)?.let { m ->
            return act(m.groupValues[1], DeviceVerb.ARM, alarmWords, m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::armedMode))
        }
        disarm.matchEntire(text)?.let { return act(it.groupValues[1], DeviceVerb.DISARM, alarmWords) }
        openValve.matchEntire(text)?.let { m ->
            if (mentions(m.groupValues[2], valveWords)) {
                return DeviceSetting(m.groupValues[2], SettingValue.Action(if (m.groupValues[1] == "open") DeviceVerb.OPEN else DeviceVerb.CLOSE))
            }
        }
        return null
    }

    /** Home Assistant's names for the ways an alarm arms. */
    private fun armedMode(said: String) = when (said) {
        "stay" -> "home"
        "holiday" -> "vacation"
        else -> said
    }

    private fun shape(text: String): DeviceSetting? {
        setValueFirst.matchEntire(text)?.let { return setting(it.groupValues[2], it.groupValues[1]) }
        setTo.matchEntire(text)?.let { return setting(it.groupValues[1], it.groupValues[2]) }
        setIn.matchEntire(text)?.let { m -> if (m.groupValues[2] != "on") return setting(m.groupValues[1], m.groupValues[2]) }
        bareTo.matchEntire(text)?.let { return setting(it.groupValues[1], it.groupValues[2]) }
        turnUp.matchEntire(text)?.let { return DeviceSetting(it.groupValues[2], SettingValue.Step(it.groupValues[1] == "up")) }
        turnUpAfter.matchEntire(text)?.let { return DeviceSetting(it.groupValues[1], SettingValue.Step(it.groupValues[2] == "up")) }
        raise.matchEntire(text)?.let { m ->
            return DeviceSetting(m.groupValues[2], SettingValue.Step(m.groupValues[1] in setOf("increase", "raise", "bump up")))
        }
        warmer.matchEntire(text)?.let { m ->
            val target = m.groupValues[1]
            val warm = m.groupValues[2] in setOf("warmer", "hotter")
            // A light's white gets warmer as its colour temperature goes down.
            if (mentions(target, lightWords)) return DeviceSetting("$target warmth", SettingValue.Step(!warm))
            return DeviceSetting("$target temperature", SettingValue.Step(warm))
        }
        switchOn.matchEntire(text)?.let { return DeviceSetting(it.groupValues[2], SettingValue.Word(it.groupValues[1])) }
        switchOnAfter.matchEntire(text)?.let { return DeviceSetting(it.groupValues[1], SettingValue.Word(it.groupValues[2])) }
        enable.matchEntire(text)?.let { m ->
            val on = m.groupValues[1] == "enable" || m.groupValues[1] == "activate"
            return DeviceSetting(m.groupValues[2], SettingValue.Word(if (on) "on" else "off"))
        }
        mute.matchEntire(text)?.let { m -> return DeviceSetting("${m.groupValues[2]} mute", SettingValue.Word(if (m.groupValues[1] == "mute") "on" else "off")) }
        return null
    }

    private fun value(said: String): SettingValue {
        number.matchEntire(said)?.let { m ->
            val unit = when (m.groupValues[2]) {
                "" -> null
                "%", "percent", "per cent" -> NumberUnit.PERCENT
                "kelvin", "k" -> NumberUnit.KELVIN
                else -> NumberUnit.DEGREES
            }
            m.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { return SettingValue.Number(it, unit) }
        }
        return SettingValue.Word(said.removePrefix("the ").trim())
    }

    private val lightWords = setOf("light", "lights", "lamp", "lamps", "bulb", "bulbs", "led", "leds", "strip", "lightstrip", "spotlight", "spotlights")
    private val mediaWords = setOf("tv", "tvs", "television", "speaker", "speakers", "soundbar", "receiver", "stereo", "chromecast", "sonos")
    private val robotWords = setOf("vacuum", "vacuums", "hoover", "roomba", "robot", "mower", "lawnmower", "automower", "roborock")
    private val lockWords = setOf("door", "doors", "lock", "locks", "gate", "deadbolt", "latch")
    private val alarmWords = setOf("alarm", "alarms", "security", "system", "house", "home", "panel", "siren")
    private val valveWords = setOf("valve", "valves", "sprinkler", "sprinklers", "irrigation", "tap", "water")

    /** Words that make a sentence about a device in the house rather than the phone or a search. */
    private val deviceWords = setOf(
        "ac", "a/c", "aircon", "aircondition", "airconditioner", "conditioner", "conditioners", "conditioning", "climate",
        "thermostat", "thermostats", "heater", "heaters", "heating", "radiator", "radiators", "heatpump", "purifier", "purifiers",
        "fan", "fans", "humidifier", "humidifiers", "dehumidifier", "dehumidifiers", "boiler", "temperature", "temp",
        "blind", "blinds", "shutter", "shutters", "curtain", "curtains", "warmth", "mute",
    ) + lightWords + mediaWords + robotWords + valveWords + setOf("alarm", "security")

    private fun mentions(target: String, words: Set<String>): Boolean =
        Accents.fold(target.lowercase()).split(' ', '-').any { it in words }

    private fun setting(target: String, value: String): DeviceSetting? {
        val device = target.trim().takeIf { it.isNotEmpty() } ?: return null
        return DeviceSetting(device, value(value.trim()))
    }

    /**
     * For a sentence nothing else understood ([text], normalised the way requests are): any device
     * and setting Home Assistant might have, not just familiar ones.
     */
    fun loose(text: String): DeviceSetting? = parse(text, strict = false)
}
