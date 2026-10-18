package com.bestassistant.integrations.homeassistant

import com.bestassistant.integrations.homeassistant.GroupPlanner.Call
import com.bestassistant.integrations.homeassistant.HomeAssistant.Area
import com.bestassistant.integrations.homeassistant.HomeAssistant.Entity
import java.util.Locale

/**
 * Turns "set the bedroom AC to cooling", "turn the lamp blue" or "start the vacuum" into a Home
 * Assistant service call, from what each device itself says it can do: an air conditioner's
 * modes, fan speeds, swing and temperature range, an air purifier's presets and speed, a light's
 * colours, whites and effects, a TV's volume and inputs, a vacuum's suction, and the extra selects,
 * numbers and switches its integration adds to the same device ("Child lock", "Favorite level").
 * Nothing about any device is written into PocketSearch, so a new one just works.
 *
 * The device is the one whose name, area or kind best matches the words; the words left over say
 * which setting ("fan", "mode", "swing"), and the value has to be one the device offers.
 */
object SettingPlanner {

    /** The settings Home Assistant defines for every device of a kind, and words people use for them. */
    enum class Aspect(val words: List<String>) {
        TEMPERATURE(listOf("temperature", "temp", "degrees", "thermostat")),
        MODE(listOf("mode", "program")),
        PRESET(listOf("preset", "mode", "profile", "program")),
        FAN(listOf("fan", "speed")),
        SWING(listOf("swing", "oscillation", "louvers", "louvres", "flaps")),
        SPEED(listOf("speed", "power", "level", "fan")),
        OSCILLATION(listOf("oscillation", "oscillate", "oscillating", "swing", "rotation")),
        HUMIDITY(listOf("humidity")),
        BRIGHTNESS(listOf("brightness", "dimmer")),
        POSITION(listOf("position")),
        COLOR(listOf("color", "colour", "colors", "colours")),
        COLOR_TEMPERATURE(listOf("warmth", "white", "whites", "kelvin", "tone")),
        EFFECT(listOf("effect", "effects", "animation", "pattern")),
        VOLUME(listOf("volume", "loudness")),
        MUTE(listOf("mute", "muted", "muting")),
        SOURCE(listOf("source", "input", "inputs")),
        SOUND_MODE(listOf("sound", "equalizer", "eq", "audio")),
        SUCTION(listOf("suction", "power", "cleaning", "fan", "speed")),
    }

    sealed interface Plan {
        /** [devices] by name; [aspect] or, for a device's own extra setting, its [label]; set to [value]. */
        data class Act(val calls: List<Call>, val devices: List<String>, val aspect: Aspect?, val label: String?, val value: Shown) : Plan

        /** More than one device fits and the user said one: which? With each one's area, when it has one. */
        data class Ambiguous(val devices: List<String>, val areas: List<String?> = devices.map { null }) : Plan

        /** The device has nothing that takes that value; [offers] is what it has. */
        data class NoSuchSetting(val device: String, val offers: List<Offer>) : Plan

        data class OutOfRange(val device: String, val aspect: Aspect?, val label: String?, val min: Double, val max: Double, val unit: NumberUnit?) : Plan

        /**
         * They named a kind of device ("the desk AC") and Home Assistant lists none of it. Not
         * left to Assist, whose loose matching would rather switch the desk lamp.
         */
        data class NotFound(val said: String) : Plan

        /** The device can't do what was asked: "start the kitchen light". */
        data class CantDo(val device: String) : Plan

        /**
         * Locking, unlocking, arming or disarming it takes its code, which PocketSearch won't take by voice:
         * it would be said out loud and kept in the transcript.
         */
        data class NeedsCode(val device: String) : Plan

        /** Nothing of theirs fits: left to Assist (a question, a name PocketSearch doesn't know). */
        data object NotMine : Plan
    }

    /** What a device offers: options to choose from, or a range. */
    data class Offer(val aspect: Aspect?, val label: String?, val options: List<String>? = null, val min: Double? = null, val max: Double? = null, val unit: NumberUnit? = null)

    /** The new value, to say back. */
    sealed interface Shown {
        data class Number(val value: Double, val unit: NumberUnit?) : Shown
        data class Option(val option: String) : Shown
        data class Switch(val on: Boolean) : Shown
        data class Step(val up: Boolean) : Shown

        /** The device itself switched on or off. */
        data class Power(val on: Boolean) : Shown

        /** The device told to do something: started, docked, unlocked, armed ([mode]: "night"). */
        data class Did(val verb: DeviceVerb, val mode: String? = null) : Shown
    }

    // region A device's settings

    private sealed interface Setting {
        val entity: Entity
        val aspect: Aspect?
        val label: String?
        val keywords: List<String> get() = aspect?.words ?: tokens(label.orEmpty())
    }

    private class Choice(
        override val entity: Entity,
        override val aspect: Aspect?,
        override val label: String?,
        val options: List<String>,
        val current: String?,
        val call: (String) -> Call,
    ) : Setting

    private class Range(
        override val entity: Entity,
        override val aspect: Aspect?,
        override val label: String?,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: NumberUnit?,
        val current: Double?,
        val call: (Double) -> Call,
        val stepCall: ((Boolean) -> Call)? = null,
    ) : Setting

    private class Toggle(override val entity: Entity, override val aspect: Aspect?, override val label: String?, val call: (Boolean) -> Call) : Setting

    /** The kinds of device PocketSearch finds by name, the most specific first. */
    private val PRIMARY = listOf(
        "climate", "humidifier", "water_heater", "fan", "light", "cover", "valve", "media_player", "vacuum", "lawn_mower", "lock",
        "alarm_control_panel", "remote", "switch", "input_boolean", "siren", "button", "input_button", "script", "scene",
    )

