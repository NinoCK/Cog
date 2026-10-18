package com.bestassistant.integrations.homeassistant

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The user's own Home Assistant, reached directly (usually on the home network) with a long-lived
 * access token they created. Requests go to Home Assistant's built-in, rule-based Assist agent,
 * which already knows their devices and areas; it answers with its own fixed sentences. Nothing passes through anyone else's server.
 */
class HomeAssistant(private val address: () -> String, private val token: () -> String) {

    sealed interface Reply {
        /**
         * Home Assistant did it (or answered a question); [speech] is its own sentence, if any, and
         * [targets] the devices it acted on, by name ("Turned on the light" doesn't say which).
         */
        data class Done(val speech: String?, val targets: List<String> = emptyList()) : Reply

        /** It understood the request but couldn't do it ("no device called ..."): [speech] says why. */
        data class Refused(val speech: String?) : Reply

        /** None of its sentences match: not something for Home Assistant. */
        data object NotUnderstood : Reply

        data object NotSetUp : Reply
        data object Unauthorized : Reply
        data object Unreachable : Reply
    }

    val isSetUp: Boolean get() = baseUrl(address()) != null && token().isNotBlank()

    /** Hands [text] to Assist in [language] ("en"). */
    suspend fun process(text: String, language: String): Reply {
        val base = baseUrl(address()) ?: return Reply.NotSetUp
        val key = token().trim().ifEmpty { return Reply.NotSetUp }
        val body = JSONObject().put("text", text).put("language", language)
        // The built-in agent first: the user's own default may be a language model, which PocketSearch avoids.
        var response = post("$base/api/conversation/process", key, JSONObject(body.toString()).put("agent_id", BUILT_IN_AGENT))
        if (response is Http.Status && response.code in setOf(400, 404, 422)) {
            response = post("$base/api/conversation/process", key, body)
        }
        return when (response) {
            is Http.Failed -> Reply.Unreachable
            is Http.Status -> if (response.code == 401 || response.code == 403) Reply.Unauthorized else Reply.Unreachable
            is Http.Ok -> parse(response.body)
        }
    }

    /**
     * A device Home Assistant knows: "light.kitchen_ceiling", called "Kitchen ceiling", in the area
     * with id [area]. [device] groups it with its device's other entities (an air purifier's fan,
     * its mode select, its child lock switch). [attributes] are what it says it can do: an air
     * conditioner's "hvac_modes", "fan_modes", "min_temp", a fan's "preset_modes", "percentage".
     * Lists are lists of strings, numbers are doubles, and a capability with no value yet is null.
     */
    data class Entity(
        val id: String,
        val name: String,
        val area: String?,
        val state: String,
        val isGroup: Boolean = false,
        val device: String? = null,
        val deviceName: String? = null,
        val attributes: Map<String, Any?> = emptyMap(),
    ) {
        val domain: String get() = id.substringBefore('.')

        @Suppress("UNCHECKED_CAST")
        fun list(key: String): List<String> = (attributes[key] as? List<String>).orEmpty()

        fun number(key: String): Double? = attributes[key] as? Double
    }

    data class Area(val id: String, val name: String)

    sealed interface Devices {
        /** [areas] is null when this token can't list them (rendering templates takes an administrator's token). */
        data class Found(val entities: List<Entity>, val areas: List<Area>?) : Devices
        data class Failed(val reply: Reply) : Devices
    }

    /**
     * Every light, switch, fan, cover, media player, thermostat, lock, vacuum and so on, with its
     * area and state, read fresh from Home Assistant: nothing has to be set up in PocketSearch for each
     * device. [sensors] adds sensors, binary sensors and people, for questions about the house.
     */
    suspend fun devices(sensors: Boolean = false): Devices {
        val base = baseUrl(address()) ?: return Devices.Failed(Reply.NotSetUp)
        val key = token().trim().ifEmpty { return Devices.Failed(Reply.NotSetUp) }
        val domains = if (sensors) DOMAINS + SENSOR_DOMAINS else DOMAINS
        when (val response = post("$base/api/template", key, JSONObject().put("template", catalog(domains)))) {
            is Http.Ok -> parseCatalog(response.body)?.let { return it }
            is Http.Failed -> return Devices.Failed(Reply.Unreachable)
            // Not an administrator's token: the plain list of states, without areas.
            is Http.Status -> Unit
        }
        return when (val response = request("$base/api/states", key, null)) {
            is Http.Ok -> parseStates(response.body, domains)?.let { Devices.Found(it, null) } ?: Devices.Failed(Reply.Unreachable)
            is Http.Status -> Devices.Failed(if (response.code == 401 || response.code == 403) Reply.Unauthorized else Reply.Unreachable)
            is Http.Failed -> Devices.Failed(Reply.Unreachable)
        }
    }

