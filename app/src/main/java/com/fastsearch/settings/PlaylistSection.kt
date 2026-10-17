package com.fastsearch.settings

import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestassistant.media.SavedPlaylist
import com.bestassistant.media.SpotifyLinks
import com.fastsearch.R
import com.fastsearch.fastSearch

/**
 * The user's Spotify playlists, saved by name and share link so "play my road trip playlist"
 * opens and starts the right one. Nothing is read from Spotify itself.
 */
@Composable
internal fun PlaylistSection() {
    val store = LocalContext.current.fastSearch.playlists
    val saved by store.all.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)

    ListItem(
        colors = transparent,
        leadingContent = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
        headlineContent = {
            Text(stringResource(R.string.playlists_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
    saved.forEach { playlist ->
        ListItem(
            colors = transparent,
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
            headlineContent = { Text(playlist.name) },
            supportingContent = { Text(stringResource(kindLabel(playlist))) },
            trailingContent = {
                IconButton(onClick = { store.remove(playlist) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.playlist_remove, playlist.name))
                }
            },
        )
    }
    ListItem(
        modifier = Modifier.clickable { adding = true },
        colors = transparent,
        leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.playlists_add)) },
    )

    if (adding) {
        AddPlaylistDialog(
            onSave = { name, link ->
                store.add(name, link)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

private fun kindLabel(playlist: SavedPlaylist): Int = when (SpotifyLinks.type(playlist.link)) {
    "playlist" -> R.string.playlist_kind_playlist
    "album" -> R.string.playlist_kind_album
    "artist" -> R.string.playlist_kind_artist
    "track" -> R.string.playlist_kind_track
    else -> R.string.playlist_kind_link
}

@Composable
private fun AddPlaylistDialog(onSave: (name: String, link: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var linkText by remember { mutableStateOf("") }
    val link = SpotifyLinks.find(linkText)
    val canSave = name.isNotBlank() && link != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
        title = { Text(stringResource(R.string.playlists_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text(stringResource(R.string.playlist_link_label)) },
                    supportingText = { Text(stringResource(R.string.playlist_link_hint)) },
                    isError = linkText.isNotBlank() && link == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    trailingIcon = {
                        // Read only when tapped: the clipboard is the user's business otherwise.
                        IconButton(
                            onClick = {
                                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { linkText = it.trim() }
                            },
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.playlist_paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (link != null) onSave(name, link) }, enabled = canSave) {
                Text(stringResource(R.string.playlist_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel)) } },
    )
}
