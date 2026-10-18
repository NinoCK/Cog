package com.fastsearch

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.bestassistant.calendar.CalendarStore
import com.bestassistant.device.Answers
import com.bestassistant.device.CommandRunner
import com.bestassistant.integrations.AppIntegrationHost
import com.bestassistant.integrations.Integrations
import com.bestassistant.integrations.homeassistant.HomeAssistantIntegration
import com.bestassistant.integrations.jellyfin.JellyfinIntegration
import com.bestassistant.integrations.spotify.SpotifyIntegration
import com.bestassistant.integrations.tasker.TaskerIntegration
import com.bestassistant.language.AppLanguage
import com.bestassistant.media.MediaControl
import com.bestassistant.money.ExchangeRates
import com.bestassistant.parking.Parking
import com.bestassistant.phone.CallHistory
import com.bestassistant.reminders.Reminders
import com.bestassistant.routines.RoutineRunner
import com.bestassistant.routines.Routines
import com.bestassistant.speech.Responder
import com.bestassistant.speech.Speaker
import com.bestassistant.wake.WakeModel
import com.bestassistant.wiki.WikipediaClient
import com.bestassistant.wiki.WiktionaryClient
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
    val wiktionary = WiktionaryClient()
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
    lateinit var wakeModel: WakeModel
        private set
    /** Tasker, Home Assistant, Spotify: each a module of its own, installed (or not) from settings. */
    lateinit var integrations: Integrations
        private set
    lateinit var reminders: Reminders
        private set
    lateinit var parking: Parking
        private set
    lateinit var calendar: CalendarStore
        private set
    lateinit var calls: CallHistory
        private set
    lateinit var exchangeRates: ExchangeRates
        private set

    /** "Good morning" and the like: the user's routines, and what runs them. */
    lateinit var routines: Routines
        private set
    val routineRunner by lazy { RoutineRunner(this) }

    /** Answers to information requests, looked up on the spot (for routines). */
    val answers by lazy { Answers(this) }

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
        val host = AppIntegrationHost(this, { localized }, appScope)
        // Jellyfin before Home Assistant: "pause the movie", "turn on subtitles" are what's playing, not a device.
        integrations = Integrations(
            this, host,
            listOf(TaskerIntegration(host), JellyfinIntegration(host), HomeAssistantIntegration(host), SpotifyIntegration(host)),
        )
        reminders = Reminders(this)
        parking = Parking(this)
        calendar = CalendarStore(this)
        calls = CallHistory(this)
        exchangeRates = ExchangeRates(this)
        routines = Routines(this)
        commands = CommandRunner(
            this, { localized }, media,
            settings = { preferences.settings.value }, integrations = integrations,
            location = location, reminders = reminders, parking = parking,
            calendar = calendar, calls = calls,
        )
        speaker = Speaker(this, preferences)
        responder = Responder({ localized }, media)
        wakeModel = WakeModel(this, appScope)
        apps = AppRepository(this, appScope)
        // Index apps right away so the first assistant invocation already has results.
        apps.refresh()
    }

    /** Applies English to formatting and resources. */
    fun applyLanguage() {
        AppLanguage.apply()
        localized = AppLanguage.wrap(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android resets the default locale here; the phone's region may also have changed.
        applyLanguage()
    }
}

val Context.fastSearch: FastSearchApp get() = applicationContext as FastSearchApp
