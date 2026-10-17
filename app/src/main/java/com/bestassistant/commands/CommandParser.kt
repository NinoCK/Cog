package com.bestassistant.commands

import java.time.LocalDateTime
import java.util.Locale

/**
 * Turns what the user typed or said into a [Command], or null when it's an ordinary search.
 *
 * Deliberately rule-based: every phrase that works is one a matcher below accepts, so the same
 * words always do the same thing. Matchers are strict about leftover words, so "alarm clock
 * app" or "call of duty" stay searches instead of turning into commands.
 */
object CommandParser {

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Command? {
        // Greek requests have their own rules; an English sentence with a Greek name falls through.
        if (GreekCommandParser.isGreek(input)) GreekCommandParser.parse(input, now)?.let { return it }
        val text = normalize(input)
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        return conversion(text)
            ?: timer(text)
            ?: alarm(text, now)
            ?: flashlight(text)
            ?: volume(text)
            ?: media(text)
            ?: music(text)
            ?: weather(text)
            ?: clock(text)
            ?: camera(text)
            ?: navigate(text)
            ?: call(text)
            ?: message(text)
            ?: panel(text)
            ?: lookup(text)
            ?: openApp(text)
    }

    // region Normalisation

    private val politePrefixes = listOf(
        "hey assistant ", "ok assistant ", "ok cog ", "okay cog ", "hey cog ", "cog ", "hey ", "ok ", "okay ", "please ", "can you ", "could you ",
        "would you ", "will you ", "can u ", "i want to ", "i'd like to ", "i need to ", "go ahead and ",
        "just ", "quickly ",
    )
    private val politeSuffixes = listOf(" please", " for me", " thanks", " thank you", " right now", " now")

    internal fun normalize(input: String): String {
        var text = input.trim().lowercase(Locale.ROOT)
            .replace('’', '\'')
            // Commas between words are punctuation ("hey, set a timer"); inside numbers they're decimals.
            .replace(Regex(",(?=\\s|$)"), " ")
            .replace(Regex("\\s+"), " ")
            .trimEnd('?', '!', '.', ',', ' ')
        var changed = true
        while (changed) {
            changed = false
            for (prefix in politePrefixes) {
                if (text.startsWith(prefix) && text.length > prefix.length) {
                    text = text.removePrefix(prefix).trimStart(',', ' ')
                    changed = true
                }
            }
            for (suffix in politeSuffixes) {
                if (text.endsWith(suffix) && text.length > suffix.length) {
                    text = text.removeSuffix(suffix).trimEnd(',', ' ')
                    changed = true
                }
            }
        }
        return text
    }

    private fun words(text: String) = text.split(' ').filter { it.isNotEmpty() }

    /** True when every word of [text] is in [allowed]; used to reject sentences with extra intent. */
    private fun onlyWords(text: String, allowed: Set<String>) = words(text).all { it in allowed }

    // endregion

    // region Clock app

    private val timerWord = Regex("\\b(?:timer|countdown)s?\\b")
    private val showTimers = Regex("^(?:show |open |check |view |see |list )?(?:my |the |all )?(?:timers?|countdowns?)$")
    private val timerFillers = setOf(
        "set", "start", "create", "make", "put", "begin", "add", "a", "an", "the", "my", "new", "me", "for",
        "of", "on", "to", "and", "with", "called", "named", "labeled", "labelled", "label", "timer", "countdown",
        "minute", "up",
    )

    private fun timer(text: String): Command? {
        if (showTimers.matches(text)) return Command.ShowTimers
        if (!timerWord.containsMatchIn(text)) return null
        val window = longestDuration(words(text)) ?: return null
        val label = leftover(window.rest, timerFillers)
        if (window.seconds > MAX_TIMER_SECONDS) return null
        return Command.SetTimer(window.seconds, label)
    }

    private class DurationWindow(val seconds: Int, val rest: List<String>)

    /** The longest run of words that reads as a duration; the remaining words become the label. */
    private fun longestDuration(words: List<String>): DurationWindow? {
        for (length in words.size downTo 1) {
            for (start in 0..words.size - length) {
                val span = words.subList(start, start + length)
                // A lone number ("timer 5") means minutes; inside longer spans units are required.
                val seconds = Spoken.duration(span.joinToString(" "), bareNumberUnit = if (length == 1) 60 else null)
                    ?: continue
                return DurationWindow(seconds, words.subList(0, start) + words.subList(start + length, words.size))
            }
        }
        return null
    }

