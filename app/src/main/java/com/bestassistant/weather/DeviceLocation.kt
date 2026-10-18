package com.bestassistant.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Coarse location straight from the platform LocationManager: no Play services. A recent
 * last-known fix is used when there is one, so asking for the weather doesn't spin up GPS.
 */
class DeviceLocation(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Exact location, for "remember where I parked"; the user may have allowed only the approximate one. */
    fun hasPrecisePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission() || manager == null) return null
        val providers = providers()
        val lastKnown = lastKnown(providers)
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < FRESH_MS) return lastKnown

        val provider = listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
        return fix(provider, TIMEOUT_MS) ?: lastKnown
    }

    /**
     * A new fix, as exact as the phone allows (satellites when precise location is allowed), for
     * where the car is or where to tell someone the user is. A fix from the last minute will do.
     */
    @SuppressLint("MissingPermission")
    suspend fun precise(): Location? {
        if (!hasPermission() || manager == null) return null
        val providers = providers()
        val lastKnown = lastKnown(providers)
        val now = System.currentTimeMillis()
        if (lastKnown != null && now - lastKnown.time < RECENT_MS && lastKnown.accuracy <= GOOD_METERS) return lastKnown

        val order = if (hasPrecisePermission()) listOf(FUSED, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf(FUSED, LocationManager.NETWORK_PROVIDER)
        return fix(order.firstOrNull { it in providers }, PRECISE_TIMEOUT_MS)
            ?: lastKnown?.takeIf { now - it.time < FRESH_MS }
    }

    private fun providers(): List<String> = runCatching { manager?.getProviders(true) }.getOrNull().orEmpty()

    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(providers: List<String>): Location? = withContext(Dispatchers.IO) {
        providers.mapNotNull { runCatching { manager?.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fix(provider: String?, timeout: Long): Location? {
        if (provider == null || manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
                }.onFailure { continuation.resume(null) }
            }
        }
    }

    private companion object {
        const val FUSED = "fused"
        const val FRESH_MS = 30 * 60 * 1000L
        const val TIMEOUT_MS = 6_000L
        const val RECENT_MS = 60 * 1000L
        const val GOOD_METERS = 50f
        const val PRECISE_TIMEOUT_MS = 10_000L
    }
}
