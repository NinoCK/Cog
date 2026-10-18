package com.bestassistant.automation

import com.bestassistant.automation.HomeAssistant.Area
import com.bestassistant.automation.HomeAssistant.Entity
import com.bestassistant.commands.Asked
import com.bestassistant.commands.Condition
import com.bestassistant.commands.DeviceQuery
import com.bestassistant.commands.Quantity

/**
 * Answers questions about the house from what Home Assistant lists: "what's the temperature in
 * the bedroom" from the bedroom's temperature sensors, "is the front door locked" from the lock,
 * "are any windows open" from the window contacts, "is Anna home" from Anna's person.
 *
 * The device or room is found the way [SettingPlanner] finds one: by name, area and kind, every
 * word said accounted for. A sensor counts as its device's kind too, so "the vacuum's battery" is
 * the battery sensor on the vacuum.
 */
object QueryPlanner {

    /** A device as the answer names it, and its state as Home Assistant has it ("on", "armed_away"). */
    data class Seen(val name: String, val domain: String, val deviceClass: String?, val state: String)

    /** A reading: [value] when it's a number, [text] as Home Assistant has it, and its [unit]. */
    data class Reading(val device: String, val value: Double?, val text: String, val unit: String?)

    sealed interface Answer {
        /** What [quantity] [readings] show, in [area] when the user named one. */
        data class Measured(val quantity: Quantity, val readings: List<Reading>, val area: Area?) : Answer

        /** Which devices are as asked ([yes]) and which aren't ([no]), in [area] when named. */
        data class Checked(val condition: Condition, val yes: List<Seen>, val no: List<Seen>, val area: Area?) : Answer

        /** What a device is doing, and its battery when it has one. */
        data class Status(val device: Seen, val battery: Reading?) : Answer

        /** Nothing fits: left to Assist. */
        data object NotMine : Answer
    }

    private val fillers = setOf(
        "the", "my", "our", "a", "an", "of", "in", "on", "at", "for", "all", "any", "there", "and", "please", "it", "its", "s",
        "sensor", "sensors", "current", "currently", "right", "now", "still",
    )

    /** Words for the whole house: no room in particular. */
    private val everywhere = setOf("inside", "indoors", "house", "home", "flat", "apartment", "everywhere", "place")

    /** Words for everybody Home Assistant knows. */
    private val everybody = setOf("anyone", "anybody", "someone", "somebody", "everyone", "everybody", "people", "family")

    private val kindWords = mapOf(
        "light" to listOf("light", "lights", "lamp", "lamps", "bulb", "bulbs"),
        "switch" to listOf("switch", "switches", "plug", "plugs", "socket", "sockets", "outlet", "outlets"),
        "fan" to listOf("fan", "fans"),
        "cover" to listOf("blind", "blinds", "shutter", "shutters", "curtain", "curtains", "shade", "shades", "awning", "garage", "cover", "covers"),
        "valve" to listOf("valve", "valves", "sprinkler", "sprinklers", "irrigation", "tap", "water"),
        "media_player" to listOf("tv", "tvs", "television", "speaker", "speakers", "soundbar", "receiver", "stereo"),
        "climate" to listOf("ac", "acs", "aircon", "conditioner", "heating", "heater", "thermostat", "climate", "heatpump"),
        "humidifier" to listOf("humidifier", "dehumidifier"),
        "water_heater" to listOf("boiler", "heater"),
        "vacuum" to listOf("vacuum", "hoover", "roomba", "robot"),
        "lawn_mower" to listOf("mower", "lawnmower", "automower", "robot"),
        "lock" to listOf("lock", "locks", "door", "doors", "deadbolt", "latch", "gate"),
        "alarm_control_panel" to listOf("alarm", "alarms", "security", "system"),
        "person" to listOf("person", "people"),
    )

