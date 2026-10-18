package com.bestassistant.integrations.homeassistant

import com.bestassistant.integrations.homeassistant.HomeAssistant.Area
import com.bestassistant.integrations.homeassistant.HomeAssistant.Entity

/**
 * Turns "turn off all the lights in the kitchen" into Home Assistant service calls, from the
 * devices and areas Home Assistant itself lists, so a new light works without touching Cog.
 * Only devices that need it are switched, so the count said back is what actually changed.
 */
object GroupPlanner {

    sealed interface Plan {
        /** [count] devices to change, in [area] if the user named one. */
        data class Act(val calls: List<Call>, val count: Int, val area: Area?) : Plan

        /** They're all already that way. */
        data class AlreadyDone(val area: Area?) : Plan

        data class NoDevices(val area: Area?) : Plan

        /** An area Home Assistant doesn't have (or can't list for this token): left to Assist. */
        data object UnknownArea : Plan
    }

    data class Call(val domain: String, val service: String, val entityIds: List<String>, val data: Map<String, Any> = emptyMap())

    /** "Turn off everything": what's on and safe to switch off together. Never switches or locks. */
    private val EVERYTHING = listOf(DeviceKind.LIGHT, DeviceKind.FAN, DeviceKind.MEDIA)

    fun plan(group: HomeGroup, entities: List<Entity>, areas: List<Area>?): Plan {
        val area = group.area?.let { said -> areas?.let { findArea(said, it) } ?: return Plan.UnknownArea }
        val domains = (group.kind?.let(::listOf) ?: EVERYTHING).map { it.domain }.toSet()
        val devices = entities.filter { entity ->
            entity.domain in domains && !entity.isGroup && entity.state != "unavailable" && (area == null || entity.area == area.id)
        }
        if (devices.isEmpty()) return Plan.NoDevices(area)
        val changing = devices.filter { needsChange(it, group.action) }
        if (changing.isEmpty()) return Plan.AlreadyDone(area)
        val calls = changing.groupBy { it.domain }.map { (domain, list) -> Call(domain, service(group.action, domain), list.map { it.id }) }
        return Plan.Act(calls, changing.size, area)
    }

    private fun service(action: HomeAction, domain: String) = when (action) {
        HomeAction.ON -> "turn_on"
        HomeAction.OFF -> "turn_off"
        HomeAction.OPEN -> if (domain == "valve") "open_valve" else "open_cover"
        HomeAction.CLOSE -> if (domain == "valve") "close_valve" else "close_cover"
        HomeAction.LOCK -> "lock"
    }

    private fun needsChange(entity: Entity, action: HomeAction): Boolean = when (action) {
        HomeAction.ON -> entity.state == "off" || entity.state == "unknown" || entity.state == "standby"
        HomeAction.OFF -> entity.state != "off" && entity.state != "standby"
        HomeAction.OPEN -> entity.state != "open" && entity.state != "opening"
        HomeAction.CLOSE -> entity.state != "closed" && entity.state != "closing"
        HomeAction.LOCK -> entity.state != "locked" && entity.state != "locking"
    }

    // region Areas

    private val fillers = setOf("the", "my", "our", "room", "area")

    private fun tokens(text: String): List<String> = Accents.fold(text.lowercase())
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(' ')
        .filter { it.isNotEmpty() && it !in fillers }

    /** Same word, allowing for endings: "kitchen's" is "kitchen", "bedrooms" is "bedroom". */
    internal fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val shared = a.zip(b).takeWhile { (x, y) -> x == y }.size
        val shorter = minOf(a.length, b.length)
        return shared >= 3 && shared >= shorter - 1 && maxOf(a.length, b.length) - shorter <= 3
    }

    /**
     * The area the user meant: one whose name has exactly their words, else the only one whose
     * name has all of them ("bedroom" for "Master bedroom" when there's no plain "Bedroom").
     * Its id counts as a name too, so "kitchen" finds "Cucina" when its id is "kitchen".
     */
    fun findArea(said: String, areas: List<Area>): Area? {
        val words = tokens(said).ifEmpty { return null }
        fun names(area: Area) = listOf(tokens(area.name), tokens(area.id.replace('_', ' '))).filter { it.isNotEmpty() }
        val covering = areas.filter { area -> names(area).any { name -> words.all { w -> name.any { sameWord(w, it) } } } }
        val exact = covering.filter { area ->
            names(area).any { name -> words.all { w -> name.any { sameWord(w, it) } } && name.all { n -> words.any { sameWord(it, n) } } }
        }
        return exact.singleOrNull() ?: covering.singleOrNull()
    }

    // endregion
}
