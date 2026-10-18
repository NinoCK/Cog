package com.bestassistant.commands

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Something the user asked the assistant to do, parsed deterministically from what they typed or
 * said. Commands are plain data: [CommandParser] produces them and the device layer runs them.
 */
@Immutable
sealed interface Command {

    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : Command
    data object ShowAlarms : Command

    data class SetTimer(val seconds: Int, val label: String? = null) : Command
    data object ShowTimers : Command

    data class Flashlight(val state: Switch) : Command
    data class Volume(val change: VolumeChange) : Command
    data class Media(val action: MediaAction) : Command

    /** "what's playing": reads out the song from the active player's media session. */
    data object NowPlaying : Command

    /** "like this song": adds it to the player's likes (Spotify's Liked Songs). */
    data object LikeSong : Command
    data class Shuffle(val on: Boolean) : Command
    data class Repeat(val on: Boolean) : Command

    data class Weather(val place: String?, val day: WeatherDay) : Command

    /** Current time and date, at [zone] or on the device when it's null. */
    data class Clock(val zone: ZoneId?, val place: String?) : Command

    data class Convert(val conversion: UnitConverter.Conversion) : Command

    /** "call mom": resolved against contacts before it can run. */
    data class Call(val who: String) : Command
    data class DialNumber(val number: String) : Command

    /** "text alex saying on my way": resolved against contacts before it can run. */
    data class Message(val who: String, val body: String?) : Command

    /** "open spotify": narrows app results to [name]; runs as the top app. */
    data class OpenApp(val name: String) : Command

    /**
     * "play iron maiden on spotify": search-and-play in a music app ([app] null = the usual one).
     * [kind] is what the user said it is ("the song ...", "my ... playlist"); for a song "by"
     * someone, [query] is the title and [artist] the performer.
     */
    data class PlayMusic(
        val query: String,
        val app: MusicApp?,
        val kind: MusicKind = MusicKind.ANY,
        val artist: String? = null,
    ) : Command

    /** "what is a quasar", "who was ada lovelace": answered from Wikipedia. */
    data class Lookup(val topic: String) : Command

    /**
     * "take me to the airport", "drive home with waze": turn-by-turn directions to [destination], or
     * to the user's saved [place] (home, work). [app] is the navigation app the user named; null uses
     * the one chosen in settings. [map] is "show berlin on the map": a map, not a route.
     */
    data class Navigate(
        val destination: String,
        val place: SavedPlace? = null,
        val app: NavigationApp? = null,
        val map: Boolean = false,
    ) : Command

    /** "search for cheap flights": the query in the browser, with the search engine from settings. */
    data class WebSearch(val query: String) : Command

    /** "search youtube for cat videos": the query in YouTube's own search. */
    data class YouTubeSearch(val query: String) : Command

    /**
     * "remind me at 5 to call mom": a notification at [time] saying [what], as the user said it.
     * Either may be null when the user didn't say; Cog asks for the time before setting it.
     */
    data class Remind(val what: String?, val time: ReminderTime?) : Command
    data object ShowReminders : Command
    data object CancelReminders : Command

    /** "whose name day is today": the Greek name days on [date]. */
    data class NameDays(val date: LocalDate) : Command

    /** "when is Eleni's name day": when [name] has its name day, as the user said it. */
    data class NameDay(val name: String) : Command

    /** "remember where I parked": the phone's location, kept for [FindCar]. */
    data object SaveParking : Command

    /** "take me to my car" ([directions]: walking directions), "where did I park" (the spot on a map). */
    data class FindCar(val directions: Boolean) : Command

    /** "send alex my location": a message with a map link to where the phone is; resolved against contacts. */
    data class ShareLocation(val who: String) : Command

    /** "run the task called lights out": starts that Tasker task. */
    data class RunTask(val name: String) : Command

    /**
     * "turn on the kitchen light": [text] is handed to Home Assistant's own (rule-based) Assist,
     * which knows the user's devices and areas, and answers with its own sentence. [group] is set
     * for "turn off all the lights" and the like, which Cog does itself across every such device;
     * [setting] for "set the air conditioner to cooling" or "start the vacuum", which Cog matches
     * against what the device itself says it can do; [query] for "is the front door locked?",
     * answered from what Home Assistant says about its devices and sensors.
     */
    data class SmartHome(
        val text: String,
        val group: HomeGroup? = null,
        val setting: DeviceSetting? = null,
        val query: DeviceQuery? = null,
    ) : Command
    data class Camera(val video: Boolean) : Command
    data class OpenPanel(val panel: SettingsPanel, val requested: Switch?) : Command

