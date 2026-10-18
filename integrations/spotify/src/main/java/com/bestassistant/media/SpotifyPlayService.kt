package com.bestassistant.media

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bestassistant.integrations.spotify.PlayButtons
import com.bestassistant.integrations.spotify.SpotifyIntegration
import com.bestassistant.integrations.spotify.SpotifyPress

/**
 * Spotify opens an artist's, album's or playlist's page for other apps but won't start it (the
 * old ":play" link suffix no longer works). With the user's permission, this service taps the
 * page's play button once, right after a "play ..." request: it only acts in Spotify, only for a
 * few seconds after being armed by [SpotifyPress.arm], and reads nothing else.
 *
 * It keeps the package it had before Spotify became an integration: Android remembers the user's
 * permission by the service's name, and a new one would quietly need allowing again.
 */
class SpotifyPlayService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            val request = SpotifyPress.current() ?: return
            if (tryPress(request)) SpotifyPress.disarm() else handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        running = this
        if (SpotifyPress.current() != null) watch()
    }

    override fun onDestroy() {
        if (running === this) running = null
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // polled while armed instead

    override fun onInterrupt() = Unit

    /** Starts looking for the play button; called when a request arms the press. */
    fun watch() {
        handler.removeCallbacks(check)
        handler.post(check)
    }

    /** True when done: pressed, or the page already plays. */
    private fun tryPress(request: SpotifyPress.Request): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName != SpotifyIntegration.SPOTIFY_PACKAGE) return false
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val seen = ArrayList<PlayButtons.ScreenNode>()
        collect(root, nodes, seen)
        val waited = SystemClock.uptimeMillis() - request.armedAt
        return when (val choice = PlayButtons.choose(seen, resources.displayMetrics.heightPixels, request.expectedName, waited)) {
            PlayButtons.Choice.NotYet -> false
            PlayButtons.Choice.Playing -> true
            is PlayButtons.Choice.Press -> {
                val pressed = click(nodes[choice.index])
                Log.i(TAG, "Pressed play on the Spotify page: $pressed")
                pressed
            }
        }
    }

    private fun collect(node: AccessibilityNodeInfo, nodes: MutableList<AccessibilityNodeInfo>, seen: MutableList<PlayButtons.ScreenNode>) {
        if (!node.isVisibleToUser) return
        val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
        val viewId = node.viewIdResourceName
        if (label.isNotBlank() || viewId != null) {
            val bounds = Rect().also(node::getBoundsInScreen)
            nodes += node
            seen += PlayButtons.ScreenNode(label, viewId, bounds.top, bounds.bottom, bounds.width(), bounds.height())
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, nodes, seen) }
    }

    /** Clicks [node], or the nearest clickable parent (labels often sit on an inner icon). */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = target ?: return false
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = current.parent
        }
        return false
    }

    companion object {
        private const val TAG = "SpotifyPlayService"
        private const val POLL_MS = 300L

        @Volatile
        internal var running: SpotifyPlayService? = null
    }
}
