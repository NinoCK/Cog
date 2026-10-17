package com.fastsearch.data

import android.content.Context
import androidx.core.content.edit
import kotlin.math.ln
import kotlin.math.min

/**
 * Remembers what the user launches so results improve with use:
 *  - frecency (how often and how recently an app was opened) orders the suggestions row;
 *  - query affinity ("c" usually means Chrome for this user) breaks ties while typing.
 */
class UsageStore(context: Context) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun recordLaunch(key: String, query: String) {
        val now = System.currentTimeMillis()
        val prefixes = query.replace(" ", "").take(MAX_AFFINITY_PREFIX)
        prefs.edit {
            putInt("n:$key", prefs.getInt("n:$key", 0) + 1)
            putLong("t:$key", now)
            for (length in 1..prefixes.length) {
                val affinityKey = "q:${prefixes.take(length)}|$key"
                putInt(affinityKey, prefs.getInt(affinityKey, 0) + 1)
            }
        }
    }

    /** Frecency in roughly 0..100: decays with a one-week half-life-ish curve. */
    fun frecency(key: String, now: Long = System.currentTimeMillis()): Double {
        val launches = prefs.getInt("n:$key", 0)
        if (launches == 0) return 0.0
        val days = (now - prefs.getLong("t:$key", now)).coerceAtLeast(0) / DAY_MS
        return 40.0 * ln(1.0 + launches) / (1.0 + days / 7.0)
    }

    /** Ranking boost for [key] given what the user has typed (normalized, spaces removed). */
    fun boost(key: String, compactQuery: String): Int {
        val affinity = if (compactQuery.isNotEmpty() && compactQuery.length <= MAX_AFFINITY_PREFIX) {
            prefs.getInt("q:$compactQuery|$key", 0)
        } else {
            0
        }
        return min(affinity, 5) * 60 + min(frecency(key), 120.0).toInt()
    }

    private companion object {
        const val MAX_AFFINITY_PREFIX = 4
        const val DAY_MS = 86_400_000.0
    }
}
