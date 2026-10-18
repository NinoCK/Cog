package com.bestassistant.integrations.jellyfin

import com.bestassistant.integration.Request
import com.bestassistant.integration.RequestParser
import com.bestassistant.integration.Slot

/** A request for Jellyfin; [said] is the whole request, for its row in the results. */
sealed interface JellyfinRequest : Request {
    val said: String

    /** The player it's for, as said ("the bedroom tv", "my phone"); null: the one playing, or the usual one. */
    val player: String? get() = null
}

/**
 * "play inception on the living room tv": [what] to play, and where. The player is only a guess
 * until the players are known: "Murder on the Orient Express" names none, so [unsplit] is what's
 * played when [player] turns out not to be one (null when the whole thing isn't a request).
 */
data class PlayRequest(val what: Wanted, override val player: String?, val unsplit: Wanted?, override val said: String) : JellyfinRequest

sealed interface Wanted {
    /** A movie, show, album, artist or playlist by name; a show plays from where the user is in it. */
    data class Title(val name: String, val type: TitleType = TitleType.ANY, val shuffle: Boolean = false, val fromStart: Boolean = false) : Wanted

    /** "season 2 episode 3 of the office"; no [season]: the one the user is on. No [episode]: the season, from its first unseen. */
    data class Episode(val show: String, val season: Int?, val episode: Int?) : Wanted

    /** The episode after the one playing, or the next of [show]. */
    data class NextEpisode(val show: String?) : Wanted

    /** The episode before the one playing. */
    data object PreviousEpisode : Wanted

    /** The newest episode of [show]. */
    data class LatestEpisode(val show: String) : Wanted

    /** Something picked at random: of [type], in [genre], or an episode of [show]. */
    data class Random(val type: TitleType, val genre: String? = null, val show: String? = null) : Wanted

    /** Whatever the user stopped watching last. */
    data object Resume : Wanted
}

enum class TitleType { ANY, MOVIE, SHOW, MUSIC }

/** Pause, resume, stop, or skip in the queue. */
data class ControlRequest(val action: Transport, override val player: String?, override val said: String) : JellyfinRequest

enum class Transport { PAUSE, RESUME, STOP, NEXT, PREVIOUS }

/** Skip forward or back by [seconds] (negative: back), or to [seconds] in when not [relative]. */
data class SeekRequest(val seconds: Int, val relative: Boolean, override val said: String) : JellyfinRequest

data class VolumeRequest(val change: VolumeChange, override val said: String) : JellyfinRequest

sealed interface VolumeChange {
    data class To(val percent: Int) : VolumeChange
    data object Up : VolumeChange
    data object Down : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
}

/** Subtitles on (in [language], if said) or off. */
data class SubtitleRequest(val on: Boolean, val language: String?, override val said: String) : JellyfinRequest

/** The audio track in [language] ("original": the one the video was made in). */
data class AudioRequest(val language: String, override val said: String) : JellyfinRequest

data class QuestionRequest(val asked: Asked, override val said: String) : JellyfinRequest

enum class Asked { NOW_PLAYING, TIME_LEFT, NEW, NEXT_UP }

/** "do I have the matrix": whether [title] is on the server. */
data class HaveRequest(val title: String, override val said: String) : JellyfinRequest

/** Mark [title] (null: what's playing) seen or not. */
data class MarkRequest(val title: String?, val watched: Boolean, override val said: String) : JellyfinRequest

/**
 * Finds requests for Jellyfin. Anything that names Jellyfin is its own; otherwise only once it's
 * set up ([ready]), and only what's clearly about video: "pause the movie", "turn on subtitles",
 * "skip ahead 30 seconds", "play the next episode", "play season 2 of the office", or "play" with
 * the name of a movie or show on the server ([library]), so "play thriller" stays music unless
 * there's a movie by that name.
 *
 * Early, before music, for all of that; late, after everything Cog does itself, "watch ..." with
 * any name, which nothing else takes.
 */
class JellyfinRequests(private val ready: () -> Boolean, private val library: Library) : RequestParser {