    private fun leftover(words: List<String>, fillers: Set<String>): String? =
        words.filter { it !in fillers }.joinToString(" ").trim().takeIf { it.isNotEmpty() }

    private val alarmWord = Regex("\\b(?:alarm|wake me|wake up|get me up)\\b")
    private val showAlarms = Regex("^(?:show |open |check |view |see |list )?(?:my |the |all )?alarms?$")
    private val relativeAlarm = Regex("^.*\\bin (.+)$")
    private val morning = Regex("\\b(?:in the morning|tomorrow morning|this morning|morning)\\b")
    private val evening = Regex("\\b(?:in the evening|this evening|tonight|in the afternoon|this afternoon|at night|evening|afternoon)\\b")
    private val alarmFillers = setOf(
        "set", "an", "a", "the", "my", "new", "alarm", "for", "at", "to", "wake", "me", "up", "get", "tomorrow",
        "today", "called", "named", "labeled", "labelled", "label", "on", "create", "make", "add", "please", "and",
    )

    private fun alarm(text: String, now: LocalDateTime): Command? {
        if (showAlarms.matches(text)) return Command.ShowAlarms
        if (!alarmWord.containsMatchIn(text)) return null

        // "wake me up in 8 hours", "alarm in 20 minutes"
        relativeAlarm.matchEntire(text)?.let { m ->
            Spoken.duration(m.groupValues[1])?.let { seconds ->
                val at = now.plusSeconds(seconds.toLong())
                return Command.SetAlarm(at.hour, at.minute)
            }
        }

        val meridiemHint = when {
            morning.containsMatchIn(text) -> Spoken.Meridiem.AM
            evening.containsMatchIn(text) -> Spoken.Meridiem.PM
            else -> null
        }
        val stripped = text.replace(morning, " ").replace(evening, " ").replace(Regex("\\s+"), " ").trim()
        val words = words(stripped)
        for (length in minOf(words.size, 5) downTo 1) {
            for (start in 0..words.size - length) {
                val time = Spoken.clock(words.subList(start, start + length).joinToString(" ")) ?: continue
                val rest = words.subList(0, start) + words.subList(start + length, words.size)
                // Leftover non-filler words are the label: "alarm 6am gym".
                val label = leftover(rest, alarmFillers)
                val withHint = if (time.meridiem == null && meridiemHint != null && time.hour in 1..12) {
                    time.copy(meridiem = meridiemHint)
                } else {
                    time
                }
                val resolved = withHint.resolve(now.toLocalTime())
                return Command.SetAlarm(resolved.hour, resolved.minute, label)
            }
        }
        return null
    }

    // endregion

    // region Device controls

    private val torchWords = setOf(
        "turn", "switch", "toggle", "the", "my", "on", "off", "flashlight", "flash", "light", "torch", "enable",
        "disable", "activate", "deactivate", "use",
    )

    private fun flashlight(text: String): Command? {
        val mentionsTorch = Regex("\\b(?:flashlight|flash light|torch)\\b").containsMatchIn(text)
        if (!mentionsTorch || !onlyWords(text, torchWords)) return null
        val words = words(text)
        val state = when {
            "on" in words || "enable" in words || "activate" in words -> Switch.ON
            "off" in words || "disable" in words || "deactivate" in words -> Switch.OFF
            else -> Switch.TOGGLE
        }
        return Command.Flashlight(state)
    }

    private val volumeSet = Regex("^(?:set |turn |change |put )?(?:the |media )?volume (?:to |at )?(\\d{1,3})(?: ?%| percent)?$")
    private val volumeSetReverse = Regex("^(?:set |turn |change |put )?(?:the )?(?:media )?volume (?:to |at )?(max|maximum|full|min|minimum|half|zero)$")

