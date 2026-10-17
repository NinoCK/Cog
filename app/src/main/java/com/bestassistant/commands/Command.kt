package com.bestassistant.commands

import androidx.compose.runtime.Immutable
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

    data class Navigate(val destination: String) : Command
    data class Camera(val video: Boolean) : Command
    data class OpenPanel(val panel: SettingsPanel, val requested: Switch?) : Command

    /** Commands that do something on their own when the user presses Enter. */
    val isSelfContained: Boolean
        get() = when (this) {
            is Call, is Message, is OpenApp, is Weather, is Clock, is Convert, is Lookup -> false
            else -> true
        }

    /** Commands that are only meaningful once a contact has been found for them. */
    val needsContact: Boolean get() = this is Call || this is Message

    /** Recognised with enough confidence that web suggestions for the text would be noise. */
    val isConfident: Boolean get() = this !is OpenApp && !needsContact

    /** Commands that drive another app's player, which needs media control (notification access). */
    val usesMediaSession: Boolean get() = this is NowPlaying || this is LikeSong || this is Shuffle || this is Repeat

    /** Answers shown on screen and read out on Enter (rather than an action). */
    val isInformation: Boolean get() = this is Weather || this is Clock || this is Convert || this is Lookup
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