    override fun parse(text: String, slot: Slot): JellyfinRequest? {
        val explicit = jellyfinWord.containsMatchIn(text)
        val body = if (explicit) text.replace(jellyfinWord, " ").replace(spaces, " ").trim() else text
        if (body.isEmpty()) return null
        if (!explicit && !ready()) return null
        // "play thriller on spotify" is music, whatever the library holds; "watch netflix" is another app.
        if (!explicit && (onOtherApp.matches(body) || body.substringAfter(' ').let { it in otherApps || it in notTitles })) return null
        if (slot == Slot.LATE) return if (explicit) null else play(body, text, explicit = false, late = true)
        return control(body, text, explicit)
            ?: seek(body, text, explicit)
            ?: tracks(body, text)
            ?: volume(body, text, explicit)
            ?: question(body, text, explicit)
            ?: mark(body, text)
            ?: play(body, text, explicit, late = false)
    }

    // region Playing

    private fun play(body: String, said: String, explicit: Boolean, late: Boolean): PlayRequest? {
        relative(body)?.let { return PlayRequest(it, null, null, said) }
        val m = playLead.matchEntire(body) ?: return null
        val verb = m.groupValues[1]
        val rest = m.groupValues[2].trim()
        val watching = verb !in playVerbs
        if (rest.isEmpty()) return if (verb in resumeVerbs) PlayRequest(Wanted.Resume, null, null, said) else null
        // Late, only what nothing before understood: "watch ...".
        if (late && !watching) return null
        val loose = explicit || late
        val shuffle = verb == "shuffle"
        val whole = wanted(rest, loose, shuffle)
        playerSplit.matchEntire(rest)?.let { split ->
            val player = split.groupValues[2].removePrefix("the ").removePrefix("my ").trim()
            if (player.split(' ').size <= MAX_PLAYER_WORDS) {
                wanted(split.groupValues[1].trim(), loose, shuffle)?.let { return PlayRequest(it, player, whole, said) }
            }
        }
        return whole?.let { PlayRequest(it, null, null, said) }
    }

    /** "the next episode", "previous episode": from the one playing. */
    private fun relative(body: String): Wanted? = when {
        nextEpisode.matches(body) -> Wanted.NextEpisode(null)
        previousEpisode.matches(body) -> Wanted.PreviousEpisode
        else -> null
    }

    /** What follows "play": [loose] takes any name, else only one the library has. */
    private fun wanted(text: String, loose: Boolean, shuffle: Boolean): Wanted? {
        var part = text
        val fromStart = fromStartSuffix.find(part)?.let { part = part.removeRange(it.range).trim(); true } ?: false

        nextOf.matchEntire(part)?.let { return Wanted.NextEpisode(it.groupValues[1].ifEmpty { null }) }
        latestOf.matchEntire(part)?.let { return Wanted.LatestEpisode(it.groupValues[1]) }
        randomEpisodeOf.matchEntire(part)?.let { return Wanted.Random(TitleType.SHOW, show = it.groupValues[1]) }
        random(part, loose)?.let { return it }
        episode(part)?.let { return it }

        typed.matchEntire(part)?.let { m ->
            val type = typeOf(m.groupValues[1])
            // "play the album ..." is music's, unless Jellyfin was asked.
            if (type == TitleType.MUSIC && !loose) return null
            return Wanted.Title(m.groupValues[2], type, shuffle, fromStart)
        }
        typedAfter.matchEntire(part)?.let { m -> return Wanted.Title(m.groupValues[1], typeOf(m.groupValues[2]), shuffle, fromStart) }
        if (loose || library.has(part)) return Wanted.Title(part, TitleType.ANY, shuffle, fromStart)
        return null
    }

    private fun random(part: String, loose: Boolean): Wanted? {
        randomKind.matchEntire(part)?.let { m ->
            val article = m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty()
            val genre = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { Spoken.genres[it] }
            val kind = m.groupValues[4]
            if (!article && genre == null) return null
            if (kind == "something" && !loose && genre == null) return null
            return Wanted.Random(
                when (kind) {
                    "show", "tv show", "series" -> TitleType.SHOW
                    "something" -> TitleType.ANY
                    else -> TitleType.MOVIE
                },
                genre,
            )
        }
        randomGenre.matchEntire(part)?.let { m -> return Wanted.Random(TitleType.MOVIE, Spoken.genres[m.groupValues[1]]) }
        return null
    }

