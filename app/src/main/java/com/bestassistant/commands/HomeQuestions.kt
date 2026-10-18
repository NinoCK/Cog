package com.bestassistant.commands

/**
 * Questions about the house: "what's the temperature in the bedroom", "how humid is the bathroom",
 * "what's the vacuum's battery", "is the front door locked", "are any windows open", "is Anna
 * home", "what's the washing machine doing".
 *
 * Only claimed when they name a room or a device, so "what's the temperature in London" stays the
 * weather and "is the bakery open" a search. Which sensor or device answers is decided later,
 * against what Home Assistant lists.
 */
object HomeQuestions {

    private val quantities: List<Pair<String, Quantity>> =
        Quantity.entries.flatMap { q -> q.words.map { it to q } }.sortedByDescending { it.first.length }
    private val quantityWords = quantities.joinToString("|") { Regex.escape(it.first) }

    private val measureOf = Regex(
        "^(?:what(?:'s| is| are) |tell me |check |show me |give me )?(?:the |my )?(?:current )?($quantityWords)(?: level| reading)? " +
            "(?:in|of|at|on|for|inside|from) (?:the |my |our )?(.+)$",
    )
    private val measureAfter = Regex(
        "^(?:what(?:'s| is| are) |tell me |check |show me |give me )?(?:the |my |our )?(.+?)(?:'s|s')? (?:current )?($quantityWords)(?: level| reading)?$",
    )
    private val howWarm = Regex("^how (hot|warm|cold|cool|humid|bright|dark) is (?:it )?(?:in )?(?:the |my |our )?(.+)$")
    private val howMuch = Regex(
        "^how much (battery|charge|power|energy) (?:does|is|has) (?:the |my |our )?(.+?) " +
            "(?:have|got|has|left|using|use|drawing|draw|consuming|consume|used)(?: left| right now| now| today)?$",
    )
    private val isState = Regex(
        "^(?:is|are) (?:the |my |our |all (?:of )?(?:the |my )?|any (?:of )?(?:the |my )?)?(.+?) (?:still |currently |now )?" +
            "(on|off|open|opened|closed|shut|locked|unlocked|armed|disarmed|running|playing|charging|docked|home|at home|cleaning|mowing)" +
            "((?: (?:in|at|on) (?:the |my |our )?.+)?)$",
    )
    private val isThere = Regex(
        "^(?:is|are) there (?:any |a |an )?(.+?) (on|open|opened|unlocked|running|playing)((?: (?:in|at|on) (?:the |my |our )?.+)?)$",
    )
    private val status = Regex("^(?:what(?:'s| is) )?(?:the )?(?:status|state) of (?:the |my |our )?(.+)$")
    private val doing = Regex("^what(?:'s| is) (?:the |my |our )?(.+?) (?:doing|up to)$")
    private val statusAfter = Regex("^(?:what(?:'s| is) )?(?:the |my |our )?(.+?)(?:'s)? (?:status|state)$")

    fun parse(text: String): DeviceQuery? {
        measureOf.matchEntire(text)?.let { m -> return measure(m.groupValues[2], quantity(m.groupValues[1])) }
        measureAfter.matchEntire(text)?.let { m ->
            // "Lower the bedroom temperature" is a request, not a question.
            if (m.groupValues[1].substringBefore(' ') in verbs) return null
            return measure(m.groupValues[1], quantity(m.groupValues[2]))
        }
        howWarm.matchEntire(text)?.let { m ->
            val quantity = when (m.groupValues[1]) {
                "humid" -> Quantity.HUMIDITY
                "bright", "dark" -> Quantity.ILLUMINANCE
                else -> Quantity.TEMPERATURE
            }
            return measure(m.groupValues[2], quantity)
        }
        howMuch.matchEntire(text)?.let { m ->
            val quantity = when (m.groupValues[1]) {
                "battery", "charge" -> Quantity.BATTERY
                "power" -> Quantity.POWER
                else -> Quantity.ENERGY
            }
            return measure(m.groupValues[2], quantity)
        }
        (isThere.matchEntire(text) ?: isState.matchEntire(text))?.let { m ->
            val condition = condition(m.groupValues[2]) ?: return null
            val target = (m.groupValues[1] + m.groupValues[3]).trim()
            // "Is Anna home": anyone Home Assistant knows, by name; anything else must sound like the house.
            if (condition != Condition.HOME && !mentionsHouse(target)) return null
            if (target in notTargets) return null
            return DeviceQuery(target, Asked.State(condition))
        }
        (status.matchEntire(text) ?: doing.matchEntire(text) ?: statusAfter.matchEntire(text))?.let { m ->
            val target = m.groupValues[1].trim()
            return DeviceQuery(target, Asked.Status).takeIf { mentionsHouse(target) && target !in notTargets }
        }
        return null
    }

