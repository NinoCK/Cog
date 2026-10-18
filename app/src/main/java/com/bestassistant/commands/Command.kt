package com.bestassistant.commands

import androidx.compose.runtime.Immutable
import com.bestassistant.integration.Request
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

    /** A request an installed integration found ("turn on the kitchen light" for Home Assistant), which it runs. */
    data class Integration(val integration: String, val request: Request) : Command
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

/** What a "play ..." request names; shared with the integrations that play music. */
typealias MusicKind = com.bestassistant.integration.MusicKind

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
