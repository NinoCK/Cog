package com.bestassistant.money

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** The ECB's reference rates of one working day: how much of each currency one euro buys. */
@Immutable
data class Rates(val date: LocalDate, val perEuro: Map<String, Double>) {

    /** [amount] of [from] in [to]; null when the ECB has no rate for one of them. */
    fun convert(amount: Double, from: String, to: String): Double? {
        val fromRate = if (from == EURO) 1.0 else perEuro[from] ?: return null
        val toRate = if (to == EURO) 1.0 else perEuro[to] ?: return null
        return amount / fromRate * toRate
    }

    fun has(code: String) = code == EURO || code in perEuro

    companion object {
        const val EURO = "EUR"
    }
}

/**
 * Currency rates from the European Central Bank: the daily euro reference rates, a public file with
 * no key and no account. Fetched at most every few hours and kept on the phone, so conversions
 * keep working offline with the last rates.
 */
class ExchangeRates(context: Context) {

    private val prefs = context.getSharedPreferences("exchange_rates", Context.MODE_PRIVATE)
    private val lock = Mutex()

    @Volatile
    private var cached: Rates? = null
    private var fetchedAt = 0L

    /** The latest rates; the ones kept from before when the ECB can't be reached. A failure only when there are none. */
    suspend fun rates(): Result<Rates> = lock.withLock {
        val now = System.currentTimeMillis()
        val known = cached ?: stored()?.also { cached = it }
        if (known != null && now - fetchedAt < FRESH_MS) return Result.success(known)
        val fresh = download()?.let(::parse)
        if (fresh != null) {
            cached = fresh
            fetchedAt = now
            store(fresh, now)
            return Result.success(fresh)
        }
        return known?.let { Result.success(it) } ?: Result.failure(IllegalStateException("ECB rates unavailable"))
    }

    private fun stored(): Rates? = runCatching {
        val json = JSONObject(prefs.getString(KEY_RATES, null) ?: return null)
        fetchedAt = prefs.getLong(KEY_FETCHED, 0L)
        val perEuro = json.getJSONObject("rates").let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } }
        Rates(LocalDate.parse(json.getString("date")), perEuro)
    }.getOrNull()

    private fun store(rates: Rates, at: Long) {
        val json = JSONObject().put("date", rates.date.toString()).put("rates", JSONObject(rates.perEuro))
        prefs.edit {
            putString(KEY_RATES, json.toString())
            putLong(KEY_FETCHED, at)
        }
    }

    private suspend fun download(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(URL_DAILY).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", "PocketSearch/1.0 (Android; privacy-first assistant)")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "ECB rates request failed", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "ExchangeRates"
        private const val URL_DAILY = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        private const val KEY_RATES = "rates"
        private const val KEY_FETCHED = "fetched"

        /** The ECB publishes once a working day, around 16:00 in Frankfurt. */
        private const val FRESH_MS = 4 * 3_600_000L

        private val day = Regex("<Cube\\s+time=['\"](\\d{4}-\\d{2}-\\d{2})['\"]")
        private val rate = Regex("<Cube\\s+currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]")

        /** The rates in the ECB's daily XML file. */
        fun parse(xml: String): Rates? {
            val date = day.find(xml)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val perEuro = rate.findAll(xml).mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.let { m.groupValues[1] to it } }.toMap()
            return if (perEuro.isEmpty()) null else Rates(date, perEuro)
        }
    }
}
