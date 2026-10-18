package com.bestassistant.integration

import android.content.Intent

/**
 * How an integration plays music in one music app, for "play ...": it finds the app's own page
 * for what was asked for (the artist, the album, a saved playlist) and starts it there. Without
 * one, Cog asks the app to "search and play" instead.
 */
interface MusicService {

    /** The music app's package. */
    val packageName: String

    /** The app's own page for what was asked for, or null (Cog then searches instead). */
    suspend fun find(query: String, kind: MusicKind, artist: String?): MusicPage?

    /** What opens [page] in the app; anything that starts it once it shows is readied too. */
    fun open(page: MusicPage): Intent

    /** The app's own search for [query], for when it ignores "search and play". */
    fun search(query: String): Intent?

    /**
     * Once the app is up, with [page] open (null: none was found): starts it. False when it
     * couldn't, and Cog then asks the app's player itself.
     */
    suspend fun start(page: MusicPage?): Boolean
}

/**
 * A page in a music app: [link] opens it, [name] is what it's called (when known, so the right
 * page is the one started), and [media] is what the app's player can be asked to play, if anything.
 */
data class MusicPage(val link: String, val name: String?, val media: String? = null)

/** What a "play ..." request names; [ANY] when the user didn't say. [LIKED] is the user's liked songs. */
enum class MusicKind { ANY, ARTIST, SONG, ALBUM, PLAYLIST, LIKED }
