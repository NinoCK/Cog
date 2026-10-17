package com.fastsearch.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.bestassistant.language.AssistantLanguage
import com.bestassistant.speech.VoiceGender
import com.bestassistant.speech.VoiceStyle
import com.fastsearch.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class SearchEngine(val id: String, @param:StringRes val label: Int, private val template: String) {
    DUCKDUCKGO("duckduckgo", R.string.engine_duckduckgo, "https://duckduckgo.com/?q=%s"),
    GOOGLE("google", R.string.engine_google, "https://www.google.com/search?q=%s"),
    BRAVE("brave", R.string.engine_brave, "https://search.brave.com/search?q=%s"),
    STARTPAGE("startpage", R.string.engine_startpage, "https://www.startpage.com/do/search?q=%s"),
    BING("bing", R.string.engine_bing, "https://www.bing.com/search?q=%s");

    fun searchUrl(query: String): String = template.replace("%s", Uri.encode(query))

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class UnitSystem {
    AUTO, METRIC, IMPERIAL;

    /** Fahrenheit and mph; AUTO follows the countries that still use them. */
    fun isImperial(locale: Locale = Locale.getDefault()): Boolean = when (this) {
        METRIC -> false
        IMPERIAL -> true
        AUTO -> locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")
    }
}

data class AppSettings(
    val webSuggestions: Boolean = true,
    val instantAnswers: Boolean = true,
    val searchContacts: Boolean = true,
    val suggestedApps: Boolean = true,
    val blurBackground: Boolean = true,
    val dynamicColor: Boolean = true,
    val engine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val weather: Boolean = true,
    val units: UnitSystem = UnitSystem.AUTO,
    /** Run a spoken command as soon as it's recognised, instead of waiting for Enter. */
    val runSpokenCommands: Boolean = true,
    /** Answer "what is ..." from Wikipedia. */
    val wikipedia: Boolean = true,
    val speech: SpeechMode = SpeechMode.ALWAYS,
    val language: AssistantLanguage = AssistantLanguage.SYSTEM,
    val voiceStyle: VoiceStyle = VoiceStyle.COG,
    /** Cog has a man's voice, so male by default. */
    val voiceGender: VoiceGender = VoiceGender.MALE,
    /** A text-to-speech voice name; null picks the best installed voice for the language. */
    val systemVoice: String? = null,
    /** Start listening as soon as the overlay opens (typing stops it). */
    val listenOnOpen: Boolean = true,
    /** Listen for [wakePhrase] in the background and open the assistant when it's heard. */
    val wakeWord: Boolean = false,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    /** The user's own Spotify developer Client ID; empty when they haven't set one up. */
    val spotifyClientId: String = "",
) {
    companion object {
        const val DEFAULT_WAKE_PHRASE = "ok Cog"
    }
}

/** When replies are read aloud. */
enum class SpeechMode {
    /** After voice input, and when a typed request is run with Enter or a tap. */
    ALWAYS,

    /** Only when the request was spoken. */
    VOICE_ONLY,
    OFF,
}

/**
 * Settings backed by SharedPreferences: reads are synchronous and tiny, which keeps the
 * assistant's first frame from waiting on disk or flashing the wrong theme.
 */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    // Held in a field: SharedPreferences only keeps a weak reference to listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    var askedForContacts: Boolean
        get() = prefs.getBoolean(KEY_ASKED_CONTACTS, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_CONTACTS, value) }

    var askedForMicrophone: Boolean
        get() = prefs.getBoolean(KEY_ASKED_MIC, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_MIC, value) }

    var askedForLocation: Boolean
        get() = prefs.getBoolean(KEY_ASKED_LOCATION, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_LOCATION, value) }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        state.value = next
        prefs.edit {
            putBoolean(KEY_WEB, next.webSuggestions)
            putBoolean(KEY_ANSWERS, next.instantAnswers)
            putBoolean(KEY_CONTACTS, next.searchContacts)
            putBoolean(KEY_SUGGESTED, next.suggestedApps)
            putBoolean(KEY_BLUR, next.blurBackground)
            putBoolean(KEY_DYNAMIC, next.dynamicColor)
            putString(KEY_ENGINE, next.engine.id)
            putString(KEY_THEME, next.theme.name)
            putBoolean(KEY_WEATHER, next.weather)
            putString(KEY_UNITS, next.units.name)
            putBoolean(KEY_RUN_SPOKEN, next.runSpokenCommands)
            putBoolean(KEY_WIKIPEDIA, next.wikipedia)
            putString(KEY_SPEECH, next.speech.name)
            putString(KEY_LANGUAGE, next.language.name)
            putString(KEY_VOICE_STYLE, next.voiceStyle.name)
            putString(KEY_VOICE_GENDER, next.voiceGender.name)
            putString(KEY_SYSTEM_VOICE, next.systemVoice)
            putBoolean(KEY_LISTEN_ON_OPEN, next.listenOnOpen)
            putBoolean(KEY_WAKE_WORD, next.wakeWord)
            putString(KEY_WAKE_PHRASE, next.wakePhrase)
            putString(KEY_SPOTIFY_CLIENT_ID, next.spotifyClientId)
        }
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            webSuggestions = prefs.getBoolean(KEY_WEB, defaults.webSuggestions),
            instantAnswers = prefs.getBoolean(KEY_ANSWERS, defaults.instantAnswers),
            searchContacts = prefs.getBoolean(KEY_CONTACTS, defaults.searchContacts),
            suggestedApps = prefs.getBoolean(KEY_SUGGESTED, defaults.suggestedApps),
            blurBackground = prefs.getBoolean(KEY_BLUR, defaults.blurBackground),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, defaults.dynamicColor),
            engine = SearchEngine.fromId(prefs.getString(KEY_ENGINE, null)),
            theme = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(defaults.theme),
            weather = prefs.getBoolean(KEY_WEATHER, defaults.weather),
            units = runCatching { UnitSystem.valueOf(prefs.getString(KEY_UNITS, null)!!) }.getOrDefault(defaults.units),
            runSpokenCommands = prefs.getBoolean(KEY_RUN_SPOKEN, defaults.runSpokenCommands),
            wikipedia = prefs.getBoolean(KEY_WIKIPEDIA, defaults.wikipedia),
            speech = runCatching { SpeechMode.valueOf(prefs.getString(KEY_SPEECH, null)!!) }.getOrDefault(defaults.speech),
            language = AssistantLanguage.fromId(prefs.getString(KEY_LANGUAGE, null)),
            voiceStyle = VoiceStyle.fromId(prefs.getString(KEY_VOICE_STYLE, null)),
            voiceGender = VoiceGender.fromId(prefs.getString(KEY_VOICE_GENDER, null)),
            systemVoice = prefs.getString(KEY_SYSTEM_VOICE, null),
            listenOnOpen = prefs.getBoolean(KEY_LISTEN_ON_OPEN, defaults.listenOnOpen),
            wakeWord = prefs.getBoolean(KEY_WAKE_WORD, defaults.wakeWord),
            wakePhrase = prefs.getString(KEY_WAKE_PHRASE, null)?.takeIf { it.isNotBlank() } ?: defaults.wakePhrase,
            spotifyClientId = prefs.getString(KEY_SPOTIFY_CLIENT_ID, null).orEmpty(),
        )
    }

    private companion object {
        const val KEY_WEB = "web_suggestions"
        const val KEY_ANSWERS = "instant_answers"
        const val KEY_CONTACTS = "search_contacts"
        const val KEY_SUGGESTED = "suggested_apps"
        const val KEY_BLUR = "blur_background"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_ENGINE = "engine"
        const val KEY_THEME = "theme"
        const val KEY_ASKED_CONTACTS = "asked_contacts"
        const val KEY_ASKED_LOCATION = "asked_location"
        const val KEY_WEATHER = "weather"
        const val KEY_UNITS = "units"
        const val KEY_RUN_SPOKEN = "run_spoken_commands"
        const val KEY_WIKIPEDIA = "wikipedia"
        const val KEY_SPEECH = "speech"
        const val KEY_LANGUAGE = "language"
        const val KEY_VOICE_STYLE = "voice_style"
        const val KEY_VOICE_GENDER = "voice_gender"
        const val KEY_SYSTEM_VOICE = "system_voice"
        const val KEY_LISTEN_ON_OPEN = "listen_on_open"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_WAKE_PHRASE = "wake_phrase"
        const val KEY_ASKED_MIC = "asked_microphone"
        const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
    }
}
