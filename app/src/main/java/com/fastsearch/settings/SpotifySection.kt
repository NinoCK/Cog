package com.fastsearch.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestassistant.media.SpotifyRemote
import com.fastsearch.R
import com.fastsearch.data.AppSettings
import com.fastsearch.fastSearch

/**
 * The user's own Spotify Client ID, with everything they need to register it in Spotify's
 * dashboard (redirect URI, package name and this APK's fingerprint) ready to copy.
 */
@Composable
internal fun SpotifySection(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    /** Asks Spotify for the user's OK, then connects; registered by the screen so the answer isn't lost. */
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val remote = context.fastSearch.spotify
    // One source of truth: what the connection is doing, shared with the assistant's own playback.
    val status by remote.status.collectAsStateWithLifecycle()
    var clientId by remember { mutableStateOf(settings.spotifyClientId) }
    val fingerprint = remember { SpotifyRemote.signingFingerprint(context) }
    val valid = SpotifyRemote.isClientId(clientId)

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.Link, contentDescription = null) },
        headlineContent = {
            Text(stringResource(R.string.spotify_connect_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        },
    )

    // Once connected the setup steps only get in the way; "Change" shows them again, and a new
    // successful connection folds them away.
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status) { if (status == SpotifyRemote.Status.Connected) editing = false }
    if (status == SpotifyRemote.Status.Connected && !editing) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.spotify_status_connected), style = MaterialTheme.typography.bodyMedium, color = colors.primary)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.spotify_change)) }
                FilledTonalButton(onClick = onConnect) { Text(stringResource(R.string.spotify_connect_again)) }
            }
        }
        return
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.spotify_step1), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD_URL)).addCategory(Intent.CATEGORY_BROWSABLE))
                }
            },
        ) { Text(stringResource(R.string.spotify_open_dashboard)) }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.spotify_step2), style = MaterialTheme.typography.bodyMedium)
    }
    CopyRow(stringResource(R.string.spotify_redirect), SpotifyRemote.REDIRECT_URI)
    CopyRow(stringResource(R.string.spotify_package), context.packageName)
    fingerprint?.let { CopyRow(stringResource(R.string.spotify_fingerprint), it) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.spotify_step3), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = clientId,
            onValueChange = { text ->
                clientId = text.trim()
                // Saved as typed, so the status always describes the ID in the field.
                update { it.copy(spotifyClientId = clientId) }
                remote.clientIdChanged()
            },
            label = { Text(stringResource(R.string.spotify_client_id)) },
            supportingText = {
                if (clientId.isNotEmpty() && !valid) Text(stringResource(R.string.spotify_client_id_invalid))
            },
            isError = clientId.isNotEmpty() && !valid,
            enabled = !status.isBusy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(enabled = valid && !status.isBusy, onClick = onConnect) {
            Text(stringResource(if (status == SpotifyRemote.Status.Connected) R.string.spotify_connect_again else R.string.spotify_connect))
        }
        val message = when (val current = status) {
            SpotifyRemote.Status.NotSetUp -> if (clientId.isEmpty()) stringResource(R.string.spotify_status_not_set) else null
            SpotifyRemote.Status.Ready -> stringResource(R.string.spotify_status_ready)
            SpotifyRemote.Status.Authorizing -> stringResource(R.string.spotify_status_authorizing)
            SpotifyRemote.Status.Connecting -> stringResource(R.string.spotify_status_connecting)
            SpotifyRemote.Status.Connected -> stringResource(R.string.spotify_status_connected)
            SpotifyRemote.Status.NotAuthorized -> stringResource(R.string.spotify_status_not_authorized)
            SpotifyRemote.Status.NoSpotify -> stringResource(R.string.spotify_status_no_spotify)
            SpotifyRemote.Status.NotLoggedIn -> stringResource(R.string.spotify_status_not_logged_in)
            SpotifyRemote.Status.TimedOut -> stringResource(R.string.spotify_status_timeout)
            is SpotifyRemote.Status.Refused -> stringResource(R.string.spotify_status_refused, current.reason)
            is SpotifyRemote.Status.Failed -> stringResource(R.string.spotify_status_failed, current.reason)
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = when (status) {
                    SpotifyRemote.Status.Connected -> colors.primary
                    is SpotifyRemote.Status.Refused, is SpotifyRemote.Status.Failed, SpotifyRemote.Status.TimedOut,
                    SpotifyRemote.Status.NotAuthorized, SpotifyRemote.Status.NoSpotify, SpotifyRemote.Status.NotLoggedIn,
                    -> colors.error
                    else -> colors.onSurfaceVariant
                },
            )
        }
    }
}

/** A value to paste into Spotify's dashboard, with a copy button. */
@Composable
private fun CopyRow(label: String, value: String) {
    val context = LocalContext.current
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        overlineContent = { Text(label) },
        headlineContent = { Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) },
        trailingContent = {
            IconButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
                    // Android 13+ shows its own clipboard confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.spotify_copy, label))
            }
        },
    )
}

private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
