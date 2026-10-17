package com.bestassistant.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CommandParserTest {

    /** Saturday 17 Oct 2026, 08:15 in the morning. */
    private val morning = LocalDateTime.of(2026, 10, 17, 8, 15)

    private fun parse(text: String, now: LocalDateTime = morning) = CommandParser.parse(text, now)

    private fun alarm(text: String, now: LocalDateTime = morning): Command.SetAlarm {
        val command = parse(text, now)
        assertTrue("expected an alarm for '$text', got $command", command is Command.SetAlarm)
        return command as Command.SetAlarm
    }

    private fun timer(text: String): Command.SetTimer {
        val command = parse(text)
        assertTrue("expected a timer for '$text', got $command", command is Command.SetTimer)
        return command as Command.SetTimer
    }

    @Test
    fun alarmsWithExplicitTimes() {
        assertEquals(Command.SetAlarm(7, 30), alarm("set an alarm for 7:30 am"))
        assertEquals(Command.SetAlarm(19, 30), alarm("alarm 7:30pm"))
        assertEquals(Command.SetAlarm(6, 0), alarm("wake me up at 6am"))
        assertEquals(Command.SetAlarm(7, 0), alarm("alarm 07:00"))
        assertEquals(Command.SetAlarm(21, 45), alarm("Set alarm 21:45."))
        assertEquals(Command.SetAlarm(7, 30), alarm("alarm 0730"))
        assertEquals(Command.SetAlarm(12, 0), alarm("alarm at noon"))
        assertEquals(Command.SetAlarm(0, 0), alarm("alarm midnight"))
        assertEquals(Command.SetAlarm(7, 30), alarm("alarm for half past 7 am"))
        assertEquals(Command.SetAlarm(7, 45), alarm("alarm at quarter to 8 in the morning"))
        assertEquals(Command.SetAlarm(6, 0), alarm("hey, could you wake me up at six tomorrow morning please?"))
    }

    @Test
    fun ambiguousAlarmTimesPickTheNextOccurrence() {
        // At 08:15, "7" next happens at 19:00, "9" at 09:00.
        assertEquals(19, alarm("alarm 7").hour)
        assertEquals(9, alarm("alarm 9").hour)
        // Late in the evening, "7" means tomorrow morning.
        assertEquals(7, alarm("alarm 7", LocalDateTime.of(2026, 10, 17, 23, 0)).hour)
        assertEquals(7, alarm("alarm 7 in the morning").hour)
        assertEquals(21, alarm("alarm 9 tonight").hour)
    }

    @Test
    fun relativeAlarmsAndLabels() {
        assertEquals(Command.SetAlarm(8, 45), alarm("alarm in 30 minutes"))
        assertEquals(Command.SetAlarm(16, 15), alarm("wake me up in 8 hours"))
        assertEquals(Command.SetAlarm(6, 0, "gym"), alarm("alarm 6am gym"))
        assertEquals(Command.SetAlarm(6, 0, "gym"), alarm("set an alarm for 6am called gym"))
    }

    @Test
    fun timers() {
        assertEquals(Command.SetTimer(300), timer("timer 5 min"))
        assertEquals(Command.SetTimer(300), timer("set a timer for 5 minutes"))
        assertEquals(Command.SetTimer(300), timer("5 minute timer"))
        assertEquals(Command.SetTimer(300), timer("timer 5"))
        assertEquals(Command.SetTimer(5400), timer("set a timer for 1 hour and 30 minutes"))
        assertEquals(Command.SetTimer(5400), timer("timer 1h30"))
        assertEquals(Command.SetTimer(5400), timer("timer for an hour and a half"))
        assertEquals(Command.SetTimer(1800), timer("half an hour timer"))
        assertEquals(Command.SetTimer(90), timer("countdown 90 seconds"))
        assertEquals(Command.SetTimer(1500), timer("set a timer for twenty-five minutes"))
        assertEquals(Command.SetTimer(600, "pasta"), timer("10 minute pasta timer"))
        assertEquals(Command.SetTimer(600, "pasta"), timer("set a timer for 10 minutes for pasta"))
        assertEquals(Command.SetTimer(150), timer("timer 2.5 min"))
    }

    @Test
    fun showClockScreens() {
        assertEquals(Command.ShowAlarms, parse("alarms"))
        assertEquals(Command.ShowAlarms, parse("show my alarms"))
        assertEquals(Command.ShowTimers, parse("timer"))
        assertEquals(Command.ShowTimers, parse("timers"))
    }

    @Test
    fun deviceControls() {
        assertEquals(Command.Flashlight(Switch.ON), parse("turn on the flashlight"))
        assertEquals(Command.Flashlight(Switch.OFF), parse("torch off"))
        assertEquals(Command.Flashlight(Switch.TOGGLE), parse("flashlight"))
        assertEquals(Command.Volume(VolumeChange.Up), parse("volume up"))
        assertEquals(Command.Volume(VolumeChange.Down), parse("turn it down"))
        assertEquals(Command.Volume(VolumeChange.Set(40)), parse("set volume to 40%"))
        assertEquals(Command.Volume(VolumeChange.Set(100)), parse("max volume"))
        assertEquals(Command.Volume(VolumeChange.Mute), parse("mute"))
        assertEquals(Command.Media(MediaAction.PAUSE), parse("pause the music"))
        assertEquals(Command.Media(MediaAction.NEXT), parse("next song"))
        assertEquals(Command.Media(MediaAction.PLAY), parse("resume"))
        assertEquals(Command.OpenPanel(SettingsPanel.WIFI, Switch.ON), parse("turn on wifi"))
        assertEquals(Command.OpenPanel(SettingsPanel.BLUETOOTH, Switch.OFF), parse("bluetooth off"))
        assertEquals(Command.OpenPanel(SettingsPanel.WIFI, null), parse("wi-fi"))
        assertEquals(Command.OpenPanel(SettingsPanel.DND, null), parse("do not disturb"))
        assertEquals(Command.OpenPanel(SettingsPanel.BLUETOOTH, null), parse("open bluetooth settings"))
        assertEquals(Command.OpenPanel(SettingsPanel.VOLUME, null), parse("volume"))
    }

    @Test
    fun weather() {
        assertEquals(Command.Weather(null, WeatherDay.NOW), parse("weather"))
        assertEquals(Command.Weather(null, WeatherDay.NOW), parse("what's the weather like?"))
        assertEquals(Command.Weather("london", WeatherDay.NOW), parse("weather in London"))
        assertEquals(Command.Weather("london", WeatherDay.NOW), parse("weather london"))
        assertEquals(Command.Weather("london", WeatherDay.NOW), parse("london weather"))
        assertEquals(Command.Weather("paris", WeatherDay.TOMORROW), parse("weather tomorrow in paris"))
        assertEquals(Command.Weather(null, WeatherDay.TOMORROW), parse("will it rain tomorrow"))
        assertEquals(Command.Weather(null, WeatherDay.TOMORROW), parse("tomorrow's weather"))
        assertEquals(Command.Weather("new york", WeatherDay.WEEK), parse("forecast for new york this week"))
        assertEquals(Command.Weather(null, WeatherDay.NOW), parse("how cold is it outside"))
        assertEquals(Command.Weather(null, WeatherDay.TODAY), parse("do i need an umbrella today"))
    }

    @Test
    fun clockAndConversions() {
        assertEquals(Command.Clock(null, null), parse("what time is it"))
        assertEquals(Command.Clock(null, null), parse("what's the date today"))
        val tokyo = parse("time in tokyo") as Command.Clock
        assertEquals(ZoneId.of("Asia/Tokyo"), tokyo.zone)
        assertEquals("Tokyo", tokyo.place)
        assertEquals(ZoneId.of("America/New_York"), (parse("what time is it in new york") as Command.Clock).zone)
        assertEquals(ZoneId.of("America/Los_Angeles"), (parse("sf time") as Command.Clock).zone)

        val km = (parse("5 km to miles") as Command.Convert).conversion
        assertEquals(3.10686, km.result, 1e-5)
        val f = (parse("100f in c") as Command.Convert).conversion
        assertEquals(37.7778, f.result, 1e-4)
        val grams = (parse("how many grams in a pound") as Command.Convert).conversion
        assertEquals(453.592, grams.result, 1e-3)
        assertEquals("3.10686", km.plainResult)
    }

    @Test
    fun peopleAppsAndPlaces() {
        assertEquals(Command.Call("mom"), parse("call mom"))
        assertEquals(Command.Call("alex smith"), parse("call alex smith on mobile"))
        assertEquals(Command.DialNumber("+15551234567"), parse("call +1 555 123 4567"))
        assertEquals(Command.Message("alex", "on my way"), parse("text alex saying on my way"))
        assertEquals(Command.Message("mom", null), parse("send a message to mom"))
        assertEquals(Command.OpenApp("spotify"), parse("open spotify"))
        assertEquals(Command.OpenApp("google maps"), parse("launch the google maps app"))
        assertEquals(Command.Navigate("airport"), parse("navigate to the airport"))
        assertEquals(Command.Navigate("eiffel tower"), parse("take me to eiffel tower"))
        assertEquals(Command.Navigate("berlin"), parse("show berlin on the map"))
        assertEquals(Command.Camera(video = false), parse("take a selfie"))
        assertEquals(Command.Camera(video = true), parse("record a video"))
    }

    @Test
    fun music() {
        assertEquals(Command.PlayMusic("iron maiden", MusicApp.SPOTIFY), parse("playing Iron maiden in spotify"))
        assertEquals(Command.PlayMusic("iron maiden", MusicApp.SPOTIFY), parse("play iron maiden on spotify"))
        assertEquals(Command.PlayMusic("iron maiden", MusicApp.SPOTIFY), parse("Play Iron Maiden on Spotify."))
        assertEquals(Command.PlayMusic("metallica", MusicApp.YOUTUBE_MUSIC), parse("play some Metallica on YouTube Music"))
        assertEquals(Command.PlayMusic("lofi beats", MusicApp.YOUTUBE), parse("put on lofi beats on youtube"))
        assertEquals(Command.PlayMusic("the number of the beast", null), parse("play the number of the beast"))
        assertEquals(Command.PlayMusic("jazz", null), parse("I want to listen to some jazz"))
        assertEquals(Command.OpenApp("Spotify"), parse("play spotify"))
        // The media keys keep their meaning.
        assertEquals(Command.Media(MediaAction.PLAY), parse("play music"))
        assertEquals(Command.Media(MediaAction.NEXT), parse("play next song"))
    }

    @Test
    fun musicKinds() {
        assertEquals(
            Command.PlayMusic("fear of the dark", MusicApp.SPOTIFY, MusicKind.SONG, "iron maiden"),
            parse("play the song fear of the dark by iron maiden on spotify"),
        )
        assertEquals(Command.PlayMusic("fear of the dark", null, MusicKind.SONG, "iron maiden"), parse("play fear of the dark by iron maiden"))
        assertEquals(Command.PlayMusic("stand by me", null, MusicKind.SONG, "ben e king"), parse("play stand by me by ben e king"))
        assertEquals(Command.PlayMusic("stand by me", null), parse("play stand by me"))
        assertEquals(Command.PlayMusic("hallowed be thy name", null, MusicKind.SONG), parse("play the song hallowed be thy name"))
        assertEquals(Command.PlayMusic("powerslave", null, MusicKind.ALBUM), parse("play the album powerslave"))
        assertEquals(Command.PlayMusic("metallica", MusicApp.SPOTIFY, MusicKind.ARTIST), parse("play songs by metallica on spotify"))
        assertEquals(Command.PlayMusic("the beatles", null), parse("play the beatles"))

        assertEquals(Command.PlayMusic("workout", MusicApp.SPOTIFY, MusicKind.PLAYLIST), parse("play my workout playlist on spotify"))
        assertEquals(Command.PlayMusic("road trip", null, MusicKind.PLAYLIST), parse("play the playlist road trip"))
        assertEquals(Command.PlayMusic("chill vibes", null, MusicKind.PLAYLIST), parse("play playlist called chill vibes"))
        assertEquals(Command.PlayMusic("road trip", null, MusicKind.PLAYLIST), parse("play my playlist road trip"))
        assertEquals(Command.PlayMusic("my liked songs", MusicApp.SPOTIFY, MusicKind.LIKED), parse("play my liked songs on spotify"))
    }

    @Test
    fun wikipedia() {
        assertEquals(Command.Lookup("quasar"), parse("what is a quasar?"))
        assertEquals(Command.Lookup("ada lovelace"), parse("Who was Ada Lovelace"))
        assertEquals(Command.Lookup("eiffel tower"), parse("what's the eiffel tower"))
        assertEquals(Command.Lookup("capital of australia"), parse("what is the capital of Australia"))
        assertEquals(Command.Lookup("lisbon"), parse("tell me about Lisbon"))
        assertEquals(Command.Lookup("serendipity"), parse("what does serendipity mean"))
        assertEquals(Command.Lookup("black holes"), parse("black holes wikipedia"))
        // Other parts of the assistant answer these.
        assertEquals(Command.Clock(null, null), parse("what time is it"))
        assertEquals(Command.Weather(null, WeatherDay.NOW), parse("what's the weather"))
        assertTrue(parse("what is 5 km in miles") is Command.Convert)
        assertNull(parse("what is 12*7"))
        assertNull(parse("what's up"))
    }

    @Test
    fun ordinarySearchesStaySearches() {
        val searches = listOf(
            "", "chrome", "play", "next", "alarm clock", "fire alarm", "weather channel app", "bad weather",
            "screen time", "drive", "drive files", "where is the liver", "kotlin coroutines", "data", "display",
            "12*(3+4)", "github.com", "light", "2 apples to oranges", "youtube music", "spotify",
            "play store", "play games", "playstation",
        )
        for (text in searches) {
            assertNull("'$text' should be a plain search, got ${parse(text)}", parse(text))
        }
    }

    @Test
    fun playerControls() {
        assertEquals(Command.NowPlaying, parse("what's playing?"))
        assertEquals(Command.NowPlaying, parse("what song is this"))
        assertEquals(Command.LikeSong, parse("like this song"))
        assertEquals(Command.LikeSong, parse("add this to my liked songs"))
        assertEquals(Command.Shuffle(true), parse("turn on shuffle"))
        assertEquals(Command.Shuffle(false), parse("shuffle off"))
        assertEquals(Command.Repeat(true), parse("repeat this song"))
        assertEquals(Command.Repeat(false), parse("stop repeating"))
        // "shuffle <artist>" still plays music.
        assertEquals(Command.PlayMusic("metallica", null), parse("shuffle metallica"))
    }

    @Test
    fun wakePhraseIsNotPartOfTheRequest() {
        assertEquals(Command.SetTimer(300), parse("ok cog, set a timer for 5 minutes"))
        assertEquals(Command.Media(MediaAction.NEXT), parse("hey cog next song"))
    }

    @Test
    fun spokenNumbers() {
        assertEquals("25 minutes", Spoken.digitize("twenty-five minutes"))
        assertEquals("7 30", Spoken.digitize("seven thirty"))
        assertNotNull(Spoken.clock("7 30"))
        assertNull(Spoken.clock("25:00"))
        assertNull(Spoken.clock("13pm"))
        assertNull(Spoken.duration("pasta"))
    }
}
