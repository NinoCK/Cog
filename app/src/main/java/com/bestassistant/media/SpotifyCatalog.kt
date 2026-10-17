package com.bestassistant.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import com.bestassistant.commands.MusicKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the Spotify page of an artist, song or album, so "play iron maiden" can open the artist
 * and press play instead of landing on a search. Spotify's own API needs an account and a key;
 * Wikidata records Spotify IDs for artists (P1902), tracks (P2207) and albums (P2205), openly
 * and without either. Found IDs are kept on the phone, so each name is looked up once.
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
        // The artist narrows a song down: "fear of the dark" + "iron maiden" finds the track, not the album.
        val text = listOfNotNull(name, artist).joinToString(" ")
        val search = get(
            "$API?action=query&format=json&list=search&srlimit=$CANDIDATES&srprop=" +
                "&srsearch=${Uri.encode("$text haswbstatement:${type.property}")}",
        ) ?: return null
        val ids = parseSearch(search)
        val item = if (ids.isEmpty()) {
            null
        } else {
            val entities = get(
                "$API?action=wbgetentities&format=json&props=labels%7Caliases&languages=en%7Cel%7Cmul" +
                    "&ids=${Uri.encode(ids.joinToString("|"))}",
            ) ?: return null
            firstNamed(entities, ids, name)
        }
        val id = if (item == null) {
            null
        } else {
            val claims = get("$API?action=wbgetclaims&format=json&entity=$item&property=${type.property}") ?: return null
            parseClaim(claims, type.property)
        }
        if (id == null) {
            misses.put(key, true)
            return null
        }
        val uri = "spotify:${type.path}:$id"
        found.edit { putString(key, uri) }
        return uri
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                // Wikimedia asks API clients to identify themselves.
                setRequestProperty("User-Agent", "Cog/1.0 (Android; privacy-first assistant)")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Request failed: ${url.substringBefore('?')}", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "SpotifyCatalog"
        private const val API = "https://www.wikidata.org/w/api.php"
        private const val CANDIDATES = 5
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
    }
}
