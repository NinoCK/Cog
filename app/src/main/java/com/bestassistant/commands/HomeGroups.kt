package com.bestassistant.commands

/**
 * Requests for a whole group of Home Assistant devices: "turn off all the lights", "kitchen lights
 * on", "close the blinds in the bedroom".
 *
 * A request counts as a group when it says "all" or names the devices in the plural; "turn on the
 * kitchen light" is one device, and stays with Home Assistant's own Assist. The area is kept as
 * said; which of Home Assistant's areas it means is only known once its list is fetched.
 */
object HomeGroups {

    private enum class Verb { ON, OFF, OPEN, CLOSE, RAISE, LOWER, LOCK }

    private data class Noun(val kind: DeviceKind, val plural: Boolean)

    private fun action(verb: Verb, kind: DeviceKind?): HomeAction? = when (kind) {
        DeviceKind.COVER, DeviceKind.VALVE -> when (verb) {
            Verb.OPEN, Verb.RAISE -> HomeAction.OPEN
            Verb.CLOSE, Verb.LOWER -> HomeAction.CLOSE
            else -> null
        }
        DeviceKind.LOCK -> HomeAction.LOCK.takeIf { verb == Verb.LOCK }
        // Everything at once is only ever switched off.
        null -> HomeAction.OFF.takeIf { verb == Verb.OFF }
        else -> when (verb) {
            Verb.ON -> HomeAction.ON
            Verb.OFF -> HomeAction.OFF
            else -> null
        }
    }

    private val nouns: List<Pair<String, Noun>> = buildList {
        fun add(kind: DeviceKind, plural: List<String>, singular: List<String>) {
            plural.forEach { add(it to Noun(kind, true)) }
            singular.forEach { add(it to Noun(kind, false)) }
        }
        add(DeviceKind.LIGHT, listOf("lights", "lamps", "bulbs", "light bulbs"), listOf("light", "lamp", "bulb"))
        add(DeviceKind.SWITCH, listOf("switches", "plugs", "smart plugs", "sockets", "outlets"), listOf("switch", "plug", "socket", "outlet"))
        add(DeviceKind.FAN, listOf("fans"), listOf("fan"))
        add(
            DeviceKind.COVER,
            listOf("blinds", "shutters", "roller shutters", "curtains", "shades", "covers", "rollers", "awnings", "garage doors"),
            listOf("blind", "shutter", "curtain", "shade", "cover"),
        )
        add(DeviceKind.MEDIA, listOf("tvs", "televisions", "speakers", "media players"), listOf("tv", "television", "speaker", "media player"))
        add(
            DeviceKind.CLIMATE,
            listOf("air conditioners", "air conditioning", "acs", "heaters", "heating", "thermostats", "heat pumps", "radiators"),
            listOf("air conditioner", "heater", "thermostat", "heat pump", "radiator"),
        )
        add(DeviceKind.LOCK, listOf("locks", "doors"), listOf("lock", "door"))
        add(DeviceKind.VALVE, listOf("valves", "sprinklers", "water valves"), listOf("valve", "sprinkler", "water valve"))
    }.sortedByDescending { it.first.length }

    private val verbs = mapOf(
        "on" to Verb.ON, "off" to Verb.OFF, "open" to Verb.OPEN, "close" to Verb.CLOSE, "shut" to Verb.CLOSE,
        "raise" to Verb.RAISE, "lower" to Verb.LOWER, "lock" to Verb.LOCK,
    )

    private val switchFirst = Regex("^(?:turn|switch|power|shut) (on|off) (.+)$")
    private val switchLast = Regex("^(?:turn|switch|power|shut) (.+?) (on|off)((?: (?:in|at|throughout|across) .+)?)$")
    private val verbFirst = Regex("^(open|close|shut|raise|lower|lock) (.+)$")
    private val bare = Regex("^(.+?) (on|off)((?: (?:in|at|throughout|across) .+)?)$")
    private val inArea = Regex("^(.+?) (?:in|at|throughout|across) (?:the |my |our )?(.+)$")
    private val leads = listOf("all of the ", "all of my ", "all the ", "all my ", "all ", "every ", "the ", "my ", "our ")
    private val everything = setOf("everything", "all", "all devices", "devices", "every device", "everything else")
    private val everywhere = setOf(
        "everywhere", "house", "the house", "whole house", "the whole house", "entire house", "home", "the home", "whole home",
        "flat", "apartment", "all rooms", "every room",
    )

    fun parse(text: String): HomeGroup? {
        val (verbWord, said) = switchFirst.matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: switchLast.matchEntire(text)?.let { it.groupValues[2] to it.groupValues[1] + it.groupValues[3] }
            ?: verbFirst.matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: bare.matchEntire(text)?.let { it.groupValues[2] to it.groupValues[1] + it.groupValues[3] }
            ?: return null
        val verb = verbs[verbWord] ?: return null
        var phrase = said.trim().removeSuffix(" everywhere")
        var area: String? = null
        inArea.matchEntire(phrase)?.let { m ->
            phrase = m.groupValues[1]
            area = m.groupValues[2].takeUnless { it in everywhere }
        }
        var all = false
        var stripped = true
        while (stripped) {
            stripped = false
            for (lead in leads) {
                if (phrase.startsWith(lead)) {
                    phrase = phrase.removePrefix(lead)
                    all = all || lead.startsWith("all") || lead.startsWith("every")
                    stripped = true
                }
            }
        }
        if (phrase in everything) return action(verb, null)?.let { HomeGroup(it, null, area) }
        val (word, noun) = nouns.firstOrNull { (word, _) -> phrase == word || phrase.endsWith(" $word") } ?: return null
        if (!noun.plural && !all) return null
        // "kitchen lights": the words before the devices name the area.
        val before = phrase.removeSuffix(word).trim()
        if (before.isNotEmpty()) {
            if (area != null) return null
            area = before
        }
        val action = action(verb, noun.kind) ?: return null
        return HomeGroup(action, noun.kind, area)
    }
}
