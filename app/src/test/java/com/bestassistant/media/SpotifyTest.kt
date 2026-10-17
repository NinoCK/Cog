package com.bestassistant.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTest {

    @Test
    fun namesMatchTheWayPeopleSayThem() {
        assertTrue(MusicNames.same("Iron Maiden", "iron maiden"))
        assertTrue(MusicNames.same("The Beatles", "beatles"))
        assertTrue(MusicNames.same("AC/DC", "acdc"))
        assertTrue(MusicNames.same("Guns N' Roses", "guns n roses"))
        // Greek case endings: "βάλε Βασίλη Παπακωνσταντίνου"
        assertTrue(MusicNames.same("Βασίλης Παπακωνσταντίνου", "βασίλη παπακωνσταντίνου"))
        assertTrue(MusicNames.same("Μάλαμας", "μάλαμα"))
        assertFalse(MusicNames.same("Ella Fitzgerald", "jazz"))
        assertFalse(MusicNames.same("Iron Maiden", "maiden"))
    }

    @Test
    fun savedPlaylists() {
        val saved = listOf("Road Trip", "Workout Mix", "Γυμναστική", "Chill")
        fun find(spoken: String, exactOnly: Boolean = false) = MusicNames.bestPlaylist(spoken, saved, exactOnly) { it }

        assertEquals("Road Trip", find("road trip"))
        assertEquals("Road Trip", find("my road trip playlist"))
        assertEquals("Workout Mix", find("workout"))
        assertEquals("Γυμναστική", find("γυμναστικη"))
        assertEquals("Γυμναστική", find("τη λίστα γυμναστική"))
        // Without "playlist" only the exact name counts, so "play workout" can still be a song.
        assertNull(find("workout", exactOnly = true))
        assertEquals("Chill", find("chill", exactOnly = true))
        assertNull(find("jazz"))
    }

    @Test
    fun spotifyLinks() {
        assertEquals(
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            SpotifyLinks.find("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=1a2b3c4d5e6f"),
        )
        assertEquals(
            "spotify:album:5J5fnu7PEwhgzdkiBBtblw",
            SpotifyLinks.find("Check this out https://open.spotify.com/intl-el/album/5J5fnu7PEwhgzdkiBBtblw"),
        )
        assertEquals(
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            SpotifyLinks.find("spotify:user:someone:playlist:37i9dQZF1DXcBWIGoYBM5M"),
        )
        assertEquals("https://spotify.link/AbC123xyz", SpotifyLinks.find("https://spotify.link/AbC123xyz"))
        assertNull(SpotifyLinks.find("https://example.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))

        assertEquals("playlist", SpotifyLinks.type("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(SpotifyLinks.type("https://spotify.link/AbC123xyz"))
    }

    /** An artist page on a 2400 px tall screen: header, the big play button, a track row, the mini player. */
    private fun artistPage(playLabel: String = "Play", miniPlayer: String = "Play") = listOf(
        PlayButtons.ScreenNode("Iron Maiden", null, 500, 620, 900, 120),
        PlayButtons.ScreenNode("Enable shuffle", null, 800, 900, 100, 100),
        PlayButtons.ScreenNode(playLabel, "com.spotify.music:id/play_button", 780, 920, 140, 140),
        PlayButtons.ScreenNode("Play preview", null, 1000, 1100, 200, 100),
        PlayButtons.ScreenNode("The Trooper", null, 1200, 1300, 900, 100),
        PlayButtons.ScreenNode(miniPlayer, null, 2050, 2130, 80, 80),
    )

    @Test
    fun pressesThePagesPlayButton() {
        assertEquals(PlayButtons.Choice.Press(2), PlayButtons.choose(artistPage(), 2400, "iron maiden", waitedMs = 300))
        assertEquals(PlayButtons.Choice.Press(2), PlayButtons.choose(artistPage("Αναπαραγωγή"), 2400, "iron maiden", 300))
        // Free accounts get a shuffle-play button instead.
        assertEquals(PlayButtons.Choice.Press(2), PlayButtons.choose(artistPage("Shuffle play"), 2400, "iron maiden", 300))
    }

    @Test
    fun leavesAPlayingPageAlone() {
        assertEquals(PlayButtons.Choice.Playing, PlayButtons.choose(artistPage("Pause"), 2400, "iron maiden", 300))
        assertEquals(PlayButtons.Choice.Playing, PlayButtons.choose(artistPage("Παύση"), 2400, "iron maiden", 300))
    }

    @Test
    fun waitsForTheRightPage() {
        // The page from before (another artist) is still showing: wait, unless it's taking long.
        val oldPage = artistPage().map { if (it.label == "Iron Maiden") it.copy(label = "Metallica") else it }
        assertEquals(PlayButtons.Choice.NotYet, PlayButtons.choose(oldPage, 2400, "iron maiden", 300))
        assertEquals(PlayButtons.Choice.Press(2), PlayButtons.choose(oldPage, 2400, "iron maiden", 3_000))
        // With no name to check, a short grace period.
        assertEquals(PlayButtons.Choice.NotYet, PlayButtons.choose(artistPage(), 2400, null, 300))
        assertEquals(PlayButtons.Choice.Press(2), PlayButtons.choose(artistPage(), 2400, null, 1_500))
    }

    @Test
    fun neverPressesTheMiniPlayer() {
        val onlyMiniPlayer = artistPage().filter { it.viewId == null }
        assertEquals(PlayButtons.Choice.NotYet, PlayButtons.choose(onlyMiniPlayer, 2400, "iron maiden", 300))
    }

    @Test
    fun clientIdAndFingerprint() {
        assertTrue(SpotifyRemote.isClientId("0123456789abcdef0123456789ABCDEF"))
        assertTrue(SpotifyRemote.isClientId(" 0123456789abcdef0123456789abcdef "))
        assertFalse(SpotifyRemote.isClientId("0123456789abcdef"))
        assertFalse(SpotifyRemote.isClientId("not a client id at all, really!!"))
        // SHA1("abc"), in the colon-separated form Spotify's dashboard takes.
        assertEquals(
            "A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D",
            SpotifyRemote.fingerprint("abc".toByteArray()),
        )
    }

    @Test
    fun wikidataResponses() {
        val search = """{"batchcomplete":"","query":{"searchinfo":{"totalhits":3},"search":[
            {"ns":0,"title":"Q1768"},{"ns":0,"title":"Q42482"},{"ns":0,"title":"Q209646"}]}}"""
        assertEquals(listOf("Q1768", "Q42482", "Q209646"), SpotifyCatalog.parseSearch(search))

        val entities = """{"entities":{
            "Q1768":{"labels":{"en":{"language":"en","value":"Ella Fitzgerald"}},
                     "aliases":{"en":[{"language":"en","value":"Queen of Jazz"}]}},
            "Q42482":{"labels":{"en":{"language":"en","value":"Iron Maiden"},"el":{"language":"el","value":"Iron Maiden"}},"aliases":{}},
            "Q7555416":{"labels":{"el":{"language":"el","value":"Σωκράτης Μάλαμας"}},
                        "aliases":{"el":[{"language":"el","value":"Μάλαμας"}]}}}}"""
        // Search can rank other items first; only one actually called that counts.
        assertEquals("Q42482", SpotifyCatalog.firstNamed(entities, listOf("Q1768", "Q42482"), "iron maiden"))
        assertEquals("Q7555416", SpotifyCatalog.firstNamed(entities, listOf("Q7555416"), "μάλαμα"))
        assertNull(SpotifyCatalog.firstNamed(entities, listOf("Q1768"), "jazz"))

        val claims = """{"claims":{"P2207":[
            {"mainsnak":{"datavalue":{"value":"6p8eFfPw3nQkf37aT3AkmK","type":"string"}},"rank":"normal"},
            {"mainsnak":{"datavalue":{"value":"0P4JuEXYJmu2Q7BTtQW5nR","type":"string"}},"rank":"preferred"}]}}"""
        assertEquals("0P4JuEXYJmu2Q7BTtQW5nR", SpotifyCatalog.parseClaim(claims, "P2207"))
        assertNull(SpotifyCatalog.parseClaim("""{"claims":{}}""", "P1902"))
    }

    /** Sadomas, a Greek rapper Wikidata doesn't have, as MusicBrainz knows him. */
    @Test
    fun musicBrainzFindsArtistsWikidataLacks() {
        val search = """{"count":2,"artists":[
            {"id":"ed12a438-8e35-4d2a-a175-ce5d869ce7d6","score":100,"name":"Sadomas","disambiguation":"Greek Rapper"},
            {"id":"0e936b57-1219-4ef4-8753-7f4bc15d66d9","score":83,"name":"Thitis x Sadomas"},
            {"id":"11111111-1111-1111-1111-111111111111","score":70,"name":"Someone Else","aliases":[{"name":"Sadomas"}]}]}"""
        assertEquals(
            listOf("ed12a438-8e35-4d2a-a175-ce5d869ce7d6", "11111111-1111-1111-1111-111111111111"),
            SpotifyCatalog.parseMusicBrainzArtists(search, "sadomas"),
        )
        // Speech recognition splits names it doesn't know.
        assertEquals("ed12a438-8e35-4d2a-a175-ce5d869ce7d6", SpotifyCatalog.parseMusicBrainzArtists(search, "Sado mas").first())
        assertEquals(emptyList<String>(), SpotifyCatalog.parseMusicBrainzArtists(search, "thitis"))

        val details = """{"name":"Sadomas","relations":[
            {"type":"bandsintown","ended":false,"url":{"resource":"https://www.bandsintown.com/a/3821979"}},
            {"type":"free streaming","ended":true,"url":{"resource":"https://open.spotify.com/artist/0000000000000000000000"}},
            {"type":"free streaming","ended":false,"url":{"resource":"https://open.spotify.com/album/5J5fnu7PEwhgzdkiBBtblw"}},
            {"type":"free streaming","ended":false,"url":{"resource":"https://open.spotify.com/artist/3eZXDyJChvCDzsAGtNeGLP"}},
            {"type":"free streaming","ended":false,"url":{"resource":"https://www.deezer.com/artist/11199068"}}]}"""
        assertEquals("3eZXDyJChvCDzsAGtNeGLP", SpotifyCatalog.parseMusicBrainzSpotify(details))
        assertNull(SpotifyCatalog.parseMusicBrainzSpotify("""{"name":"Nobody","relations":[]}"""))
    }
}