    /** Runs [service] of [domain] ("light", "turn_off") on [entityIds], with its [data] ("temperature" to 22). */
    suspend fun call(domain: String, service: String, entityIds: List<String>, data: Map<String, Any> = emptyMap()): Reply {
        val base = baseUrl(address()) ?: return Reply.NotSetUp
        val key = token().trim().ifEmpty { return Reply.NotSetUp }
        val body = JSONObject().put("entity_id", JSONArray(entityIds))
        data.forEach { (name, value) -> body.put(name, value) }
        // Devices can take a while to answer: wait longer than for a question.
        return when (val response = request("$base/api/services/$domain/$service", key, body.toString(), readTimeout = 20_000)) {
            is Http.Ok -> Reply.Done(null)
            is Http.Status -> when (response.code) {
                401, 403 -> Reply.Unauthorized
                400 -> Reply.Refused(null)
                else -> Reply.Unreachable
            }
            is Http.Failed -> Reply.Unreachable
        }
    }

    /**
     * What PocketSearch sees in Home Assistant, for the settings screen: how many devices of each kind
     * ("light" to 7), the air conditioners by name, and how many areas (null: this token can't list them).
     */
    data class Seen(val counts: Map<String, Int>, val airConditioners: List<String>, val areas: Int?)

    suspend fun seen(): Seen? = (devices() as? Devices.Found)?.let(::seen)

    /** For the settings screen: whether the address and token work. */
    suspend fun check(): Reply {
        val base = baseUrl(address()) ?: return Reply.NotSetUp
        val key = token().trim().ifEmpty { return Reply.NotSetUp }
        return when (val response = request("$base/api/", key, null)) {
            is Http.Ok -> Reply.Done(null)
            is Http.Status -> if (response.code == 401 || response.code == 403) Reply.Unauthorized else Reply.Unreachable
            is Http.Failed -> Reply.Unreachable
        }
    }

    private sealed interface Http {
        data class Ok(val body: String) : Http
        data class Status(val code: Int) : Http
        data object Failed : Http
    }

    private suspend fun post(url: String, token: String, body: JSONObject) = request(url, token, body.toString())