    /** "The second one": which of the devices listed in a question; "the last one" is -1. */
    private val ORDINALS = mapOf("first" to 0, "second" to 1, "third" to 2, "fourth" to 3, "fifth" to 4, "last" to -1)

    /** Light colour modes that can show any colour. */
    private val COLOR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")

    /** Whites people ask for, as colour temperatures. */
    private val WHITES = linkedMapOf(
        "warm white" to 2700, "soft white" to 2700, "neutral white" to 4000, "white" to 4000, "cool white" to 5000,
        "cold white" to 6000, "daylight" to 6500,
    )

    private const val KELVIN_STEP = 500.0

    private fun call(e: Entity, service: String, vararg data: Pair<String, Any>) = Call(e.domain, service, listOf(e.id), mapOf(*data))

    private fun settings(e: Entity, all: List<Entity>): List<Setting> = buildList {
        val a = e.attributes
        when (e.domain) {
            "climate" -> {
                if ("temperature" in a) {
                    add(Range(e, Aspect.TEMPERATURE, null, e.number("min_temp") ?: 7.0, e.number("max_temp") ?: 35.0,
                        e.number("target_temp_step") ?: 0.5, NumberUnit.DEGREES, e.number("temperature"), { call(e, "set_temperature", "temperature" to it) }))
                }
                e.list("hvac_modes").ifNotEmpty { add(Choice(e, Aspect.MODE, null, it, e.state, { o -> call(e, "set_hvac_mode", "hvac_mode" to o) })) }
                e.list("fan_modes").ifNotEmpty { add(Choice(e, Aspect.FAN, null, it, a["fan_mode"] as? String, { o -> call(e, "set_fan_mode", "fan_mode" to o) })) }
                e.list("swing_modes").ifNotEmpty { add(Choice(e, Aspect.SWING, null, it, a["swing_mode"] as? String, { o -> call(e, "set_swing_mode", "swing_mode" to o) })) }
                e.list("preset_modes").ifNotEmpty { add(Choice(e, Aspect.PRESET, null, it, a["preset_mode"] as? String, { o -> call(e, "set_preset_mode", "preset_mode" to o) })) }
                if ("humidity" in a && "min_humidity" in a) {
                    add(Range(e, Aspect.HUMIDITY, null, e.number("min_humidity") ?: 30.0, e.number("max_humidity") ?: 99.0, 1.0,
                        NumberUnit.PERCENT, e.number("humidity"), { call(e, "set_humidity", "humidity" to it.roundToIntValue()) }))
                }
            }
            "fan" -> {
                if ("percentage" in a) {
                    add(Range(e, Aspect.SPEED, null, 0.0, 100.0, e.number("percentage_step") ?: 10.0, NumberUnit.PERCENT, e.number("percentage"),
                        { call(e, "set_percentage", "percentage" to it.roundToIntValue()) },
                        { up -> call(e, if (up) "increase_speed" else "decrease_speed") }))
                }
                e.list("preset_modes").ifNotEmpty { add(Choice(e, Aspect.PRESET, null, it, a["preset_mode"] as? String, { o -> call(e, "set_preset_mode", "preset_mode" to o) })) }
                if ("oscillating" in a) add(Toggle(e, Aspect.OSCILLATION, null) { on -> call(e, "oscillate", "oscillating" to on) })
            }
            "humidifier" -> {
                add(Range(e, Aspect.HUMIDITY, null, e.number("min_humidity") ?: 0.0, e.number("max_humidity") ?: 100.0, 1.0,
                    NumberUnit.PERCENT, e.number("humidity"), { call(e, "set_humidity", "humidity" to it.roundToIntValue()) }))
                e.list("available_modes").ifNotEmpty { add(Choice(e, Aspect.MODE, null, it, a["mode"] as? String, { o -> call(e, "set_mode", "mode" to o) })) }
            }
            "water_heater" -> {
                add(Range(e, Aspect.TEMPERATURE, null, e.number("min_temp") ?: 30.0, e.number("max_temp") ?: 80.0, 1.0,
                    NumberUnit.DEGREES, e.number("temperature"), { call(e, "set_temperature", "temperature" to it) }))
                e.list("operation_list").ifNotEmpty { add(Choice(e, Aspect.MODE, null, it, e.state, { o -> call(e, "set_operation_mode", "operation_mode" to o) })) }
            }
            "light" -> {
                val modes = e.list("supported_color_modes")
                if (modes.any { it != "onoff" }) {
                    add(Range(e, Aspect.BRIGHTNESS, null, 1.0, 100.0, 10.0, NumberUnit.PERCENT, null,
                        { call(e, "turn_on", "brightness_pct" to it.roundToIntValue()) },
                        { up -> call(e, "turn_on", "brightness_step_pct" to if (up) 10 else -10) }))
                }
                val colored = modes.any { it in COLOR_MODES }
                val min = e.number("min_color_temp_kelvin") ?: 2000.0
                val max = e.number("max_color_temp_kelvin") ?: 6500.0
                if (colored || "color_temp" in modes) {
                    val options = WHITES.keys.toList() + if (colored) HomeSettings.colors.filter { it !in WHITES } else emptyList()
                    add(Choice(e, Aspect.COLOR, null, options, null) { o ->
                        // Home Assistant makes a white from a colour temperature on lights that only do colours.
                        WHITES[o]?.let { call(e, "turn_on", "color_temp_kelvin" to it.coerceIn(min.toInt(), max.toInt())) }
                            ?: call(e, "turn_on", "color_name" to o.replace(" ", ""))
                    })
                }
                if ("color_temp" in modes) {
                    add(Range(e, Aspect.COLOR_TEMPERATURE, null, min, max, KELVIN_STEP, NumberUnit.KELVIN, e.number("color_temp_kelvin"),
                        { call(e, "turn_on", "color_temp_kelvin" to it.roundToIntValue()) },
                        // Without a current white (the light is off, or showing a colour): a warm or a cool one.
                        { cooler -> call(e, "turn_on", "color_temp_kelvin" to (if (cooler) 5000 else 2700).coerceIn(min.toInt(), max.toInt())) }))
                }
                e.list("effect_list").ifNotEmpty { add(Choice(e, Aspect.EFFECT, null, it, a["effect"] as? String, { o -> call(e, "turn_on", "effect" to o) })) }
            }
            "media_player" -> {
                if ("volume_level" in a) {
                    add(Range(e, Aspect.VOLUME, null, 0.0, 100.0, 10.0, NumberUnit.PERCENT, e.number("volume_level")?.let { Math.round(it * 100).toDouble() },
                        { call(e, "volume_set", "volume_level" to it / 100) },
                        { up -> call(e, if (up) "volume_up" else "volume_down") }))
                }
                if ("is_volume_muted" in a) add(Toggle(e, Aspect.MUTE, null) { on -> call(e, "volume_mute", "is_volume_muted" to on) })
                e.list("source_list").ifNotEmpty { add(Choice(e, Aspect.SOURCE, null, it, a["source"] as? String, { o -> call(e, "select_source", "source" to o) })) }
                e.list("sound_mode_list").ifNotEmpty {
                    add(Choice(e, Aspect.SOUND_MODE, null, it, a["sound_mode"] as? String, { o -> call(e, "select_sound_mode", "sound_mode" to o) }))
                }
            }
            "vacuum" -> e.list("fan_speed_list").ifNotEmpty {
                add(Choice(e, Aspect.SUCTION, null, it, a["fan_speed"] as? String, { o -> call(e, "set_fan_speed", "fan_speed" to o) }))
            }
            "valve" -> if ("current_position" in a) {
                add(Range(e, Aspect.POSITION, null, 0.0, 100.0, 10.0, NumberUnit.PERCENT, e.number("current_position"),
                    { call(e, "set_valve_position", "position" to it.roundToIntValue()) },
                    { up -> call(e, if (up) "open_valve" else "close_valve") }))
            }
            "cover" -> {
                if ("current_position" in a) {
                    add(Range(e, Aspect.POSITION, null, 0.0, 100.0, 10.0, NumberUnit.PERCENT, e.number("current_position"),
                        { call(e, "set_cover_position", "position" to it.roundToIntValue()) },
                        { up -> call(e, if (up) "open_cover" else "close_cover") }))
                }
            }
        }
        // What the integration adds to the same device: "Air purifier Mode", "AC Display light".
        for (other in all) {
            if (other.id == e.id || other.state == "unavailable" || !sameDevice(e, other)) continue
            val label = label(other, e)
            when (other.domain) {
                "select" -> other.list("options").ifNotEmpty {
                    add(Choice(other, null, label, it, other.state, { o -> call(other, "select_option", "option" to o) }))
                }
                "number" -> add(Range(other, null, label, other.number("min") ?: 0.0, other.number("max") ?: 100.0, other.number("step") ?: 1.0,
                    unit(other.attributes["unit_of_measurement"] as? String), other.state.toDoubleOrNull(), { call(other, "set_value", "value" to it) }))
                "switch" -> add(Toggle(other, null, label) { on -> call(other, if (on) "turn_on" else "turn_off") })
            }
        }
    }

