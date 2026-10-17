package com.bestassistant.device

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.bestassistant.commands.Command
import com.bestassistant.commands.MediaAction
import com.bestassistant.commands.MusicApp
import com.bestassistant.commands.MusicKind
import com.bestassistant.commands.SettingsPanel
import com.bestassistant.commands.Switch
import com.bestassistant.commands.VolumeChange
import com.bestassistant.media.MediaControl
import com.bestassistant.media.SavedPlaylists
import com.bestassistant.media.SpotifyCatalog
import com.bestassistant.media.SpotifyPress
import com.bestassistant.media.SpotifyRemote
import com.fastsearch.R
import com.fastsearch.data.Contact
import kotlin.math.roundToInt

/** What running a command needs from the UI. */
sealed interface Step {
    /**
     * Start the first of [intents] that resolves, then confirm with [confirmation]. [then] runs in
     * the background afterwards (e.g. starting the song once the music app is up).
     */
    data class Launch(
        val intents: List<Intent>,
        val confirmation: String? = null,
        val then: (suspend () -> Unit)? = null,
        /** Better intents found online (e.g. the artist's Spotify page), tried before [intents]. */
        val lookup: (suspend () -> List<Intent>)? = null,
    ) : Step

    /** Already done in-process. */
    data class Done(val confirmation: String?) : Step

    data class Failed(val message: String) : Step
}

/**
 * Runs commands with public platform APIs only: intents that the clock, camera, maps and
 * dialer apps understand, CameraManager for the torch, and AudioManager for volume and media.
 * No root; the one accessibility service ([SpotifyPress]) is optional and only taps Spotify's play button.
 */
