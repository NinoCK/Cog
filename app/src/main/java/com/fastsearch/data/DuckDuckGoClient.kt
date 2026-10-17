package com.fastsearch.data

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Immutable
data class InstantAnswer(
    val heading: String,
    val text: String,
    val source: String,
    val url: String?,
    val imageUrl: String?,
)

/**
 * DuckDuckGo's free, keyless endpoints:
 *  - autocomplete (the same one browsers use for DuckDuckGo search suggestions);
 *  - the Instant Answer API for short factual answers (Wikipedia abstracts, definitions...).
 * Responses are cached briefly so backspacing doesn't refetch.
 */
class DuckDuckGoClient {
    private val suggestionCache = LruCache<String, List<String>>(64)
    private val answerCache = LruCache<String, Optional>(64)
    private val imageCache = LruCache<String, ImageBitmap>(16)

    /** LruCache can't hold nulls, so "no answer" is cached as an empty wrapper. */
    private class Optional(val value: InstantAnswer?)

    suspend fun suggestions(query: String): List<String> {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        suggestionCache.get(key)?.let { return it }
        val url = "https://duckduckgo.com/ac/?q=${Uri.encode(key)}&type=list&kl=wt-wt"
        val body = get(url) ?: return emptyList()
        val list = runCatching {
            val array = JSONArray(body).getJSONArray(1)
            (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        suggestionCache.put(key, list)
        return list
    }

    suspend fun instantAnswer(query: String): InstantAnswer? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        answerCache.get(key)?.let { return it.value }
        val url = "https://api.duckduckgo.com/?q=${Uri.encode(key)}&format=json&no_html=1&skip_disambig=1&no_redirect=1&t=fastsearch"
        val body = get(url) ?: return null // network failures aren't cached
        val answer = runCatching { parseAnswer(query.trim(), JSONObject(body)) }
            .onFailure { Log.w(TAG, "Unparseable instant answer (${body.length} chars): ${body.take(120)}", it) }
            .getOrNull()
        answerCache.put(key, Optional(answer))
        return answer
    }

    private fun parseAnswer(query: String, json: JSONObject): InstantAnswer? {
        val heading = json.optString("Heading")
        val image = json.optString("Image").takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://duckduckgo.com$it"
        }

        // Direct answers (e.g. "ip address", "random number") — skip "calc", we do maths locally.
        val answer = json.opt("Answer") as? String
        val answerType = json.optString("AnswerType")
        if (!answer.isNullOrBlank() && answerType != "calc") {
            return InstantAnswer(
                heading = heading.ifBlank { query },
                text = answer,
                source = "DuckDuckGo",
                url = null,
                imageUrl = null,
            )
        }

        val abstract = json.optString("AbstractText")
        if (abstract.isNotBlank()) {
            return InstantAnswer(
                heading = heading.ifBlank { query },
                text = abstract,
                source = json.optString("AbstractSource").ifBlank { "DuckDuckGo" },
                url = json.optString("AbstractURL").takeIf { it.isNotBlank() },
                imageUrl = image,
            )
        }

        val definition = json.optString("Definition")
        if (definition.isNotBlank()) {
            return InstantAnswer(
                heading = heading.ifBlank { query },
                text = definition,
                source = json.optString("DefinitionSource").ifBlank { "DuckDuckGo" },
                url = json.optString("DefinitionURL").takeIf { it.isNotBlank() },
                imageUrl = null,
            )
        }
        return null
    }

    fun cachedImage(url: String): ImageBitmap? = imageCache.get(url)

    suspend fun image(url: String): ImageBitmap? = imageCache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(url)
            try {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.also { imageCache.put(url, it) }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(url)
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "HTTP $code for ${url.substringBefore('?')}")
                    return@runCatching null
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Request failed: ${url.substringBefore('?')}", it) }.getOrNull()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 4_000
            setRequestProperty("User-Agent", "FastSearch/1.0 (Android)")
            setRequestProperty("Accept", "application/json, image/*")
        }

    private companion object {
        const val TAG = "DuckDuckGo"
    }
}