    private fun volume(text: String): Command? {
        volumeSet.matchEntire(text)?.let { m ->
            val percent = m.groupValues[1].toInt()
            return if (percent in 0..100) Command.Volume(VolumeChange.Set(percent)) else null
        }
        volumeSetReverse.matchEntire(text)?.let { m ->
            val percent = when (m.groupValues[1]) {
                "max", "maximum", "full" -> 100
                "half" -> 50
                else -> 0
            }
            return Command.Volume(VolumeChange.Set(percent))
        }
        val change = when (text) {
            "max volume", "maximum volume", "full volume" -> VolumeChange.Set(100)
            "volume up", "turn up the volume", "turn the volume up", "turn volume up", "turn it up", "louder",
            "increase volume", "increase the volume", "raise volume", "raise the volume", "make it louder", "volume +",
            -> VolumeChange.Up
            "volume down", "turn down the volume", "turn the volume down", "turn volume down", "turn it down",
            "quieter", "softer", "decrease volume", "decrease the volume", "lower volume", "lower the volume",
            "reduce volume", "reduce the volume", "make it quieter", "volume -",
            -> VolumeChange.Down
            "mute", "mute volume", "mute the volume", "volume mute", "mute media", "mute sound", "mute audio",
            "mute music",
            -> VolumeChange.Mute
            "unmute", "unmute volume", "unmute the volume", "volume unmute", "unmute media", "unmute sound",
            "unmute audio", "unmute music",
            -> VolumeChange.Unmute
            else -> return null
        }
        return Command.Volume(change)
    }

    private val nowPlaying = setOf(
        "what's playing", "what is playing", "whats playing", "what's this song", "what is this song", "what song is this",
        "what song is playing", "what's the song", "what song is that", "which song is this", "who sings this",
        "who sings this song", "who is singing", "who's singing", "what am i listening to", "name this song",
        "what's playing right now", "what's on", "song name", "what track is this", "what's this track",
    )
    private val likeSong = setOf(
        "like this song", "like this", "like the song", "like this track", "like it", "love this song", "heart this song",
        "save this song", "save this track", "save the song", "add this song to my liked songs", "add to liked songs",
        "add this to my liked songs", "add this song to liked songs", "add to my liked songs", "add this to liked songs",
    )
    private val shuffleOn = setOf(
        "shuffle", "shuffle on", "turn on shuffle", "turn shuffle on", "enable shuffle", "shuffle mode on", "start shuffling",
        "shuffle the songs", "shuffle songs", "shuffle my music", "shuffle the music",
    )
    private val shuffleOff = setOf(
        "shuffle off", "turn off shuffle", "turn shuffle off", "disable shuffle", "shuffle mode off", "stop shuffling",
        "stop shuffle", "no shuffle",
    )
    private val repeatOn = setOf(
        "repeat", "repeat on", "turn on repeat", "turn repeat on", "enable repeat", "repeat this song", "repeat the song",
        "repeat song", "loop this song", "loop the song", "put this song on repeat", "put it on repeat",
    )
    private val repeatOff = setOf(
        "repeat off", "turn off repeat", "turn repeat off", "disable repeat", "stop repeating", "no repeat", "stop looping",
    )

    private fun media(text: String): Command? {
        when (text) {
            in nowPlaying -> return Command.NowPlaying
            in likeSong -> return Command.LikeSong
            in shuffleOn -> return Command.Shuffle(true)
            in shuffleOff -> return Command.Shuffle(false)
            in repeatOn -> return Command.Repeat(true)
            in repeatOff -> return Command.Repeat(false)
        }
        val action = when (text) {
            "pause", "pause music", "pause the music", "pause song", "pause the song", "pause media", "pause playback",
            "pause it", "stop music", "stop the music", "stop playback", "stop playing", "stop the song",
            -> MediaAction.PAUSE
            "resume", "resume music", "resume the music", "resume playback", "resume song", "unpause", "play music",
            "continue playing", "continue the music", "play media", "keep playing",
            -> MediaAction.PLAY
            "next song", "next track", "skip song", "skip track", "skip this song", "skip this track",
            "play next song", "play the next song", "skip the song",
            -> MediaAction.NEXT
            "previous", "previous song", "previous track", "last song", "last track", "play previous song",
            "play the previous song", "go back a song", "back a song",
            -> MediaAction.PREVIOUS
            else -> return null
        }
        return Command.Media(action)
    }

    private val musicLead = Regex(
        "^(?:play|playing|put on|listen to|i want to listen to|start playing|shuffle)" +
            // "the" stays part of the title: "the beatles", "the number of the beast".
            "(?: me)?(?: (some songs by|some music by|something by|anything by|songs by|music by|the artist|the band|" +
            "the song|the track|the single|the album|the record|the playlist|my playlist|playlist|some|a few))? (.+)$",
    )
    private val musicApp: Regex by lazy {
        Regex("^(.+?) (?:on|in|with|using|from|via) (${MusicApp.allAliases.joinToString("|") { Regex.escape(it) }})$")
    }