    /** Commands that do something on their own when the user presses Enter. */
    val isSelfContained: Boolean
        get() = when (this) {
            is Call, is Message, is ShareLocation, is OpenApp, is Weather, is Clock, is Convert, is Lookup, is NameDays, is NameDay -> false
            else -> true
        }

    /** Commands that are only meaningful once a contact has been found for them. */
    val needsContact: Boolean get() = this is Call || this is Message || this is ShareLocation

    /** Recognised with enough confidence that web suggestions for the text would be noise. */
    val isConfident: Boolean get() = this !is OpenApp && !needsContact

    /** Commands that drive another app's player, which needs media control (notification access). */
    val usesMediaSession: Boolean get() = this is NowPlaying || this is LikeSong || this is Shuffle || this is Repeat

    /** Answers shown on screen and read out on Enter (rather than an action). */
    val isInformation: Boolean
        get() = this is Weather || this is Clock || this is Convert || this is Lookup || this is NameDays || this is NameDay

    /** Commands that need to know where the phone is: they ask for location access first. */
    val needsLocation: Boolean get() = this is SaveParking || this is ShareLocation
}

/** When a reminder goes off: [After] a while, or [At] a time on a day. */
sealed interface ReminderTime {
    data class After(val seconds: Int) : ReminderTime
    data class At(val date: LocalDate, val hour: Int, val minute: Int) : ReminderTime

    /** The moment it goes off, for a request made at [now]. */
    fun resolve(now: LocalDateTime): LocalDateTime = when (this) {
        is After -> now.plusSeconds(seconds.toLong())
        is At -> date.atTime(hour, minute)
    }
}

/**
 * "turn off all the lights", "close the blinds in the bedroom": one [action] on every device of a
 * [kind] (null: everything that is on, i.e. lights, fans and media players), in the house or in the
 * [area] named, as the user said it.
 */
data class HomeGroup(val action: HomeAction, val kind: DeviceKind?, val area: String? = null)

enum class HomeAction { ON, OFF, OPEN, CLOSE, LOCK }

/**
 * "set the bedroom AC to 22", "put the air purifier in sleep mode", "turn up the AC fan":
 * [target] is the device and, maybe, which of its settings, as said ("the bedroom ac fan"); which
 * device and setting that is only becomes clear against what Home Assistant lists.
 */
data class DeviceSetting(val target: String, val value: SettingValue)

sealed interface SettingValue {
    /** "22 degrees", "40%", "3". */
    data class Number(val value: Double, val unit: NumberUnit? = null) : SettingValue

    /** "cooling", "sleep mode", "high", "on". */
    data class Word(val text: String) : SettingValue

    /** "turn up", "warmer", "lower". */
    data class Step(val up: Boolean) : SettingValue

    /** "start the vacuum", "press the doorbell button", "arm the alarm in night mode" ([mode]: "night"). */
    data class Action(val verb: DeviceVerb, val mode: String? = null) : SettingValue
}

enum class NumberUnit { DEGREES, PERCENT, KELVIN }

/** What a device can be told to do, beyond on and off. */
enum class DeviceVerb { START, STOP, PAUSE, DOCK, LOCATE, PRESS, LOCK, UNLOCK, OPEN, CLOSE, ARM, DISARM }

/**
 * "what's the temperature in the bedroom", "is the front door locked", "what's the vacuum doing":
 * [target] is the device or room as said; [asked] is what about it.
 */
data class DeviceQuery(val target: String, val asked: Asked)

sealed interface Asked {
    /** A reading: "the temperature", "the battery". */
    data class Measure(val quantity: Quantity) : Asked

    /** A yes-or-no question about its state: "is it open?". */
    data class State(val condition: Condition) : Asked

    /** "What's it doing?", "its status". */
    data object Status : Asked
}

