package com.bestassistant.commands

import java.time.ZoneId
import java.util.Locale

/**
 * Offline place -> time zone lookup. Every IANA zone city works ("tokyo", "new york",
 * "buenos aires"), plus aliases for big cities and countries that aren't zone names.
 */
object WorldClock {

    data class Place(val zone: ZoneId, val name: String)

    private val aliases = mapOf(
        "utc" to "UTC", "gmt" to "GMT", "zulu" to "UTC",
        "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles", "pacific" to "America/Los_Angeles",
        "mst" to "America/Denver", "mountain" to "America/Denver",
        "cst" to "America/Chicago", "central" to "America/Chicago",
        "est" to "America/New_York", "edt" to "America/New_York", "eastern" to "America/New_York",
        "cet" to "Europe/Paris", "cest" to "Europe/Paris", "ist" to "Asia/Kolkata", "jst" to "Asia/Tokyo",
        "aest" to "Australia/Sydney",
        "san francisco" to "America/Los_Angeles", "sf" to "America/Los_Angeles", "la" to "America/Los_Angeles",
        "seattle" to "America/Los_Angeles", "portland" to "America/Los_Angeles", "las vegas" to "America/Los_Angeles",
        "san diego" to "America/Los_Angeles", "california" to "America/Los_Angeles",
        "nyc" to "America/New_York", "boston" to "America/New_York", "washington" to "America/New_York",
        "washington dc" to "America/New_York", "miami" to "America/New_York", "atlanta" to "America/New_York",
        "philadelphia" to "America/New_York", "dallas" to "America/Chicago", "houston" to "America/Chicago",
        "austin" to "America/Chicago", "texas" to "America/Chicago", "salt lake city" to "America/Denver",
        "montreal" to "America/Toronto", "ottawa" to "America/Toronto", "rio" to "America/Sao_Paulo",
        "rio de janeiro" to "America/Sao_Paulo", "beijing" to "Asia/Shanghai", "china" to "Asia/Shanghai",
        "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata",
        "bangalore" to "Asia/Kolkata", "bengaluru" to "Asia/Kolkata", "india" to "Asia/Kolkata",
        "japan" to "Asia/Tokyo", "osaka" to "Asia/Tokyo", "korea" to "Asia/Seoul", "south korea" to "Asia/Seoul",
        "hanoi" to "Asia/Bangkok", "vietnam" to "Asia/Ho_Chi_Minh", "thailand" to "Asia/Bangkok",
        "philippines" to "Asia/Manila", "indonesia" to "Asia/Jakarta", "bali" to "Asia/Makassar",
        "uk" to "Europe/London", "england" to "Europe/London", "britain" to "Europe/London",
        "scotland" to "Europe/London", "edinburgh" to "Europe/London", "manchester" to "Europe/London",
        "ireland" to "Europe/Dublin", "france" to "Europe/Paris", "germany" to "Europe/Berlin",
        "munich" to "Europe/Berlin", "frankfurt" to "Europe/Berlin", "hamburg" to "Europe/Berlin",
        "spain" to "Europe/Madrid", "barcelona" to "Europe/Madrid", "italy" to "Europe/Rome",
        "milan" to "Europe/Rome", "venice" to "Europe/Rome", "florence" to "Europe/Rome",
        "netherlands" to "Europe/Amsterdam", "holland" to "Europe/Amsterdam", "belgium" to "Europe/Brussels",
        "switzerland" to "Europe/Zurich", "geneva" to "Europe/Zurich", "austria" to "Europe/Vienna",
        "portugal" to "Europe/Lisbon", "porto" to "Europe/Lisbon", "greece" to "Europe/Athens",
        "turkey" to "Europe/Istanbul", "ankara" to "Europe/Istanbul", "russia" to "Europe/Moscow",
        "st petersburg" to "Europe/Moscow", "poland" to "Europe/Warsaw", "krakow" to "Europe/Warsaw",
        "sweden" to "Europe/Stockholm", "norway" to "Europe/Oslo", "denmark" to "Europe/Copenhagen",
        "finland" to "Europe/Helsinki", "ukraine" to "Europe/Kyiv", "kiev" to "Europe/Kyiv",
        "cyprus" to "Asia/Nicosia", "limassol" to "Asia/Nicosia", "larnaca" to "Asia/Nicosia",
        "israel" to "Asia/Jerusalem", "tel aviv" to "Asia/Jerusalem", "uae" to "Asia/Dubai",
        "abu dhabi" to "Asia/Dubai", "egypt" to "Africa/Cairo", "south africa" to "Africa/Johannesburg",
        "cape town" to "Africa/Johannesburg", "kenya" to "Africa/Nairobi", "nigeria" to "Africa/Lagos",
        "australia" to "Australia/Sydney", "canberra" to "Australia/Sydney", "new zealand" to "Pacific/Auckland",
        "wellington" to "Pacific/Auckland", "hawaii" to "Pacific/Honolulu", "brazil" to "America/Sao_Paulo",
        "argentina" to "America/Argentina/Buenos_Aires", "mexico" to "America/Mexico_City",
        "canada" to "America/Toronto", "usa" to "America/New_York", "us" to "America/New_York",
        "america" to "America/New_York", "singapore" to "Asia/Singapore",
    )

    private val abbreviations = setOf(
        "utc", "gmt", "pst", "pdt", "mst", "cst", "est", "edt", "cet", "cest", "ist", "jst", "aest",
        "sf", "la", "nyc", "uk", "uae", "usa", "us",
    )

    /** Zone ids keyed by their city, e.g. "new york" -> America/New_York. */
    private val cities: Map<String, String> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .associateBy { it.substringAfterLast('/').replace('_', ' ').lowercase(Locale.ROOT) }
    }

    fun lookup(raw: String): Place? {
        val key = raw.trim().lowercase(Locale.ROOT)
            .removePrefix("the ")
            .replace(Regex("[.,]"), "")
            .replace(Regex("\\s+"), " ")
        if (key.isEmpty()) return null
        val id = aliases[key] ?: cities[key] ?: return null
        val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return null
        return Place(zone, displayName(raw.trim()))
    }

    private fun displayName(raw: String): String {
        val key = raw.lowercase(Locale.ROOT)
        if (key in abbreviations) return key.uppercase(Locale.ROOT)
        return key.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }
}
