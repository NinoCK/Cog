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

    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission() || manager == null) return null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val lastKnown = withContext(Dispatchers.IO) {
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < FRESH_MS) return lastKnown

        val provider = listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
        val fresh = if (provider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
                    }.onFailure { continuation.resume(null) }
                }
            }
        } else {
            null
        }
        return fresh ?: lastKnown
    }

    private companion object {
        const val FUSED = "fused"
        const val FRESH_MS = 30 * 60 * 1000L
        const val TIMEOUT_MS = 6_000L
    }
}