/** What sensors measure, by Home Assistant's device classes, with the words people use. */
enum class Quantity(val deviceClasses: Set<String>, val words: List<String>) {
    TEMPERATURE(setOf("temperature"), listOf("temperature", "temp")),
    HUMIDITY(setOf("humidity", "moisture"), listOf("humidity", "moisture")),
    BATTERY(setOf("battery"), listOf("battery", "battery level", "charge")),
    POWER(setOf("power"), listOf("power", "power usage", "power consumption", "power draw", "wattage", "consumption")),
    ENERGY(setOf("energy"), listOf("energy", "energy usage", "energy consumption")),
    CO2(setOf("carbon_dioxide"), listOf("co2", "carbon dioxide")),
    AIR_QUALITY(setOf("pm25", "aqi", "pm10", "volatile_organic_compounds"), listOf("air quality", "pm2.5", "pm 2.5", "pollution")),
    PRESSURE(setOf("pressure", "atmospheric_pressure"), listOf("pressure", "air pressure")),
    ILLUMINANCE(setOf("illuminance"), listOf("light level", "illuminance", "lux")),
}

/** The states a yes-or-no question asks about. */
enum class Condition { ON, OFF, OPEN, CLOSED, LOCKED, UNLOCKED, ARMED, DISARMED, RUNNING, PLAYING, CHARGING, DOCKED, HOME }

/** The kinds of Home Assistant device a whole group of can be switched at once, by their domain. */
enum class DeviceKind(val domain: String) {
    LIGHT("light"), SWITCH("switch"), FAN("fan"), COVER("cover"), MEDIA("media_player"), CLIMATE("climate"), LOCK("lock"), VALVE("valve"),
}

/** The places a user saves once in settings and then asks for by name: "take me home". */
enum class SavedPlace { HOME, WORK }

/** Navigation apps that start turn-by-turn directions from a link, with what people call them. */
enum class NavigationApp(val label: String, val packageName: String, val aliases: List<String>) {
    GOOGLE_MAPS("Google Maps", "com.google.android.apps.maps", listOf("google maps", "maps", "the maps", "google")),
    WAZE("Waze", "com.waze", listOf("waze", "ways")),
    ;

    companion object {
        /** Longest aliases first, so "google maps" wins over "google". */
        val allAliases: List<String> = entries.flatMap { it.aliases }.sortedByDescending { it.length }

        fun fromName(name: String): NavigationApp? = entries.firstOrNull { name in it.aliases }
    }
}

/** Music apps that accept "search and play" (MEDIA_PLAY_FROM_SEARCH), with what people call them. */
enum class MusicApp(val label: String, val packageName: String, val aliases: List<String>) {
    SPOTIFY("Spotify", "com.spotify.music", listOf("spotify")),
    YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music", listOf("youtube music", "yt music")),
    YOUTUBE("YouTube", "com.google.android.youtube", listOf("youtube")),
    DEEZER("Deezer", "deezer.android.app", listOf("deezer")),
    APPLE_MUSIC("Apple Music", "com.apple.android.music", listOf("apple music")),
    SOUNDCLOUD("SoundCloud", "com.soundcloud.android", listOf("soundcloud", "sound cloud")),
    TIDAL("Tidal", "com.aspiro.tidal", listOf("tidal")),
    AMAZON_MUSIC("Amazon Music", "com.amazon.mp3", listOf("amazon music")),
    ;

    companion object {
        fun fromName(name: String): MusicApp? = entries.firstOrNull { name in it.aliases }

        /** Longest aliases first, so "youtube music" wins over "youtube". */
        val allAliases: List<String> = entries.flatMap { it.aliases }.sortedByDescending { it.length }
    }
}

enum class Switch { ON, OFF, TOGGLE }

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

/** What a "play ..." request names; [ANY] when the user didn't say. [LIKED] is the user's liked songs. */
enum class MusicKind { ANY, ARTIST, SONG, ALBUM, PLAYLIST, LIKED }

enum class WeatherDay { NOW, TODAY, TOMORROW, WEEK }

sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
    data class Set(val percent: Int) : VolumeChange
}

/** System screens an app may open but (without elevated access) not toggle itself. */
enum class SettingsPanel { WIFI, BLUETOOTH, INTERNET, AIRPLANE, NFC, HOTSPOT, VOLUME, BATTERY, DISPLAY, LOCATION, DND }
