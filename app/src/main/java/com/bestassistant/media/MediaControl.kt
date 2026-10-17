package com.bestassistant.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Exists only so the user can grant "notification access": that's the permission Android uses
 * for controlling other apps' media sessions. Notifications themselves are ignored.
 */
class MediaListener : NotificationListenerService()

/** The song a player is on. */
data class NowPlaying(val title: String, val artist: String?, val app: String, val playing: Boolean)

/**
 * Drives music apps through their media sessions, the same channel Android Auto and the system
 * media controls use. No account, no API key: Spotify (and YouTube Music, Deezer...) accept
 * transport controls, likes and shuffle from any app with notification access. Starting a new
 * song is less dependable that way (Spotify can silently ignore it), so requests
 * open the app first and only fall back to the session ([makeSurePlaying]).
 */
class MediaControl(private val context: Context) {

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaListener::class.java)

    fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** The system screen where the user grants notification access to this app. */
    fun accessIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    private fun controllers(): List<MediaController> {
        if (!hasAccess()) return emptyList()
        return try {
            sessions.getActiveSessions(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to media sessions", e)
            emptyList()
        }
    }

    fun controllerFor(packageName: String): MediaController? = controllers().firstOrNull { it.packageName == packageName }

    /** The player that's playing, or the most recently active one. */
    fun active(): MediaController? {
        val all = controllers()
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }

    /** Waits for [packageName] to publish its media session (e.g. right after the app was opened). */
    suspend fun awaitController(packageName: String, timeoutMs: Long = 10_000): MediaController? =
        withTimeoutOrNull(timeoutMs) {
            var found = controllerFor(packageName)
            while (found == null) {
                delay(250)
                found = controllerFor(packageName)
            }
            found
        }

    /** Asks [controller] to search for [query] and start playing the best match. */
    fun playFromSearch(controller: MediaController, query: String, extras: Bundle = Bundle()): Boolean = runCatching {
        controller.transportControls.playFromSearch(query, extras)
    }.onFailure { Log.w(TAG, "playFromSearch failed", it) }.isSuccess

    /** What [packageName] was playing, to tell later whether a request started something new. */
    data class Snapshot(val title: String?, val playing: Boolean)

    fun snapshot(packageName: String): Snapshot? = controllerFor(packageName)?.let {
        Snapshot(it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), it.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    /**
     * After the music app was opened to play something: if it hasn't started anything new within a
     * few seconds, asks its media session directly, by [uri] when known, else by search. Some apps
     * only open on the first request, especially when they weren't running.
     */
    suspend fun makeSurePlaying(packageName: String, before: Snapshot?, uri: Uri?, query: String, extras: Bundle) {
        if (!hasAccess()) return
        awaitController(packageName) ?: return
        val started = withTimeoutOrNull(START_WAIT_MS) {
            while (!startedSomethingNew(packageName, before)) delay(300)
            true
        } ?: false
        if (started) return
        val controller = controllerFor(packageName) ?: return
        Log.i(TAG, "$packageName didn't start playing; asking its media session")
        val actions = controller.playbackState?.actions ?: 0L
        if (uri != null && actions and PlaybackState.ACTION_PLAY_FROM_URI != 0L) {
            runCatching { controller.transportControls.playFromUri(uri, Bundle()) }
                .onFailure { Log.w(TAG, "playFromUri failed", it) }
        } else {
            playFromSearch(controller, query, extras)
        }
    }

    private fun startedSomethingNew(packageName: String, before: Snapshot?): Boolean {
        val controller = controllerFor(packageName) ?: return false
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return false
        if (before == null || !before.playing) return true
        return controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != before.title
    }

    fun nowPlaying(): NowPlaying? {
        val controller = active() ?: return null
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        return NowPlaying(
            title = title,
            artist = artist?.takeIf { it.isNotBlank() },
            app = appLabel(controller.packageName),
            playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    /** Play, pause, next or previous on the active player; false when there's none. */
    fun transport(action: Transport): Boolean {
        val controls = active()?.transportControls ?: return false
        when (action) {
            Transport.PLAY -> controls.play()
            Transport.PAUSE -> controls.pause()
            Transport.NEXT -> controls.skipToNext()
            Transport.PREVIOUS -> controls.skipToPrevious()
        }
        return true
    }

    enum class Transport { PLAY, PAUSE, NEXT, PREVIOUS }

    /** Adds the current song to the user's likes (Spotify's "Liked Songs"). */
    fun like(): Boolean {
        val controller = active() ?: return false
        if (controller.ratingType == Rating.RATING_HEART) {
            return runCatching { controller.transportControls.setRating(Rating.newHeartRating(true)) }.isSuccess
        }
        return customAction(controller, listOf("like", "heart", "favorite", "favourite", "collection", "save", "add to"))
    }

    fun shuffle(on: Boolean): Boolean {
        val controller = active() ?: return false
        // Players built on MediaSessionCompat or Media3 understand these standard extras.
        controller.transportControls.sendCustomAction(
            COMPAT_SHUFFLE,
            Bundle().apply { putInt(COMPAT_SHUFFLE_ARG, if (on) 1 else 0) },
        )
        customAction(controller, listOf("shuffle"))
        return true
    }

    fun repeat(on: Boolean): Boolean {
        val controller = active() ?: return false
        controller.transportControls.sendCustomAction(
            COMPAT_REPEAT,
            Bundle().apply { putInt(COMPAT_REPEAT_ARG, if (on) 2 else 0) },
        )
        customAction(controller, listOf("repeat"))
        return true
    }

    /** Runs the player's own button whose id or label mentions one of [keywords]. */
    private fun customAction(controller: MediaController, keywords: List<String>): Boolean {
        val actions = controller.playbackState?.customActions.orEmpty()
        val match = actions.firstOrNull { action ->
            val text = "${action.action} ${action.name}".lowercase(Locale.ROOT)
            keywords.any { it in text } && "unlike" !in text && "remove" !in text
        } ?: return false
        return runCatching { controller.transportControls.sendCustomAction(match, Bundle()) }.isSuccess
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val TAG = "MediaControl"

        /** How long a freshly opened player gets to start the request by itself. */
        const val START_WAIT_MS = 8_000L
        const val COMPAT_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
        const val COMPAT_SHUFFLE_ARG = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
        const val COMPAT_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE"
        const val COMPAT_REPEAT_ARG = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"
    }
}