    private suspend fun request(url: String, token: String, body: String?, readTimeout: Int = 8_000): Http = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                this.readTimeout = readTimeout
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) return@withContext Http.Status(code)
                Http.Ok(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Home Assistant unreachable", e)
            Http.Failed
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad Home Assistant address", e)
            Http.Failed
        }
    }

    companion object {
        private const val TAG = "HomeAssistant"
        private const val BUILT_IN_AGENT = "conversation.home_assistant"

        /** What PocketSearch can switch, set or choose: the kinds of [DeviceKind], and the settings devices come with. */
        private val DOMAINS = DeviceKind.entries.map { it.domain } + listOf(
            "humidifier", "water_heater", "select", "number", "input_boolean", "script", "scene", "siren", "remote",
            "vacuum", "lawn_mower", "alarm_control_panel", "button", "input_button",
        )

        /** What questions about the house read: "is the window open", "is Anna home". */
        private val SENSOR_DOMAINS = listOf("sensor", "binary_sensor", "person")

        /** The attributes that say what a device can do, and how it's set now. */
        private val CAPABILITIES = listOf(
            "hvac_modes", "fan_modes", "swing_modes", "preset_modes", "available_modes", "operation_list", "options",
            "min_temp", "max_temp", "target_temp_step", "temperature", "min_humidity", "max_humidity", "humidity",
            "percentage", "percentage_step", "oscillating", "min", "max", "step", "unit_of_measurement",
            "supported_color_modes", "current_position", "hvac_mode", "fan_mode", "swing_mode", "preset_mode", "mode",
            "effect_list", "effect", "min_color_temp_kelvin", "max_color_temp_kelvin", "color_temp_kelvin",
            "volume_level", "is_volume_muted", "source_list", "source", "sound_mode_list", "sound_mode",
            "fan_speed_list", "fan_speed", "battery_level", "code_format", "code_arm_required", "supported_features",
            "device_class", "current_temperature", "current_humidity",
        )

        /**
         * Areas, then devices with their area (their own, else their device's), state, whether
         * they're a group, their device, and the capability attributes they have.
         */
        private fun catalog(domains: List<String>) = """
            {%- set ns = namespace(areas=[], entities=[]) -%}
            {%- set keys = [${CAPABILITIES.joinToString { "'$it'" }}] -%}
            {%- for id in areas() -%}{%- set ns.areas = ns.areas + [[id, area_name(id)]] -%}{%- endfor -%}
            {%- for s in states if s.domain in [${domains.joinToString { "'$it'" }}] -%}
            {%- set d = device_id(s.entity_id) -%}
            {%- set a = namespace(pairs=[]) -%}
            {%- for k in keys if k in s.attributes -%}{%- set a.pairs = a.pairs + [[k, s.attributes[k]]] -%}{%- endfor -%}
            {%- set device_name = (device_attr(d, 'name_by_user') or device_attr(d, 'name') or '') if d else '' -%}
            {%- set ns.entities = ns.entities + [[s.entity_id, s.name, area_id(s.entity_id) or '', s.state,
                s.attributes.entity_id is defined, d or '', device_name, a.pairs]] -%}
            {%- endfor -%}
            {{ {'areas': ns.areas, 'entities': ns.entities} | tojson }}
        """.trimIndent()

        /** "homeassistant.local:8123/" -> "http://homeassistant.local:8123"; null when it isn't an address. */
        fun baseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "http://$trimmed"
            val host = withScheme.substringAfter("://").substringBefore('/').substringBefore(':')
            return withScheme.takeIf { host.isNotEmpty() }
        }

        internal fun parseCatalog(body: String): Devices.Found? {
            val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
            val areas = json.optJSONArray("areas") ?: return null
            val entities = json.optJSONArray("entities") ?: return null
            return Devices.Found(
                entities = (0 until entities.length()).mapNotNull { i ->
                    val row = entities.optJSONArray(i) ?: return@mapNotNull null
                    val pairs = row.optJSONArray(7)
                    Entity(
                        id = row.optString(0),
                        name = row.optString(1),
                        area = row.optString(2).takeIf { it.isNotEmpty() && it != "null" },
                        state = row.optString(3),
                        isGroup = row.optBoolean(4),
                        device = row.optString(5).takeIf { it.isNotEmpty() && it != "null" },
                        deviceName = row.optString(6).takeIf { it.isNotEmpty() && it != "null" },
                        attributes = buildMap {
                            for (p in 0 until (pairs?.length() ?: 0)) {
                                val pair = pairs?.optJSONArray(p) ?: continue
                                put(pair.optString(0), value(pair.opt(1)))
                            }
                        },
                    )
                },
                areas = (0 until areas.length()).mapNotNull { i ->
                    val row = areas.optJSONArray(i) ?: return@mapNotNull null
                    Area(row.optString(0), row.optString(1))
                },
            )
        }

        internal fun parseStates(body: String, domains: List<String> = DOMAINS): List<Entity>? {
            val states = runCatching { JSONArray(body) }.getOrNull() ?: return null
            return (0 until states.length()).mapNotNull { i ->
                val state = states.optJSONObject(i) ?: return@mapNotNull null
                val id = state.optString("entity_id")
                if (id.substringBefore('.') !in domains) return@mapNotNull null
                val attributes = state.optJSONObject("attributes")
                Entity(
                    id = id,
                    name = attributes?.optString("friendly_name")?.takeIf { it.isNotEmpty() } ?: id.substringAfter('.').replace('_', ' '),
                    area = null,
                    state = state.optString("state"),
                    isGroup = attributes?.has("entity_id") == true,
                    attributes = CAPABILITIES.filter { attributes?.has(it) == true }.associateWith { value(attributes?.opt(it)) },
                )
            }
        }

        internal fun seen(found: Devices.Found): Seen {
            val usable = found.entities.filter { !it.isGroup && it.state != "unavailable" }
            val counts = usable.groupingBy { it.domain }.eachCount().toList().sortedByDescending { it.second }.toMap()
            val coolers = usable.filter { it.domain == "climate" && it.list("hvac_modes").let { modes -> modes.isEmpty() || "cool" in modes } }
            return Seen(counts, coolers.map { it.name }, found.areas?.size)
        }

        /** A JSON attribute as [Entity.attributes] keeps it. */
        private fun value(json: Any?): Any? = when (json) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until json.length()).map { json.optString(it) }
            is Number -> json.toDouble()
            is Boolean -> json
            else -> json.toString()
        }

        /** Reads Assist's answer: what it said, and whether it did anything. */
        internal fun parse(body: String): Reply {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return Reply.Unreachable
            val response = json.optJSONObject("response") ?: return Reply.Unreachable
            val speech = response.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
                ?.trim()?.takeIf { it.isNotEmpty() }
            return when (response.optString("response_type")) {
                "error" -> when (response.optJSONObject("data")?.optString("code")) {
                    "no_intent_match" -> Reply.NotUnderstood
                    else -> Reply.Refused(speech)
                }
                else -> {
                    val success = response.optJSONObject("data")?.optJSONArray("success")
                    val targets = (0 until (success?.length() ?: 0)).mapNotNull { i ->
                        success?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                    }
                    Reply.Done(speech, targets)
                }
            }
        }
    }
}
