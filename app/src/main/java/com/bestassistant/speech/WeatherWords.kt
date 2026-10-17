package com.bestassistant.speech

import androidx.annotation.StringRes
import com.fastsearch.R

/** WMO weather interpretation codes (as used by Open-Meteo), shared by the card and the spoken reply. */
object WeatherWords {

    @StringRes
    fun condition(code: Int): Int = when (code) {
        0 -> R.string.wmo_clear
        1 -> R.string.wmo_mostly_clear
        2 -> R.string.wmo_partly_cloudy
        3 -> R.string.wmo_overcast
        45, 48 -> R.string.wmo_fog
        51, 53, 55 -> R.string.wmo_drizzle
        56, 57, 66, 67 -> R.string.wmo_freezing_rain
        61, 63, 80, 81 -> R.string.wmo_rain
        65, 82 -> R.string.wmo_heavy_rain
        71, 73, 77, 85 -> R.string.wmo_snow
        75, 86 -> R.string.wmo_heavy_snow
        95, 96, 99 -> R.string.wmo_thunderstorm
        else -> R.string.wmo_unknown
    }

    fun emoji(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}
