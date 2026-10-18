package com.bestassistant.integrations.jellyfin

import com.bestassistant.integration.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinTest {

    private val library = Library().apply { set(listOf("Inception", "Breaking Bad", "The Office", "Murder on the Orient Express", "It", "Dune")) }
    private val parser = JellyfinRequests({ true }, library)
    private val notSetUp = JellyfinRequests({ false }, library)

    private fun early(text: String) = parser.parse(text, Slot.EARLY)
    private fun late(text: String) = parser.parse(text, Slot.LATE)

    private fun play(text: String, slot: Slot = Slot.EARLY): PlayRequest = parser.parse(text, slot) as PlayRequest

    // region Playing

    @Test
    fun `a title in the library plays`() {
        assertEquals(Wanted.Title("inception"), play("play inception").what)
        assertEquals(Wanted.Title("breaking bad"), play("watch breaking bad").what)
    }

    @Test
    fun `play with a name the library doesn't have stays music`() {
        assertNull(early("play thriller"))
        assertNull(early("play the beatles"))
        assertNull(early("play inception on spotify"))
    }

    @Test
    fun `everyday words are never titles`() {
        assertNull(early("play it"))
        assertEquals(Wanted.Title("it", TitleType.MOVIE), play("play the movie it").what)
    }

    @Test
    fun `watch takes any name late`() {
        assertNull(early("watch the matrix"))
        assertEquals(Wanted.Title("the matrix"), play("watch the matrix", Slot.LATE).what)
        assertNull(late("play the matrix"))
    }

    @Test
    fun `naming jellyfin takes any name`() {
        assertEquals(Wanted.Title("abbey road"), play("play abbey road on jellyfin").what)
        assertEquals(Wanted.Title("abbey road", TitleType.MUSIC), play("play the album abbey road on jellyfin").what)
        assertEquals(Wanted.Title("the matrix"), play("jellyfin play the matrix").what)
    }

    @Test
    fun `not set up, only requests naming jellyfin`() {
        assertNull(notSetUp.parse("play inception", Slot.EARLY))
        assertNull(notSetUp.parse("pause the movie", Slot.EARLY))
        assertTrue(notSetUp.parse("play inception on jellyfin", Slot.EARLY) is PlayRequest)
    }

    @Test
    fun `a player can follow`() {
        val request = play("play inception on the living room tv")
        assertEquals(Wanted.Title("inception"), request.what)
        assertEquals("living room tv", request.player)
    }

    @Test
    fun `a title with on in it is kept whole when no player has that name`() {
        val request = play("play murder on the orient express")
        assertEquals(Wanted.Title("murder on the orient express"), request.unsplit ?: request.what)
    }

    @Test
    fun `episodes`() {
        assertEquals(Wanted.Episode("the office", 2, 3), play("play season 2 episode 3 of the office").what)
        assertEquals(Wanted.Episode("the office", 2, 3), play("play season two episode three of the office").what)
        assertEquals(Wanted.Episode("breaking bad", 4, 1), play("watch breaking bad season 4 episode 1").what)
        assertEquals(Wanted.Episode("breaking bad", 1, 5), play("play breaking bad s01e05").what)
        assertEquals(Wanted.Episode("the office", 3, null), play("play season 3 of the office").what)
        assertEquals(Wanted.Episode("the office", null, 7), play("play episode 7 of the office").what)
        val there = play("play season 2 episode 3 of the office on the bedroom tv")
        assertEquals(Wanted.Episode("the office", 2, 3), there.what)
        assertEquals("bedroom tv", there.player)
    }

    @Test
    fun `next, previous, latest, random, resume`() {
        assertEquals(Wanted.NextEpisode(null), play("play the next episode").what)
        assertEquals(Wanted.NextEpisode(null), play("next episode").what)
        assertEquals(Wanted.NextEpisode(null), play("skip this episode").what)
        assertEquals(Wanted.PreviousEpisode, play("previous episode").what)
        assertEquals(Wanted.NextEpisode("breaking bad"), play("play the next episode of breaking bad").what)
        assertEquals(Wanted.LatestEpisode("the office"), play("play the latest episode of the office").what)
        assertEquals(Wanted.Random(TitleType.MOVIE, "Comedy"), play("put on a comedy").what)
        assertEquals(Wanted.Random(TitleType.MOVIE, "Horror"), play("watch a random horror movie").what)
        assertEquals(Wanted.Random(TitleType.MOVIE), play("play a random movie").what)
        assertEquals(Wanted.Random(TitleType.SHOW, show = "friends"), play("play a random episode of friends").what)
        assertEquals(Wanted.Resume, play("continue watching").what)
        assertEquals(Wanted.Title("breaking bad"), play("continue watching breaking bad").what)
        assertEquals(Wanted.Title("inception", TitleType.MOVIE), play("play the inception movie").what)
        assertEquals(Wanted.Title("inception", fromStart = true), play("play inception from the beginning").what)
        assertEquals(Wanted.Title("the office", shuffle = true), play("shuffle the office").what)
    }

    // endregion

    // region Controls

    @Test
    fun `transport`() {
        assertEquals(Transport.PAUSE, (early("pause the movie") as ControlRequest).action)
        assertEquals(Transport.RESUME, (early("resume the show") as ControlRequest).action)
        assertEquals(Transport.STOP, (early("stop the episode") as ControlRequest).action)
        assertEquals(Transport.PAUSE, (early("pause jellyfin") as ControlRequest).action)
        assertEquals(Transport.NEXT, (early("skip on jellyfin") as ControlRequest).action)
        val there = early("pause the movie on the bedroom tv") as ControlRequest
        assertEquals("bedroom tv", there.player)
        // The phone's own media keys stay PocketSearch's.
        assertNull(early("pause"))
        assertNull(early("open jellyfin"))
    }

    @Test
    fun `seeking`() {
        assertEquals(SeekRequest(30, true, "skip ahead 30 seconds"), early("skip ahead 30 seconds"))
        assertEquals(30, (early("fast forward") as SeekRequest).seconds)
        assertEquals(-10, (early("rewind") as SeekRequest).seconds)
        assertEquals(-10, (early("go back 10 seconds") as SeekRequest).seconds)
        assertEquals(-90, (early("rewind a minute and a half") as SeekRequest).seconds)
        assertEquals(120, (early("skip 2 minutes") as SeekRequest).seconds)
        assertEquals(SeekRequest(4800, false, "jump to 1 hour 20 minutes"), early("jump to 1 hour 20 minutes"))
        assertEquals(4800, (early("go to 1:20:00") as SeekRequest).seconds)
        assertEquals(SeekRequest(0, false, "start the movie over"), early("start the movie over"))
        assertNull(early("go back"))
        assertNull(early("go home"))
    }

    @Test
    fun `subtitles and audio`() {
        assertEquals(SubtitleRequest(true, null, "turn on subtitles"), early("turn on subtitles"))
        assertEquals(SubtitleRequest(false, null, "subtitles off"), early("subtitles off"))
        assertEquals(SubtitleRequest(false, null, "turn the subtitles off"), early("turn the subtitles off"))
        assertEquals(SubtitleRequest(true, "greek", "turn on greek subtitles"), early("turn on greek subtitles"))
        assertEquals(SubtitleRequest(true, "english", "switch subtitles to english"), early("switch subtitles to english"))
        assertEquals(AudioRequest("english", "switch the audio to english"), early("switch the audio to english"))
        assertEquals(AudioRequest("japanese", "watch it in japanese"), early("watch it in japanese"))
        assertEquals(AudioRequest("original", "original audio"), early("original audio"))
    }

    @Test
    fun `volume only for jellyfin by name`() {
        assertEquals(VolumeChange.To(30), (early("set the jellyfin volume to 30") as VolumeRequest).change)
        assertEquals(VolumeChange.Up, (early("jellyfin volume up") as VolumeRequest).change)
        assertEquals(VolumeChange.Mute, (early("mute the movie") as VolumeRequest).change)
        assertNull(early("louder"))
    }

    // endregion

    // region Questions

    @Test
    fun `questions`() {
        assertEquals(Asked.NOW_PLAYING, (early("what am i watching") as QuestionRequest).asked)
        assertEquals(Asked.NOW_PLAYING, (early("what's playing on jellyfin") as QuestionRequest).asked)
        assertEquals(Asked.TIME_LEFT, (early("how much time is left") as QuestionRequest).asked)
        assertEquals(Asked.TIME_LEFT, (early("when does the movie end") as QuestionRequest).asked)
        assertEquals(Asked.NEW, (early("what's new on jellyfin") as QuestionRequest).asked)
        assertEquals(Asked.NEW, (early("any new movies") as QuestionRequest).asked)
        assertEquals(Asked.NEXT_UP, (early("what should i watch") as QuestionRequest).asked)
        assertEquals(HaveRequest("dune", "do i have dune"), early("do i have dune"))
        assertEquals(HaveRequest("the matrix", "do i have the movie the matrix"), early("do i have the movie the matrix"))
        assertNull(early("do i have a meeting today"))
        assertEquals(MarkRequest(null, true, "mark this as watched"), early("mark this as watched"))
        assertEquals(MarkRequest("inception", false, "mark inception as unwatched"), early("mark inception as unwatched"))
    }

    // endregion

    // region Words

    @Test
    fun `titles match the way they're said`() {
        assertEquals("spider man no way home", Titles.key("Spider-Man: No Way Home"))
        assertEquals("office", Titles.key("The Office"))
        assertEquals("amelie", Titles.key("Amélie"))
        assertEquals(3, Titles.score("spider man no way home", "Spider-Man: No Way Home"))
        assertEquals(2, Titles.score("dune", "Dune: Part Two"))
        assertEquals(0, Titles.score("dune", "Inception"))
        assertTrue(library.has("the office"))
        assertTrue(library.has("office"))
    }

    @Test
    fun `numbers and lengths of time`() {
        assertEquals(21, Spoken.number("twenty one"))
        assertEquals(3, Spoken.number("third"))
        assertEquals(30, Spoken.duration("30 seconds"))
        assertEquals(90, Spoken.duration("a minute and a half"))
        assertEquals(150, Spoken.duration("2 and a half minutes"))
        assertEquals(1800, Spoken.duration("half an hour"))
        assertEquals(4800, Spoken.duration("1 hour and 20 minutes"))
        assertEquals(30, Spoken.duration("30", bareUnit = 1))
        assertNull(Spoken.duration("30"))
        assertNull(Spoken.duration("the next episode"))
    }

    // endregion

    // region The server's answers

    @Test
    fun `players are read from sessions`() {
        val body = """
            [
              {"Id": "s1", "DeviceName": "Living Room TV", "Client": "Android TV", "DeviceId": "tv", "SupportsRemoteControl": true,
               "NowPlayingItem": {"Id": "e1", "Name": "Pilot", "Type": "Episode", "SeriesName": "Breaking Bad", "SeriesId": "bb",
                                  "ParentIndexNumber": 1, "IndexNumber": 1, "RunTimeTicks": 34800000000},
               "PlayState": {"PositionTicks": 6000000000, "IsPaused": true, "VolumeLevel": 80, "SubtitleStreamIndex": -1},
               "SupportedCommands": ["SetVolume", "Mute"]},
              {"Id": "s2", "DeviceName": "Phone", "Client": "PocketSearch", "DeviceId": "cog", "SupportsRemoteControl": false}
            ]
        """.trimIndent()
        val players = Jellyfin.parsePlayers(body)
        assertEquals(1, players.size)
        val tv = players.single()
        assertEquals("Living Room TV", tv.name)
        assertEquals("Breaking Bad", tv.playing?.seriesName)
        assertEquals(1, tv.playing?.season)
        assertEquals(6000000000, tv.position)
        assertTrue(tv.paused)
        assertEquals(-1, tv.subtitleIndex)
        assertTrue("SetVolume" in tv.commands)
    }

    @Test
    fun `items carry their tracks`() {
        val body = """
            {"Items": [{"Id": "m1", "Name": "Inception", "Type": "Movie", "ProductionYear": 2010,
              "UserData": {"PlaybackPositionTicks": 100, "Played": false},
              "MediaStreams": [{"Index": 1, "Type": "Audio", "Language": "eng", "DisplayTitle": "English - AAC"},
                               {"Index": 3, "Type": "Subtitle", "Language": "gre", "IsForced": false}]}]}
        """.trimIndent()
        val item = Jellyfin.parseItems(org.json.JSONObject(body).optJSONArray("Items")).single()
        assertEquals(2010, item.year)
        assertEquals(100, item.position)
        assertEquals(listOf("Audio", "Subtitle"), item.streams.map { it.type })
        assertEquals("gre", item.streams[1].language)
    }

    @Test
    fun `addresses`() {
        assertEquals("http://jellyfin.local:8096", Jellyfin.baseUrl("jellyfin.local:8096/"))
        assertEquals("https://media.example.com", Jellyfin.baseUrl("https://media.example.com"))
        assertNull(Jellyfin.baseUrl(""))
    }

    // endregion
}