class CommandRunner(
    private val context: Context,
    /** Resources in the assistant's language. */
    private val strings: () -> Context,
    private val media: MediaControl,
    private val catalog: SpotifyCatalog,
    private val playlists: SavedPlaylists,
    private val spotify: SpotifyRemote,
) {

    private fun s(id: Int, vararg args: Any): String = strings().getString(id, *args)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val torch = Torch(context)

    fun plan(command: Command, contact: Contact? = null): Step = when (command) {
        is Command.SetAlarm -> Step.Launch(
            listOf(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.replaceFirstChar(Char::titlecase)) } },
            ),
            s(R.string.done_alarm, TimeText.clock(context, command.hour, command.minute)),
        )
        is Command.SetTimer -> Step.Launch(
            listOf(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.replaceFirstChar(Char::titlecase)) } },
            ),
            s(R.string.done_timer, TimeText.duration(strings(), command.seconds)),
        )
        Command.ShowAlarms -> Step.Launch(listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        Command.ShowTimers -> Step.Launch(listOf(Intent(AlarmClock.ACTION_SHOW_TIMERS), Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        is Command.Flashlight -> flashlight(command.state)
        is Command.Volume -> volume(command.change)
        is Command.Media -> media(command.action)
        is Command.Navigate -> Step.Launch(listOf(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(command.destination)))))
        is Command.Camera -> Step.Launch(
            listOf(
                Intent(if (command.video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                Intent(if (command.video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE),
            ),
        )
        is Command.OpenPanel -> Step.Launch(panelIntents(command.panel))
        is Command.DialNumber -> Step.Launch(listOf(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", command.number, null))))
        is Command.Call -> contact?.number?.let { number ->
            Step.Launch(listOf(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))))
        } ?: Step.Failed(s(R.string.error_no_number))
        is Command.Message -> contact?.number?.let { number ->
            Step.Launch(
                listOf(
                    Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                        .apply { command.body?.let { putExtra("sms_body", it) } },
                ),
            )
        } ?: Step.Failed(s(R.string.error_no_number))
        is Command.PlayMusic -> playMusic(command)
        Command.NowPlaying -> withPlayer { Step.Done(null) }
        Command.LikeSong -> withPlayer { if (media.like()) Step.Done(s(R.string.done_liked)) else Step.Failed(s(R.string.error_like)) }
        is Command.Shuffle -> withPlayer { media.shuffle(command.on); Step.Done(null) }
        is Command.Repeat -> withPlayer { media.repeat(command.on); Step.Done(null) }
        // Shown inline or launched as an app result: nothing to run here.
        is Command.OpenApp, is Command.Weather, is Command.Clock, is Command.Convert, is Command.Lookup -> Step.Done(null)
    }

    /** The music app to use when the user didn't name one: the first of the known ones that's installed. */
    fun defaultMusicApp(): MusicApp? = MUSIC_PREFERENCE.firstOrNull { isInstalled(it.packageName) }

    private fun isInstalled(packageName: String) =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false)

    /** Commands that act on the current player need media control and a player. */
    private inline fun withPlayer(run: () -> Step): Step = when {
        !media.hasAccess() -> Step.Failed(s(R.string.error_media_access))
        media.active() == null -> Step.Failed(s(R.string.error_no_player))
        else -> run()
    }

    /**
     * Always opens the music app, so the user sees it react. For Spotify, the artist's, song's,
     * album's or saved playlist's own page is opened (found on Wikidata or in the user's saved
     * playlists) and started: through Spotify's App Remote when the user added their Client ID,
     * else by [SpotifyPress] tapping its play button when allowed (Spotify doesn't start pages
     * opened by other apps on its own). Otherwise, and for other apps, "search and play"
     * (MEDIA_PLAY_FROM_SEARCH, what Google Assistant used) with what kind of thing was asked for.
     * If the app opens but doesn't start anything, its media session is asked directly.
     */
    private fun playMusic(command: Command.PlayMusic): Step {
        val app = command.app ?: defaultMusicApp()
        val search = searchIntent(command)
        val query = search.getStringExtra(SearchManager.QUERY).orEmpty()
        val shown = listOfNotNull(command.query, command.artist).joinToString(" ")
        val confirmation = if (app != null) s(R.string.done_play_music_app, shown, app.label) else s(R.string.done_play_music, shown)
        when (app) {
            null -> return Step.Launch(listOf(search), confirmation)
            // YouTube itself is video: open its search instead.
            MusicApp.YOUTUBE -> return Step.Launch(
                listOf(
                    Intent(Intent.ACTION_SEARCH).setPackage(app.packageName).putExtra(SearchManager.QUERY, query),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))),
                ),
                confirmation,
            )
            else -> Unit
        }
        val intents = listOfNotNull(
            Intent(search).setPackage(app.packageName),
            // If the app ignores the play intent, at least land on its search.
            if (app == MusicApp.SPOTIFY) spotifyIntent("spotify:search:" + Uri.encode(query)) else null,
            search,
        )
        val before = media.snapshot(app.packageName)
        // Set by the lookup, read by the follow-up: the page that was opened, if one was found.
        var page: SpotifyPage? = null
        val followUp: suspend () -> Unit = {
            val opened = page
            val link = opened?.link?.takeIf { it.startsWith("spotify:") }
            val started = link != null && spotify.play(link)
            if (!started) {
                // App Remote not set up or refused: tap play instead, if allowed (already armed when not set up).
                if (opened != null && spotify.isSetUp && SpotifyPress.isEnabled(context)) SpotifyPress.arm(opened.name)
                media.makeSurePlaying(app.packageName, before, link?.let(Uri::parse), query, search.extras ?: Bundle())
            }
        }
        if (app != MusicApp.SPOTIFY) return Step.Launch(intents, confirmation, then = followUp)
        return Step.Launch(
            intents,
            confirmation,
            then = followUp,
            lookup = {
                val found = spotifyPage(command)
                page = found
                listOfNotNull(
                    found?.let {
                        // Without App Remote the page's button is the only way to start it: arm right away.
                        if (!spotify.isSetUp && SpotifyPress.isEnabled(context)) SpotifyPress.arm(it.name)
                        spotifyIntent(it.link)
                    },
                )
            },
        )
    }

    /** "Search and play", with the kind of thing asked for so the app can pick an artist over a song. */
    private fun searchIntent(command: Command.PlayMusic): Intent {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        val (focus, query) = when (command.kind) {
            MusicKind.ANY -> "vnd.android.cursor.item/*" to command.query
            MusicKind.ARTIST -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, command.query)
                "vnd.android.cursor.item/artist" to command.query
            }
            MusicKind.SONG -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_TITLE, command.query)
                command.artist?.let { intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                "vnd.android.cursor.item/audio" to listOfNotNull(command.query, command.artist).joinToString(" ")
            }
            MusicKind.ALBUM -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_ALBUM, command.query)
                command.artist?.let { intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                "vnd.android.cursor.item/album" to listOfNotNull(command.query, command.artist).joinToString(" ")
            }
            MusicKind.PLAYLIST -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, command.query)
                "vnd.android.cursor.item/playlist" to command.query
            }
            MusicKind.LIKED -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, LIKED_SONGS)
                "vnd.android.cursor.item/playlist" to LIKED_SONGS
            }
        }
        return intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus).putExtra(SearchManager.QUERY, query)
    }

    /** A Spotify page to open, and the name it shows (when known) so the right page gets started. */
    private class SpotifyPage(val link: String, val name: String?)

    /**
     * The Spotify page for the request: a playlist the user saved under that name, Liked Songs, or
     * the artist, song or album from Wikidata. Null when there's none, so search takes over.
     */
    private suspend fun spotifyPage(command: Command.PlayMusic): SpotifyPage? {
        val saved = when (command.kind) {
            MusicKind.PLAYLIST -> playlists.find(command.query, exactOnly = false)
            // A playlist's name wins over an artist's: the user saved it under that name.
            MusicKind.ANY -> playlists.find(command.query, exactOnly = true)
            else -> null
        }
        if (saved != null) return SpotifyPage(saved.link, saved.name)
        return when (command.kind) {
            // Spotify shows it in the phone's language, so there's no name to check.
            MusicKind.LIKED -> SpotifyPage(SPOTIFY_LIKED_SONGS, null)
            MusicKind.PLAYLIST -> null
            else -> catalog.find(command.query, command.kind, command.artist)?.let { SpotifyPage(it, command.query) }
        }
    }

    private fun spotifyIntent(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setPackage(MusicApp.SPOTIFY.packageName)
        // Spotify asks apps that link into it to say who they are.
        .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + context.packageName))

    val isTorchOn: Boolean get() = torch.isOn

    private fun flashlight(state: Switch): Step {
        val on = when (state) {
            Switch.ON -> true
            Switch.OFF -> false
            Switch.TOGGLE -> !torch.isOn
        }
        return if (torch.set(on)) {
            Step.Done(s(if (on) R.string.done_torch_on else R.string.done_torch_off))
        } else {
            Step.Failed(s(R.string.error_torch))
        }
    }

    private fun volume(change: VolumeChange): Step {
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        return try {
            when (change) {
                VolumeChange.Up -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
                VolumeChange.Down -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
                VolumeChange.Mute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                VolumeChange.Unmute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                is VolumeChange.Set -> {
                    val min = audio.getStreamMinVolume(stream)
                    val max = audio.getStreamMaxVolume(stream)
                    val index = (min + (max - min) * change.percent / 100.0).roundToInt().coerceIn(min, max)
                    audio.setStreamVolume(stream, index, flags)
                }
            }
            // The system volume panel is the confirmation.
            Step.Done(null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Volume change refused", e)
            Step.Failed(s(R.string.error_volume))
        }
    }

    private fun media(action: MediaAction): Step {
        // The active player's own controls when allowed; otherwise a media key, which Android
        // routes to the last player.
        val transport = when (action) {
            MediaAction.PLAY -> MediaControl.Transport.PLAY
            MediaAction.PAUSE -> MediaControl.Transport.PAUSE
            MediaAction.NEXT -> MediaControl.Transport.NEXT
            MediaAction.PREVIOUS -> MediaControl.Transport.PREVIOUS
        }
        val (code, message) = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY to R.string.done_play
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE to R.string.done_pause
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT to R.string.done_next
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to R.string.done_previous
        }
        if (!media.transport(transport)) {
            val time = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
        }
        return Step.Done(s(message))
    }

    private fun panelIntents(panel: SettingsPanel): List<Intent> = when (panel) {
        SettingsPanel.WIFI -> listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
        SettingsPanel.BLUETOOTH -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        SettingsPanel.INTERNET -> listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        SettingsPanel.AIRPLANE -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        SettingsPanel.NFC -> listOf(Intent(Settings.Panel.ACTION_NFC), Intent(Settings.ACTION_NFC_SETTINGS))
        SettingsPanel.HOTSPOT -> listOf(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        SettingsPanel.VOLUME -> listOf(Intent(Settings.Panel.ACTION_VOLUME), Intent(Settings.ACTION_SOUND_SETTINGS))
        SettingsPanel.BATTERY -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        SettingsPanel.DISPLAY -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        SettingsPanel.LOCATION -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        SettingsPanel.DND -> listOf(Intent("android.settings.ZEN_MODE_SETTINGS"), Intent(Settings.ACTION_SOUND_SETTINGS))
    }

    private companion object {
        const val TAG = "CommandRunner"

        /** What Spotify and most players call the user's likes, for "search and play". */
        const val LIKED_SONGS = "Liked Songs"
        const val SPOTIFY_LIKED_SONGS = "spotify:collection:tracks"

        val MUSIC_PREFERENCE = listOf(
            MusicApp.SPOTIFY, MusicApp.YOUTUBE_MUSIC, MusicApp.DEEZER, MusicApp.APPLE_MUSIC,
            MusicApp.TIDAL, MusicApp.AMAZON_MUSIC, MusicApp.SOUNDCLOUD,
        )
    }
}

/** The back camera's flash, tracked through the system callback so "toggle" knows the state. */
private class Torch(context: Context) {
    private val camera = context.getSystemService(CameraManager::class.java)

    private val cameraId: String? by lazy {
        runCatching {
            val withFlash = camera.cameraIdList.filter {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        }.getOrNull()
    }

    @Volatile
    var isOn = false
        private set

    init {
        runCatching {
            camera.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(id: String, enabled: Boolean) {
                        if (id == cameraId) isOn = enabled
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    fun set(on: Boolean): Boolean {
        val id = cameraId ?: return false
        return runCatching { camera.setTorchMode(id, on) }
            .onSuccess { isOn = on }
            .onFailure { Log.w("Torch", "setTorchMode failed", it) }
            .isSuccess
    }
}