    /** What follows "play" that isn't music: Play Store and friends, or the media keys above. */
    private val notMusic = setOf(
        "store", "games", "books", "movies", "console", "music", "media", "it", "something", "anything",
        "next", "previous", "back", "again",
    )
    private val likedSongs = setOf(
        "my liked songs", "liked songs", "my liked songs playlist", "the liked songs", "my likes", "my favorites",
        "my favourites", "my favorite songs", "my favourite songs", "my saved songs", "my favorite music",
        "my favourite music",
    )
    private val namedPlaylist = Regex("^(?:my |the )?(.+?) playlist$")
    private val playlistNamed = Regex("^(?:my |the )?playlist (?:called |named )?(.+)$")

    /** "fear of the dark by iron maiden"; the last "by" counts, so "stand by me by ben e king" works. */
    private val byArtist = Regex("^(.+) by (.+)$")

    /** What follows "by" in titles rather than an artist: "stand by me", "by my side". */
    private val notArtists = setOf("me", "you", "us", "him", "her", "them", "my side", "your side", "myself", "yourself", "now")

    private fun music(text: String): Command? {
        val lead = musicLead.matchEntire(text) ?: return null
        val qualifier = lead.groupValues[1]
        val rest = lead.groupValues[2].trim()
        val withApp = musicApp.matchEntire(rest)
        val spoken = (withApp?.groupValues?.get(1) ?: rest).removePrefix("some ").trim()
        val app = withApp?.groupValues?.get(2)?.let(MusicApp::fromName)
        if (spoken.isEmpty() || spoken in notMusic) return null
        // "play spotify" means open it, not search Spotify for "spotify".
        if (app == null && qualifier.isEmpty()) MusicApp.fromName(spoken)?.let { return Command.OpenApp(it.label) }
        if (spoken in likedSongs) return Command.PlayMusic(spoken, app, MusicKind.LIKED)

        val kind = when (qualifier) {
            "the song", "the track", "the single" -> MusicKind.SONG
            "the album", "the record" -> MusicKind.ALBUM
            "the playlist", "my playlist", "playlist" -> MusicKind.PLAYLIST
            "", "some", "a few" -> MusicKind.ANY
            else -> MusicKind.ARTIST
        }
        when (kind) {
            MusicKind.PLAYLIST -> {
                val name = spoken.removePrefix("called ").removePrefix("named ").removeSuffix(" playlist").trim()
                return Command.PlayMusic(name.ifEmpty { return null }, app, MusicKind.PLAYLIST)
            }
            MusicKind.ANY -> {
                // "my workout playlist", "playlist called road trip"
                (namedPlaylist.matchEntire(spoken) ?: playlistNamed.matchEntire(spoken))?.let { m ->
                    val name = m.groupValues[1].trim()
                    if (name !in setOf("the", "my", "a")) return Command.PlayMusic(name, app, MusicKind.PLAYLIST)
                }
            }
            else -> Unit
        }
        if (kind != MusicKind.ARTIST) {
            byArtist.matchEntire(spoken)?.let { m ->
                val title = m.groupValues[1].trim()
                val artist = m.groupValues[2].trim()
                if (title.isNotEmpty() && artist !in notArtists) {
                    return Command.PlayMusic(title, app, if (kind == MusicKind.ANY) MusicKind.SONG else kind, artist)
                }
            }
        }
        return Command.PlayMusic(spoken, app, kind)
    }

    // endregion

    // region Weather & time

    private val weatherLead = Regex(
        "^(?:what(?:'s| is| will be)(?: the)? |how(?:'s| is| will be)(?: the)? |show(?: me)?(?: the)? |check(?: the)? |" +
            "get(?: the)? |tell me the |current )?(?:weather|forecast|temperature)(?: forecast| like| outside)*(.*)$",
    )
    private val weatherQuestion = Regex(
        "^(?:will it|is it going to|is it gonna|is it|does it look like it(?:'s| is) going to|should i expect)(?: be)? " +
            "(?:rain|snow|sunny|cold|hot|warm|windy|raining|snowing|storm|stormy)(.*)$",
    )
    private val howHot = Regex("^how (?:hot|cold|warm) (?:is it|will it be)(.*)$")
    private val umbrella = Regex("^do i need (?:an umbrella|a jacket|a coat|sunscreen)(.*)$")
    private val placeWeather = Regex("^(.+?)(?:'s)? (?:weather|forecast)(?: (?:today|tomorrow|this week|now))?$")
    private val dayWords = mapOf(
        "today" to WeatherDay.TODAY, "tonight" to WeatherDay.TODAY, "now" to WeatherDay.NOW,
        "tomorrow" to WeatherDay.TOMORROW, "this week" to WeatherDay.WEEK, "week" to WeatherDay.WEEK,
        "this weekend" to WeatherDay.WEEK, "weekend" to WeatherDay.WEEK, "next few days" to WeatherDay.WEEK,
    )
    private val herePlaces = setOf("here", "my area", "my location", "outside", "current location", "near me", "around me", "me", "my city")

