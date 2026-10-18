package com.bestassistant.integrations.spotify

import android.content.Intent
import android.net.Uri
import com.bestassistant.integration.Entry
import com.bestassistant.integration.Integration
import com.bestassistant.integration.IntegrationHost
import com.bestassistant.integration.MusicKind
import com.bestassistant.integration.MusicPage
import com.bestassistant.integration.MusicService
import com.bestassistant.integration.Report
import com.bestassistant.integration.Setting
import com.bestassistant.integration.Tone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Spotify, for "play ...": the artist's, song's, album's or saved playlist's own page is opened
 * (found on Wikidata or in the user's saved playlists) and started, through Spotify's App Remote
 * when the user added their own Client ID, else by [SpotifyPress] tapping its play button when
 * allowed (Spotify doesn't start pages opened by other apps on its own).
 */
class SpotifyIntegration(private val host: IntegrationHost) : Integration, MusicService {

    override val id: String = ID

    private val context = host.context
    private val store = host.store(ID)
    private val remote = SpotifyRemote(context) { store.get(KEY_CLIENT_ID) }
    private val catalog = SpotifyCatalog(context)
    private val playlists = SavedPlaylists(context)

    private fun s(id: Int, vararg args: Any): String = host.strings.getString(id, *args)

    override val name: String get() = s(R.string.spotify_name)
    override val description: String get() = s(R.string.spotify_description)
    override val icon: Int = R.drawable.ic_integration_spotify

    override val summary: Flow<String> = remote.status.map { status ->
        s(
            when (status) {
                SpotifyRemote.Status.Connected -> R.string.summary_spotify_connected
                SpotifyRemote.Status.NotSetUp -> R.string.summary_spotify_off
                else -> R.string.summary_spotify_unfinished
            },
        )
    }

    override val music: MusicService get() = this

    // region Music

    override val packageName: String = SPOTIFY_PACKAGE

    /** A playlist the user saved under that name, Liked Songs, or the artist, song or album from Wikidata. */
    override suspend fun find(query: String, kind: MusicKind, artist: String?): MusicPage? {
        val saved = when (kind) {
            MusicKind.PLAYLIST -> playlists.find(query, exactOnly = false)
            // A playlist's name wins over an artist's: the user saved it under that name.
            MusicKind.ANY -> playlists.find(query, exactOnly = true)
            else -> null
        }
        if (saved != null) return page(saved.link, saved.name)
        return when (kind) {
            // Spotify shows it in the phone's language, so there's no name to check.
            MusicKind.LIKED -> page(LIKED_SONGS, null)
            MusicKind.PLAYLIST -> null
            else -> catalog.find(query, kind, artist)?.let { page(it, query) }
        }
    }

    /** Short share links only open in the app; "spotify:" links can also be played. */
    private fun page(link: String, name: String?) = MusicPage(link, name, media = link.takeIf { it.startsWith("spotify:") })

    override fun open(page: MusicPage): Intent {
        // Without App Remote the page's button is the only way to start it: arm right away.
        if (!remote.isSetUp && SpotifyPress.isEnabled(context)) SpotifyPress.arm(page.name)
        return spotifyIntent(page.link)
    }

    override fun search(query: String): Intent = spotifyIntent("spotify:search:" + Uri.encode(query))

    override suspend fun start(page: MusicPage?): Boolean {
        val link = page?.media
        val started = link != null && remote.play(link)
        // App Remote not set up or refused: tap play instead, if allowed (already armed when not set up).
        if (!started && page != null && remote.isSetUp && SpotifyPress.isEnabled(context)) SpotifyPress.arm(page.name)
        return started
    }

    private fun spotifyIntent(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setPackage(SPOTIFY_PACKAGE)
        // Spotify asks apps that link into it to say who they are.
        .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + context.packageName))

    // endregion

    // region Settings

    /**
     * The user's own Spotify Client ID, with everything they need to register it in Spotify's
     * dashboard; their saved playlists; and the optional helper that presses play.
     */
    override fun settings(): List<Setting> = buildList {
        add(Setting.Section(s(R.string.section_spotify)))
        add(Setting.Note(s(R.string.spotify_connect_intro)))
        add(Setting.Note(s(R.string.spotify_step1)))
        add(Setting.Link(s(R.string.spotify_open_dashboard), DASHBOARD_URL))
        add(Setting.Note(s(R.string.spotify_step2)))
        add(Setting.Copy(s(R.string.spotify_redirect), SpotifyRemote.REDIRECT_URI))
        add(Setting.Copy(s(R.string.spotify_package), context.packageName))
        SpotifyRemote.signingFingerprint(context)?.let { add(Setting.Copy(s(R.string.spotify_fingerprint), it)) }
        add(Setting.Note(s(R.string.spotify_step3)))
        add(
            Setting.Field(
                key = KEY_CLIENT_ID,
                label = s(R.string.spotify_client_id),
                error = { text -> if (text.isNotEmpty() && !SpotifyRemote.isClientId(text)) s(R.string.spotify_client_id_invalid) else null },
                changed = { remote.clientIdChanged() },
            ),
        )
        add(
            Setting.Action(
                label = s(R.string.spotify_connect),
                enabled = { remote.isSetUp && !remote.status.value.isBusy },
                run = { actions ->
                    // Spotify's "allow Cog" screen; its answer is handled in the app's scope, so
                    // leaving the screen doesn't lose it.
                    remote.authorizationIntent(actions.activity)?.let { intent ->
                        actions.startForResult(intent) { code, data -> remote.authorizationResult(code, data) }
                    }
                },
            ),
        )
        add(Setting.Status(remote.status.map(::report)))

        add(Setting.Section(s(R.string.section_playlists)))
        add(
            Setting.Entries(
                intro = s(R.string.playlists_intro),
                add = s(R.string.playlists_add),
                items = playlists.all.map { saved -> saved.map { Entry(it.name, s(kindLabel(it.link)), it) } },
                value = s(R.string.playlist_link_label),
                valueHint = s(R.string.playlist_link_hint),
                name = s(R.string.playlist_name_label),
                parse = SpotifyLinks::find,
                save = { name, link -> playlists.add(name, link) },
                remove = { entry -> (entry.key as? SavedPlaylist)?.let(playlists::remove) },
            ),
        )

        add(Setting.Section(s(R.string.section_press)))
        add(
            Setting.Permission(
                title = s(R.string.step_press_title),
                body = s(R.string.step_press_body),
                allowed = { SpotifyPress.isEnabled(context) },
                open = SpotifyPress::settingsIntent,
            ),
        )
    }

    private fun report(status: SpotifyRemote.Status): Report? = when (status) {
        SpotifyRemote.Status.NotSetUp -> null
        SpotifyRemote.Status.Ready -> Report(s(R.string.spotify_status_ready))
        SpotifyRemote.Status.Authorizing -> Report(s(R.string.spotify_status_authorizing))
        SpotifyRemote.Status.Connecting -> Report(s(R.string.spotify_status_connecting))
        SpotifyRemote.Status.Connected -> Report(s(R.string.spotify_status_connected), Tone.GOOD)
        SpotifyRemote.Status.NotAuthorized -> Report(s(R.string.spotify_status_not_authorized), Tone.BAD)
        SpotifyRemote.Status.NoSpotify -> Report(s(R.string.spotify_status_no_spotify), Tone.BAD)
        SpotifyRemote.Status.NotLoggedIn -> Report(s(R.string.spotify_status_not_logged_in), Tone.BAD)
        SpotifyRemote.Status.TimedOut -> Report(s(R.string.spotify_status_timeout), Tone.BAD)
        is SpotifyRemote.Status.Refused -> Report(s(R.string.spotify_status_refused, status.reason), Tone.BAD)
        is SpotifyRemote.Status.Failed -> Report(s(R.string.spotify_status_failed, status.reason), Tone.BAD)
    }

    private fun kindLabel(link: String): Int = when (SpotifyLinks.type(link)) {
        "playlist" -> R.string.playlist_kind_playlist
        "album" -> R.string.playlist_kind_album
        "artist" -> R.string.playlist_kind_artist
        "track" -> R.string.playlist_kind_track
        else -> R.string.playlist_kind_link
    }

    // endregion

    companion object {
        const val ID = "spotify"

        /** Where the Client ID is kept: what Cog's settings used to keep it under is moved here once. */
        const val KEY_CLIENT_ID = "client_id"

        const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val LIKED_SONGS = "spotify:collection:tracks"
        private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
    }
}