    /** What binary sensors of each class are called. */
    private val classWords = mapOf(
        "door" to listOf("door", "doors"),
        "window" to listOf("window", "windows"),
        "garage_door" to listOf("garage", "door", "doors"),
        "opening" to listOf("door", "doors", "window", "windows", "gate", "gates"),
        "lock" to listOf("lock", "locks", "door"),
        "motion" to listOf("motion", "movement"),
        "occupancy" to listOf("occupancy", "motion", "presence"),
        "presence" to listOf("presence", "motion"),
        "moisture" to listOf("leak", "leaks", "water", "flood"),
        "smoke" to listOf("smoke", "fire"),
        "gas" to listOf("gas"),
        "battery_charging" to listOf("charging", "charger"),
        "running" to listOf("running"),
        "plug" to listOf("plug", "plugged"),
        "power" to listOf("power"),
        "connectivity" to listOf("connection", "online"),
    )

    /** Which kinds of device can answer a question about [condition]. */
    private fun answers(e: Entity, condition: Condition): Boolean {
        val cls = e.attributes["device_class"] as? String
        return when (condition) {
            Condition.ON, Condition.OFF -> e.domain in setOf(
                "light", "switch", "fan", "media_player", "climate", "input_boolean", "humidifier", "remote", "siren", "water_heater",
            ) || e.domain == "binary_sensor"
            Condition.OPEN, Condition.CLOSED -> e.domain in setOf("cover", "valve") ||
                (e.domain == "binary_sensor" && cls in setOf(null, "door", "window", "garage_door", "opening"))
            Condition.LOCKED, Condition.UNLOCKED -> e.domain == "lock" || (e.domain == "binary_sensor" && cls == "lock")
            Condition.ARMED, Condition.DISARMED -> e.domain == "alarm_control_panel"
            Condition.RUNNING -> e.domain in setOf("vacuum", "lawn_mower", "media_player", "fan", "switch") ||
                (e.domain == "binary_sensor" && cls in setOf("running", "moving", "power"))
            Condition.PLAYING -> e.domain == "media_player"
            Condition.CHARGING -> e.domain == "binary_sensor" && cls == "battery_charging"
            Condition.DOCKED -> e.domain in setOf("vacuum", "lawn_mower")
            Condition.HOME -> e.domain == "person"
        }
    }

    /** Whether [e] is as asked; null when its state doesn't say (unknown, unavailable). */
    internal fun holds(e: Entity, condition: Condition): Boolean? {
        val s = e.state
        if (s == "unavailable" || s == "unknown") return null
        val cls = e.attributes["device_class"] as? String
        val on = when (e.domain) {
            "climate", "water_heater" -> s != "off"
            "media_player" -> s !in setOf("off", "standby")
            else -> s == "on"
        }
        return when (condition) {
            Condition.ON -> on
            Condition.OFF -> !on
            Condition.OPEN -> when (e.domain) {
                "binary_sensor" -> s == "on"
                "lock" -> s == "open"
                else -> s == "open" || s == "opening"
            }
            Condition.CLOSED -> holds(e, Condition.OPEN)?.not()
            Condition.LOCKED -> if (e.domain == "binary_sensor" && cls == "lock") s == "off" else s == "locked"
            Condition.UNLOCKED -> holds(e, Condition.LOCKED)?.not()
            Condition.ARMED -> s.startsWith("armed") || s == "triggered"
            Condition.DISARMED -> s == "disarmed"
            Condition.RUNNING -> when (e.domain) {
                "vacuum" -> s == "cleaning"
                "lawn_mower" -> s == "mowing"
                "media_player" -> s == "playing"
                else -> on
            }
            Condition.PLAYING -> s == "playing"
            Condition.CHARGING -> s == "on"
            Condition.DOCKED -> s == "docked"
            Condition.HOME -> s == "home"
        }
    }

