package com.bestassistant.wiki

import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Immutable
data class Article(
    val title: String,
    val description: String?,
    /** The lead section in plain text. */
    val extract: String,
    val imageUrl: String?,
    val pageUrl: String?,
)

/**
 * "What is ..." answers straight from Wikipedia: no account, no key, no tracking. A search finds
 * the best-matching page, then the REST summary gives its plain-text lead and thumbnail.
 * The device language's Wikipedia is tried first, then English.
 */
class WikipediaClient {

    private val cache = LruCache<String, Optional>(32)

    private class Optional(val value: Article?)

    /** The article for [topic], null when Wikipedia has none, or a failure when it can't be reached. */
    suspend fun lookup(topic: String, locale: Locale = Locale.getDefault()): Result<Article?> {
        val languages = listOf(locale.language, "en").filter { it.matches(Regex("[a-z]{2,3}")) }.distinct()
        val key = "${languages.first()}:${topic.trim().lowercase(Locale.ROOT)}"
        cache.get(key)?.let { return Result.success(it.value) }

        var reachable = false
        for (language in languages) {
            val search = get("https://$language.wikipedia.org/w/rest.php/v1/search/page?q=${Uri.encode(topic)}&limit=$CANDIDATES")
                ?: continue
            reachable = true
            for (pageKey in parseSearch(search)) {
                val summary = get("https://$language.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(pageKey)}") ?: continue
                val article = parseSummary(summary) ?: continue
                cache.put(key, Optional(article))
                return Result.success(article)
            }
        }
        if (!reachable) return Result.failure(IllegalStateException("Wikipedia unreachable"))
        cache.put(key, Optional(null))
        return Result.success(null)
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 5_000
                // Wikimedia asks API clients to identify themselves.
                setRequestProperty("User-Agent", "PocketSearch/1.0 (Android; privacy-first assistant)")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Request failed: ${url.substringBefore('?')}", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "Wikipedia"
        private const val CANDIDATES = 3

        /** Page keys from a REST search response, best match first. */
        fun parseSearch(json: String): List<String> = runCatching {
            val pages = JSONObject(json).optJSONArray("pages") ?: return emptyList()
            (0 until pages.length()).mapNotNull { pages.optJSONObject(it)?.optString("key")?.takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())

        /** A usable article from a REST summary, or null for disambiguation pages and empty extracts. */
        fun parseSummary(json: String): Article? = runCatching {
            val o = JSONObject(json)
            if (o.optString("type") == "disambiguation") return null
            val extract = o.optString("extract").trim()
            if (extract.isEmpty()) return null
            Article(
                title = o.optString("title").ifBlank { return null },
                description = o.optString("description").takeIf { it.isNotBlank() },
                extract = extract,
                imageUrl = o.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() },
                pageUrl = o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        /** The first sentence or two, for reading aloud: long enough to answer, short enough to listen to. */
        fun spokenSummary(extract: String, maxChars: Int = 260): String {
            val sentences = extract.replace(Regex("\\s*\\([^()]*\\)"), "") // drop pronunciations and dates in brackets
                .split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"“])"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (sentences.isEmpty()) return extract.take(maxChars)
            val out = StringBuilder(sentences.first())
            for (sentence in sentences.drop(1)) {
                if (out.length + sentence.length + 1 > maxChars) break
                out.append(' ').append(sentence)
            }
            return out.toString()
        }
    }
}
