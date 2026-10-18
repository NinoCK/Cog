package com.bestassistant.integrations.spotify

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import com.bestassistant.integration.MusicKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the Spotify page of an artist, song or album, so "play iron maiden" can open the artist
 * and press play instead of landing on a search. Spotify's own API needs an account and a key;
 * Wikidata records Spotify IDs for artists (P1902), tracks (P2207) and albums (P2205), openly
 * and without either. Artists Wikidata doesn't have (many smaller and local ones) are looked up
 * on MusicBrainz, which links them to their Spotify page just as openly. Found IDs are kept on
 * the phone, so each name is looked up once.
 */
class SpotifyCatalog(context: Context) {

    private enum class Type(val property: String, val path: String) {
        ARTIST("P1902", "artist"), TRACK("P2207", "track"), ALBUM("P2205", "album"),
    }

    private val found = context.getSharedPreferences("spotify_catalog", Context.MODE_PRIVATE)
    private val misses = LruCache<String, Boolean>(64)

    /**
     * A "spotify:artist:..." (or track, album) URI for [name], or null when Wikidata doesn't know
     * it or can't be reached. Without a [kind] only artists count: "play jazz" shouldn't pick an
     * album called Jazz.
     */
    suspend fun find(name: String, kind: MusicKind, artist: String? = null): String? {
        val type = when (kind) {
            MusicKind.ANY, MusicKind.ARTIST -> Type.ARTIST
            MusicKind.SONG -> Type.TRACK
            MusicKind.ALBUM -> Type.ALBUM
            MusicKind.PLAYLIST, MusicKind.LIKED -> return null
        }
        val key = "${type.name}|${MusicNames.normalize(name)}|${artist?.let(MusicNames::normalize).orEmpty()}"
        found.getString(key, null)?.let { return it }
        if (misses.get(key) != null) return null

        // Unreachable (offline) returns null without remembering a miss, so the next try asks again.
        val wikidata = fromWikidata(type, name, artist)
        val result = when {
            wikidata is Lookup.Found || type != Type.ARTIST -> wikidata
            else -> when (val musicBrainz = fromMusicBrainz(name)) {
                Lookup.Unknown -> wikidata
                else -> musicBrainz
            }
        }
        return when (result) {
            is Lookup.Found -> "spotify:${type.path}:${result.id}".also { uri -> found.edit { putString(key, uri) } }
            Lookup.Unknown -> {
                misses.put(key, true)
                null
            }
            Lookup.Unreachable -> null
        }
    }

    private sealed interface Lookup {
        data class Found(val id: String) : Lookup
        data object Unknown : Lookup
        data object Unreachable : Lookup
    }

    private suspend fun fromWikidata(type: Type, name: String, artist: String?): Lookup {
        // The artist narrows a song down: "fear of the dark" + "iron maiden" finds the track, not the album.
        val text = listOfNotNull(name, artist).joinToString(" ")
        val search = get(
            "$WIKIDATA?action=query&format=json&list=search&srlimit=$CANDIDATES&srprop=" +
                "&srsearch=${Uri.encode("$text haswbstatement:${type.property}")}",
        ) ?: return Lookup.Unreachable
        val ids = parseSearch(search)
        if (ids.isEmpty()) return Lookup.Unknown
        val entities = get(
            "$WIKIDATA?action=wbgetentities&format=json&props=labels%7Caliases&languages=en%7Cel%7Cmul" +
                "&ids=${Uri.encode(ids.joinToString("|"))}",
        ) ?: return Lookup.Unreachable
        val item = firstNamed(entities, ids, name) ?: return Lookup.Unknown
        val claims = get("$WIKIDATA?action=wbgetclaims&format=json&entity=$item&property=${type.property}")
            ?: return Lookup.Unreachable
        return parseClaim(claims, type.property)?.let { Lookup.Found(it) } ?: Lookup.Unknown
    }

    private suspend fun fromMusicBrainz(name: String): Lookup {
        val words = MusicNames.normalize(name)
        if (words.isEmpty()) return Lookup.Unknown
        // Speech splits names it doesn't know ("sado mas"), so the joined form is searched too.
        val query = listOf(words, words.replace(" ", "")).distinct()
            .joinToString(" OR ") { "artist:\"$it\" OR alias:\"$it\"" }
        val search = get("$MUSICBRAINZ/artist/?fmt=json&limit=$CANDIDATES&query=${Uri.encode(query)}", retryBusy = true)
            ?: return Lookup.Unreachable
        // Two artists can share a name; the first one with a Spotify page is the likelier one.
        for (artistId in parseMusicBrainzArtists(search, name).take(2)) {
            val details = get("$MUSICBRAINZ/artist/$artistId?fmt=json&inc=url-rels", retryBusy = true)
                ?: return Lookup.Unreachable
            parseMusicBrainzSpotify(details)?.let { return Lookup.Found(it) }
        }
        return Lookup.Unknown
    }

