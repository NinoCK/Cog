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

/** One meaning of a word: what kind of word it is ("Noun") and what it means. */
@Immutable
data class Sense(val partOfSpeech: String, val text: String)

@Immutable
data class Definition(val word: String, val senses: List<Sense>, val pageUrl: String)

/**
 * "Define serendipity" from English Wiktionary, like the Wikipedia answers: no account, no key,
 * no tracking. Only the word is sent.
 */
class WiktionaryClient {

    private val cache = LruCache<String, Optional>(32)

    private class Optional(val value: Definition?)

    /** The meanings of [word], null when Wiktionary has none, or a failure when it can't be reached. */
    suspend fun define(word: String): Result<Definition?> {
        val key = word.trim().lowercase(Locale.ROOT)
        cache.get(key)?.let { return Result.success(it.value) }
        // Entries are case-sensitive: "serendipity", but "Paris" and "NASA".
        val spellings = listOf(key, key.replaceFirstChar { it.titlecase(Locale.ROOT) }, key.uppercase(Locale.ROOT)).distinct()
        var reachable = false
        for (spelling in spellings) {
            val (status, body) = get("https://en.wiktionary.org/api/rest_v1/page/definition/${Uri.encode(spelling)}") ?: continue
            reachable = true
            if (status == 404 || body == null) continue
            val senses = parse(body)
            if (senses.isEmpty()) continue
            val definition = Definition(spelling, senses, "https://en.wiktionary.org/wiki/${Uri.encode(spelling)}")
            cache.put(key, Optional(definition))
            return Result.success(definition)
        }
        if (!reachable) return Result.failure(IllegalStateException("Wiktionary unreachable"))
        cache.put(key, Optional(null))
        return Result.success(null)
    }

    /** The response's status and, when it worked, its body; null when there was no answer at all. */
    private suspend fun get(url: String): Pair<Int, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", "PocketSearch/1.0 (Android; privacy-first assistant)")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = connection.responseCode
                code to if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Request failed: ${url.substringAfterLast('/')}", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "Wiktionary"
        private const val MAX_SENSES = 4

        /** Kinds of entry that aren't meanings someone asking "define" wants. */
        private val skipped = setOf("Symbol", "Letter", "Number", "Abbreviation", "Initialism", "Acronym", "Romanization", "Prefix", "Suffix")

        /** The English meanings in a REST definition response, the first of each kind of word first. */
        fun parse(json: String): List<Sense> = runCatching {
            val entries = JSONObject(json).optJSONArray("en") ?: return emptyList()
            val byKind = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.mapNotNull { entry ->
                val kind = entry.optString("partOfSpeech").trim()
                if (kind.isEmpty() || kind in skipped) return@mapNotNull null
                val definitions = entry.optJSONArray("definitions") ?: return@mapNotNull null
                val texts = (0 until definitions.length()).mapNotNull { i ->
                    definitions.optJSONObject(i)?.optString("definition")?.let(::plain)?.takeIf { it.length > 1 }
                }
                if (texts.isEmpty()) null else kind to texts
            }
            // One meaning of each kind first ("noun: ...; verb: ..."), then more of the first kind.
            val firsts = byKind.map { (kind, texts) -> Sense(kind, texts.first()) }
            val more = byKind.firstOrNull()?.let { (kind, texts) -> texts.drop(1).map { Sense(kind, it) } }.orEmpty()
            (firsts + more).take(MAX_SENSES)
        }.getOrDefault(emptyList())

        /** A definition's HTML as plain text, without the nested sub-senses. */
        fun plain(html: String): String {
            var text = html
            // Sub-senses come as nested lists after the main sense.
            while (true) {
                val next = text.replace(Regex("<(ol|ul|dl)[^>]*>(?:(?!<\\1[ >]).)*?</\\1>", RegexOption.DOT_MATCHES_ALL), "")
                if (next == text) break
                text = next
            }
            return text.replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
