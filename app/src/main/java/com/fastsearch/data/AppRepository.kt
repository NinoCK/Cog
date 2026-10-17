package com.fastsearch.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Telephony
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.fastsearch.search.SearchKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

@Immutable
data class AppEntry(
    /** Stable identity across refreshes: component + profile. */
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val isWorkProfile: Boolean,
    val isSystemApp: Boolean,
    val searchKey: SearchKey,
)

/**
 * Index of launchable activities across all of the user's profiles, kept fresh through
 * [LauncherApps.Callback], with an icon cache sized for instant result rendering.
 */
class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val iconSizePx = (ICON_DP * context.resources.displayMetrics.density).roundToInt()

    private val state = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = state.asStateFlow()

    private val activityInfo = HashMap<String, LauncherActivityInfo>()
    private val loadMutex = Mutex()
    private var prewarmJob: Job? = null

    private val icons = object : LruCache<String, ImageBitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::invalidate)

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::invalidate)
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() = loadMutex.withLock {
        val myUser = Process.myUserHandle()
        val infos = HashMap<String, LauncherActivityInfo>()
        val entries = launcherApps.profiles.flatMap { user ->
            if (user != myUser && userManager.isQuietModeEnabled(user)) return@flatMap emptyList()
            val serial = userManager.getSerialNumberForUser(user)
            runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList()).map { info ->
                val key = "${info.componentName.flattenToShortString()}#$serial"
                infos[key] = info
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName }
                AppEntry(
                    key = key,
                    label = label,
                    packageName = info.componentName.packageName,
                    component = info.componentName,
                    user = user,
                    isWorkProfile = user != myUser,
                    isSystemApp = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    searchKey = SearchKey.of(label, info.componentName.packageName),
                )
            }
        }.sortedBy { it.label.lowercase() }

        synchronized(activityInfo) {
            activityInfo.clear()
            activityInfo.putAll(infos)
        }
        state.value = entries
        prewarmIcons(entries)
    }

    private fun invalidate(packageName: String) {
        icons.snapshot().keys.filter { it.startsWith("$packageName/") }.forEach { icons.remove(it) }
        refresh()
    }

    /** Decodes icons in the background so results render with icons on the first frame. */
    private fun prewarmIcons(entries: List<AppEntry>) {
        prewarmJob?.cancel()
        prewarmJob = scope.launch(Dispatchers.IO) {
            for (entry in entries) {
                if (icons.get(entry.key) == null) loadIconBlocking(entry)
                yield()
            }
        }
    }

    fun cachedIcon(key: String): ImageBitmap? = icons.get(key)

    suspend fun icon(entry: AppEntry): ImageBitmap? =
        icons.get(entry.key) ?: withContext(Dispatchers.IO) { loadIconBlocking(entry) }

    private fun loadIconBlocking(entry: AppEntry): ImageBitmap? {
        val info = synchronized(activityInfo) { activityInfo[entry.key] } ?: return null
        return runCatching {
            info.getBadgedIcon(density).toBitmap(iconSizePx, iconSizePx).asImageBitmap()
        }.getOrNull()?.also { icons.put(entry.key, it) }
    }

    fun launch(entry: AppEntry, sourceBounds: Rect?, options: Bundle?): Boolean = runCatching {
        launcherApps.startMainActivity(entry.component, entry.user, sourceBounds, options)
    }.isSuccess

    fun openAppInfo(entry: AppEntry, sourceBounds: Rect?): Boolean = runCatching {
        launcherApps.startAppDetailsActivity(entry.component, entry.user, sourceBounds, null)
    }.isSuccess

    fun uninstallIntent(entry: AppEntry): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null))
            .putExtra(Intent.EXTRA_USER, entry.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Apps to show before anything is typed: the user's most used apps, topped up with
     * the device's default dialer, messages, browser and camera on a fresh install.
     */
    fun suggestions(all: List<AppEntry>, usage: UsageStore, limit: Int): List<AppEntry> {
        val now = System.currentTimeMillis()
        val used = all
            .map { it to usage.frecency(it.key, now) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }
        if (used.size >= limit) return used.take(limit)

        val byPackage = all.filter { !it.isWorkProfile }.associateBy { it.packageName }
        val defaults = (defaultPackages() + POPULAR_PACKAGES).mapNotNull { byPackage[it] }
        return (used + defaults).distinctBy { it.key }.take(limit)
    }

    private fun defaultPackages(): List<String> {
        val pm = context.packageManager
        fun resolve(intent: Intent) =
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeIf { it != "android" } // "android" is the chooser, meaning no default is set
        return listOfNotNull(
            resolve(Intent(Intent.ACTION_DIAL)),
            runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
            resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            resolve(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
        )
    }

    private companion object {
        const val ICON_DP = 56
        const val ICON_CACHE_BYTES = 24 * 1024 * 1024
        val POPULAR_PACKAGES = listOf(
            "com.google.android.apps.photos", "com.sec.android.gallery3d",
            "com.google.android.gm", "com.google.android.apps.maps",
            "com.whatsapp", "com.google.android.youtube", "com.spotify.music",
            "com.android.vending", "com.google.android.calendar", "com.android.settings",
        )
    }
}