    private fun quantity(said: String): Quantity = quantities.first { it.first == said }.second

    private fun measure(target: String, quantity: Quantity): DeviceQuery? {
        val said = target.trim()
        if (said.isEmpty() || said in notTargets) return null
        // Outside is the weather's; a city is too.
        if (said in outside || !mentionsHouse(said)) return null
        return DeviceQuery(said, Asked.Measure(quantity))
    }

    private fun condition(said: String): Condition? = when (said) {
        "on" -> Condition.ON
        "off" -> Condition.OFF
        "open", "opened" -> Condition.OPEN
        "closed", "shut" -> Condition.CLOSED
        "locked" -> Condition.LOCKED
        "unlocked" -> Condition.UNLOCKED
        "armed" -> Condition.ARMED
        "disarmed" -> Condition.DISARMED
        "running", "cleaning", "mowing" -> Condition.RUNNING
        "playing" -> Condition.PLAYING
        "charging" -> Condition.CHARGING
        "docked" -> Condition.DOCKED
        "home", "at home" -> Condition.HOME
        else -> null
    }

    private val verbs = setOf(
        "set", "turn", "lower", "raise", "increase", "decrease", "reduce", "change", "make", "put", "adjust", "bump", "crank",
        "drop", "boost", "switch", "start", "stop",
    )
    private val outside = setOf("outside", "out", "outdoors", "here", "today", "tomorrow", "now")
    private val notTargets = setOf("it", "this", "that", "phone", "my phone", "the phone", "screen", "battery", "wifi", "bluetooth")

    /** Rooms and things in a house, as people name them. */
    private val houseWords = setOf(
        // Rooms.
        "room", "rooms", "bedroom", "bedrooms", "kitchen", "living", "lounge", "office", "study", "bathroom", "toilet", "wc",
        "hall", "hallway", "corridor", "landing", "stairs", "nursery", "kids", "dining", "garage", "basement", "cellar",
        "attic", "loft", "laundry", "utility", "pantry", "den", "playroom", "guest", "closet", "greenhouse", "shed",
        "conservatory", "porch", "balcony", "terrace", "patio", "garden", "yard", "pool", "inside", "indoors", "house",
        "home", "upstairs", "downstairs", "flat", "apartment", "entrance", "workshop", "gym",
        // Devices.
        "door", "doors", "window", "windows", "gate", "gates", "light", "lights", "lamp", "lamps", "tv", "television",
        "speaker", "speakers", "ac", "aircon", "conditioner", "heater", "heating", "thermostat", "radiator", "boiler", "fan",
        "fans", "purifier", "humidifier", "dehumidifier", "blinds", "blind", "shutters", "curtains", "lock", "locks",
        "alarm", "security", "vacuum", "robot", "roomba", "mower", "lawnmower", "valve", "sprinkler", "sprinklers",
        "washer", "washing", "dryer", "dishwasher", "oven", "fridge", "freezer", "fridges", "sensor", "sensors",
        "plug", "plugs", "socket", "switch", "switches", "charger", "car", "ev", "solar", "panels", "inverter",
        "aquarium", "tank", "printer", "motion", "smoke", "leak", "water", "doorbell",
    )

    private fun mentionsHouse(target: String): Boolean =
        Accents.fold(target.lowercase()).split(' ', '-').map { it.removeSuffix("'s") }.any { it in houseWords }
}