    private fun weather(text: String): Command? {
        val tail = weatherLead.matchEntire(text)?.groupValues?.get(1)?.takeIf { it.isEmpty() || it.startsWith(" ") }
            ?: weatherQuestion.matchEntire(text)?.groupValues?.get(1)
            ?: howHot.matchEntire(text)?.groupValues?.get(1)
            ?: umbrella.matchEntire(text)?.groupValues?.get(1)
        if (tail != null) return weatherTail(tail)

        placeWeather.matchEntire(text)?.let { m ->
            val subject = m.groupValues[1].removePrefix("the ").trim()
            val day = when {
                text.endsWith("tomorrow") || subject == "tomorrow" -> WeatherDay.TOMORROW
                text.endsWith("this week") -> WeatherDay.WEEK
                else -> WeatherDay.NOW
            }
            if (subject in dayWords) return Command.Weather(null, dayWords.getValue(subject))
            if (subject in herePlaces || subject == "local") return Command.Weather(null, day)
            // "london weather" yes; "weather channel app" isn't matched here, "bad weather" would be a search.
            if (words(subject).size > 3 || subject in setOf("bad", "good", "nice", "the", "app", "my")) return null
            return Command.Weather(placeName(subject), day)
        }
        return null
    }

    /** What follows "weather": optional day words and an optional "in <place>". */
    private fun weatherTail(rawTail: String): Command.Weather? {
        var tail = " ${rawTail.trim()} "
        var day = WeatherDay.NOW
        for ((phrase, value) in dayWords.entries.sortedByDescending { it.key.length }) {
            val pattern = Regex(" (?:for |on |over )?(?:the )?$phrase(?:'s)? ")
            if (pattern.containsMatchIn(tail)) {
                day = value
                tail = tail.replace(pattern, " ")
            }
        }
        tail = tail.replace(Regex(" (?:be|be like|like|outside|going to be|gonna be|today's) "), " ").trim()
        if (tail.isEmpty()) return Command.Weather(null, day)
        val place = Regex("^(?:in|for|at|near|around|of) (.+)$").matchEntire(tail)?.groupValues?.get(1)?.trim()
            // "weather london": a short bare tail is a place, unless it names something else.
            ?: tail.takeIf { words(it).size <= 3 && words(it).none { word -> word in notPlaces } }
            ?: return null
        if (place in herePlaces) return Command.Weather(null, day)
        return Command.Weather(placeName(place), day)
    }

    private val notPlaces = setOf(
        "app", "apps", "channel", "widget", "radar", "map", "maps", "station", "underground", "network",
        "settings", "alert", "alerts", "news", "report", "converter", "conversion", "sensor", "api", "data",
    )

    private fun placeName(raw: String) = raw.trim().removePrefix("the ")

    private val localTime = setOf(
        "time", "the time", "current time", "what time is it", "what's the time", "what is the time", "whats the time",
        "tell me the time", "time now", "date", "the date", "today's date", "todays date", "what's the date",
        "what is the date", "what's today's date", "what is today's date", "what's the date today", "what day is it",
        "what day is it today", "what's today", "what's the day","date today", "what is the day today",
    )
    private val timeIn = Regex("^(?:what(?:'s| is) the |the |current )?(?:local )?time (?:in|at) (.+)$")
    private val whatTimeIn = Regex("^what time is it (?:in|at) (.+)$")
    private val placeTime = Regex("^(.+?) (?:local )?time$")

