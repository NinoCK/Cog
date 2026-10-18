package com.bestassistant.parking

import android.content.Context
import android.location.Location
import androidx.core.content.edit

/** Where the car was left: saved on the phone only (and kept out of backups), one spot at a time. */
class Parking(context: Context) {

    /** [time] is when it was saved, in epoch milliseconds; [accuracy] in meters, when known. */
    data class Spot(val latitude: Double, val longitude: Double, val accuracy: Float?, val time: Long)

    private val prefs = context.getSharedPreferences("parking", Context.MODE_PRIVATE)

    fun save(location: Location) = prefs.edit {
        putString(KEY_LATITUDE, location.latitude.toString())
        putString(KEY_LONGITUDE, location.longitude.toString())
        if (location.hasAccuracy()) putFloat(KEY_ACCURACY, location.accuracy) else remove(KEY_ACCURACY)
        putLong(KEY_TIME, System.currentTimeMillis())
    }

    fun spot(): Spot? {
        val latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val accuracy = if (prefs.contains(KEY_ACCURACY)) prefs.getFloat(KEY_ACCURACY, 0f) else null
        return Spot(latitude, longitude, accuracy, prefs.getLong(KEY_TIME, 0L))
    }

    private companion object {
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_TIME = "time"
    }
}