    /** The body of [url], or null when it can't be reached. MusicBrainz answers "busy" (503) to quick repeats. */
    private suspend fun get(url: String, retryBusy: Boolean = false): String? {
        val (code, body) = request(url) ?: return null
        if (code == 503 && retryBusy) {
            delay(BUSY_RETRY_MS)
            return request(url)?.takeIf { it.first in 200..299 }?.second
        }
        return body.takeIf { code in 200..299 }
    }

    private suspend fun request(url: String): Pair<Int, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                // Wikimedia and MusicBrainz ask API clients to identify themselves.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = connection.responseCode
                code to if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Request failed: ${url.substringBefore('?')}", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "SpotifyCatalog"
        private const val WIKIDATA = "https://www.wikidata.org/w/api.php"
        private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2"
        private const val USER_AGENT = "Cog/1.0 ( https://github.com/NinoCK/Cog )"
        private const val CANDIDATES = 5
        private const val BUSY_RETRY_MS = 1_100L
        private val spotifyId = Regex("[A-Za-z0-9]{22}")

        /** Item ids ("Q42482") from a full-text search, best match first. */
        fun parseSearch(json: String): List<String> = runCatching {
            val results = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            (0 until results.length()).mapNotNull { results.optJSONObject(it)?.optString("title")?.takeIf { id -> id.startsWith("Q") } }
        }.getOrDefault(emptyList())

        /**
         * The first of [ids] whose name or alias is [name]. Search also matches descriptions and
         * related words; only an item actually called that is the one the user meant.
         */
        fun firstNamed(json: String, ids: List<String>, name: String): String? = runCatching {
            val entities = JSONObject(json).optJSONObject("entities") ?: return null
            ids.firstOrNull { id ->
                val entity = entities.optJSONObject(id) ?: return@firstOrNull false
                val names = buildList {
                    entity.optJSONObject("labels")?.let { labels ->
                        labels.keys().forEach { lang -> labels.optJSONObject(lang)?.optString("value")?.let(::add) }
                    }
                    entity.optJSONObject("aliases")?.let { aliases ->
                        aliases.keys().forEach { lang ->
                            val list = aliases.optJSONArray(lang) ?: return@forEach
                            for (i in 0 until list.length()) list.optJSONObject(i)?.optString("value")?.let(::add)
                        }
                    }
                }
                names.any { MusicNames.same(it, name) }
            }
        }.getOrNull()

        /** The Spotify id in a wbgetclaims response: the preferred statement, else the first current one. */
        fun parseClaim(json: String, property: String): String? = runCatching {
            val claims = JSONObject(json).optJSONObject("claims")?.optJSONArray(property) ?: return null
            val statements = (0 until claims.length()).mapNotNull { claims.optJSONObject(it) }
                .filter { it.optString("rank") != "deprecated" }
            val best = statements.firstOrNull { it.optString("rank") == "preferred" } ?: statements.firstOrNull()
            best?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.takeIf { spotifyId.matches(it) }
        }.getOrNull()

        /** MusicBrainz artist ids from a search, best match first, keeping only those called [name] (or aliased so). */
        fun parseMusicBrainzArtists(json: String, name: String): List<String> = runCatching {
            val artists = JSONObject(json).optJSONArray("artists") ?: return emptyList()
            (0 until artists.length()).mapNotNull { artists.optJSONObject(it) }.filter { artist ->
                val aliases = artist.optJSONArray("aliases")
                val names = listOf(artist.optString("name")) +
                    (0 until (aliases?.length() ?: 0)).mapNotNull { aliases?.optJSONObject(it)?.optString("name") }
                names.any { MusicNames.same(it, name) }
            }.mapNotNull { it.optString("id").takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())

        /** The Spotify artist id among a MusicBrainz artist's links, or null when it has none. */
        fun parseMusicBrainzSpotify(json: String): String? = runCatching {
            val relations = JSONObject(json).optJSONArray("relations") ?: return null
            (0 until relations.length()).mapNotNull { relations.optJSONObject(it) }
                .filterNot { it.optBoolean("ended") }
                .mapNotNull { it.optJSONObject("url")?.optString("resource")?.let(SpotifyLinks::find) }
                .firstOrNull { SpotifyLinks.type(it) == "artist" }
                ?.substringAfterLast(':')
        }.getOrNull()
    }
}
