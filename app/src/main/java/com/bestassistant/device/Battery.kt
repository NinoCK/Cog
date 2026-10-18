package com.bestassistant.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** The battery right now: [percent], whether it's [charging] (or [full] on the charger), and how long until it is full. */
data class BatteryState(
    val percent: Int,
    val charging: Boolean,
    val pluggedIn: Boolean,
    val full: Boolean,
    /** Android's own estimate while charging; null when it has none. */
    val minutesToFull: Int?,
)

/** Reads the battery from the system: no permission needed. */
object Battery {

    fun read(context: Context): BatteryState? = runCatching {
        val manager = context.getSystemService(BatteryManager::class.java)
        // The sticky battery broadcast: registering without a receiver just returns the last one.
        val sticky: Intent? = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            ?: if (level >= 0 && scale > 0) level * 100 / scale else return null
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val full = status == BatteryManager.BATTERY_STATUS_FULL || (plugged && percent >= 100)
        val charging = !full && (status == BatteryManager.BATTERY_STATUS_CHARGING || manager.isCharging)
        val remaining = if (charging) manager.computeChargeTimeRemaining() else -1
        BatteryState(
            percent = percent,
            charging = charging,
            pluggedIn = plugged,
            full = full,
            minutesToFull = remaining.takeIf { it > 0 }?.let { ((it + 59_999) / 60_000).toInt() },
        )
    }.getOrNull()
}
