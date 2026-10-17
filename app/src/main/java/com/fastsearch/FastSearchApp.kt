package com.fastsearch

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.bestassistant.device.CommandRunner
import com.bestassistant.language.AppLanguage
import com.bestassistant.media.MediaControl
import com.bestassistant.media.SavedPlaylists
import com.bestassistant.media.SpotifyCatalog
import com.bestassistant.media.SpotifyRemote
import com.bestassistant.speech.Responder
import com.bestassistant.speech.Speaker
import com.bestassistant.wake.WakeModel
import com.bestassistant.wiki.WikipediaClient
import com.bestassistant.weather.DeviceLocation
import com.bestassistant.weather.WeatherClient
import com.fastsearch.data.AppPreferences
import com.fastsearch.data.AppRepository
import com.fastsearch.data.ContactRepository
import com.fastsearch.data.DuckDuckGoClient
import com.fastsearch.data.UsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FastSearchApp : Application() {

    /** Lives as long as the process; used for work that must outlive a single overlay session. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var preferences: AppPreferences
        private set
    lateinit var usage: UsageStore
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var contacts: ContactRepository
        private set
    val web = DuckDuckGoClient()
    val weather = WeatherClient()
    val wikipedia = WikipediaClient()
    lateinit var speaker: Speaker
        private set
    lateinit var responder: Responder
        private set
    lateinit var location: DeviceLocation
        private set
    lateinit var commands: CommandRunner
        private set
    lateinit var media: MediaControl
        private set
    lateinit var playlists: SavedPlaylists
        private set
    lateinit var spotify: SpotifyRemote
        private set
    lateinit var wakeModel: WakeModel
        private set

    /** This app's resources in the assistant's language, for replies spoken after the overlay closes. */
    lateinit var localized: Context
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        applyLanguage()
        usage = UsageStore(this)
        contacts = ContactRepository(this)
        location = DeviceLocation(this)
        media = MediaControl(this)
        playlists = SavedPlaylists(this)
        spotify = SpotifyRemote(this) { preferences.settings.value.spotifyClientId }
        commands = CommandRunner(this, { localized }, media, SpotifyCatalog(this), playlists, spotify)
        speaker = Speaker(this, preferences)
        responder = Responder({ localized }, media)
        wakeModel = WakeModel(this, appScope)
        apps = AppRepository(this, appScope)
        // Index apps right away so the first assistant invocation already has results.
        apps.refresh()
    }

    /** Applies the language setting to formatting and resources; screens call recreate() after. */
    fun applyLanguage() {
        AppLanguage.apply(preferences.settings.value.language)
        localized = AppLanguage.wrap(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android resets the default locale here; the phone's language may also have changed.
        applyLanguage()
    }
}

val Context.fastSearch: FastSearchApp get() = applicationContext as FastSearchApp
