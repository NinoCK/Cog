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

    /** "cancel my 7am alarm", "cancel all alarms", "stop the alarm": through the clock app's own "dismiss". */
    data class DismissAlarm(val target: AlarmTarget) : Command

    /** "snooze", "snooze for 10 minutes": the alarm that is ringing. */
    data class SnoozeAlarm(val minutes: Int? = null) : Command

    /** "stop the timer": the timer that is ringing. */
    data object StopTimer : Command

    /** "how much battery do I have"; [charging]: "is my phone charging". */
    data class Battery(val charging: Boolean = false) : Command

    /**
     * "how many days until Christmas": [date] is the next [name] (or, [since], the last one); [name] null: a
     * plain date. [weeks]: the user asked in weeks.
     */
    data class DaysUntil(val date: LocalDate, val name: String?, val since: Boolean = false, val weeks: Boolean = false) : Command

    /** "what day is March 3rd": the weekday of [date]. */
    data class WhatDay(val date: LocalDate, val name: String?) : Command

    /** "flip a coin", "roll a dice", "pick a number from 1 to 10": decided when it runs. */
    data class Chance(val kind: ChanceKind) : Command

    /** "100 dollars in euros": with the European Central Bank's reference rates. Currencies are ISO codes. */
    data class Currency(val amount: Double, val from: String, val to: String) : Command

    /** "define serendipity": from Wiktionary. */
    data class Define(val word: String) : Command

    /** "how do you spell necessary": letter by letter. */
    data class Spell(val word: String) : Command

    /** "what's on my calendar today": the events of [days] days from [from]. */
    data class Agenda(val from: LocalDate, val days: Int = 1) : Command

    /** "what's my next meeting". */
    data object NextEvent : Command

    /** "add dentist tomorrow at 3": [title] or [time] null when the user didn't say; PocketSearch asks. */
    data class AddEvent(val title: String?, val time: EventTime?, val minutes: Int? = null) : Command

    /** "brightness 50%", "dim the screen". */
    data class Brightness(val change: BrightnessChange) : Command

    /** "silent mode", "vibrate", "ringer on". */
    data class Ringer(val mode: RingerMode) : Command

    /** "good morning": a routine the user set up in settings, by its [name]. */
    data class Routine(val name: String) : Command

    /** "who called me": the missed calls. */
    data object MissedCalls : Command

    /** "call back" (the last missed call), "redial" ([redial]: the last number called). */
    data class CallBack(val redial: Boolean = false) : Command

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
     * Either may be null when the user didn't say; PocketSearch asks for the time before setting it.
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

    /** A request an installed integration found ("turn on the kitchen light" for Home Assistant), which it runs. */
    data class Integration(val integration: String, val request: Request) : Command
    data class Camera(val video: Boolean) : Command
    data class OpenPanel(val panel: SettingsPanel, val requested: Switch?) : Command

    /** Commands that do something on their own when the user presses Enter. */
    val isSelfContained: Boolean
        get() = when (this) {
            is Call, is Message, is ShareLocation, is OpenApp -> false
            else -> !isInformation
        }

    /** Commands that are only meaningful once a contact has been found for them. */
    val needsContact: Boolean get() = this is Call || this is Message || this is ShareLocation

    /** Recognised with enough confidence that web suggestions for the text would be noise. */
    val isConfident: Boolean get() = this !is OpenApp && !needsContact

    /** Commands that drive another app's player, which needs media control (notification access). */
    val usesMediaSession: Boolean get() = this is NowPlaying || this is LikeSong || this is Shuffle || this is Repeat

    /** Answers shown on screen and read out on Enter (rather than an action). */
    val isInformation: Boolean
        get() = when (this) {
            is Weather, is Clock, is Convert, is Lookup, is NameDays, is NameDay, is Battery, is DaysUntil, is WhatDay,
            is Currency, is Define, is Spell, is Agenda, NextEvent, MissedCalls,
            -> true
            else -> false
        }

    /** Commands that need to know where the phone is: they ask for location access first. */
    val needsLocation: Boolean get() = this is SaveParking || this is ShareLocation

    /** Commands that read the calendar; [AddEvent] also writes to it. */
    val needsCalendar: Boolean get() = this is Agenda || this == NextEvent || this is AddEvent

    /** Commands that read the call history. */
    val needsCallLog: Boolean get() = this == MissedCalls || this is CallBack
}

/** Which alarm "cancel my alarm" means. */
sealed interface AlarmTarget {
    /** The one that is ringing ("stop the alarm"). */
    data object Ringing : AlarmTarget

    /** The next one to ring ("cancel my next alarm"). */
    data object Next : AlarmTarget

    data object All : AlarmTarget

    /** "my 7am alarm": [hour] in 24-hour time when [pm] is known, else as said (1 to 12). */
    data class At(val hour: Int, val minute: Int, val pm: Boolean?) : AlarmTarget

    /** "my gym alarm". */
    data class Labeled(val label: String) : AlarmTarget
}

/** What a [Command.Chance] decides. */
sealed interface ChanceKind {
    data object Coin : ChanceKind
    data class Dice(val count: Int = 1, val sides: Int = 6) : ChanceKind
    data class Number(val min: Int, val max: Int) : ChanceKind
}

/** When a calendar event is: [At] a time, or a whole day ([AllDay]). */
sealed interface EventTime {
    val date: LocalDate

    data class At(override val date: LocalDate, val hour: Int, val minute: Int) : EventTime
    data class AllDay(override val date: LocalDate) : EventTime
}

sealed interface BrightnessChange {
    data object Up : BrightnessChange
    data object Down : BrightnessChange
    data class Set(val percent: Int) : BrightnessChange

    /** Adaptive brightness on or off. */
    data class Auto(val on: Boolean) : BrightnessChange
}

enum class RingerMode { NORMAL, VIBRATE, SILENT }

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
