package com.bestassistant.media

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Pauses the music while the assistant listens and resumes it afterwards, but only if it was
 * playing. Players don't reliably pause for another app's audio focus, so the player itself is
 * asked: through its media session when the app has notification access, else with a media key.
 *
 * Must be used from the main thread.
 */
class MusicPause(context: Context, private val media: MediaControl) {

    private val audio = context.getSystemService(AudioManager::class.java)

    private sealed interface Paused {
        data class Session(val controller: MediaController) : Paused
        data object Key : Paused
    }

    /** What this paused; null when nothing is waiting to be resumed. */
    private var paused: Paused? = null

    fun pause() {
        if (paused != null) return
        if (media.hasAccess()) {
            val playing = media.active()?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return
            runCatching { playing.transportControls.pause() }
                .onSuccess { paused = Paused.Session(playing) }
                .onFailure { Log.w(TAG, "Couldn't pause ${playing.packageName}", it) }
        } else if (audio.isMusicActive) {
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            paused = Paused.Key
        }
    }

    fun resume() {
        val was = paused ?: return
        paused = null
        when (was) {
            is Paused.Session -> runCatching { was.controller.transportControls.play() }
                .onFailure { Log.w(TAG, "Couldn't resume ${was.controller.packageName}", it) }
            Paused.Key -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    /** The request itself decided what plays (a pause, a new song): leave the music as it is. */
    fun forget() {
        paused = null
    }

    private fun key(code: Int) {
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    private companion object {
        const val TAG = "MusicPause"
    }
}
