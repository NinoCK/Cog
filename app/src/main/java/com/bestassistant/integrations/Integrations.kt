package com.bestassistant.integrations

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.bestassistant.commands.CommandParser
import com.bestassistant.integration.Integration
import com.bestassistant.integration.IntegrationHost
import com.bestassistant.integration.MusicService
import com.bestassistant.integration.SettingsStore
import com.bestassistant.integration.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The integrations that come with Cog ([all]), and the ones the user has installed: only those
 * have their requests understood ([CommandParser.integrations], kept up to date here), their
 * settings shown, and spoken requests nothing else understood offered to them.
 */
class Integrations(context: Context, private val host: IntegrationHost, val all: List<Integration>) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val installed: StateFlow<List<Integration>> = state.asStateFlow()

    init {
        publish()
    }

    /** Where the integration with [id] keeps its settings, for its settings page. */
    fun store(id: String): SettingsStore = host.store(id)

    fun isInstalled(id: String): Boolean = state.value.any { it.id == id }

    fun install(id: String, on: Boolean) {
        prefs.edit { putBoolean(KEY_INSTALLED + id, on) }
        state.value = read()
        publish()
    }

    /** The installed integration with [id], or null when it isn't (any more). */
    operator fun get(id: String): Integration? = state.value.firstOrNull { it.id == id }

    /** How an installed integration plays music in the app with [packageName], if one does. */
    fun music(packageName: String): MusicService? = state.value.firstNotNullOfOrNull { integration ->
        integration.music?.takeIf { it.packageName == packageName }
    }

    /** Whether any installed integration takes the spoken requests nothing else understood. */
    val takesUnknown: Boolean get() = state.value.any { it.takesUnknown }

    /** [said], which nothing else understood, offered to each installed integration that takes such requests. */
    suspend fun fallback(said: String): Step? {
        for (integration in state.value) {
            if (integration.takesUnknown) integration.fallback(said)?.let { return it }
        }
        return null
    }

    // Everything that comes with Cog is installed until the user removes it, as it was before integrations.
    private fun read() = all.filter { prefs.getBoolean(KEY_INSTALLED + it.id, true) }

    private fun publish() {
        CommandParser.integrations = state.value.mapNotNull { integration ->
            integration.parser?.let { CommandParser.Installed(integration.id, it) }
        }
    }

    private companion object {
        const val FILE = "integrations"
        const val KEY_INSTALLED = "installed."
    }
}

/** What integrations get of Cog: [strings] follows the assistant's language. */
class AppIntegrationHost(
    override val context: Context,
    private val localized: () -> Context,
    override val scope: CoroutineScope,
) : IntegrationHost {

    private val stores = HashMap<String, PreferenceStore>()

    init {
        moveOldSettings(context)
    }

    override val strings: Context get() = localized()

    @Synchronized
    override fun store(id: String): SettingsStore = stores.getOrPut(id) { PreferenceStore(context, id) }

    override fun isRequest(text: String): Boolean = CommandParser.parse(text) != null

    override fun normalize(text: String): String = CommandParser.normalize(text)

    private companion object {
        /**
         * Before integrations, Cog's own settings held the Spotify Client ID, the Home Assistant
         * address and token, and whether Tasker's permission was asked for: moved once to where
         * each integration now keeps them.
         */
        fun moveOldSettings(context: Context) {
            val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val secrets = context.getSharedPreferences(PreferenceStore.SECRETS_FILE, Context.MODE_PRIVATE)
            fun move(from: SharedPreferences, oldKey: String, id: String, key: String, secret: Boolean) {
                val value = from.getString(oldKey, null) ?: return
                val store = PreferenceStore(context, id)
                if (value.isNotEmpty() && store.get(key, secret).isEmpty()) store.set(key, value, secret)
                from.edit { remove(oldKey) }
            }
            move(settings, "spotify_client_id", "spotify", "client_id", secret = false)
            move(settings, "home_assistant_url", "home_assistant", "url", secret = false)
            move(secrets, "home_assistant_token", "home_assistant", "token", secret = true)
            // Whether Tasker's permission was asked for already: refused again after that, it's refused for good.
            if (settings.contains("asked_tasker")) {
                if (settings.getBoolean("asked_tasker", false)) PreferenceStore(context, "tasker").set("asked_permission", "true", secret = false)
                settings.edit { remove("asked_tasker") }
            }
        }
    }
}

/**
 * An integration's settings in a file of its own; secrets go with Cog's other credentials, which
 * are kept out of device backups (see res/xml/backup_rules.xml).
 */
private class PreferenceStore(context: Context, private val id: String) : SettingsStore {

    private val prefs = context.getSharedPreferences("integration_$id", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)
    private val version = MutableStateFlow(0)
    override val changes: StateFlow<Int> = version.asStateFlow()

    override fun get(key: String, secret: Boolean): String =
        (if (secret) secrets.getString("$id.$key", null) else prefs.getString(key, null)).orEmpty()

    override fun set(key: String, value: String, secret: Boolean) {
        if (secret) secrets.edit { putString("$id.$key", value) } else prefs.edit { putString(key, value) }
        version.value++
    }

    companion object {
        const val SECRETS_FILE = "secrets"
    }
}