    private fun sameDevice(e: Entity, other: Entity): Boolean =
        if (e.device != null) other.device == e.device
        else other.id.substringAfter('.').startsWith(e.id.substringAfter('.') + "_")

    /** "Air Purifier Child Lock" on the device "Air Purifier": "Child Lock". */
    private fun label(other: Entity, primary: Entity): String {
        for (prefix in listOfNotNull(other.deviceName, primary.deviceName, primary.name)) {
            if (other.name.length > prefix.length && other.name.startsWith(prefix, ignoreCase = true)) return other.name.substring(prefix.length).trim()
        }
        return other.name
    }

    private fun unit(raw: String?): NumberUnit? = when {
        raw == null -> null
        raw == "%" -> NumberUnit.PERCENT
        raw.startsWith("°") -> NumberUnit.DEGREES
        raw == "K" -> NumberUnit.KELVIN
        else -> null
    }

    private fun Double.roundToIntValue(): Int = Math.round(this).toInt()

    private inline fun List<String>.ifNotEmpty(block: (List<String>) -> Unit) {
        if (isNotEmpty()) block(this)
    }

    // endregion

    // region Which device

    private val fillers = setOf(
        "the", "my", "our", "a", "an", "of", "in", "on", "at", "to", "for", "all", "both", "every", "its", "and", "please", "it", "two",
    )
    private val pluralWords = setOf(
        "all", "both", "every", "lights", "lamps", "fans", "acs", "conditioners", "heaters", "blinds", "purifiers", "tvs", "speakers",
        "locks", "doors", "valves", "sprinklers", "vacuums",
    )
    private val acWords = listOf("ac", "acs", "aircon", "aircondition", "airconditioner", "conditioner", "conditioners", "conditioning")
    private val purifierWords = listOf("purifier")
    private val kindWords = mapOf(
        "climate" to listOf("climate", "thermostat", "heating", "heater", "heatpump"),
        "fan" to listOf("fan"),
        "humidifier" to listOf("humidifier", "dehumidifier"),
        "water_heater" to listOf("boiler", "heater"),
        "light" to listOf("light", "lights", "lamp", "lamps", "bulb", "bulbs", "lightstrip"),
        "cover" to listOf("blind", "blinds", "shutter", "shutters", "curtain", "curtains", "shade", "shades", "awning"),
        "valve" to listOf("valve", "valves", "sprinkler", "sprinklers", "irrigation", "tap"),
        "switch" to listOf("switch", "plug", "socket", "outlet"),
        "media_player" to listOf("tv", "tvs", "television", "speaker", "speakers", "soundbar", "receiver", "stereo"),
        "vacuum" to listOf("vacuum", "vacuums", "hoover", "roomba", "robot"),
        "lawn_mower" to listOf("mower", "lawnmower", "automower", "robot"),
        "lock" to listOf("lock", "locks", "door", "doors", "deadbolt", "latch"),
        "alarm_control_panel" to listOf("alarm", "alarms", "security"),
        "button" to listOf("button", "buttons"),
        "input_button" to listOf("button", "buttons"),
        "scene" to listOf("scene"),
        "script" to listOf("script"),
    )