    private fun clock(text: String): Command? {
        if (text in localTime) return Command.Clock(null, null)
        val place = timeIn.matchEntire(text)?.groupValues?.get(1)
            ?: whatTimeIn.matchEntire(text)?.groupValues?.get(1)
            ?: placeTime.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val found = WorldClock.lookup(place) ?: return null
        return Command.Clock(found.zone, found.name)
    }

    // endregion

    // region Apps & people

    private val photo = Regex("^(?:take|snap|shoot|capture|grab)(?: me)? (?:a |an |my )?(?:photo|picture|pic|selfie|snapshot|photograph)$")
    private val video = Regex("^(?:record|take|shoot|capture|film|start recording)(?: a| an)?(?: new)? video$")

    private fun camera(text: String): Command? = when {
        photo.matches(text) -> Command.Camera(video = false)
        video.matches(text) -> Command.Camera(video = true)
        else -> null
    }

    private val navigation = Regex(
        "^(?:navigate|navigation|directions|get directions|give me directions|show me directions|route)" +
            "(?: me)?(?: to| for)? (.+)$",
    )
    // "drive" and "walk" alone are too ambiguous ("drive files"); they need a "to".
    private val travelTo = Regex("^(?:drive|walk|cycle|take me|bring me|how do i get|how to get|how can i get|get me) (?:me )?to (.+)$")
    private val mapQuery = Regex("^(?:show |find )?(?:me )?(.+?) on (?:the |a )?maps?$")
    private val mapOf = Regex("^(?:show me |open )?(?:a )?map of (.+)$")

    private fun navigate(text: String): Command? {
        val destination = navigation.matchEntire(text)?.groupValues?.get(1)
            ?: travelTo.matchEntire(text)?.groupValues?.get(1)
            ?: mapQuery.matchEntire(text)?.groupValues?.get(1)
            ?: mapOf.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val cleaned = destination.removePrefix("to ").removePrefix("the ").trim()
        if (cleaned.isEmpty() || cleaned in setOf("my phone", "my car keys")) return null
        return Command.Navigate(cleaned)
    }

    private val callPattern = Regex("^(?:call|phone|ring|dial|facetime|video call)(?: up)? (.+?)(?: (?:on|at) (?:their |his |her )?(?:mobile|cell|work|home|phone))?$")
    private val phoneNumber = Regex("^\\+?[\\d][\\d\\s().-]{2,}$")

    private fun call(text: String): Command? {
        val who = callPattern.matchEntire(text)?.groupValues?.get(1)?.trim() ?: return null
        if (who.isEmpty() || who in setOf("me", "back", "it", "them")) return null
        if (phoneNumber.matches(who)) return Command.DialNumber(who.filter { it.isDigit() || it == '+' })
        return Command.Call(who)
    }

    private val messagePattern = Regex(
        "^(?:text|message|msg|sms|send (?:a |an )?(?:text|message|sms|msg)(?: message)? to|send) (.+?)" +
            "(?:(?: saying| that says| that| with the message| with|:) (.+))?$",
    )

    private fun message(text: String): Command? {
        val m = messagePattern.matchEntire(text) ?: return null
        // A bare "send" only counts with a message body ("send mom i'm on my way" is too ambiguous).
        if (text.startsWith("send ") && !text.startsWith("send a") && m.groupValues[2].isEmpty()) return null
        val who = m.groupValues[1].trim()
        if (who.isEmpty()) return null
        return Command.Message(who, m.groupValues[2].trim().takeIf { it.isNotEmpty() })
    }

