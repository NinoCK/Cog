package com.bestassistant.media

import java.text.Normalizer
import java.util.Locale

/**
 * Compares names the way people say them: case, accents, punctuation, a leading "the" and Greek
 * case endings ("Βασίλη" for "Βασίλης") don't matter.
 */
object MusicNames {

    private val articles = setOf("the", "ο", "η", "οι", "το", "τα", "τον", "την", "τη", "τουσ", "τισ")

    fun normalize(name: String): String {
        val words = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ς', 'σ')
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
        return (if (words.size > 1 && words.first() in articles) words.drop(1) else words).joinToString(" ")
    }

    /** Greek names change their last letter with case: "Μάλαμας", "του Μάλαμα". */
    private fun stem(normalized: String) = normalized.split(' ').joinToString(" ") { it.removeSuffix("σ") }

    fun same(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.replace(" ", "") == y.replace(" ", "") || stem(x) == stem(y)
    }

    private val playlistWords = setOf("my", "playlist", "playlists", "list", "μου", "λιστα", "λιστεσ")

    /** A playlist name without "my", "playlist" and Greek endings, for matching what was said to what was saved. */
    fun playlistKey(name: String): String =
        stem(normalize(name).split(' ').filter { it !in playlistWords }.joinToString(" "))

    /**
     * The saved playlist [spoken] refers to: the same name, or (unless [exactOnly]) the one saved
     * name that contains it or is contained in it.
     */
    fun <T> bestPlaylist(spoken: String, saved: List<T>, exactOnly: Boolean, name: (T) -> String): T? {
        val key = playlistKey(spoken)
        if (key.isEmpty()) return null
        saved.firstOrNull { playlistKey(name(it)) == key }?.let { return it }
        if (exactOnly || key.length < 3) return null
        val close = saved.filter {
            val other = playlistKey(name(it))
            other.length >= 3 && (other.contains(key) || key.contains(other))
        }
        return close.singleOrNull()
    }
}

/** Spotify share links and URIs, reduced to the "spotify:playlist:<id>" form the app opens. */
object SpotifyLinks {

    private val web = Regex(
        "https?://open\\.spotify\\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?(?:user/[^/\\s]+/)?(playlist|album|artist|track)/([A-Za-z0-9]{22})",
    )
    private val uri = Regex("spotify:(?:user:[^:\\s]+:)?(playlist|album|artist|track):([A-Za-z0-9]{22})")

    /** Short share links can't be read offline; Spotify opens them itself. */
    private val short = Regex("https?://(?:spotify\\.link|spoti\\.fi)/[A-Za-z0-9]+")

    /** The Spotify link in [text] ("Copy link" output, a URI, or a short link), or null. */
    fun find(text: String): String? {
        (web.find(text) ?: uri.find(text))?.let { return "spotify:${it.groupValues[1]}:${it.groupValues[2]}" }
        return short.find(text)?.value
    }

    /** "playlist", "album", "artist" or "track" for a "spotify:" URI; null for short links. */
    fun type(link: String): String? = uri.matchEntire(link)?.groupValues?.get(1)
}
