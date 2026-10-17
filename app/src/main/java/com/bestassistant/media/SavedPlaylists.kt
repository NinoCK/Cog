package com.bestassistant.media

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A playlist (or album, artist, song) the user saved under the name they'll say. */
@Immutable
data class SavedPlaylist(val name: String, val link: String)

/**
 * The user's own playlists. Without Spotify's API (an account and a key) the app can't list
 * them, so the user adds the ones they want by name and share link; they stay on the phone.
 */
class SavedPlaylists(context: Context) {

    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val all: StateFlow<List<SavedPlaylist>> = state.asStateFlow()

    /** Saves [link] as [name], replacing a playlist saved under the same name. */
    fun add(name: String, link: String) {
        val playlist = SavedPlaylist(name.trim(), link)
        val key = MusicNames.playlistKey(playlist.name)
        write(state.value.filterNot { MusicNames.playlistKey(it.name) == key } + playlist)
    }

    fun remove(playlist: SavedPlaylist) = write(state.value - playlist)

    /** The saved playlist the user named; [exactOnly] when they didn't say it was a playlist. */
    fun find(spoken: String, exactOnly: Boolean): SavedPlaylist? =
        MusicNames.bestPlaylist(spoken, state.value, exactOnly) { it.name }

    private fun write(playlists: List<SavedPlaylist>) {
        state.value = playlists
        val json = JSONArray()
        playlists.forEach { json.put(JSONObject().put(NAME, it.name).put(LINK, it.link)) }
        prefs.edit { putString(KEY, json.toString()) }
    }

    private fun read(): List<SavedPlaylist> = runCatching {
        val json = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            SavedPlaylist(o.optString(NAME).ifBlank { return@mapNotNull null }, o.optString(LINK).ifBlank { return@mapNotNull null })
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "saved"
        const val NAME = "name"
        const val LINK = "link"
    }
}
