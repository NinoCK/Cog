package com.bestassistant.integration

import android.content.Context
import androidx.annotation.DrawableRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One outside service Cog can work with (Home Assistant, Spotify), as a module of its own. The
 * user installs it from Cog's settings, where its [settings] then appear; until then Cog neither
 * understands its requests nor offers anything to it.
 *
 * An integration adds requests to what Cog understands ([parser], [plan], [card]), may take the
 * spoken requests nothing else understood ([fallback]), and may be the way Cog plays music in an
 * app ([music]). Everything else it needs from Cog comes through its [IntegrationHost].
 */
interface Integration {

    /** Stable and never shown ("home_assistant"): it keys the integration's settings and whether it's installed. */
    val id: String

    /** Its name in settings: "Home Assistant". */
    val name: String

    /** One line on what it adds, shown in the list of integrations. */
    val description: String

    /** A 24dp vector icon, tinted like the rest of the settings. */
    @get:DrawableRes
    val icon: Int

    /** How it's set up now ("Connected", "Not set up"), under its name in settings. */
    val summary: Flow<String>

    /** Its settings page, top to bottom. */
    fun settings(): List<Setting>

    /** Finds its requests in what the user said; null when it adds none. */
    val parser: RequestParser? get() = null

    /** Carries out a [request] its [parser] found. */
    fun plan(request: Request): Step = Step.Failed("")

    /** How [request] shows in the results before it runs. */
    fun card(request: Request): Card? = null

    /**
     * Whether [fallback] may be offered the spoken requests nothing else understood: true while
     * it's set up to take them.
     */
    val takesUnknown: Boolean get() = false

    /** A spoken request nothing understood ([said], as heard); null when it doesn't understand it either. */
    suspend fun fallback(said: String): Step? = null

    /** The music app it starts music in, when it's one. */
    val music: MusicService? get() = null
}

/**
 * Finds an integration's requests in what the user said. Cog asks at two points in its own order
 * of matching ([Slot]), always with [text] already normalised: lowercase, without "please" and the like.
 */
fun interface RequestParser {
    fun parse(text: String, slot: Slot): Request?
}

/** Where in Cog's own order of matching an integration is asked about what was said. */
enum class Slot {
    /**
     * Right after the phone's own controls (timers, alarms, the torch, volume, play and pause),
     * before music, weather, the clock and the rest: for requests that read like those
     * ("put the AC on cool" is no song, "what's the temperature in the bedroom" no weather).
     */
    EARLY,

    /** After everything Cog does itself, just before it searches the web for what was said. */
    LATE,
}

/** A request an integration found; its own data class, which only it reads. */
interface Request

/** A request's row in the results: what will happen. */
data class Card(val title: String, val subtitle: String)

/** What an integration may use of Cog. */
interface IntegrationHost {
    /** The application. */
    val context: Context

    /** Resources in the assistant's language, for what Cog says and shows. */
    val strings: Context

    /** Lives as long as the app, for work that must outlive a screen. */
    val scope: CoroutineScope

    /** Where the integration with [id] keeps its settings. */
    fun store(id: String): SettingsStore

    /** True when [text] is a request of its own: in answer to a question, a new request rather than an answer. */
    fun isRequest(text: String): Boolean

    /** [text] the way requests are matched: lowercase, without "please" and the like. */
    fun normalize(text: String): String
}

/** An integration's own settings, kept on the phone. Secrets are left out of backups. */
interface SettingsStore {
    fun get(key: String, secret: Boolean = false): String
    fun set(key: String, value: String, secret: Boolean = false)

    /** Changes with every [set], for what depends on the settings. */
    val changes: StateFlow<Int>
}
