package com.bestassistant.weather

import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

@Immutable
data class Forecast(
    /** Where the forecast is for; null means "your location". */
    val place: String?,
    val temperature: Double,
    val feelsLike: Double,
    val code: Int,
    val isDay: Boolean,
    val wind: Double,
    val humidity: Int,
    val days: List<DayForecast>,
    val imperial: Boolean,
) {
    val temperatureUnit: String get() = if (imperial) "°F" else "°C"
    val windUnit: String get() = if (imperial) "mph" else "km/h"
}

@Immutable
data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val max: Double,
    val min: Double,
    /** Chance of precipitation in percent, when the model provides it. */
    val precipitation: Int?,
)

data class GeoPlace(val name: String, val latitude: Double, val longitude: Double)

/**
 * Open-Meteo: free, keyless, no account and no tracking. Only coarse coordinates are sent
 * (rounded to ~1 km), and city names for "weather in <city>".
 */
class WeatherClient {

    private val places = LruCache<String, Optional<GeoPlace>>(32)
    private val forecasts = LruCache<String, Cached>(16)

    private class Optional<T>(val value: T?)
    private class Cached(val forecast: Forecast, val at: Long)

    suspend fun geocode(name: String): Result<GeoPlace?> {
        val key = name.trim().lowercase(Locale.ROOT)
        places.get(key)?.let { return Result.success(it.value) }
        val language = Locale.getDefault().language.ifEmpty { "en" }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(key)}&count=1&language=$language&format=json"
        val body = get(url) ?: return Result.failure(IllegalStateException("network"))
        val place = runCatching {
            val results = JSONObject(body).optJSONArray("results")
            val first = results?.optJSONObject(0) ?: return@runCatching null
            val region = first.optString("country").ifBlank { first.optString("admin1") }
            GeoPlace(
                name = listOf(first.getString("name"), region).filter { it.isNotBlank() }.joinToString(", "),
                latitude = first.getDouble("latitude"),
                longitude = first.getDouble("longitude"),
            )
        }.getOrNull()
        places.put(key, Optional(place))
        return Result.success(place)
    }

    suspend fun forecast(latitude: Double, longitude: Double, place: String?, imperial: Boolean): Forecast? {
        val lat = coarse(latitude)
        val lon = coarse(longitude)
        val key = "$lat,$lon,$imperial"
        forecasts.get(key)?.let { cached ->
            if (System.currentTimeMillis() - cached.at < CACHE_MS) return cached.forecast.copy(place = place)
        }
        val units = if (imperial) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,relative_humidity_2m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7$units"
        val body = get(url) ?: return null
        val forecast = runCatching { parse(JSONObject(body), place, imperial) }
            .onFailure { Log.w(TAG, "Unparseable forecast", it) }
            .getOrNull() ?: return null
        forecasts.put(key, Cached(forecast, System.currentTimeMillis()))
        return forecast
    }

    private fun parse(json: JSONObject, place: String?, imperial: Boolean): Forecast {
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val max = daily.getJSONArray("temperature_2m_max")
        val min = daily.getJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val days = (0 until dates.length()).map { i ->
            DayForecast(
                date = LocalDate.parse(dates.getString(i)),
                code = codes.optInt(i),
                max = max.optDouble(i),
                min = min.optDouble(i),
                precipitation = rain?.takeIf { !it.isNull(i) }?.optInt(i),
            )
        }
        return Forecast(
            place = place,
            temperature = current.getDouble("temperature_2m"),
            feelsLike = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
            code = current.optInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            wind = current.optDouble("wind_speed_10m", 0.0),
            humidity = current.optInt("relative_humidity_2m"),
            days = days,
            imperial = imperial,
        )
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "HTTP ${connection.responseCode} for ${url.substringBefore('?')}")
                    return@runCatching null
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Request failed: ${url.substringBefore('?')}", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "Weather"
        const val CACHE_MS = 10 * 60 * 1000L

        /** Two decimals is roughly 1 km: enough for weather, not enough to pinpoint a home. */
        fun coarse(value: Double): String = String.format(Locale.ROOT, "%.2f", (value * 100).roundToInt() / 100.0)
    }
}