    private fun episode(part: String): Wanted? {
        fun n(text: String) = Spoken.number(text)
        seasonEpisodeOf.matchEntire(part)?.let { m ->
            return Wanted.Episode(m.groupValues[3], n(m.groupValues[1]) ?: return null, n(m.groupValues[2]) ?: return null)
        }
        episodeOfSeasonOf.matchEntire(part)?.let { m ->
            return Wanted.Episode(m.groupValues[3], n(m.groupValues[2]) ?: return null, n(m.groupValues[1]) ?: return null)
        }
        showSeasonEpisode.matchEntire(part)?.let { m ->
            return Wanted.Episode(m.groupValues[1], n(m.groupValues[2]) ?: return null, n(m.groupValues[3]) ?: return null)
        }
        showCode.matchEntire(part)?.let { m -> return Wanted.Episode(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        seasonOf.matchEntire(part)?.let { m -> return Wanted.Episode(m.groupValues[2], n(m.groupValues[1]) ?: return null, null) }
        showSeason.matchEntire(part)?.let { m -> return Wanted.Episode(m.groupValues[1], n(m.groupValues[2]) ?: return null, null) }
        episodeOf.matchEntire(part)?.let { m -> return Wanted.Episode(m.groupValues[2], null, n(m.groupValues[1]) ?: return null) }
        showEpisode.matchEntire(part)?.let { m -> return Wanted.Episode(m.groupValues[1], null, n(m.groupValues[2]) ?: return null) }
        return null
    }

    private fun typeOf(word: String) = when (word) {
        "movie", "film" -> TitleType.MOVIE
        "show", "tv show", "series", "tv series" -> TitleType.SHOW
        else -> TitleType.MUSIC
    }

    // endregion

    // region Controlling what plays

    private fun control(body: String, said: String, explicit: Boolean): JellyfinRequest? {
        if (restart.matches(body)) return SeekRequest(0, relative = false, said)
        val m = (if (explicit) bareTransport.matchEntire(body) else null) ?: videoTransport.matchEntire(body) ?: return null
        val action = when (m.groupValues[1]) {
            "pause" -> Transport.PAUSE
            "stop", "end" -> Transport.STOP
            "next", "skip" -> Transport.NEXT
            "previous", "back" -> Transport.PREVIOUS
            else -> Transport.RESUME
        }
        val player = m.groups[2]?.value?.removePrefix("the ")?.removePrefix("my ")?.trim()?.takeIf { it.isNotEmpty() }
        return ControlRequest(action, player, said)
    }

    private fun seek(body: String, said: String, explicit: Boolean): SeekRequest? {
        jumpTo.matchEntire(body)?.let { m -> Spoken.duration(m.groupValues[1])?.let { return SeekRequest(it, relative = false, said) } }
        forward.matchEntire(body)?.let { m ->
            val amount = m.groupValues[1]
            return SeekRequest(if (amount.isEmpty()) DEFAULT_FORWARD else Spoken.duration(amount, bareUnit = 1) ?: return null, relative = true, said)
        }
        skipBy.matchEntire(body)?.let { m -> Spoken.duration(m.groupValues[1])?.let { return SeekRequest(it, relative = true, said) } }
        back.matchEntire(body)?.let { m ->
            val amount = m.groupValues[2]
            if (amount.isEmpty()) return if (m.groupValues[1] == "rewind" || explicit) SeekRequest(-DEFAULT_BACK, relative = true, said) else null
            return SeekRequest(-(Spoken.duration(amount, bareUnit = 1) ?: return null), relative = true, said)
        }
        return null
    }

    private fun tracks(body: String, said: String): JellyfinRequest? {
        subtitlesOnOff.matchEntire(body)?.let { m ->
            val word = m.groupValues.drop(1).first { it in onOffWords }
            return SubtitleRequest(word in onWords, null, said)
        }
        subtitlesIn.matchEntire(body)?.let { m -> return SubtitleRequest(true, m.groupValues[1].takeIf { it != "original" }, said) }
        languageSubtitles.matchEntire(body)?.let { m -> return SubtitleRequest(true, m.groupValues[1].takeIf { it != "original" }, said) }
        audioIn.matchEntire(body)?.let { m -> return AudioRequest(m.groupValues[1], said) }
        (languageAudio.matchEntire(body) ?: watchIn.matchEntire(body))?.let { m -> return AudioRequest(m.groupValues[1], said) }
        return null
    }

    private fun volume(body: String, said: String, explicit: Boolean): VolumeRequest? {
        videoMute.matchEntire(body)?.let { m -> return VolumeRequest(if (m.groupValues[1] == "mute") VolumeChange.Mute else VolumeChange.Unmute, said) }
        if (!explicit) return null
        volumeTo.matchEntire(body)?.let { m ->
            val percent = m.groupValues[1].toInt().takeIf { it in 0..100 } ?: return null
            return VolumeRequest(VolumeChange.To(percent), said)
        }
        val change = when (body) {
            in upWords -> VolumeChange.Up
            in downWords -> VolumeChange.Down
            "mute", "mute it", "mute the volume", "mute the sound" -> VolumeChange.Mute
            "unmute", "unmute it", "unmute the volume", "unmute the sound" -> VolumeChange.Unmute
            else -> return null
        }
        return VolumeRequest(change, said)
    }

    // endregion

    // region Questions

    private fun question(body: String, said: String, explicit: Boolean): JellyfinRequest? {
        val asked = when {
            nowPlaying.matches(body) || (explicit && body in explicitNowPlaying) -> Asked.NOW_PLAYING
            timeLeft.any { it.matches(body) } -> Asked.TIME_LEFT
            newToWatch.matches(body) || (explicit && body in explicitNew) -> Asked.NEW
            nextUp.matches(body) || (explicit && body == "next up") -> Asked.NEXT_UP
            else -> null
        }
        asked?.let { return QuestionRequest(it, said) }
        val title = haveIt.matchEntire(body)?.groupValues?.get(2)
            ?: if (explicit) isIt.matchEntire(body)?.groupValues?.get(1) else null
        if (title != null) {
            val typedTitle = haveTyped.matchEntire(body) != null
            if (explicit || typedTitle || library.has(title)) return HaveRequest(title, said)
        }
        return null
    }

    private fun mark(body: String, said: String): MarkRequest? {
        val m = markAs.matchEntire(body) ?: return null
        val title = m.groupValues[1].takeIf { it !in currentWords }
        return MarkRequest(title, m.groupValues[2] in watchedWords, said)
    }

    // endregion

    private companion object {
        const val MAX_PLAYER_WORDS = 4
        const val DEFAULT_FORWARD = 30
        const val DEFAULT_BACK = 10

        val spaces = Regex("\\s+")
        val jellyfinWord = Regex("(?:^|\\s)(?:(?:on|in|with|using|through|from|via|to) )?(?:my |the )?(?:jellyfin|jelly fin)(?: app| server)?(?=\\s|$)")

        /** Other apps "play ..." and "watch ..." can name, besides players: "play thriller on spotify", "watch netflix". */
        val otherApps = setOf(
            "spotify", "youtube music", "youtube", "you tube", "deezer", "tidal", "apple music", "soundcloud", "amazon music",
            "pandora", "the radio", "radio", "netflix", "disney plus", "disney+", "prime video", "amazon prime", "hbo", "hbo max",
            "max", "hulu", "twitch", "tiktok", "plex",
        )

        /** "watch tv", "watch the news": not a title, though "on the tv" can be a player. */
        val notTitles = setOf("tv", "television", "the tv", "the news", "news", "videos", "a video")
        val onOtherApp = Regex("^.+ (?:on|in|with|using|from|via) (?:${otherApps.joinToString("|") { Regex.escape(it) }})$")

        val playVerbs = setOf("play", "put on", "start", "start playing", "stream", "shuffle", "continue", "resume", "play me")
        val resumeVerbs = setOf("continue watching", "resume watching", "keep watching", "pick up where i left off")
        val playLead = Regex(
            "^(play me|play|put on|start playing|start watching|start|stream|shuffle|watch|i want to watch|i wanna watch|let me watch|" +
                "let's watch|lets watch|can we watch|we want to watch|continue watching|resume watching|keep watching|continue|resume|" +
                "pick up where i left off)(?: (.+))?$",
        )
        val playerSplit = Regex("^(.+) (?:on|in) (.+)$")

        val nextEpisode = Regex("^(?:play |watch |skip to |go to |start )?(?:the )?next episode$|^skip (?:this |the )?episode$")
        val previousEpisode = Regex("^(?:play |watch |go back to |go to )?(?:the )?(?:previous|last) episode$")
        val fromStartSuffix = Regex(" (?:from|at) (?:the )?(?:very )?(?:beginning|start|top)$| from scratch$")
        val nextOf = Regex("^(?:the )?next episode(?: of (.+))?$")
        val latestOf = Regex("^(?:the )?(?:latest|newest|new|most recent|last) episode of (.+)$")
        val randomEpisodeOf = Regex("^(?:a )?random episode of (.+)$")
        val randomKind = Regex("^(a |an |some )?(random )?(?:${Spoken.GENRE} )?(movie|movies|film|films|show|tv show|series|something)$")
        val randomGenre = Regex("^(?:a |an |some )(?:random )?${Spoken.GENRE}$")

        private const val N = Spoken.NUMBER
        val seasonEpisodeOf = Regex("^(?:season|series) $N,? episode $N (?:of|from) (.+)$")
        val episodeOfSeasonOf = Regex("^episode $N of (?:season|series) $N (?:of|from) (.+)$")
        val showSeasonEpisode = Regex("^(.+?),? (?:season|series) $N,? episode $N$")
        val showCode = Regex("^(.+?) s(\\d{1,2}) ?e(\\d{1,3})$")
        val seasonOf = Regex("^(?:season|series) $N (?:of|from) (.+)$")
        val showSeason = Regex("^(.+?),? (?:season|series) $N$")
        val episodeOf = Regex("^episode $N (?:of|from) (.+)$")
        val showEpisode = Regex("^(.+?),? episode $N$")

        val typed = Regex("^(?:the )?(movie|film|show|tv show|series|tv series|album|artist|band|song|track|playlist) (?:called |named )?(.+)$")
        val typedAfter = Regex("^(?:the )?(.+?) (movie|film|series|tv show)$")

        const val VIDEO = "(?:the |this |my )?(?:movie|film|show|episode|video|series|tv show)"
        val videoTransport = Regex("^(pause|stop|end|resume|unpause|continue|play|keep playing) $VIDEO(?: (?:on|in) (.+))?$")
        val bareTransport = Regex("^(pause|stop|resume|unpause|continue|next|skip|previous|back)(?: it| playback| playing)?(?: (?:on|in) (.+))?$")
        val restart = Regex("^(?:(?:start|play|watch) (?:it|$VIDEO) (?:over|again|from the (?:beginning|start|top))|restart $VIDEO|start over|start from the beginning)$")

        val jumpTo = Regex("^(?:go|jump|skip|seek|fast forward|move|take me) to (?:the )?(.+?)(?: mark| minute mark| in)?$")
        val forward = Regex("^(?:(?:skip|jump|go|move) (?:ahead|forward|forwards)|fast forward|fast-forward)(?: by)?(?: (.+?))?$")
        val skipBy = Regex("^skip (.+?)(?: ahead| forward)?$")
        val back = Regex("^(rewind|go back|skip back|jump back|back up|move back|skip backwards?|jump backwards?|go backwards?)(?: by)?(?: (.+?))?$")

        val onWords = setOf("on", "enable", "show", "turn on", "switch on", "put on")
        val onOffWords = onWords + setOf("off", "disable", "hide", "remove", "turn off", "switch off", "no", "stop")
        val subtitlesOnOff = Regex(
            "^(?:(turn on|turn off|switch on|switch off|put on|enable|disable|show|hide|remove|no|stop) (?:the )?(?:subtitles|subs|captions|closed captions|cc)|" +
                "(?:turn|switch|put) (?:the )?(?:subtitles|subs|captions) (on|off)|(?:subtitles|subs|captions) (on|off))$",
        )
        val subtitlesIn = Regex("^(?:(?:switch|change|set|put|turn) )?(?:the )?(?:subtitles|subs|captions) (?:to|in|into) ${Spoken.LANGUAGE}$")
        val languageSubtitles = Regex("^(?:(?:turn on|switch on|put on|show|enable|use|give me|switch to|change to) )?(?:the )?${Spoken.LANGUAGE} (?:subtitles|subs|captions)(?: on)?$")
        val audioIn = Regex("^(?:(?:switch|change|set|put|turn) )?(?:the )?(?:audio|language|audio track|audio language|dub|dubbing|sound|voices) (?:to|in|into) ${Spoken.LANGUAGE}$")
        val watchIn = Regex("^(?:play|watch) (?:it|this|$VIDEO) in ${Spoken.LANGUAGE}$")
        val languageAudio = Regex("^(?:(?:switch to|change to|use) )?(?:the )?${Spoken.LANGUAGE} (?:audio|dub|dubbing|audio track|version)$")

        val volumeTo = Regex("^(?:set |turn |change |put )?(?:the )?volume (?:to |at )?(\\d{1,3})(?: ?%| percent)?$")
        val upWords = setOf("volume up", "turn up the volume", "turn the volume up", "turn it up", "louder", "make it louder", "raise the volume")
        val downWords = setOf("volume down", "turn down the volume", "turn the volume down", "turn it down", "quieter", "softer", "make it quieter", "lower the volume")
        val videoMute = Regex("^(mute|unmute) $VIDEO$")

        val nowPlaying = Regex(
            "^(?:what am i watching|what are we watching|which episode is this|what episode am i on|what episode are we on|" +
                "what season is this|what(?:'s| is) this (?:movie|film|show|episode)|what (?:movie|film|show|episode) is this|" +
                "what(?:'s| is) playing on (?:the )?tv)$",
        )
        val explicitNowPlaying = setOf("what's playing", "what is playing", "what's on", "what is on", "now playing", "what's playing now")
        val timeLeft = listOf(
            Regex("^how (?:much|long)(?: time)?(?: is)? left(?: (?:in|on|of) $VIDEO)?$"),
            Regex("^how much longer(?: (?:is|does) $VIDEO(?: have| last| go on)?)?$"),
            Regex("^when (?:does|will) $VIDEO (?:end|finish|be over)$"),
            Regex("^how long until $VIDEO (?:ends|is over|finishes)$"),
            Regex("^time left(?: (?:in|on) $VIDEO)?$"),
        )
        val newToWatch = Regex(
            "^(?:what(?:'s| is) new to watch|(?:are there |any |what are the |show me the )?(?:new|latest|recently added) (?:movies|shows|episodes|films|tv shows)(?: (?:are there|added))?)$",
        )
        val explicitNew = setOf("what's new", "what is new", "anything new", "what's been added", "what was added", "what's new there")
        val nextUp = Regex(
            "^(?:what should (?:i|we) watch(?: next| tonight| now)?|what(?:'s| is) next up(?: for me)?|what (?:was|were) (?:i|we) watching|" +
                "what (?:can|should) (?:i|we) (?:continue|keep) watching|what(?:'s| is) on my (?:watch ?list|continue watching))$",
        )
        val haveIt = Regex("^(do (?:i|we) have|is there|have (?:i|we) got) (?:the (?:movie|film|show|series) )?(.+)$")
        val haveTyped = Regex("^(?:do (?:i|we) have|is there|have (?:i|we) got) the (?:movie|film|show|series) .+$")
        val isIt = Regex("^is (.+)$")

        val markAs = Regex("^mark (.+?) as (watched|seen|played|unwatched|unseen|unplayed|not watched|not seen)$")
        val watchedWords = setOf("watched", "seen", "played")
        val currentWords = setOf("this", "it", "this episode", "this movie", "the movie", "the episode", "the show", "this film", "the film")
    }
}