    fun answer(query: DeviceQuery, entities: List<Entity>, areas: List<Area>?): Answer {
        val said = SettingPlanner.tokens(query.target).filter { it !in fillers }
        val area = mentionedArea(said, areas.orEmpty())
        val areaWords = area?.let { names(it) }.orEmpty()
        val rest = said.filter { w -> areaWords.none { GroupPlanner.sameWord(w, it) } && w !in everywhere && w !in everybody }
        val usable = entities.filter { !it.isGroup && it.state != "unavailable" && (area == null || it.area == area.id) }
        val onDevice = entities.groupBy { it.device }.filterKeys { it != null }.mapValues { (_, list) -> list.map { it.domain }.toSet() }

        fun words(e: Entity): List<String> {
            val cls = e.attributes["device_class"] as? String
            val siblings = e.device?.let { onDevice[it] }.orEmpty()
            return SettingPlanner.tokens(e.name) + SettingPlanner.tokens(e.deviceName.orEmpty()) +
                (siblings + e.domain).flatMap { kindWords[it].orEmpty() } + (if (e.domain == "binary_sensor") classWords[cls].orEmpty() else emptyList())
        }

        /** The candidates that account for every word left, those whose name says the most first. */
        fun best(candidates: List<Entity>): List<Entity> {
            val scored = candidates.mapNotNull { e ->
                val known = words(e)
                val name = SettingPlanner.tokens(e.name) + SettingPlanner.tokens(e.deviceName.orEmpty())
                if (rest.all { w -> known.any { GroupPlanner.sameWord(w, it) } }) e to rest.count { w -> name.any { GroupPlanner.sameWord(w, it) } } else null
            }
            val top = scored.maxOfOrNull { it.second } ?: return emptyList()
            return scored.filter { it.second == top }.map { it.first }
        }

        return when (val asked = query.asked) {
            is Asked.Measure -> {
                val q = asked.quantity
                val sensors = best(usable.filter { it.domain == "sensor" && (it.attributes["device_class"] as? String) in q.deviceClasses })
                val readings = sensors.mapNotNull(::reading).ifEmpty {
                    // An air conditioner's own thermometer, or a vacuum that reports its battery itself.
                    val key = when (q) {
                        Quantity.TEMPERATURE -> "current_temperature"
                        Quantity.HUMIDITY -> "current_humidity"
                        Quantity.BATTERY -> "battery_level"
                        else -> return Answer.NotMine
                    }
                    best(usable.filter { key in it.attributes && it.number(key) != null }).map { e ->
                        val value = e.number(key)!!
                        Reading(e.name, value, value.toString(), if (q == Quantity.TEMPERATURE) "°" else "%")
                    }
                }
                if (readings.isEmpty()) Answer.NotMine else Answer.Measured(q, readings, area)
            }
            is Asked.State -> {
                val c = asked.condition
                val candidates = usable.filter { answers(it, c) }
                // "Is the front door open": its contact sensor, before its lock.
                var found = best(candidates.filter { it.domain != "lock" || c !in setOf(Condition.OPEN, Condition.CLOSED) })
                if (found.isEmpty()) found = best(candidates)
                val known = found.mapNotNull { e -> holds(e, c)?.let { e to it } }
                if (known.isEmpty()) return Answer.NotMine
                Answer.Checked(c, known.filter { it.second }.map { seen(it.first) }, known.filterNot { it.second }.map { seen(it.first) }, area)
            }
            Asked.Status -> {
                val primaries = usable.filter { it.domain !in setOf("sensor", "binary_sensor", "select", "number", "button", "input_button", "scene", "script") }
                val device = best(primaries).singleOrNull() ?: best(usable).singleOrNull() ?: return Answer.NotMine
                val battery = entities.firstOrNull {
                    it.domain == "sensor" && it.device != null && it.device == device.device && it.attributes["device_class"] == "battery"
                }?.let(::reading) ?: device.number("battery_level")?.let { Reading(device.name, it, it.toString(), "%") }
                Answer.Status(seen(device), battery)
            }
        }
    }

    private fun seen(e: Entity) = Seen(e.name, e.domain, e.attributes["device_class"] as? String, e.state)

    private fun reading(e: Entity): Reading? {
        if (e.state == "unknown" || e.state == "unavailable") return null
        return Reading(e.name, e.state.toDoubleOrNull(), e.state, e.attributes["unit_of_measurement"] as? String)
    }

    private fun names(area: Area) = SettingPlanner.tokens(area.name) + SettingPlanner.tokens(area.id.replace('_', ' '))

    /** The area whose whole name was said ("living room" in "the living room lights"), the longest one if several. */
    private fun mentionedArea(said: List<String>, areas: List<Area>): Area? = areas
        .filter { area ->
            listOf(SettingPlanner.tokens(area.name), SettingPlanner.tokens(area.id.replace('_', ' ')))
                .any { name -> name.isNotEmpty() && name.all { n -> said.any { GroupPlanner.sameWord(it, n) } } }
        }
        .maxByOrNull { SettingPlanner.tokens(it.name).size }
}
