package com.fastsearch.assist.ui

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.bestassistant.commands.Command
import com.fastsearch.data.AppEntry
import com.fastsearch.data.Contact

/** Everything the overlay can do; implemented by the activity, which owns launching. */
interface SearchActions {
    fun launchApp(entry: AppEntry, bounds: Rect?)
    fun appInfo(entry: AppEntry, bounds: Rect?)
    fun uninstall(entry: AppEntry)
    fun openContact(contact: Contact, bounds: Rect?)
    fun call(contact: Contact, bounds: Rect?)
    fun message(contact: Contact, bounds: Rect?)
    fun copy(text: String)
    fun openUrl(url: String, bounds: Rect?)
    fun webSearch(query: String, bounds: Rect?)
    fun requestContactsPermission()
    /** Starts listening, or stops and uses what was heard so far. */
    fun toggleListening()
    fun openSettings()

    /** Runs an assistant command (or copies an inline answer). */
    fun runCommand(command: Command, contact: Contact?, bounds: Rect?)
    fun requestLocationPermission()
}

/** Image loading for rows, backed by the in-memory caches in the repositories. */
interface ImageSource {
    fun cachedAppIcon(entry: AppEntry): ImageBitmap?
    suspend fun appIcon(entry: AppEntry): ImageBitmap?
    fun cachedPhoto(uri: Uri): ImageBitmap?
    suspend fun photo(uri: Uri): ImageBitmap?
    fun cachedWebImage(url: String): ImageBitmap?
    suspend fun webImage(url: String): ImageBitmap?
}

val LocalImageSource = staticCompositionLocalOf<ImageSource> { error("ImageSource not provided") }

/** Remembers where an element sits on screen so launches can animate out of it. */
@Stable
class BoundsHolder {
    var rect: Rect? = null
}

fun Modifier.trackBounds(holder: BoundsHolder?): Modifier =
    if (holder == null) this else onGloballyPositioned { holder.rect = it.boundsInWindow() }
