package com.bestassistant.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Spotify's official App Remote: tells the Spotify app on this phone to play a "spotify:" URI,
 * which Spotify won't do for links opened by other apps. Each user brings their own Client ID
 * (a free Spotify developer app; Spotify requires its owner to have Premium), so nobody shares
 * anybody's registration.
 *
 * Setting up is two steps, both from the settings screen: the user's OK, asked with Spotify's
 * sign-in library from our own activity (App Remote's built-in "allow" screen is started from
 * Spotify's background service, which recent Android versions block, and then the connection just
 * never answers); then the connection itself. [status] is the one source of truth for both.
 */
class SpotifyRemote(private val context: Context, private val clientId: () -> String) {

    sealed interface Status {
        /** No valid Client ID. */
        data object NotSetUp : Status

        /** A Client ID, not yet confirmed with Spotify. */
        data object Ready : Status
        data object Authorizing : Status
        data object Connecting : Status
        data object Connected : Status
        data object NotAuthorized : Status
        data object NoSpotify : Status
        data object NotLoggedIn : Status
        data object TimedOut : Status

        /** Spotify's sign-in refused, with its reason ("INVALID_CLIENT: Invalid redirect URI"). */
        data class Refused(val reason: String) : Status
        data class Failed(val reason: String) : Status

        val isBusy: Boolean get() = this == Authorizing || this == Connecting
    }

    private val prefs = context.getSharedPreferences("spotify_remote", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(restingStatus())
    val status: StateFlow<Status> = state.asStateFlow()

    @Volatile
    private var remote: SpotifyAppRemote? = null
    private var connectedWith: String? = null

    val isSetUp: Boolean get() = isClientId(clientId())

    /** What to show when nothing is going on: connected before with this Client ID, or not yet. */
    private fun restingStatus(): Status = when {
        !isClientId(clientId()) -> Status.NotSetUp
        prefs.getString(KEY_CONFIRMED, null) == clientId().trim() -> Status.Connected
        else -> Status.Ready
    }

    /** The Client ID was edited: drop the old connection and start over. */
    fun clientIdChanged() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        state.value = restingStatus()
    }

    /** Step 1 of setting up: the screen where the user allows Cog, inside Spotify. */
    fun authorizationIntent(activity: Activity): Intent? {
        if (!isSetUp) return null
        state.value = Status.Authorizing
        val request = AuthorizationRequest.Builder(clientId().trim(), AuthorizationResponse.Type.CODE, REDIRECT_URI)
            .setScopes(arrayOf(SCOPE))
            .build()
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }

    /**
     * Step 2: what Spotify answered. Allowed means App Remote may connect now; the code itself
     * isn't needed (App Remote never talks to Spotify's servers from here).
     */
    suspend fun authorizationResult(resultCode: Int, data: Intent?) {
        val response = AuthorizationClient.getResponse(resultCode, data)
        when (response.type) {
            AuthorizationResponse.Type.CODE, AuthorizationResponse.Type.TOKEN -> connectNow()
            AuthorizationResponse.Type.ERROR -> {
                Log.w(TAG, "Spotify sign-in refused: ${response.error}")
                state.value = Status.Refused(response.error ?: "unknown error")
            }
            // Closed without answering.
            else -> state.value = restingStatus()
        }
    }

    /** Connects and records the outcome in [status]. */
    suspend fun connectNow() {
        if (!isSetUp) {
            state.value = Status.NotSetUp
            return
        }
        state.value = Status.Connecting
        state.value = try {
            withTimeout(CONNECT_TIMEOUT_MS) { connect() }
            Status.Connected
        } catch (e: TimeoutCancellationException) {
            Status.TimedOut
        } catch (e: CancellationException) {
            state.value = restingStatus()
            throw e
        } catch (e: Throwable) {
            failure(e)
        }
    }

    /** Plays [uri] in Spotify; false when not set up, not allowed, or Spotify refused. */
    suspend fun play(uri: String): Boolean {
        if (!isSetUp) return false
        return try {
            val connected = withTimeout(CONNECT_TIMEOUT_MS) { connect() }
            if (!state.value.isBusy) state.value = Status.Connected
            withTimeout(PLAY_TIMEOUT_MS) {
                suspendCancellableCoroutine { done ->
                    connected.playerApi.play(uri)
                        .setResultCallback { if (done.isActive) done.resume(true) }
                        .setErrorCallback { error ->
                            Log.w(TAG, "Spotify refused to play $uri", error)
                            if (done.isActive) done.resume(false)
                        }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Spotify didn't answer in time")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val failed = failure(e)
            if (!state.value.isBusy) state.value = failed
            false
        }
    }

    private fun failure(e: Throwable): Status {
        Log.w(TAG, "Spotify connection failed", e)
        if (e is UserNotAuthorizedException) prefs.edit { remove(KEY_CONFIRMED) }
        return when (e) {
            is UserNotAuthorizedException -> Status.NotAuthorized
            is CouldNotFindSpotifyApp -> Status.NoSpotify
            is NotLoggedInException -> Status.NotLoggedIn
            else -> Status.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * The open connection, or a new one. The "allow" screen is never shown from here: it's asked
     * for up front ([authorizationIntent]), where Android lets it appear.
     */
    private suspend fun connect(): SpotifyAppRemote = withContext(Dispatchers.Main) {
        val id = clientId().trim()
        remote?.let { current ->
            if (current.isConnected && connectedWith == id) return@withContext current
            SpotifyAppRemote.disconnect(current)
            remote = null
        }
        val params = ConnectionParams.Builder(id)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(false)
            .build()
        suspendCancellableCoroutine<SpotifyAppRemote> { done ->
            SpotifyAppRemote.connect(
                context,
                params,
                object : Connector.ConnectionListener {
                    override fun onConnected(connected: SpotifyAppRemote) {
                        remote = connected
                        connectedWith = id
                        prefs.edit { putString(KEY_CONFIRMED, id) }
                        if (done.isActive) done.resume(connected)
                    }

                    // Also called later if an open connection drops; the next request reconnects.
                    override fun onFailure(error: Throwable) {
                        remote = null
                        if (done.isActive) done.resumeWithException(error)
                    }
                },
            )
        }
    }

    companion object {
        private const val TAG = "SpotifyRemote"
        private const val KEY_CONFIRMED = "confirmed_client_id"

        /** What the user registers in Spotify's dashboard; the manifest placeholders say the same. */
        const val REDIRECT_URI = "bestassistant://spotify-callback"
        private const val SCOPE = "app-remote-control"

        /** Cold-starting Spotify in the background can take a few seconds. */
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val PLAY_TIMEOUT_MS = 5_000L

        private val clientIdPattern = Regex("[0-9a-fA-F]{32}")

        fun isClientId(text: String) = clientIdPattern.matches(text.trim())

        /** The SHA1 of the key this APK is signed with, as Spotify's dashboard asks for it. */
        fun signingFingerprint(context: Context): String? = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners ?: return null
            signers.firstOrNull()?.toByteArray()?.let(::fingerprint)
        }.getOrNull()

        /** SHA1 of [bytes] as colon-separated uppercase hex: "2C:80:F2:...". */
        fun fingerprint(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString(":") { "%02X".format(it) }
    }
}