    /**
     * Every word that names a kind of device, whether or not the user has one. "Lock" isn't one
     * here: it's also a setting ("the purifier's child lock").
     */
    private val anyKind = (kindWords.values.flatten() + acWords + purifierWords).toSet() - setOf("lock", "locks")

    /** Words for the same room: "the desk AC" finds the AC in "Office". */
    private val rooms = listOf(
        listOf("living", "lounge", "family"), listOf("office", "desk", "study"), listOf("toilet", "wc", "restroom", "loo"),
        listOf("kids", "children", "nursery", "playroom"), listOf("garden", "yard", "backyard"), listOf("veranda", "terrace", "patio", "deck"),
        listOf("hall", "hallway", "corridor", "landing"), listOf("basement", "cellar"), listOf("attic", "loft"), listOf("laundry", "utility"),
        listOf("guest", "spare"), listOf("entrance", "entry", "foyer"),
    )

    /** Lowercase, accent-free words; "A/C", "a c" and "air con" are all "ac". */
    internal fun tokens(text: String): List<String> {
        val words = Accents.fold(text.lowercase(Locale.ROOT))
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .filter { it.isNotEmpty() }
        val joined = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val pair = words[i] to words.getOrNull(i + 1)
            if (pair == ("a" to "c") || pair == ("air" to "con")) {
                joined += "ac"
                i += 2
            } else {
                joined += words[i]
                i++
            }
        }
        return joined
    }

    private fun same(a: String, b: String) = GroupPlanner.sameWord(a, b)

    /** [words] with the other names for their rooms. */
    private fun withRooms(words: List<String>): List<String> =
        words + words.flatMap { w -> rooms.filter { room -> room.any { same(w, it) } }.flatten() }

    private fun names(e: Entity) = withRooms(tokens(e.name) + tokens(e.deviceName.orEmpty()))

    /** What people call a device of its kind; an air purifier is usually a fan to Home Assistant. */
    private fun kindOf(e: Entity, names: List<String>): List<String> {
        val purifier = e.domain in setOf("fan", "humidifier") && names.any { it.startsWith("purif") }
        // "The AC" is a climate device that cools, not the boiler's thermostat or a radiator valve.
        val cools = e.domain == "climate" && e.list("hvac_modes").let { it.isEmpty() || "cool" in it }
        return kindWords[e.domain].orEmpty() + (if (purifier) purifierWords else emptyList()) + (if (cools) acWords else emptyList())
    }

    /** Words that name a setting: "fan", "mode", "temperature", and the device's own extras ("display"). */
    private fun settingVocabulary(settings: List<Setting>): List<String> =
        Aspect.entries.flatMap { it.words } + settingWords + settings.flatMap { it.keywords }

    /**
     * How [entity] accounts for each of the user's words: its name (3 points), its area (2), its
     * kind (1), or one of its settings. Words it can't account for are [unknown]: it isn't the one.
     */
    private class Fit(val entity: Entity, val score: Int, val matched: List<String>, val aspect: List<String>, val unknown: List<String>)

    private fun fit(e: Entity, said: List<String>, areas: Map<String, List<String>>, settings: List<Setting>): Fit {
        val names = names(e)
        val area = withRooms(e.area?.let { areas[it] }.orEmpty())
        val kind = kindOf(e, names)
        val vocabulary = settingVocabulary(settings)
        var score = 0
        val matched = mutableListOf<String>()
        val aspect = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (w in said) {
            when {
                names.any { same(w, it) } -> { score += 3; matched += w }
                area.any { same(w, it) } -> { score += 2; matched += w }
                kind.any { same(w, it) } -> score += 1
                vocabulary.any { same(w, it) } -> aspect += w
                else -> unknown += w
            }
        }
        return Fit(e, score, matched, aspect, unknown)
    }

    // endregion

    fun plan(request: DeviceSetting, entities: List<Entity>, areas: List<Area>?): Plan {
        val raw = tokens(request.target)
        val said = raw.filter { it !in fillers }
        val plural = raw.any { it in pluralWords }
        val areaWords = areas.orEmpty().associate { it.id to tokens(it.name) + tokens(it.id.replace('_', ' ')) }
        val primaries = primaries(entities)

        // A kind of device said ("AC", "lamp", "vacuum") is a must, not a hint: "the desk AC"
        // is never the desk lamp. A device counts when it's of that kind or its name says so.
        val kinds = said.filter { w -> anyKind.any { same(w, it) } }
        val ofKind = if (kinds.isEmpty()) primaries else primaries.filter { e ->
            val names = names(e)
            val kind = kindOf(e, names)
            kinds.any { w -> kind.any { same(w, it) } || names.any { same(w, it) } }
        }
        if (ofKind.isEmpty()) return if (kinds.isEmpty()) Plan.NotMine else Plan.NotFound(request.target)

        val fits = ofKind.map { fit(it, said, areaWords, settings(it, entities)) }
        val valid = fits.filter { it.unknown.isEmpty() }
        if (valid.isEmpty()) {
            if (kinds.isEmpty()) return Plan.NotMine
            // "The desk AC" when no AC is called that: the only AC there is, or ask which.
            val unknown = fits.flatMap { it.unknown }.distinct()
            val namesARoom = unknown.any { w -> areaWords.values.any { area -> area.any { same(w, it) } } }
            val devices = ofKind.distinctBy { it.device ?: it.id }
            if (devices.size == 1 && !namesARoom) {
                val only = fits.first { it.entity == devices.single() }
                return decide(listOf(Fit(only.entity, 1, emptyList(), only.aspect, emptyList())), request, entities, areas, plural, strict = false)
            }
            return ambiguous(devices, areas)
        }
        val best = valid.maxOf { it.score }
        val top = valid.filter { it.score == best }
        return decide(top, request, entities, areas, plural, strict = best == 0)
    }

    /**
     * The answer to "Which one?", among the [offered] devices: a name or area ("the living room"),
     * an ordinal ("the second"), or all of them ("both").
     */
    fun choose(request: DeviceSetting, offered: List<String>, answer: String, entities: List<Entity>, areas: List<Area>?): Plan {
        val primaries = primaries(entities)
        val devices = offered.mapNotNull { name -> primaries.firstOrNull { it.name == name } }
        if (devices.isEmpty()) return Plan.NotMine
        val raw = tokens(answer)
        val said = raw.filter { it !in fillers }
        val ordinal = said.firstNotNullOfOrNull { ORDINALS[it] }
        val areaWords = areas.orEmpty().associate { it.id to tokens(it.name) + tokens(it.id.replace('_', ' ')) }
        val chosen = when {
            raw.any { it in pluralWords || it == "two" || it == "three" } -> devices
            ordinal != null -> listOfNotNull(devices.getOrNull(if (ordinal < 0) devices.lastIndex else ordinal))
            else -> {
                val scored = devices.map { it to fit(it, said, areaWords, emptyList()) }
                val best = scored.maxOf { it.second.score }
                if (best == 0) emptyList() else scored.filter { it.second.score == best }.map { it.first }
            }
        }
        if (chosen.isEmpty()) return Plan.NotMine
        val target = tokens(request.target).filter { it !in fillers }
        val fits = chosen.map { e ->
            val settings = settings(e, entities)
            val vocabulary = settingVocabulary(settings)
            Fit(e, 1, emptyList(), target.filter { w -> vocabulary.any { same(w, it) } }, emptyList())
        }
        return decide(fits, request, entities, areas, plural = chosen.size > 1, strict = false)
    }

    private fun primaries(entities: List<Entity>) = entities.filter { it.domain in PRIMARY && !it.isGroup && it.state != "unavailable" }

    private fun ambiguous(devices: List<Entity>, areas: List<Area>?) =
        Plan.Ambiguous(devices.map { it.name }, devices.map { e -> areas?.firstOrNull { it.id == e.area }?.name })

    /** What to do with the devices that fit best: act on one (or all, when asked), or ask which. */
    private fun decide(top: List<Fit>, request: DeviceSetting, entities: List<Entity>, areas: List<Area>?, plural: Boolean, strict: Boolean): Plan {
        val value = request.value
        val toggle = (value as? SettingValue.Word)?.let { onOff(it.text) }
        if (strict && toggle != null && top.all { it.aspect.isEmpty() }) return Plan.NotMine
        val outcomes = top.map { it to settle(it.entity, settings(it.entity, entities), it.aspect, value, toggle, strict) }
        val acts = outcomes.filter { it.second is Plan.Act }
        if (acts.isEmpty()) {
            if (strict) return Plan.NotMine
            return outcomes.map { it.second }.firstOrNull { it !is Plan.NotMine } ?: Plan.NotMine
        }
        // One device can show up as more than one entity (an AC's climate and its display switch): its most specific one.
        val perDevice = acts.groupBy { it.first.entity.device ?: it.first.entity.id }.values
            .map { list -> list.minBy { PRIMARY.indexOf(it.first.entity.domain) } }
        // "The kitchen light", when the kitchen has several and "kitchen" is just the room: all of them
        // (of one kind: "the office" alone is the office lamp or the office AC).
        val oneKind = perDevice.map { it.first.entity.domain }.distinct().size == 1
        val shared = perDevice.map { it.first.entity.area }.distinct().singleOrNull()?.takeIf { oneKind }
        val roomWords = shared?.let { id -> areas?.firstOrNull { it.id == id } }?.let { withRooms(tokens(it.name) + tokens(it.id.replace('_', ' '))) }
        val wholeRoom = roomWords != null && perDevice.all { (fit, _) -> fit.matched.isNotEmpty() && fit.matched.all { w -> roomWords.any { same(w, it) } } }
        val chosen = if (perDevice.size > 1 && !plural && !wholeRoom) {
            // With an AC in every room, "turn off the AC" is the one that's on, "set it to 22" the one that's running.
            val running = perDevice.filter { it.first.entity.state !in setOf("off", "unknown", "standby", "closed", "docked") }
            val wanted = if (toggle == true && running.size < perDevice.size) perDevice - running.toSet() else running
            wanted.singleOrNull()?.let(::listOf) ?: return ambiguous(perDevice.map { it.first.entity }, areas)
        } else {
            perDevice
        }
        val first = chosen.first().second as Plan.Act
        return first.copy(
            calls = chosen.flatMap { (it.second as Plan.Act).calls },
            devices = chosen.map { it.first.entity.name },
        )
    }

    /** Switching a device itself on or off, by its kind. */
    private fun power(e: Entity, on: Boolean): Call? = when (e.domain) {
        "light", "switch", "fan", "climate", "media_player", "humidifier", "water_heater", "input_boolean", "siren", "script", "remote" ->
            call(e, if (on) "turn_on" else "turn_off")
        "scene" -> if (on) call(e, "turn_on") else null
        "cover" -> call(e, if (on) "open_cover" else "close_cover")
        else -> null
    }

    /** "Turn on" for devices that are told what to do instead: the vacuum starts, the sprinkler opens. */
    private fun powerVerb(e: Entity, on: Boolean): DeviceVerb? = when (e.domain) {
        "vacuum", "lawn_mower" -> if (on) DeviceVerb.START else DeviceVerb.DOCK
        "valve" -> if (on) DeviceVerb.OPEN else DeviceVerb.CLOSE
        "alarm_control_panel" -> if (on) DeviceVerb.ARM else DeviceVerb.DISARM
        "button", "input_button" -> DeviceVerb.PRESS.takeIf { on }
        else -> null
    }

    /** Ways an alarm arms, the usual first, with the bit that says it can in its supported features. */
    private val ARM_MODES = linkedMapOf("away" to 2, "home" to 1, "night" to 4, "vacation" to 32)

    /** Telling [e] to do something: start, dock, press, unlock, arm ([asked]: the way, "night"). */
    private fun perform(e: Entity, verb: DeviceVerb, asked: String?): Plan {
        val d = e.domain
        var mode: String? = null
        val service = when (verb) {
            DeviceVerb.START -> mapOf("vacuum" to "start", "lawn_mower" to "start_mowing", "media_player" to "media_play")[d]
            DeviceVerb.STOP -> mapOf("vacuum" to "stop", "lawn_mower" to "pause", "media_player" to "media_stop")[d]
            DeviceVerb.PAUSE -> mapOf("vacuum" to "pause", "lawn_mower" to "pause", "media_player" to "media_pause")[d]
            DeviceVerb.DOCK -> mapOf("vacuum" to "return_to_base", "lawn_mower" to "dock")[d]
            DeviceVerb.LOCATE -> "locate".takeIf { d == "vacuum" }
            DeviceVerb.PRESS -> "press".takeIf { d == "button" || d == "input_button" }
            DeviceVerb.LOCK -> "lock".takeIf { d == "lock" }
            DeviceVerb.UNLOCK -> "unlock".takeIf { d == "lock" }
            DeviceVerb.OPEN -> mapOf("valve" to "open_valve", "cover" to "open_cover")[d]
            DeviceVerb.CLOSE -> mapOf("valve" to "close_valve", "cover" to "close_cover")[d]
            DeviceVerb.DISARM -> "alarm_disarm".takeIf { d == "alarm_control_panel" }
            DeviceVerb.ARM -> if (d != "alarm_control_panel") null else {
                val features = e.number("supported_features")?.toInt()
                fun can(way: String) = features == null || features and ARM_MODES.getValue(way) != 0
                mode = if (asked == null) ARM_MODES.keys.firstOrNull(::can) else asked.takeIf { it in ARM_MODES && can(it) }
                mode?.let { "alarm_arm_$it" }
            }
        } ?: return Plan.CantDo(e.name)
        val coded = e.attributes["code_format"] != null
        val needsCode = when (verb) {
            DeviceVerb.LOCK, DeviceVerb.UNLOCK, DeviceVerb.DISARM -> coded
            DeviceVerb.ARM -> coded && e.attributes["code_arm_required"] != false
            else -> false
        }
        if (needsCode) return Plan.NeedsCode(e.name)
        return Plan.Act(listOf(call(e, service)), listOf(e.name), null, null, Shown.Did(verb, mode))
    }

    // region Which setting, and to what

    private fun onOff(text: String): Boolean? = when (tokens(text).joinToString(" ")) {
        "on", "enabled" -> true
        "off", "disabled" -> false
        else -> null
    }

    /** Words in the value that say which setting rather than what to: "sleep mode", "hdmi input". */
    private val settingWords = setOf("mode", "setting", "preset", "speed", "level", "program", "input", "source", "effect", "color", "colour")

    /** [strict]: no device was named, so the words must name one of its settings. */
    private fun settle(e: Entity, settings: List<Setting>, aspect: List<String>, value: SettingValue, toggle: Boolean?, strict: Boolean): Plan {
        if (value is SettingValue.Action) return perform(e, value.verb, value.mode)
        val valueTokens = (value as? SettingValue.Word)?.let { tokens(it.text) }.orEmpty()
        // "Set the alarm to away": arming it, not a setting.
        if (e.domain == "alarm_control_panel" && valueTokens.isNotEmpty()) {
            when (val way = valueTokens.filter { it !in settingWords && it !in fillers }.joinToString(" ")) {
                "away", "home", "night", "vacation" -> return perform(e, DeviceVerb.ARM, way)
                "stay" -> return perform(e, DeviceVerb.ARM, "home")
                "armed", "on" -> return perform(e, DeviceVerb.ARM, null)
                "disarmed", "off" -> return perform(e, DeviceVerb.DISARM, null)
            }
        }
        val words = aspect + valueTokens.filter { it in settingWords }
        // The settings the words name, the best named first: "favorite level" is the "Favorite Level"
        // number before the speed (whose words include "level").
        fun named(s: Setting) = words.count { w -> s.keywords.any { same(w, it) } }
        val focused = if (words.isEmpty()) settings else settings.filter { named(it) > 0 }.sortedByDescending(::named)
        val noSuch = Plan.NoSuchSetting(e.name, settings.map(::offer))

        if (toggle != null) {
            // "Turn on the AC" switches the AC; "turn on the AC swing" is one of its settings.
            if (aspect.isEmpty()) {
                powerVerb(e, toggle)?.let { return perform(e, it, null) }
                val call = power(e, toggle) ?: return Plan.NotMine
                return Plan.Act(listOf(call), listOf(e.name), null, null, Shown.Power(toggle))
            }
            for (s in focused) {
                when (s) {
                    is Toggle -> return act(s, s.call(toggle), Shown.Switch(toggle))
                    is Choice -> {
                        val option = if (toggle) s.options.firstOrNull { it == "on" } ?: s.options.firstOrNull { it in setOf("both", "vertical", "horizontal") }
                        else s.options.firstOrNull { it == "off" }
                        if (option != null) return act(s, s.call(option), Shown.Option(option))
                    }
                    is Range -> Unit
                }
            }
            return noSuch
        }

        // Words that aren't a setting at all ("the AC in the kids' room" with no such area) are ignored;
        // a setting the device doesn't have ("the purifier's temperature") is not swapped for another.
        val namesSetting = words.any { w -> Aspect.entries.any { a -> a.words.any { same(w, it) } } }
        if (focused.isEmpty() && words.isNotEmpty() && (strict || namesSetting)) return noSuch
        val pool = focused.ifEmpty { settings }
        return when (value) {
            is SettingValue.Word -> {
                val wanted = valueTokens.filter { it !in settingWords && it !in fillers }
                if (wanted.isEmpty()) return noSuch
                val phrases = listOf(wanted) + synonyms(wanted)
                val choices = pool.filterIsInstance<Choice>()
                // The user's own word before its synonyms, an exact option ("auto" over "heat_cool")
                // before one that contains it ("sleep" in "Sleep mode").
                for (exact in listOf(true, false)) {
                    for (phrase in phrases) {
                        for (s in choices) {
                            val option = s.options.firstOrNull { o -> matches(tokens(o), phrase, exact) }
                            if (option != null) return act(s, s.call(option), Shown.Option(option))
                        }
                    }
                }
                // "High" for a fan that only has a speed in percent.
                val level = level(wanted)
                val range = pool.filterIsInstance<Range>().firstOrNull { it.aspect == Aspect.SPEED || it.aspect == Aspect.BRIGHTNESS || it.aspect == Aspect.VOLUME }
                    ?: pool.filterIsInstance<Range>().singleOrNull()?.takeIf { focused.isNotEmpty() && words.isNotEmpty() }
                if (level != null && range != null) {
                    val target = when (level) {
                        Level.MIN -> range.min.coerceAtLeast(range.step)
                        Level.LOW -> range.min + (range.max - range.min) / 3
                        Level.MEDIUM -> range.min + (range.max - range.min) * 2 / 3
                        Level.HIGH, Level.MAX -> range.max
                    }.let { if (range.unit == NumberUnit.PERCENT) Math.round(it).toDouble() else it }
                    return act(range, range.call(target), Shown.Number(target, range.unit))
                }
                noSuch
            }
            is SettingValue.Number -> {
                if (value.unit == NumberUnit.KELVIN) {
                    // "3000 kelvin" can only be a light's white, whatever else was said.
                    val white = settings.filterIsInstance<Range>().firstOrNull { it.unit == NumberUnit.KELVIN } ?: return noSuch
                    if (value.value < white.min || value.value > white.max) {
                        return Plan.OutOfRange(e.name, white.aspect, white.label, white.min, white.max, white.unit)
                    }
                    return act(white, white.call(value.value), Shown.Number(value.value, NumberUnit.KELVIN))
                }
                val ranges = pool.filterIsInstance<Range>().filter {
                    when (value.unit) {
                        NumberUnit.DEGREES -> it.unit == NumberUnit.DEGREES || it.aspect == Aspect.TEMPERATURE
                        NumberUnit.PERCENT -> it.unit == NumberUnit.PERCENT
                        NumberUnit.KELVIN -> false
                        null -> it.unit != NumberUnit.KELVIN
                    }
                }
                // A bare number is a temperature for anything that has one ("set the AC to 22").
                val temperature = ranges.firstOrNull { it.aspect == Aspect.TEMPERATURE }
                val range = when {
                    value.unit == NumberUnit.PERCENT -> ranges.firstOrNull()
                    temperature != null -> temperature
                    value.unit == null && words.isEmpty() -> numbered(pool, value.value)?.let { return it } ?: ranges.singleOrNull()
                    else -> ranges.firstOrNull()
                } ?: return numbered(pool, value.value) ?: noSuch
                if (value.value < range.min || value.value > range.max) {
                    return Plan.OutOfRange(range.entity.name, range.aspect, range.label, range.min, range.max, range.unit)
                }
                act(range, range.call(value.value), Shown.Number(value.value, range.unit ?: value.unit))
            }
            is SettingValue.Step -> {
                val default = when (e.domain) {
                    "climate", "water_heater" -> Aspect.TEMPERATURE
                    "fan" -> Aspect.SPEED
                    "humidifier" -> Aspect.HUMIDITY
                    "light" -> Aspect.BRIGHTNESS
                    "media_player" -> Aspect.VOLUME
                    else -> Aspect.POSITION
                }
                val s = if (words.isEmpty()) settings.firstOrNull { it.aspect == default } else focused.firstOrNull()
                when (s) {
                    is Range -> {
                        val now = s.current
                        when {
                            now != null -> {
                                val next = (if (value.up) now + s.step else now - s.step).coerceIn(s.min, s.max)
                                act(s, s.call(next), Shown.Number(next, s.unit))
                            }
                            s.stepCall != null -> act(s, s.stepCall.invoke(value.up), Shown.Step(value.up))
                            else -> noSuch
                        }
                    }
                    is Choice -> {
                        // "Turn up the AC fan": the next speed in the device's own order.
                        val steps = s.options.filter { it !in setOf("auto", "off", "on") }
                        val index = steps.indexOf(s.current)
                        if (index < 0) return noSuch
                        val next = steps[(if (value.up) index + 1 else index - 1).coerceIn(0, steps.lastIndex)]
                        act(s, s.call(next), Shown.Option(next))
                    }
                    else -> noSuch
                }
            }
            is SettingValue.Action -> perform(e, value.verb, value.mode)
        }
    }

    /** "Set the fan speed to 3" on a device whose speeds are named "1" to "5", or "Level 1" to "Level 3". */
    private fun numbered(pool: List<Setting>, number: Double): Plan? {
        val text = number.shown()
        for (s in pool.filterIsInstance<Choice>()) {
            val option = s.options.firstOrNull { o -> tokens(o).let { it == listOf(text) || (it.size == 2 && it.last() == text) } }
            if (option != null) return act(s, s.call(option), Shown.Option(option))
        }
        return null
    }

    private fun act(s: Setting, call: Call, shown: Shown) = Plan.Act(listOf(call), listOf(s.entity.name), s.aspect, s.label, shown)

    private fun offer(s: Setting): Offer = when (s) {
        is Choice -> Offer(s.aspect, s.label, options = s.options)
        is Range -> Offer(s.aspect, s.label, min = s.min, max = s.max, unit = s.unit)
        is Toggle -> Offer(s.aspect, s.label, options = listOf("on", "off"))
    }

    private fun matches(option: List<String>, phrase: List<String>, exact: Boolean): Boolean {
        if (option.isEmpty() || phrase.isEmpty()) return false
        return if (exact) option.size == phrase.size && option.zip(phrase).all { (a, b) -> same(a, b) }
        else phrase.all { p -> option.any { same(p, it) } }
    }

    private enum class Level { MIN, LOW, MEDIUM, HIGH, MAX }

    private fun level(words: List<String>): Level? = when (words.joinToString(" ")) {
        "min", "minimum", "lowest" -> Level.MIN
        "low", "slow" -> Level.LOW
        "medium", "mid", "middle" -> Level.MEDIUM
        "high", "fast", "strong" -> Level.HIGH
        "max", "maximum", "full", "highest" -> Level.MAX
        else -> null
    }

    /** What devices call the words people say: "cooling" is "cool", "night" may be "sleep". */
    private val synonymTable: List<Pair<Set<String>, List<List<String>>>> = listOf(
        setOf("cool", "cooling", "cold", "colder") to listOf(listOf("cool")),
        setOf("heat", "heating", "warm", "hot") to listOf(listOf("heat")),
        setOf("dry", "dehumidify", "dehumidifying", "dehumidification") to listOf(listOf("dry")),
        setOf("fan", "ventilation", "ventilate") to listOf(listOf("fan", "only"), listOf("fan")),
        setOf("auto", "automatic") to listOf(listOf("auto"), listOf("heat", "cool")),
        setOf("sleep", "sleeping", "night") to listOf(listOf("sleep"), listOf("night"), listOf("silent")),
        setOf("silent", "quiet") to listOf(listOf("silent"), listOf("quiet"), listOf("sleep")),
        setOf("eco", "economy") to listOf(listOf("eco")),
        setOf("boost", "turbo", "powerful") to listOf(listOf("boost"), listOf("turbo"), listOf("powerful"), listOf("strong")),
        setOf("favorite", "favourite") to listOf(listOf("favorite"), listOf("favourite")),
        setOf("comfort") to listOf(listOf("comfort")),
        setOf("away") to listOf(listOf("away")),
        setOf("home") to listOf(listOf("home")),
        setOf("manual") to listOf(listOf("manual")),
        setOf("low") to listOf(listOf("low")),
        setOf("medium", "mid", "middle") to listOf(listOf("medium"), listOf("middle"), listOf("mid"), listOf("standard"), listOf("balanced")),
        setOf("high") to listOf(listOf("high"), listOf("strong")),
        setOf("max", "maximum", "full") to listOf(listOf("max"), listOf("full"), listOf("high"), listOf("turbo")),
        setOf("min", "minimum") to listOf(listOf("min"), listOf("low")),
        setOf("vertical") to listOf(listOf("vertical")),
        setOf("horizontal") to listOf(listOf("horizontal")),
        setOf("tv", "television") to listOf(listOf("tv"), listOf("live", "tv")),
        setOf("bluetooth", "bt") to listOf(listOf("bluetooth"), listOf("bt")),
        setOf("optical") to listOf(listOf("optical"), listOf("toslink")),
    )

    private fun synonyms(words: List<String>): List<List<String>> {
        val phrase = words.joinToString(" ")
        return synonymTable.filter { (said, _) -> phrase in said || words.any { w -> said.any { same(w, it) } } }.flatMap { it.second }
    }

    private fun Double.shown(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

    // endregion
}
