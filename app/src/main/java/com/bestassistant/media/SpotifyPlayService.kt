package com.bestassistant.media

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bestassistant.commands.MusicApp

/**
 * Spotify opens an artist's, album's or playlist's page for other apps but won't start it (the
 * old ":play" link suffix no longer works). With the user's permission, this service taps the
 * page's play button once, right after a "play ..." request: it only acts in Spotify, only for a
 * few seconds after being armed by [SpotifyPress.arm], and reads nothing else.
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
        if (root.packageName != MusicApp.SPOTIFY.packageName) return false
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

/** Arms the one-time play press, and whether the user has allowed it. */
object SpotifyPress {

    class Request(val expectedName: String?, val armedAt: Long)

    @Volatile
    private var request: Request? = null

    /** Presses play on the Spotify page about to open; [expectedName] is the name it should show. */
    fun arm(expectedName: String?) {
        request = Request(expectedName, SystemClock.uptimeMillis())
        SpotifyPlayService.running?.let { service -> service.mainExecutor.execute { service.watch() } }
    }

    fun disarm() {
        request = null
    }

    fun current(): Request? = request?.takeIf { SystemClock.uptimeMillis() - it.armedAt < ARMED_MS }

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, SpotifyPlayService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    /** The system list of accessibility services, where the user turns this one on. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Long enough for Spotify to start cold and load the page. */
    private const val ARMED_MS = 10_000L
}

/** Finds Spotify's big play button among what's on screen. Pure, so it's unit-tested. */
object PlayButtons {

    data class ScreenNode(val label: String, val viewId: String?, val top: Int, val bottom: Int, val width: Int, val height: Int)

    sealed interface Choice {
        data class Press(val index: Int) : Choice
        /** The page's button says pause: it's already playing. */
        data object Playing : Choice
        data object NotYet : Choice
    }

    private val playWords = listOf("play", "shuffle play", "αναπαραγωγη", "τυχαια αναπαραγωγη")
    private val pauseWords = listOf("pause", "παυση")
    private val notThePageButton = listOf("preview", "trailer", "video", "προεπισκοπηση", "βιντεο")

    private fun starts(label: String, words: List<String>) = words.any { label == it || label.startsWith("$it ") }

    private fun isPause(node: ScreenNode) = starts(MusicNames.normalize(node.label), pauseWords)

    private fun isPlay(node: ScreenNode): Boolean {
        val label = MusicNames.normalize(node.label)
        if (notThePageButton.any { it in label }) return false
        if (label.isEmpty()) return node.viewId?.lowercase()?.let { "play" in it && "pause" !in it && "mini" !in it } == true
        return starts(label, playWords)
    }

    /**
     * The button to press: the biggest play (or pause) button above the mini player and tab bar.
     * Waits until the page shows [expectedName], or a moment when there's none to check, so the
     * page that was open before Spotify navigated isn't the one started.
     */
    fun choose(nodes: List<ScreenNode>, screenHeight: Int, expectedName: String?, waitedMs: Long): Choice {
        val ready = if (expectedName == null) {
            waitedMs >= GRACE_MS
        } else {
            waitedMs >= NAME_WAIT_MS || nodes.any { MusicNames.same(it.label, expectedName) }
        }
        if (!ready) return Choice.NotYet
        val limit = screenHeight * PAGE_PART
        val best = nodes.withIndex()
            .filter { (_, node) -> node.bottom <= limit && node.width > 0 && (isPlay(node) || isPause(node)) }
            .maxWithOrNull(compareBy<IndexedValue<ScreenNode>> { it.value.width * it.value.height }.thenByDescending { it.value.top })
            ?: return Choice.NotYet
        return if (isPause(best.value)) Choice.Playing else Choice.Press(best.index)
    }

    /** The mini player and tab bar sit in the bottom part of the screen. */
    private const val PAGE_PART = 0.82
    private const val GRACE_MS = 1_200L
    private const val NAME_WAIT_MS = 2_500L
}
