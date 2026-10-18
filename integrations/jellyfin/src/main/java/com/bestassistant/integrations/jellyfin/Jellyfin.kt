package com.bestassistant.integrations.jellyfin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The user's own Jellyfin server, reached directly (usually on the home network) and signed in to
 * as the user: its library searched, and its players (the TV app, the web app, Kodi) told what to
 * play and how. Nothing passes through anyone else's server.
 *
 * [login] is the address, user name and password from settings; the token it signs in for is kept
 * by [session] and signed in for again whenever the server stops taking it.
 */
class Jellyfin(private val login: () -> Login, private val session: Session, private val device: String) {

    /** What settings hold. */
    data class Login(val address: String, val user: String, val password: String)

    /** Where the token is kept: [read] what was signed in for, [save] a new one (null forgets it). */
    interface Session {
        fun read(): Signed?
        fun save(signed: Signed?)

        /** Stable and random, so the server keeps one session for Cog. */
        val deviceId: String
    }

    /** A token, with the server and user it was signed in for. */
    data class Signed(val address: String, val user: String, val userId: String, val token: String)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val why: Problem) : Result<Nothing>
    }

    enum class Problem { NOT_SET_UP, UNAUTHORIZED, UNREACHABLE, REFUSED }

    /** A player the user has open (the TV app, a browser) that can be told what to do. */
    data class Player(
        val id: String,
        val name: String,
        val client: String,
        val deviceId: String,
        val playing: Item?,
        val position: Long,
        val paused: Boolean,
        val muted: Boolean,
        val volume: Int?,
        val audioIndex: Int?,
        val subtitleIndex: Int?,
        val commands: Set<String>,
    )

    /** Something in the library: a movie, a show, an episode, an album, a song. */
    data class Item(
        val id: String,
        val name: String,
        val type: String,
        val year: Int? = null,
        val seriesName: String? = null,
        val seriesId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val runtime: Long? = null,
        val position: Long = 0,
        val played: Boolean = false,
        val originalTitle: String? = null,
        val streams: List<Stream> = emptyList(),
        val isFolder: Boolean = false,
    )

    /** One of a video's audio or subtitle tracks: [index] is what a player is told to switch to. */
    data class Stream(val index: Int, val type: String, val language: String?, val title: String?, val isDefault: Boolean, val forced: Boolean)

    val isSetUp: Boolean get() = login().let { baseUrl(it.address) != null && it.user.isNotBlank() }

    // region Signing in

    /** Signs in afresh, for the settings screen. */
    suspend fun signIn(): Result<Signed> {
        val credentials = login()
        val base = baseUrl(credentials.address) ?: return Result.Failed(Problem.NOT_SET_UP)
        if (credentials.user.isBlank()) return Result.Failed(Problem.NOT_SET_UP)
        val body = JSONObject().put("Username", credentials.user.trim()).put("Pw", credentials.password)
        return when (val response = http("POST", "$base/Users/AuthenticateByName", null, body.toString())) {
            is Http.Ok -> {
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                val token = json?.optString("AccessToken").orEmpty()
                val userId = json?.optJSONObject("User")?.optString("Id").orEmpty()
                if (token.isEmpty() || userId.isEmpty()) return Result.Failed(Problem.UNREACHABLE)
                Signed(base, credentials.user.trim(), userId, token).also(session::save).let { Result.Ok(it) }
            }
            is Http.Status -> Result.Failed(if (response.code == 401 || response.code == 403) Problem.UNAUTHORIZED else Problem.UNREACHABLE)
            Http.Failed -> Result.Failed(Problem.UNREACHABLE)
        }
    }

    /** The token for the address and user in settings: the one kept, or a new one. */
    private suspend fun signed(): Result<Signed> {
        val credentials = login()
        val base = baseUrl(credentials.address) ?: return Result.Failed(Problem.NOT_SET_UP)
        session.read()?.takeIf { it.address == base && it.user == credentials.user.trim() }?.let { return Result.Ok(it) }
        return signIn()
    }

    /** Asks [path] with the kept token, signing in again once when the server no longer takes it. */
    private suspend fun call(method: String, path: String, body: String? = null): Result<String> {
        var signed = when (val result = signed()) {
            is Result.Ok -> result.value
            is Result.Failed -> return result
        }
        repeat(2) { attempt ->
            val url = signed.address + path.replace(USER, signed.userId)
            when (val response = http(method, url, signed.token, body)) {
                is Http.Ok -> return Result.Ok(response.body)
                Http.Failed -> return Result.Failed(Problem.UNREACHABLE)
                is Http.Status -> when {
                    (response.code == 401 || response.code == 403) && attempt == 0 -> {
                        session.save(null)
                        signed = when (val again = signIn()) {
                            is Result.Ok -> again.value
                            is Result.Failed -> return again
                        }
                    }
                    response.code == 401 || response.code == 403 -> return Result.Failed(Problem.UNAUTHORIZED)
                    response.code in 400..499 -> return Result.Failed(Problem.REFUSED)
                    else -> return Result.Failed(Problem.UNREACHABLE)
                }
            }
        }
        return Result.Failed(Problem.UNAUTHORIZED)
    }

    private suspend fun json(path: String): Result<JSONObject> = when (val result = call("GET", path)) {
        is Result.Ok -> runCatching { JSONObject(result.value) }.getOrNull()?.let { Result.Ok(it) } ?: Result.Failed(Problem.UNREACHABLE)
        is Result.Failed -> result
    }

    private suspend fun items(path: String): Result<List<Item>> = when (val result = json(path)) {
        is Result.Ok -> Result.Ok(parseItems(result.value.optJSONArray("Items")))
        is Result.Failed -> result
    }

    // endregion

    // region The library

    /** Movies and shows (and, with [music], artists, albums, songs and playlists) whose names match [query]. */
    suspend fun search(query: String, types: List<String>, limit: Int = 20): Result<List<Item>> =
        items("/Items?userId=$USER&searchTerm=${encode(query)}&Recursive=true&IncludeItemTypes=${types.joinToString(",")}&Limit=$limit&Fields=$FIELDS&EnableImages=false")

    /** Each of [ids], as the user sees it (where they stopped, whether they've seen it), with its tracks. */
    suspend fun get(ids: List<String>): Result<List<Item>> =
        items("/Items?userId=$USER&Ids=${ids.joinToString(",")}&Fields=$FIELDS,MediaStreams&EnableImages=false")

    /** Every movie and show, for knowing their names when they're said. */
    suspend fun titles(): Result<List<Item>> =
        items("/Items?userId=$USER&Recursive=true&IncludeItemTypes=Movie,Series&Fields=OriginalTitle,ProductionYear&EnableImages=false&EnableUserData=false")

    /** What the user would watch next of [seriesId] (or, without one, of every show they're watching). */
    suspend fun nextUp(seriesId: String? = null, limit: Int = 1): Result<List<Item>> =
        items("/Shows/NextUp?userId=$USER&Limit=$limit&Fields=$FIELDS&EnableImages=false&enableResumable=true" + (seriesId?.let { "&seriesId=$it" } ?: ""))

    /** All the episodes of [seriesId] that are actually there, in order. */
    suspend fun episodes(seriesId: String): Result<List<Item>> =
        items("/Shows/$seriesId/Episodes?userId=$USER&Fields=$FIELDS&isMissing=false&EnableImages=false")

    /** What the user stopped partway through, most recent first. */
    suspend fun resumable(limit: Int = 5): Result<List<Item>> =
        items("/Items?userId=$USER&Recursive=true&Filters=IsResumable&IncludeItemTypes=Movie,Episode&SortBy=DatePlayed&SortOrder=Descending&Limit=$limit&Fields=$FIELDS&EnableImages=false")

    /** What was added most recently. */
    suspend fun latest(limit: Int = 6): Result<List<Item>> =
        items("/Items?userId=$USER&Recursive=true&IncludeItemTypes=Movie,Series&SortBy=DateCreated&SortOrder=Descending&Limit=$limit&Fields=$FIELDS&EnableImages=false")

    /** Something of [type] picked at random, in [genre] if given; [unwatched] for something not seen yet. */
    suspend fun random(type: String, genre: String?, unwatched: Boolean): Result<List<Item>> =
        items(
            "/Items?userId=$USER&Recursive=true&IncludeItemTypes=$type&SortBy=Random&Limit=1&Fields=$FIELDS&EnableImages=false" +
                (genre?.let { "&Genres=${encode(it)}" } ?: "") + (if (unwatched) "&IsPlayed=false" else ""),
        )

    /** Marks [itemId] seen (or not). */
    suspend fun setPlayed(itemId: String, played: Boolean): Result<Unit> {
        val method = if (played) "POST" else "DELETE"
        // Jellyfin 10.9 moved it; older servers only know the old address.
        return when (val result = call(method, "/UserPlayedItems/$itemId?userId=$USER")) {
            is Result.Ok -> Result.Ok(Unit)
            is Result.Failed -> if (result.why == Problem.REFUSED) unit(call(method, "/Users/$USER/PlayedItems/$itemId")) else result
        }
    }

    // endregion

    // region Players

    /** The players the user has open that can be told what to do, the most recently used first; Cog itself isn't one. */
    suspend fun players(): Result<List<Player>> = when (val result = call("GET", "/Sessions?controllableByUserId=$USER&activeWithinSeconds=$ACTIVE_SECONDS")) {
        is Result.Ok -> Result.Ok(parsePlayers(result.value).filter { it.deviceId != session.deviceId })
        is Result.Failed -> result
    }

    /** Starts [ids] on [player] (the rest queued after the first), [at] ticks in, or shuffled. */
    suspend fun play(player: Player, ids: List<String>, at: Long = 0, shuffle: Boolean = false): Result<Unit> {
        val command = if (shuffle) "PlayShuffle" else "PlayNow"
        val start = if (at > 0) "&startPositionTicks=$at" else ""
        return unit(call("POST", "/Sessions/${player.id}/Playing?playCommand=$command&itemIds=${ids.joinToString(",")}$start"))
    }

    /** "Pause", "Unpause", "Stop", "NextTrack", "PreviousTrack", "Seek" (to [ticks]). */
    suspend fun playstate(player: Player, command: String, ticks: Long? = null): Result<Unit> =
        unit(call("POST", "/Sessions/${player.id}/Playing/$command" + (ticks?.let { "?seekPositionTicks=$it" } ?: "")))

    /** One of Jellyfin's general commands ("SetVolume", "Mute", "SetSubtitleStreamIndex"), with its [arguments]. */
    suspend fun command(player: Player, name: String, arguments: Map<String, String> = emptyMap()): Result<Unit> {
        val body = JSONObject().put("Name", name).put("Arguments", JSONObject(arguments as Map<*, *>))
        return unit(call("POST", "/Sessions/${player.id}/Command", body.toString()))
    }

    /** Where [item] streams from, for a video player on this phone; null until signed in. */
    suspend fun streamUrl(item: Item): String? {
        val signed = (signed() as? Result.Ok)?.value ?: return null
        val kind = if (item.type == "Audio") "Audio" else "Videos"
        return "${signed.address}/$kind/${item.id}/stream?static=true&api_key=${signed.token}"
    }

    // endregion

    private fun unit(result: Result<String>): Result<Unit> = when (result) {
        is Result.Ok -> Result.Ok(Unit)
        is Result.Failed -> result
    }

    private sealed interface Http {
        data class Ok(val body: String) : Http
        data class Status(val code: Int) : Http
        data object Failed : Http
    }

    private suspend fun http(method: String, url: String, token: String?, body: String?): Http = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                requestMethod = method
                val auth = authorization(token)
                // Newer servers read "Authorization", older ones only their own header.
                setRequestProperty("Authorization", auth)
                setRequestProperty("X-Emby-Authorization", auth)
                setRequestProperty("Accept", "application/json")
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (method == "POST") connection.outputStream.use { it.write(body.orEmpty().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) return@withContext Http.Status(code)
                Http.Ok(if (code == 204) "" else connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Jellyfin unreachable", e)
            Http.Failed
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad Jellyfin address", e)
            Http.Failed
        }
    }

    private fun authorization(token: String?): String {
        val parts = mutableListOf(
            "Client=\"$CLIENT\"",
            "Device=\"${device.replace("\"", "")}\"",
            "DeviceId=\"${session.deviceId}\"",
            "Version=\"$VERSION\"",
        )
        token?.let { parts += "Token=\"$it\"" }
        return "MediaBrowser " + parts.joinToString(", ")
    }

    companion object {
        private const val TAG = "Jellyfin"
        private const val CLIENT = "Cog"
        private const val VERSION = "1.0"

        /** Replaced by the signed-in user's id in each address. */
        private const val USER = "{user}"

        /** Players used within this long count as open. */
        private const val ACTIVE_SECONDS = 960

        private const val FIELDS = "ProductionYear,OriginalTitle,SeriesName,RunTimeTicks"

        /** A tick is a tenth of a microsecond. */
        const val TICKS_PER_SECOND = 10_000_000L

        private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        /** "jellyfin.local:8096/" -> "http://jellyfin.local:8096"; null when it isn't an address. */
        fun baseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "http://$trimmed"
            val host = withScheme.substringAfter("://").substringBefore('/').substringBefore(':')
            return withScheme.takeIf { host.isNotEmpty() }
        }

        internal fun parseItems(items: JSONArray?): List<Item> =
            (0 until (items?.length() ?: 0)).mapNotNull { i -> items?.optJSONObject(i)?.let(::parseItem) }

        internal fun parseItem(json: JSONObject): Item? {
            val id = json.optString("Id").takeIf { it.isNotEmpty() } ?: return null
            val userData = json.optJSONObject("UserData")
            val streams = json.optJSONArray("MediaStreams")
                ?: json.optJSONArray("MediaSources")?.optJSONObject(0)?.optJSONArray("MediaStreams")
            return Item(
                id = id,
                name = json.optString("Name"),
                type = json.optString("Type"),
                year = json.optInt("ProductionYear", 0).takeIf { it > 0 },
                seriesName = json.optString("SeriesName").takeIf { it.isNotEmpty() },
                seriesId = json.optString("SeriesId").takeIf { it.isNotEmpty() },
                season = if (json.has("ParentIndexNumber")) json.optInt("ParentIndexNumber") else null,
                episode = if (json.has("IndexNumber")) json.optInt("IndexNumber") else null,
                runtime = json.optLong("RunTimeTicks", 0).takeIf { it > 0 },
                position = userData?.optLong("PlaybackPositionTicks", 0) ?: 0,
                played = userData?.optBoolean("Played") ?: false,
                originalTitle = json.optString("OriginalTitle").takeIf { it.isNotEmpty() },
                streams = (0 until (streams?.length() ?: 0)).mapNotNull { i ->
                    val stream = streams?.optJSONObject(i) ?: return@mapNotNull null
                    Stream(
                        index = stream.optInt("Index"),
                        type = stream.optString("Type"),
                        language = stream.optString("Language").takeIf { it.isNotEmpty() },
                        title = (stream.optString("DisplayTitle").takeIf { it.isNotEmpty() } ?: stream.optString("Title")).takeIf { it.isNotEmpty() },
                        isDefault = stream.optBoolean("IsDefault"),
                        forced = stream.optBoolean("IsForced"),
                    )
                },
                isFolder = json.optBoolean("IsFolder"),
            )
        }

        internal fun parsePlayers(body: String): List<Player> {
            val sessions = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
            return (0 until sessions.length()).mapNotNull { i ->
                val json = sessions.optJSONObject(i) ?: return@mapNotNull null
                if (!json.optBoolean("SupportsRemoteControl")) return@mapNotNull null
                val state = json.optJSONObject("PlayState")
                val commands = json.optJSONArray("SupportedCommands")
                Player(
                    id = json.optString("Id"),
                    name = json.optString("DeviceName").ifEmpty { json.optString("Client") },
                    client = json.optString("Client"),
                    deviceId = json.optString("DeviceId"),
                    playing = json.optJSONObject("NowPlayingItem")?.let(::parseItem),
                    position = state?.optLong("PositionTicks", 0) ?: 0,
                    paused = state?.optBoolean("IsPaused") ?: false,
                    muted = state?.optBoolean("IsMuted") ?: false,
                    volume = state?.takeIf { it.has("VolumeLevel") }?.optInt("VolumeLevel"),
                    audioIndex = state?.takeIf { it.has("AudioStreamIndex") }?.optInt("AudioStreamIndex"),
                    subtitleIndex = state?.takeIf { it.has("SubtitleStreamIndex") }?.optInt("SubtitleStreamIndex"),
                    commands = (0 until (commands?.length() ?: 0)).map { commands!!.optString(it) }.toSet(),
                )
            }.filter { it.id.isNotEmpty() }
        }
    }
}