    private val panelVerbs = setOf(
        "turn", "switch", "toggle", "enable", "disable", "on", "off", "the", "my", "settings", "setting", "open",
        "show", "panel", "connect", "to", "a", "go", "check", "manage", "options", "menu", "page",
    )
    private val panelNames = listOf(
        "wi-fi" to SettingsPanel.WIFI, "wifi" to SettingsPanel.WIFI, "wi fi" to SettingsPanel.WIFI,
        "wireless" to SettingsPanel.WIFI,
        "bluetooth" to SettingsPanel.BLUETOOTH, "blue tooth" to SettingsPanel.BLUETOOTH,
        "mobile data" to SettingsPanel.INTERNET, "cellular data" to SettingsPanel.INTERNET,
        "cellular" to SettingsPanel.INTERNET, "internet" to SettingsPanel.INTERNET, "data" to SettingsPanel.INTERNET,
        "airplane mode" to SettingsPanel.AIRPLANE, "aeroplane mode" to SettingsPanel.AIRPLANE,
        "flight mode" to SettingsPanel.AIRPLANE, "airplane" to SettingsPanel.AIRPLANE,
        "nfc" to SettingsPanel.NFC,
        "hotspot" to SettingsPanel.HOTSPOT, "hot spot" to SettingsPanel.HOTSPOT, "tethering" to SettingsPanel.HOTSPOT,
        "volume" to SettingsPanel.VOLUME, "sound" to SettingsPanel.VOLUME, "sounds" to SettingsPanel.VOLUME,
        "battery saver" to SettingsPanel.BATTERY, "power saving" to SettingsPanel.BATTERY,
        "power saver" to SettingsPanel.BATTERY, "battery" to SettingsPanel.BATTERY,
        "brightness" to SettingsPanel.DISPLAY, "display" to SettingsPanel.DISPLAY, "dark mode" to SettingsPanel.DISPLAY,
        "dark theme" to SettingsPanel.DISPLAY,
        "location" to SettingsPanel.LOCATION, "gps" to SettingsPanel.LOCATION,
        "location services" to SettingsPanel.LOCATION,
        "do not disturb" to SettingsPanel.DND, "dnd" to SettingsPanel.DND, "focus mode" to SettingsPanel.DND,
    ).sortedByDescending { it.first.length }

    private fun panel(text: String): Command? {
        for ((name, panel) in panelNames) {
            val pattern = Regex("(?:^| )${Regex.escape(name)}(?: |$)")
            if (!pattern.containsMatchIn(text)) continue
            val rest = text.replace(pattern, " ").trim()
            if (!onlyWords(rest, panelVerbs)) return null
            // A bare "data" or "display" is more likely a search; require a verb or "settings".
            if (rest.isEmpty() && name in setOf("data", "display", "sound", "sounds", "location", "airplane")) return null
            val words = words(rest)
            val requested = when {
                "on" in words || "enable" in words -> Switch.ON
                "off" in words || "disable" in words -> Switch.OFF
                "toggle" in words -> Switch.TOGGLE
                else -> null
            }
            return Command.OpenPanel(panel, requested)
        }
        return null
    }

    private val whatIs = Regex("^(?:what|who) (?:is|are|was|were)(?: an?| the)? (.+)$")
    private val whatsShort = Regex("^(?:what's|whats|who's|whos)(?: an?| the)? (.+)$")
    private val tellMe = Regex(
        "^(?:tell me about|tell me something about|define|definition of|meaning of|look up|lookup|" +
            "search wikipedia for|wikipedia|wiki)(?: an?| the)? (.+)$",
    )
    private val whatDoesMean = Regex("^what does(?: an?| the)? (.+) mean$")
    private val onWikipedia = Regex("^(.+?) (?:on |in )?wikipedia$")

    /** Questions that aren't about a topic, or that other parts of the assistant answer. */
    private val notTopics = setOf(
        "up", "new", "going on", "happening", "this", "that", "it", "there", "you", "your name", "my name",
        "the matter", "wrong", "next", "on", "playing", "this song", "calling",
    )

    private fun lookup(text: String): Command? {
        val topic = whatDoesMean.matchEntire(text)?.groupValues?.get(1)
            ?: whatIs.matchEntire(text)?.groupValues?.get(1)
            ?: whatsShort.matchEntire(text)?.groupValues?.get(1)
            ?: tellMe.matchEntire(text)?.groupValues?.get(1)
            ?: onWikipedia.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val cleaned = topic.trim().removePrefix("the ").trim()
        if (cleaned.isEmpty() || cleaned in notTopics) return null
        // "what is 12*7" is the calculator's.
        if (com.fastsearch.search.Calculator.evaluate(cleaned) != null) return null
        return Command.Lookup(cleaned)
    }

    private val openPattern = Regex("^(?:open|launch|start|run|go to|show me) (?:up )?(?:the |my )?(.+?)(?: app| application)?$")

    private fun openApp(text: String): Command? {
        val name = openPattern.matchEntire(text)?.groupValues?.get(1)?.trim() ?: return null
        if (name.isEmpty()) return null
        panel(name)?.let { return it }
        return Command.OpenApp(name)
    }

    // endregion

    private fun conversion(text: String): Command? = UnitConverter.parse(text)?.let(Command::Convert)

    private const val MAX_LENGTH = 160
    private const val MAX_TIMER_SECONDS = 24 * 3600
}
