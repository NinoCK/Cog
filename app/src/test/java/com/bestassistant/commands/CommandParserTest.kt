package com.bestassistant.commands

import com.bestassistant.integrations.homeassistant.DeviceKind
import com.bestassistant.integrations.homeassistant.DeviceSetting
import com.bestassistant.integrations.homeassistant.DeviceVerb
import com.bestassistant.integrations.homeassistant.HomeAction
import com.bestassistant.integrations.homeassistant.HomeAssistantIntegration
import com.bestassistant.integrations.homeassistant.HomeGroup
import com.bestassistant.integrations.homeassistant.HomeRequest
import com.bestassistant.integrations.homeassistant.HomeRequests
import com.bestassistant.integrations.homeassistant.HomeSettings
import com.bestassistant.integrations.homeassistant.NumberUnit
import com.bestassistant.integrations.homeassistant.SettingValue
import com.bestassistant.integrations.jellyfin.ControlRequest
import com.bestassistant.integrations.jellyfin.JellyfinIntegration
import com.bestassistant.integrations.jellyfin.JellyfinRequests
import com.bestassistant.integrations.jellyfin.Library
import com.bestassistant.integrations.jellyfin.PlayRequest
import com.bestassistant.integrations.jellyfin.SeekRequest
import com.bestassistant.integrations.jellyfin.SubtitleRequest
import com.bestassistant.integrations.tasker.TaskRequest
import com.bestassistant.integrations.tasker.TaskRequests
import com.bestassistant.integrations.tasker.TaskerIntegration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CommandParserTest {

    /** Saturday 17 Oct 2026, 08:15 in the morning. */
    private val morning = LocalDateTime.of(2026, 10, 17, 8, 15)

    private fun parse(text: String, now: LocalDateTime = morning) = CommandParser.parse(text, now)

    /** With the Home Assistant integration installed, as most of these sentences assume. */
    @Before
    fun installHomeAssistant() {
        CommandParser.integrations = listOf(CommandParser.Installed(HomeAssistantIntegration.ID, HomeRequests))
    }

    @After
    fun uninstall() {
        CommandParser.integrations = emptyList()
    }

    private fun smartHome(text: String, group: HomeGroup? = null, setting: DeviceSetting? = null) =
        Command.Integration(HomeAssistantIntegration.ID, HomeRequest(text, group, setting))

    private fun homeRequest(text: String) = ((parse(text) as? Command.Integration)?.request as? HomeRequest)

    private fun home(text: String) = homeRequest(text)?.text

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
        assertEquals(Command.Navigate("berlin", map = true), parse("show berlin on the map"))
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
        assertEquals(Command.Lookup("theory of relativity"), parse("tell me about the theory of relativity"))
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
        assertEquals(Command.SetTimer(300), parse("ok pocket, set a timer for 5 minutes"))
        assertEquals(Command.Media(MediaAction.NEXT), parse("hey pocket next song"))
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

    @Test
    fun directionsHomeAndWork() {
        val home = Command.Navigate("home", SavedPlace.HOME)
        val work = Command.Navigate("work", SavedPlace.WORK)
        assertEquals(home, parse("take me home"))
        assertEquals(home, parse("navigate home"))
        assertEquals(home, parse("navigate to my house"))
        assertEquals(home, parse("go home"))
        assertEquals(home, parse("drive home"))
        assertEquals(home, parse("hey pocket, take me home please"))
        assertEquals(work, parse("take me to work"))
        assertEquals(work, parse("directions to the office"))
        assertEquals(work, parse("go to work"))
        assertEquals(Command.Navigate("home", SavedPlace.HOME, NavigationApp.WAZE), parse("take me home with waze"))
        assertEquals(Command.Navigate("home", SavedPlace.HOME, NavigationApp.WAZE), parse("waze home"))
        assertEquals(Command.Navigate("work", SavedPlace.WORK, NavigationApp.GOOGLE_MAPS), parse("navigate to work using google maps"))
        assertEquals(Command.Navigate("airport", app = NavigationApp.WAZE), parse("navigate to the airport on waze"))
        assertEquals(Command.Navigate("airport", app = NavigationApp.WAZE), parse("waze to the airport"))
        assertEquals(Command.Navigate("airport"), parse("let's go to the airport"))
        // A place on a map is shown, not routed to.
        assertEquals(Command.Navigate("berlin", map = true), parse("show me berlin on the map"))
        // Still not navigation.
        assertEquals(Command.OpenApp("waze"), parse("open waze"))
        assertEquals(null, parse("ways to save money"))
        assertEquals(Command.OpenApp("google maps"), parse("open google maps"))
    }

    @Test
    fun webSearch() {
        assertEquals(Command.WebSearch("cheap flights to rome"), parse("search for cheap flights to rome"))
        assertEquals(Command.WebSearch("pizza near me"), parse("search pizza near me"))
        assertEquals(Command.WebSearch("pizza near me"), parse("google pizza near me"))
        assertEquals(Command.WebSearch("best headphones"), parse("search the web for best headphones"))
        assertEquals(Command.WebSearch("best headphones"), parse("search for best headphones online"))
        assertEquals(Command.WebSearch("train times"), parse("look up train times online"))
        assertEquals(Command.WebSearch("kotlin coroutines"), parse("can you search online for kotlin coroutines"))
        // "google maps" is the app; Wikipedia keeps its own phrasing.
        assertEquals(null, parse("google maps"))
        assertEquals(Command.Lookup("quasar"), parse("search wikipedia for quasar"))
        assertEquals(Command.Lookup("quasar"), parse("look up quasar"))
    }

    /** With Tasker installed too, ahead of Home Assistant as in the app. */
    private fun installTasker() {
        CommandParser.integrations = listOf(CommandParser.Installed(TaskerIntegration.ID, TaskRequests)) + CommandParser.integrations
    }

    private fun task(name: String) = Command.Integration(TaskerIntegration.ID, TaskRequest(name))

    @Test
    fun taskerTasks() {
        installTasker()
        assertEquals(task("lights out"), parse("run the task called lights out"))
        assertEquals(task("lights out"), parse("run task lights out"))
        assertEquals(task("lights out"), parse("start the lights out task"))
        assertEquals(task("lights out"), parse("run lights out in tasker"))
        assertEquals(task("good night"), parse("tasker good night"))
        assertEquals(task("good night"), parse("trigger the tasker task good night"))
        assertEquals(Command.OpenApp("tasker"), parse("open tasker"))
        // PocketSearch's own and Home Assistant's requests stay theirs.
        assertEquals(Command.Flashlight(Switch.ON), parse("turn on the flashlight"))
        assertEquals(HomeAssistantIntegration.ID, (parse("turn off all the lights") as? Command.Integration)?.integration)
    }

    @Test
    fun withoutTaskerInstalled() {
        // What was said is then taken like any other words: an app to open, if there's one by that name, or a search.
        assertEquals(Command.OpenApp("task called lights out"), parse("run the task called lights out"))
        assertEquals(null, parse("tasker good night"))
        assertTrue(parse("start the lights out task") !is Command.Integration)
    }

    /** With Tasker and Jellyfin installed too, in the app's order, and a library with one movie PocketSearch knows by name. */
    private fun installJellyfin() {
        val library = Library().apply { set(listOf("Inception")) }
        CommandParser.integrations = listOf(
            CommandParser.Installed(TaskerIntegration.ID, TaskRequests),
            CommandParser.Installed(JellyfinIntegration.ID, JellyfinRequests({ true }, library)),
        ) + CommandParser.integrations
    }

    private fun jellyfin(text: String) = (parse(text) as? Command.Integration)?.takeIf { it.integration == JellyfinIntegration.ID }?.request

    @Test
    fun jellyfin() {
        installJellyfin()
        assertTrue(jellyfin("play inception") is PlayRequest)
        assertTrue(jellyfin("watch the matrix") is PlayRequest)
        assertTrue(jellyfin("play season 2 episode 3 of the office on the bedroom tv") is PlayRequest)
        assertTrue(jellyfin("pause the movie") is ControlRequest)
        assertTrue(jellyfin("pause the movie on the tv") is ControlRequest)
        assertTrue(jellyfin("skip ahead 30 seconds") is SeekRequest)
        assertTrue(jellyfin("turn on subtitles") is SubtitleRequest)
        // The phone's own controls, music, Home Assistant and Tasker keep theirs.
        assertEquals(Command.Media(MediaAction.PAUSE), parse("pause"))
        assertEquals(Command.Media(MediaAction.PLAY), parse("resume"))
        assertTrue(parse("play thriller") is Command.PlayMusic)
        assertTrue(parse("play inception on spotify") is Command.PlayMusic)
        assertTrue(parse("continue") !is Command.Integration)
        assertTrue(jellyfin("watch netflix") == null)
        assertEquals(HomeAssistantIntegration.ID, (parse("turn off all the lights") as? Command.Integration)?.integration)
        assertEquals(HomeAssistantIntegration.ID, (parse("pause the tv") as? Command.Integration)?.integration)
        assertEquals(task("lights out"), parse("start the lights out task"))
    }

    @Test
    fun smartHome() {
        assertEquals("turn on the kitchen light", home("turn on the kitchen light"))
        assertEquals(
            smartHome("turn off all the lights", HomeGroup(HomeAction.OFF, DeviceKind.LIGHT)),
            parse("please turn off all the lights"),
        )
        assertEquals("turn the bedroom lamp off", home("turn the bedroom lamp off"))
        assertEquals("switch on the tv", home("switch on the tv"))
        assertEquals("dim the living room lights", home("dim the living room lights"))
        assertEquals("set the thermostat to 21 degrees", home("set the thermostat to 21 degrees"))
        assertEquals("set the living room lights to 40%", home("set the living room lights to 40%"))
        assertEquals("lock the front door", home("lock the front door"))
        assertEquals("close the garage door", home("close the garage door"))
        assertEquals(smartHome("open the blinds", HomeGroup(HomeAction.OPEN, DeviceKind.COVER)), parse("open the blinds"))
        assertEquals("activate movie night scene", home("activate movie night scene"))
        assertEquals("is the front door locked", home("ask home assistant is the front door locked"))
        assertEquals("start the vacuum", home("start the vacuum in home assistant"))
        // The phone's own switches come first.
        assertEquals(Command.Flashlight(Switch.ON), parse("turn on the flashlight"))
        assertEquals(Command.OpenPanel(SettingsPanel.WIFI, Switch.OFF), parse("turn off wifi"))
        assertEquals(Command.Volume(VolumeChange.Set(50)), parse("set the volume to 50"))
        assertEquals(Command.Shuffle(true), parse("turn on shuffle"))
        assertEquals(Command.OpenApp("calendar"), parse("open the calendar"))
        assertEquals(null, parse("set dark mode"))
    }

    @Test
    fun smartHomeSettings() {
        fun setting(text: String) = homeRequest(text)?.setting
        assertEquals(DeviceSetting("the ac", SettingValue.Number(22.0)), setting("set the ac to 22"))
        assertEquals(DeviceSetting("the bedroom ac", SettingValue.Number(22.5, NumberUnit.DEGREES)), setting("set the bedroom ac to 22.5 degrees"))
        assertEquals(DeviceSetting("the ac in the bedroom", SettingValue.Word("cooling")), setting("set the ac in the bedroom to cooling"))
        assertEquals(DeviceSetting("the air purifier", SettingValue.Word("sleep mode")), setting("put the air purifier in sleep mode"))
        assertEquals(DeviceSetting("the ac fan", SettingValue.Word("high")), setting("set the ac fan to high"))
        assertEquals(DeviceSetting("the purifier speed", SettingValue.Number(40.0, NumberUnit.PERCENT)), setting("set the purifier speed to 40%"))
        assertEquals(DeviceSetting("the ac", SettingValue.Number(21.0, NumberUnit.DEGREES)), setting("set 21 degrees on the ac"))
        assertEquals(DeviceSetting("the temperature", SettingValue.Number(23.0)), setting("set the temperature to 23"))
        assertEquals(DeviceSetting("the heating", SettingValue.Step(true)), setting("turn up the heating"))
        assertEquals(DeviceSetting("the ac", SettingValue.Step(false)), setting("turn the ac down"))
        assertEquals(DeviceSetting("the bedroom temperature", SettingValue.Step(true)), setting("make the bedroom warmer"))
        assertEquals(DeviceSetting("the ac swing", SettingValue.Word("on")), setting("turn on the ac swing"))
        // Any "turn on/off ..." goes to PocketSearch's own matching first, whatever the device is called.
        assertEquals(DeviceSetting("desk ac", SettingValue.Word("on")), setting("turn desk ac on"))
        assertEquals(DeviceSetting("the fridge plug", SettingValue.Word("off")), setting("turn off the fridge plug"))
        assertEquals(DeviceSetting("desk ac", SettingValue.Number(22.0, NumberUnit.DEGREES)), setting("turn desk ac to 22 degrees"))
        assertEquals(DeviceSetting("the purifier child lock", SettingValue.Word("off")), setting("disable the purifier child lock"))
        assertEquals(DeviceSetting("bedroom ac", SettingValue.Number(24.0)), setting("bedroom ac to 24"))
        assertEquals(
            smartHome("set kevin to sleep mode", setting = DeviceSetting("kevin", SettingValue.Word("sleep mode"))),
            parse("ask home assistant to set kevin to sleep mode"),
        )
        // Not about a device in the house.
        assertNull(setting("set the timer to 5 minutes"))
        assertEquals(Command.Volume(VolumeChange.Set(50)), parse("set the volume to 50"))
        assertNull(setting("set the mood to happy"))
        assertEquals(DeviceSetting("kevin", SettingValue.Word("sleep mode")), HomeSettings.loose(CommandParser.normalize("set Kevin to sleep mode")))
    }

    @Test
    fun smartHomeColoursMediaAndActions() {
        fun setting(text: String) = homeRequest(text)?.setting
        fun action(verb: DeviceVerb, mode: String? = null) = SettingValue.Action(verb, mode)
        assertEquals(DeviceSetting("the kitchen light", SettingValue.Word("red")), setting("turn the kitchen light red"))
        assertEquals(DeviceSetting("all the lights", SettingValue.Word("warm white")), setting("make all the lights warm white"))
        assertEquals(DeviceSetting("the lamp", SettingValue.Number(3000.0, NumberUnit.KELVIN)), setting("set the lamp to 3000 kelvin"))
        assertEquals(DeviceSetting("the kitchen light warmth", SettingValue.Step(false)), setting("make the kitchen light warmer"))
        assertEquals(DeviceSetting("the light effect", SettingValue.Word("rainbow")), setting("set the light effect to rainbow"))
        assertEquals(DeviceSetting("the tv volume", SettingValue.Number(20.0)), setting("set the tv volume to 20"))
        assertEquals(DeviceSetting("the tv", SettingValue.Step(true)), setting("turn up the tv"))
        assertEquals(DeviceSetting("the tv", SettingValue.Word("hdmi 2")), setting("switch the tv to hdmi 2"))
        assertEquals(DeviceSetting("the tv mute", SettingValue.Word("on")), setting("mute the tv"))
        assertEquals(DeviceSetting("the vacuum", action(DeviceVerb.START)), setting("start the vacuum"))
        assertEquals(DeviceSetting("the robot", action(DeviceVerb.PAUSE)), setting("pause the robot"))
        assertEquals(DeviceSetting("the vacuum", action(DeviceVerb.DOCK)), setting("send the vacuum back to its dock"))
        assertEquals(DeviceSetting("vacuum", action(DeviceVerb.START)), setting("vacuum the house"))
        assertEquals(DeviceSetting("the mower", action(DeviceVerb.DOCK)), setting("dock the mower"))
        assertEquals(DeviceSetting("the front door", action(DeviceVerb.UNLOCK)), setting("unlock the front door"))
        assertEquals(DeviceSetting("the front door", action(DeviceVerb.LOCK)), setting("lock the front door"))
        assertEquals(DeviceSetting("the alarm", action(DeviceVerb.ARM, "night")), setting("arm the alarm for the night"))
        assertEquals(DeviceSetting("alarm", action(DeviceVerb.ARM, "away")), setting("arm away"))
        assertEquals(DeviceSetting("the alarm", action(DeviceVerb.ARM)), setting("arm the alarm"))
        assertEquals(DeviceSetting("the security system", action(DeviceVerb.DISARM)), setting("disarm the security system"))
        assertEquals(DeviceSetting("the garden valve", action(DeviceVerb.OPEN)), setting("open the garden valve"))
        assertEquals(DeviceSetting("the doorbell", action(DeviceVerb.PRESS)), setting("press the doorbell button"))
        // Not about a device in the house.
        assertNull(setting("lock the screen"))
        assertNull(setting("arm wrestling"))
        assertEquals(Command.Navigate("home", SavedPlace.HOME), parse("take me home"))
        assertEquals(Command.OpenApp("spotify"), parse("open spotify"))
    }

    private fun group(text: String) = homeRequest(text)?.group

    @Test
    fun smartHomeGroups() {
        val allLightsOff = HomeGroup(HomeAction.OFF, DeviceKind.LIGHT)
        for (text in listOf(
            "turn off all the lights", "turn off all lights", "turn off the lights", "turn the lights off", "switch off every light",
            "lights off", "all lights off", "turn off the lights everywhere", "turn off all the lights in the house", "shut off the lamps",
        )) assertEquals(text, allLightsOff, group(text))
        assertEquals(HomeGroup(HomeAction.ON, DeviceKind.LIGHT), group("turn on all the lights"))
        assertEquals(HomeGroup(HomeAction.ON, DeviceKind.LIGHT), group("lights on"))
        assertEquals(HomeGroup(HomeAction.OFF, DeviceKind.LIGHT, "kitchen"), group("turn off the lights in the kitchen"))
        assertEquals(HomeGroup(HomeAction.OFF, DeviceKind.LIGHT, "kitchen"), group("turn the kitchen lights off"))
        assertEquals(HomeGroup(HomeAction.ON, DeviceKind.LIGHT, "living room"), group("living room lights on"))
        assertEquals(HomeGroup(HomeAction.ON, DeviceKind.LIGHT, "living room"), group("turn on all the lights in the living room"))
        assertEquals(HomeGroup(HomeAction.CLOSE, DeviceKind.COVER, "bedroom"), group("close the blinds in the bedroom"))
        assertEquals(HomeGroup(HomeAction.CLOSE, DeviceKind.COVER), group("lower all the shutters"))
        assertEquals(HomeGroup(HomeAction.LOCK, DeviceKind.LOCK), group("lock all the doors"))
        assertEquals(HomeGroup(HomeAction.OFF, DeviceKind.MEDIA), group("turn off the tvs"))
        assertEquals(HomeGroup(HomeAction.OFF, DeviceKind.SWITCH), group("turn off all plugs"))
        assertEquals(HomeGroup(HomeAction.OFF, DeviceKind.CLIMATE), group("turn off the air conditioners"))
        assertEquals(HomeGroup(HomeAction.OFF, null), group("turn off everything"))
        assertEquals(HomeGroup(HomeAction.OFF, null), group("turn everything off"))
        assertEquals(HomeGroup(HomeAction.OFF, null, "kitchen"), group("turn off everything in the kitchen"))
        assertEquals(
            smartHome("turn off all the lights", HomeGroup(HomeAction.OFF, DeviceKind.LIGHT)),
            parse("ask home assistant to turn off all the lights"),
        )
        // One device stays with Home Assistant's Assist.
        assertEquals("turn on the kitchen light", home("turn on the kitchen light"))
        assertEquals("switch on the tv", home("switch on the tv"))
        assertEquals("close the garage door", home("close the garage door"))
        // Never everything on, and never unlock everything.
        assertNull(group("turn on everything"))
        assertNull(group("unlock all the doors"))
        assertNull(group("close all the doors"))
        // The phone's own switches still come first.
        assertEquals(Command.Flashlight(Switch.OFF), parse("turn off the flashlight"))
        assertEquals(Command.OpenPanel(SettingsPanel.WIFI, Switch.OFF), parse("turn off wifi"))
    }

    @Test
    fun questionsNotAboutTheHouse() {
        assertTrue(parse("what's the temperature in london") is Command.Weather)
        assertTrue(parse("what's the temperature outside") is Command.Weather)
        assertTrue(parse("what's the temperature") is Command.Weather)
        assertNull(homeRequest("is the bakery open"))
        assertTrue(homeRequest("lower the bedroom temperature").let { it != null && it.query == null })
    }

    @Test
    fun withoutHomeAssistantInstalled() {
        CommandParser.integrations = emptyList()
        assertTrue(parse("turn off all the lights") !is Command.Integration)
        assertTrue(parse("is the front door locked") !is Command.Integration)
        assertEquals(Command.Flashlight(Switch.ON), parse("turn on the flashlight"))
    }

    private val today = morning.toLocalDate()

    @Test
    fun youTubeSearches() {
        assertEquals(Command.YouTubeSearch("cat videos"), parse("search youtube for cat videos"))
        assertEquals(Command.YouTubeSearch("cat videos"), parse("search on YouTube for cat videos"))
        assertEquals(Command.YouTubeSearch("how to tie a tie"), parse("look up how to tie a tie on youtube"))
        assertEquals(Command.YouTubeSearch("lofi beats"), parse("youtube search lofi beats"))
        assertEquals(Command.YouTubeSearch("the new trailer"), parse("watch the new trailer on youtube"))
        assertEquals(Command.OpenApp("YouTube"), parse("search youtube"))
        // Music on YouTube is still "play", and other searches stay web searches.
        assertEquals(Command.PlayMusic("lofi beats", MusicApp.YOUTUBE), parse("play lofi beats on youtube"))
        assertEquals(Command.WebSearch("pizza near me"), parse("search pizza near me"))
    }

    @Test
    fun reminders() {
        // Saturday 08:15: "at 5" is this afternoon.
        assertEquals(Command.Remind("call mom", ReminderTime.At(today, 17, 0)), parse("remind me at 5 to call mom"))
        assertEquals(Command.Remind("call mom", ReminderTime.At(today, 17, 0)), parse("remind me to call mom at 5"))
        assertEquals(Command.Remind("check the oven", ReminderTime.After(1200)), parse("remind me in 20 minutes to check the oven"))
        assertEquals(Command.Remind(null, ReminderTime.After(1200)), parse("remind me in twenty minutes"))
        assertEquals(Command.Remind("pay the rent", ReminderTime.At(today.plusDays(1), 9, 0)), parse("remind me tomorrow at 9 to pay the rent"))
        assertEquals(Command.Remind("pay the rent", ReminderTime.At(today.plusDays(1), 9, 0)), parse("remind me tomorrow to pay the rent"))
        assertEquals(Command.Remind("take out the trash", ReminderTime.At(today, 20, 0)), parse("remind me to take out the trash tonight"))
        assertEquals(Command.Remind("watch the game", ReminderTime.At(today, 19, 30)), parse("set a reminder for 7:30 pm to watch the game"))
        assertEquals(Command.Remind("book a table for 4", ReminderTime.At(today, 20, 0)), parse("remind me to book a table for 4 at 8"))
        assertEquals(Command.Remind("call the bank", ReminderTime.At(today.plusDays(2), 9, 0)), parse("remind me on monday to call the bank"))
        assertEquals(Command.Remind("call the bank", ReminderTime.At(today.plusDays(2), 15, 0)), parse("remind me on monday at 3 to call the bank"))
        assertEquals(Command.Remind("put the cake in", ReminderTime.After(600)), parse("remind me to put the cake in in 10 minutes"))
        assertEquals(Command.Remind("water the plants", ReminderTime.After(3 * 86400)), parse("remind me in 3 days to water the plants"))
        assertEquals(Command.Remind(null, ReminderTime.After(600)), parse("set a reminder for 10 minutes"))
        assertEquals(Command.Remind("call mom", null), parse("remind me to call mom"))
        assertEquals(Command.Remind(null, null), parse("remind me"))
        assertEquals(Command.ShowReminders, parse("show my reminders"))
        assertEquals(Command.ShowReminders, parse("what are my reminders"))
        assertEquals(Command.CancelReminders, parse("cancel all my reminders"))
        // Alarms and timers are still theirs.
        assertEquals(Command.SetAlarm(7, 0), parse("set an alarm for 7 am"))
        assertEquals(Command.SetTimer(600), parse("set a timer for 10 minutes"))
    }

    @Test
    fun reminderAnswers() {
        assertEquals(ReminderTime.At(today, 17, 0), CommandParser.reminderTime("at 5", morning))
        assertEquals(ReminderTime.At(today, 17, 0), CommandParser.reminderTime("5 pm", morning))
        assertEquals(ReminderTime.After(600), CommandParser.reminderTime("in ten minutes", morning))
        assertEquals(ReminderTime.At(today.plusDays(1), 9, 0), CommandParser.reminderTime("tomorrow", morning))
        assertNull(CommandParser.reminderTime("whenever", morning))
        assertEquals(Command.Remind("call mom", ReminderTime.At(today, 17, 0)), CommandParser.reminderAbout("to call mom at 5", morning))
    }

    @Test
    fun nameDays() {
        assertEquals(Command.NameDays(today), parse("who celebrates today"))
        assertEquals(Command.NameDays(today), parse("whose name day is it today"))
        assertEquals(Command.NameDays(today.plusDays(1)), parse("name days tomorrow"))
        assertEquals(Command.NameDays(today), parse("name days"))
        assertEquals(Command.NameDay("Eleni"), parse("when is Eleni's name day"))
        assertEquals(Command.NameDay("George"), parse("when does george celebrate"))
        assertEquals(Command.NameDay("Maria"), parse("name day of maria"))
    }

    @Test
    fun parkingAndLocation() {
        assertEquals(Command.SaveParking, parse("remember where I parked"))
        assertEquals(Command.SaveParking, parse("I parked here"))
        assertEquals(Command.FindCar(directions = true), parse("take me to my car"))
        assertEquals(Command.FindCar(directions = true), parse("navigate back to my car"))
        assertEquals(Command.FindCar(directions = false), parse("where did I park"))
        assertEquals(Command.FindCar(directions = false), parse("where's my car"))
        assertEquals(Command.ShareLocation("alex"), parse("send alex my location"))
        assertEquals(Command.ShareLocation("alex"), parse("send my location to alex"))
        assertEquals(Command.ShareLocation("mom"), parse("tell mom where I am"))
        assertEquals(Command.ShareLocation("dad"), parse("share my location with dad"))
        // Directions elsewhere and plain messages are unchanged.
        assertTrue(parse("take me to the airport") is Command.Navigate)
        assertEquals(Command.Message("alex", "on my way"), parse("text alex saying on my way"))
    }
}
