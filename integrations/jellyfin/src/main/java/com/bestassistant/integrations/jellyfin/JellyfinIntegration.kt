package com.bestassistant.integrations.jellyfin

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.bestassistant.integration.Card
import com.bestassistant.integration.FieldKind
import com.bestassistant.integration.Integration
import com.bestassistant.integration.IntegrationHost
import com.bestassistant.integration.Report
import com.bestassistant.integration.Request
import com.bestassistant.integration.RequestParser
import com.bestassistant.integration.Setting
import com.bestassistant.integration.Step
import com.bestassistant.integration.Tone
import com.bestassistant.integrations.jellyfin.Jellyfin.Item
import com.bestassistant.integrations.jellyfin.Jellyfin.Player
import com.bestassistant.integrations.jellyfin.Jellyfin.Problem
import com.bestassistant.integrations.jellyfin.Jellyfin.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The user's own Jellyfin server, signed in to as them: movies, shows, episodes and music played
 * by name on the players they have open (the TV app, a browser, Kodi) or on this phone, and what
 * plays controlled by voice: paused, skipped through, subtitled, dubbed, turned up, asked about.
 */
class JellyfinIntegration(private val host: IntegrationHost) : Integration {

    override val id: String = ID

    private val store = host.store(ID)

    // The token is kept apart from the settings page's own values: a new one mustn't clear what a check just reported.
    private val kept = host.store(SESSION_ID)

    private val session = object : Jellyfin.Session {
        override fun read(): Jellyfin.Signed? {
            val token = kept.get(KEY_TOKEN, secret = true).ifEmpty { return null }
            return Jellyfin.Signed(kept.get(KEY_SIGNED_ADDRESS), kept.get(KEY_SIGNED_USER), kept.get(KEY_USER_ID), token)
        }

        override fun save(signed: Jellyfin.Signed?) {
            kept.set(KEY_SIGNED_ADDRESS, signed?.address.orEmpty())
            kept.set(KEY_SIGNED_USER, signed?.user.orEmpty())
            kept.set(KEY_USER_ID, signed?.userId.orEmpty())
            kept.set(KEY_TOKEN, signed?.token.orEmpty(), secret = true)
        }

        override val deviceId: String
            get() = kept.get(KEY_DEVICE_ID).ifEmpty { UUID.randomUUID().toString().also { kept.set(KEY_DEVICE_ID, it) } }
    }

    private val jellyfin = Jellyfin(
        login = { Jellyfin.Login(store.get(KEY_URL), store.get(KEY_USER), store.get(KEY_PASSWORD, secret = true)) },
        session = session,
        device = "PocketSearch on ${Build.MODEL}",
    )

    private val library = Library()
    private val titlesFile = File(host.context.noBackupFilesDir, TITLES_FILE)
    private val refreshing = Mutex()

    init {
        readTitles()
        host.scope.launch { refreshTitles(force = false) }
    }

    private fun s(id: Int, vararg args: Any): String = host.strings.getString(id, *args)

    override val name: String get() = s(R.string.jf_name)
    override val description: String get() = s(R.string.jf_description)
    override val icon: Int = R.drawable.ic_integration_jellyfin

    override val summary: Flow<String> = store.changes.map {
        if (jellyfin.isSetUp) s(R.string.jf_summary_set_up, store.get(KEY_USER)) else s(R.string.jf_summary_not_set_up)
    }

    override val parser: RequestParser = JellyfinRequests({ jellyfin.isSetUp }, library)

    override fun plan(request: Request): Step {
        val asked = request as? JellyfinRequest ?: return Step.Failed(s(R.string.jf_error_not_understood))
        if (!jellyfin.isSetUp) return Step.Failed(s(R.string.jf_error_not_set_up))
        host.scope.launch { refreshTitles(force = false) }
        return Step.Remote {
            when (asked) {
                is PlayRequest -> play(asked)
                is ControlRequest -> control(asked)
                is SeekRequest -> seek(asked)
                is VolumeRequest -> volume(asked)
                is SubtitleRequest -> subtitles(asked)
                is AudioRequest -> audio(asked)
                is QuestionRequest -> answer(asked)
                is HaveRequest -> have(asked)
                is MarkRequest -> mark(asked)
            }
        }
    }

    override fun card(request: Request): Card? = (request as? JellyfinRequest)?.let {
        Card(it.said.replaceFirstChar(Char::titlecase), s(if (jellyfin.isSetUp) R.string.jf_name else R.string.jf_card_not_set_up))
    }

    // region Settings

    /** The server's address, the user to sign in as, which player to use when none is said, and a check that it all works. */
    override fun settings(): List<Setting> = listOf(
        Setting.Note(s(R.string.jf_intro)),
        Setting.Field(KEY_URL, s(R.string.jf_url), hint = s(R.string.jf_url_hint), kind = FieldKind.ADDRESS),
        Setting.Field(KEY_USER, s(R.string.jf_user)),
        Setting.Field(KEY_PASSWORD, s(R.string.jf_password), kind = FieldKind.SECRET),
        Setting.Check(
            label = s(R.string.jf_test),
            ready = { Jellyfin.baseUrl(store.get(KEY_URL)) != null && store.get(KEY_USER).isNotBlank() },
            running = s(R.string.jf_status_checking),
            run = ::check,
        ),
        Setting.Status(store.changes.map { if (jellyfin.isSetUp) null else Report(s(R.string.jf_status_not_set)) }),
        Setting.Section(s(R.string.jf_players)),
        Setting.Note(s(R.string.jf_players_note)),
        Setting.Field(KEY_DEFAULT_PLAYER, s(R.string.jf_default_player), hint = s(R.string.jf_default_player_hint)),
        Setting.Note(s(R.string.jf_phone_note), small = true),
        Setting.Section(s(R.string.jf_examples)),
        Setting.Note(s(R.string.jf_examples_text)),
    )

    /** Signs in afresh, and says which players are open and how much of the library PocketSearch knows by name. */
    private suspend fun check(): Report {
        when (val signed = jellyfin.signIn()) {
            is Result.Failed -> return Report(problem(signed.why), Tone.BAD)
            is Result.Ok -> Unit
        }
        val titles = refreshTitles(force = true) ?: library.size
        val players = (jellyfin.players() as? Result.Ok)?.value.orEmpty()
        val lines = listOf(
            s(R.string.jf_status_ok, store.get(KEY_USER).trim()),
            host.strings.resources.getQuantityString(R.plurals.jf_status_titles, titles, titles),
            if (players.isEmpty()) s(R.string.jf_status_no_players) else s(R.string.jf_status_players, players.joinToString(", ") { playerName(it) }),
        )
        return Report(lines.joinToString("\n"), Tone.GOOD)
    }

    // endregion

    // region Titles known by name

    private fun readTitles() {
        val lines = runCatching { titlesFile.readLines() }.getOrNull() ?: return
        library.set(lines.drop(1))
    }

    /** Reads every movie and show's name again when the list is old or from another server; how many there are, or null when it wasn't read. */
    private suspend fun refreshTitles(force: Boolean): Int? {
        if (!jellyfin.isSetUp) return null
        return refreshing.withLock {
            val address = Jellyfin.baseUrl(store.get(KEY_URL)).orEmpty()
            val header = runCatching { titlesFile.bufferedReader().use { it.readLine() } }.getOrNull()?.split('\t')
            val fresh = header?.getOrNull(1) == address &&
                (header.getOrNull(0)?.toLongOrNull() ?: 0) > System.currentTimeMillis() - TITLES_MAX_AGE_MS
            if (fresh && !force) return@withLock null
            val items = (jellyfin.titles() as? Result.Ok)?.value ?: return@withLock null
            val names = items.flatMap { listOfNotNull(it.name, it.originalTitle) }.filter { it.isNotBlank() && '\n' !in it }.distinct()
            library.set(names)
            runCatching { titlesFile.writeText((listOf("${System.currentTimeMillis()}\t$address") + names).joinToString("\n")) }
            items.size
        }
    }

    // endregion

    // region Playing

    /** What to play, found in the library: [ids] in order from [first], started [start] ticks in. */
    private data class Pick(val first: Item, val ids: List<String>, val start: Long = 0, val shuffle: Boolean = false)

    private sealed interface Found {
        data class One(val pick: Pick) : Found

        /** More than one has that name ("Dune": 1984 or 2021): the user says which. */
        data class Several(val items: List<Item>, val expand: suspend (Item) -> Found) : Found

        data class Not(val step: Step) : Found
    }

    private suspend fun play(request: PlayRequest): Step {
        val players = when (val result = jellyfin.players()) {
            is Result.Ok -> result.value
            is Result.Failed -> return failed(result.why)
        }
        var wanted = request.what
        var where: Target? = null
        request.player?.let { said ->
            val named = target(said, players)
            if (named is Target.Unknown) {
                // "murder on the orient express": a title, not a player called "the orient express".
                wanted = request.unsplit ?: return Step.Failed(s(R.string.jf_no_such_player, said, playerList(players)))
            } else {
                where = named
            }
        }
        val playing = playing(players)
        return when (val found = find(wanted, playing)) {
            is Found.Not -> found.step
            is Found.One -> deliver(found.pick, where ?: defaultTarget(players, playing), players, answering = false)
            is Found.Several -> Step.Ask(s(R.string.jf_which_title, orList(found.items.map(::titleWithYear)))) { answer ->
                if (host.isRequest(answer)) return@Ask null
                val item = chooseItem(found.items, answer) ?: return@Ask Step.Failed(s(R.string.jf_not_one_of_them))
                when (val chosen = found.expand(item)) {
                    is Found.One -> deliver(chosen.pick, where ?: defaultTarget(players, playing), players, answering = true)
                    is Found.Not -> chosen.step
                    is Found.Several -> Step.Failed(s(R.string.jf_not_one_of_them))
                }
            }
        }
    }

    /** Starts [pick] where it goes: a player, this phone, or (when several are open) where the user says. */
    private suspend fun deliver(pick: Pick, target: Target, players: List<Player>, answering: Boolean): Step {
        val title = label(pick.first)
        return when (target) {
            is Target.On -> start(pick, target.player)
            Target.Phone -> onPhone(pick, answering)
            Target.None -> Step.Failed(s(R.string.jf_no_players, title))
            is Target.Unknown -> Step.Failed(s(R.string.jf_no_such_player, target.said, playerList(players)))
            is Target.Choose -> Step.Ask(s(R.string.jf_which_player, title, orList(target.players.map(::playerName)))) { answer ->
                if (host.isRequest(answer)) return@Ask null
                when (val chosen = target(answer, target.players)) {
                    is Target.On -> start(pick, chosen.player)
                    Target.Phone -> onPhone(pick, answering = true)
                    else -> Step.Failed(s(R.string.jf_no_such_player, answer, playerList(target.players)))
                }
            }
        }
    }

    private suspend fun start(pick: Pick, player: Player): Step {
        val ids = pick.ids.take(MAX_QUEUE)
        return when (val result = jellyfin.play(player, ids, pick.start, pick.shuffle)) {
            is Result.Failed -> failed(result.why)
            is Result.Ok -> {
                val title = label(pick.first)
                val where = playerName(player)
                val said = when {
                    pick.shuffle -> s(R.string.jf_shuffling, title, where)
                    pick.start > 0 -> s(R.string.jf_resuming, title, where, duration(pick.start))
                    else -> s(R.string.jf_playing, title, where)
                }
                Step.Done(said, spoken = said)
            }
        }
    }

    /** In a video player on this phone, streamed straight from the server. */
    private suspend fun onPhone(pick: Pick, answering: Boolean): Step {
        val item = pick.first
        if (item.isFolder) return Step.Failed(s(R.string.jf_phone_one_only))
        val url = jellyfin.streamUrl(item) ?: return failed(Problem.UNAUTHORIZED)
        val type = if (item.type == "Audio") "audio/*" else "video/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), type).putExtra("title", label(item))
        val said = s(R.string.jf_playing_phone, label(item))
        if (!answering) return Step.Launch(listOf(intent), confirmation = said, spoken = said)
        // An answer to a question can only finish with words: the player is opened from here.
        return try {
            host.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Step.Done(said, spoken = said)
        } catch (e: ActivityNotFoundException) {
            Step.Failed(s(R.string.jf_no_video_app))
        }
    }

    /** Finds what [wanted] names; [playing] is the player playing something, for "the next episode". */
    private suspend fun find(wanted: Wanted, playing: Player?): Found = when (wanted) {
        is Wanted.Title -> title(wanted)
        is Wanted.Episode -> series(wanted.show) { show -> episodeOf(show, wanted.season, wanted.episode) }
        is Wanted.NextEpisode -> when {
            wanted.show != null -> series(wanted.show) { show -> nextOf(show, fromStart = false) }
            playing?.playing?.seriesId != null -> step(playing.playing, +1)
            else -> resume(nextUpFirst = true)
        }
        Wanted.PreviousEpisode -> playing?.playing?.takeIf { it.seriesId != null }?.let { step(it, -1) }
            ?: Found.Not(Step.Failed(s(R.string.jf_nothing_playing)))
        is Wanted.LatestEpisode -> series(wanted.show) { show ->
            val episodes = episodes(show.id) ?: return@series Found.Not(failed(lastProblem ?: Problem.UNREACHABLE))
            val last = episodes.lastOrNull { (it.season ?: 0) > 0 } ?: return@series Found.Not(Step.Failed(s(R.string.jf_no_episodes, show.name)))
            Found.One(queue(last, episodes, fromStart = false))
        }
        is Wanted.Random -> random(wanted)
        Wanted.Resume -> resume(nextUpFirst = false)
    }

    // A failure found on the way, carried out of the lambdas below.
    private var lastProblem: Problem? = null

    private suspend fun title(wanted: Wanted.Title): Found {
        val types = when (wanted.type) {
            TitleType.MOVIE -> listOf("Movie")
            TitleType.SHOW -> listOf("Series")
            TitleType.MUSIC -> MUSIC_TYPES
            TitleType.ANY -> VIDEO_TYPES + MUSIC_TYPES
        }
        var items = when (val result = jellyfin.search(wanted.name, types)) {
            is Result.Ok -> result.value
            is Result.Failed -> return Found.Not(failed(result.why))
        }
        // Said without its punctuation ("spider man no way home"), which the server's search may miss.
        if (items.isEmpty()) library.name(wanted.name)?.let { exact -> items = (jellyfin.search(exact, types) as? Result.Ok)?.value.orEmpty() }
        val best = best(wanted.name, items, types) ?: return Found.Not(Step.Failed(s(R.string.jf_not_found, wanted.name)))
        val expand: suspend (Item) -> Found = { item -> expand(item, wanted.shuffle, wanted.fromStart) }
        return if (best.size > 1) Found.Several(best, expand) else expand(best.single())
    }

    /** What playing [item] means: a show from where the user is in it, a movie from where they stopped. */
    private suspend fun expand(item: Item, shuffle: Boolean, fromStart: Boolean): Found {
        if (shuffle) return Found.One(Pick(item, listOf(item.id), shuffle = true))
        return when (item.type) {
            "Series" -> nextOf(item, fromStart)
            "Episode" -> {
                val episodes = item.seriesId?.let { episodes(it) }
                Found.One(if (episodes != null) queue(item, episodes, fromStart) else Pick(item, listOf(item.id), resumeAt(item, fromStart)))
            }
            "Movie", "Video", "MusicVideo" -> Found.One(Pick(item, listOf(item.id), resumeAt(item, fromStart)))
            else -> Found.One(Pick(item, listOf(item.id)))
        }
    }

    /** The episode the user is up to in [show]; the first when they've seen it all, or [fromStart]. */
    private suspend fun nextOf(show: Item, fromStart: Boolean): Found {
        val episodes = episodes(show.id) ?: return Found.Not(failed(lastProblem ?: Problem.UNREACHABLE))
        val first = episodes.firstOrNull { (it.season ?: 0) > 0 } ?: episodes.firstOrNull()
            ?: return Found.Not(Step.Failed(s(R.string.jf_no_episodes, show.name)))
        if (fromStart) return Found.One(queue(first, episodes, fromStart = true))
        val next = (jellyfin.nextUp(show.id) as? Result.Ok)?.value?.firstOrNull()
            ?.let { up -> episodes.firstOrNull { it.id == up.id } ?: up }
            ?: first
        return Found.One(queue(next, episodes, fromStart = false))
    }

    /** "Season 2 episode 3"; without a season, the one the user is on; without an episode, the season's first unseen. */
    private suspend fun episodeOf(show: Item, season: Int?, number: Int?): Found {
        val episodes = episodes(show.id) ?: return Found.Not(failed(lastProblem ?: Problem.UNREACHABLE))
        val inSeason = season ?: (jellyfin.nextUp(show.id) as? Result.Ok)?.value?.firstOrNull()?.season ?: 1
        val ofSeason = episodes.filter { it.season == inSeason }
        if (ofSeason.isEmpty()) return Found.Not(Step.Failed(s(R.string.jf_no_season, show.name, inSeason)))
        val episode = when (number) {
            null -> ofSeason.firstOrNull { !it.played } ?: ofSeason.first()
            -1 -> ofSeason.last()
            else -> ofSeason.firstOrNull { it.episode == number }
                ?: return Found.Not(Step.Failed(s(R.string.jf_no_episode, show.name, inSeason, number)))
        }
        return Found.One(queue(episode, episodes, fromStart = false))
    }

    /** The episode [by] before or after [current], from the same show. */
    private suspend fun step(current: Item, by: Int): Found {
        val episodes = current.seriesId?.let { episodes(it) } ?: return Found.Not(failed(lastProblem ?: Problem.UNREACHABLE))
        val at = episodes.indexOfFirst { it.id == current.id }
        val episode = episodes.getOrNull(at + by)?.takeIf { at >= 0 }
            ?: return Found.Not(Step.Failed(s(if (by > 0) R.string.jf_last_episode else R.string.jf_first_episode)))
        return Found.One(queue(episode, episodes, fromStart = true))
    }

    private suspend fun random(wanted: Wanted.Random): Found {
        wanted.show?.let { name ->
            return series(name) { show ->
                val episodes = episodes(show.id)?.filter { (it.season ?: 0) > 0 }.orEmpty()
                val episode = episodes.randomOrNull() ?: return@series Found.Not(Step.Failed(s(R.string.jf_no_episodes, show.name)))
                Found.One(Pick(episode, listOf(episode.id), resumeAt(episode, fromStart = false)))
            }
        }
        val type = when (wanted.type) {
            TitleType.SHOW -> "Series"
            TitleType.ANY -> "Movie,Series"
            else -> "Movie"
        }
        // Something not seen yet if there is one.
        val picked = (jellyfin.random(type, wanted.genre, unwatched = true) as? Result.Ok)?.value?.firstOrNull()
            ?: when (val any = jellyfin.random(type, wanted.genre, unwatched = false)) {
                is Result.Ok -> any.value.firstOrNull()
                is Result.Failed -> return Found.Not(failed(any.why))
            }
            ?: return Found.Not(Step.Failed(wanted.genre?.let { s(R.string.jf_no_genre, it.lowercase()) } ?: s(R.string.jf_library_empty)))
        return expand(picked, shuffle = false, fromStart = false)
    }

    /** What the user stopped partway through last; [nextUpFirst]: rather the next episode of what they watch. */
    private suspend fun resume(nextUpFirst: Boolean): Found {
        val partway = suspend { (jellyfin.resumable(1) as? Result.Ok)?.value?.firstOrNull() }
        val nextUp = suspend { (jellyfin.nextUp() as? Result.Ok)?.value?.firstOrNull() }
        val item = (if (nextUpFirst) nextUp() ?: partway() else partway() ?: nextUp())
            ?: return Found.Not(Step.Failed(s(R.string.jf_nothing_to_resume)))
        return expand(item, shuffle = false, fromStart = false)
    }

    /** The show called [name], handed to [then]. */
    private suspend fun series(name: String, then: suspend (Item) -> Found): Found {
        val shows = when (val result = jellyfin.search(name, listOf("Series"))) {
            is Result.Ok -> result.value
            is Result.Failed -> return Found.Not(failed(result.why))
        }
        val best = best(name, shows, listOf("Series")) ?: return Found.Not(Step.Failed(s(R.string.jf_no_show, name)))
        return if (best.size > 1) Found.Several(best, then) else then(best.single())
    }

    private suspend fun episodes(seriesId: String): List<Item>? = when (val result = jellyfin.episodes(seriesId)) {
        is Result.Ok -> result.value
        is Result.Failed -> {
            lastProblem = result.why
            null
        }
    }

    /** [episode] first, then the ones after it, so the player goes on to the next by itself. */
    private fun queue(episode: Item, episodes: List<Item>, fromStart: Boolean): Pick {
        val at = episodes.indexOfFirst { it.id == episode.id }
        val ids = if (at < 0) listOf(episode.id) else episodes.drop(at).map { it.id }
        return Pick(episode, ids, resumeAt(episode, fromStart))
    }

    private fun resumeAt(item: Item, fromStart: Boolean): Long = if (fromStart || item.played) 0 else item.position

    /**
     * The best matches for [said] among [items]: the closest names, video before music. More than
     * one only when they're equally close and of the same kind ("Dune" 1984 and 2021).
     */
    private fun best(said: String, items: List<Item>, types: List<String>): List<Item>? {
        if (items.isEmpty()) return null
        val scored = items.map { it to Titles.score(said, it.name, it.originalTitle) }
        val top = scored.maxOf { it.second }
        // Jellyfin found it by something other than its name: trust its order.
        if (top == 0) return listOf(items.first())
        val closest = scored.filter { it.second == top }.map { it.first }
            .sortedBy { types.indexOf(it.type).let { i -> if (i < 0) types.size else i } }
        val kind = closest.first().type
        val same = closest.filter { it.type == kind }
        return if (top == EXACT && same.size > 1) same.take(MAX_CHOICES) else listOf(closest.first())
    }

    /** The answer to "Which one? Dune (1984) or Dune (2021)": a year, "the first", "the new one". */
    private fun chooseItem(items: List<Item>, answer: String): Item? {
        val said = host.normalize(answer).removePrefix("the ").removeSuffix(" one")
        Regex("\\b(\\d{4})\\b").find(said)?.let { m -> items.firstOrNull { it.year == m.groupValues[1].toInt() }?.let { return it } }
        when (said) {
            "new", "newer", "newest", "latest", "recent", "most recent", "new version" -> return items.maxByOrNull { it.year ?: 0 }
            "old", "older", "oldest", "original", "classic", "old version" -> return items.minByOrNull { it.year ?: Int.MAX_VALUE }
        }
        Spoken.number(said)?.let { n -> return if (n == -1) items.last() else items.getOrNull(n - 1) }
        return items.maxByOrNull { Titles.score(said, it.name, it.originalTitle) }?.takeIf { Titles.score(said, it.name, it.originalTitle) > 0 }
    }

    // endregion

    // region Players

    private sealed interface Target {
        data class On(val player: Player) : Target
        data object Phone : Target
        data class Unknown(val said: String) : Target
        data class Choose(val players: List<Player>) : Target
        data object None : Target
    }

    /** The player [said] names among [players]: by its name or its app ("the tv"), or this phone. */
    private fun target(said: String, players: List<Player>): Target {
        val name = host.normalize(said).removePrefix("the ").removePrefix("my ").trim()
        if (name in PHONE_WORDS) {
            // Jellyfin's own app open on this phone is better than a bare video player.
            val own = setOfNotNull(Build.MODEL, Settings.Global.getString(host.context.contentResolver, "device_name")).map(Titles::key)
            return players.firstOrNull { Titles.key(it.name) in own }?.let { Target.On(it) } ?: Target.Phone
        }
        Spoken.number(name)?.let { n -> players.getOrNull(if (n == -1) players.lastIndex else n - 1)?.let { return Target.On(it) } }
        val scored = players.map { it to maxOf(Titles.score(name, it.name), Titles.score(name, it.client)) }
        val top = scored.maxOfOrNull { it.second } ?: 0
        return if (top == 0) Target.Unknown(said) else Target.On(scored.first { it.second == top }.first)
    }

    /** Where to play when no player was said: the usual one, the only one, the one already playing, or the user's choice. */
    private fun defaultTarget(players: List<Player>, playing: Player?): Target {
        if (players.isEmpty()) return Target.None
        usual(players)?.let { return Target.On(it) }
        if (players.size == 1) return Target.On(players.single())
        playing?.let { return Target.On(it) }
        return Target.Choose(players.take(MAX_CHOICES))
    }

    /** The player set in settings, when it's open. */
    private fun usual(players: List<Player>): Player? {
        val name = store.get(KEY_DEFAULT_PLAYER).trim().ifEmpty { return null }
        return (target(name, players) as? Target.On)?.player
    }

    /** The player something is playing on: the usual one if several are, else the most recently used. */
    private fun playing(players: List<Player>): Player? {
        val busy = players.filter { it.playing != null }
        if (busy.size <= 1) return busy.firstOrNull()
        return usual(busy) ?: busy.first()
    }

    /** The player a command is for: the one named, else the one playing. */
    private suspend fun controlled(named: String?): Pair<Player?, Step?> {
        val players = when (val result = jellyfin.players()) {
            is Result.Ok -> result.value
            is Result.Failed -> return null to failed(result.why)
        }
        if (named != null) {
            return when (val target = target(named, players)) {
                is Target.On -> target.player to null
                else -> null to Step.Failed(s(R.string.jf_no_such_player, named, playerList(players)))
            }
        }
        return (playing(players) ?: return null to Step.Failed(s(R.string.jf_nothing_playing))) to null
    }

    private fun playerName(player: Player): String = player.name.ifEmpty { player.client }

    private fun playerList(players: List<Player>): String =
        if (players.isEmpty()) s(R.string.jf_no_players_open) else s(R.string.jf_players_open, orList(players.map(::playerName)))

    // endregion

    // region Controlling what plays

    private suspend fun control(request: ControlRequest): Step {
        val (player, problem) = controlled(request.player)
        if (player == null) return problem!!
        val (command, done) = when (request.action) {
            Transport.PAUSE -> "Pause" to R.string.jf_paused
            Transport.RESUME -> "Unpause" to R.string.jf_resumed
            Transport.STOP -> "Stop" to R.string.jf_stopped
            Transport.NEXT -> "NextTrack" to R.string.jf_skipped
            Transport.PREVIOUS -> "PreviousTrack" to R.string.jf_went_back
        }
        return when (val result = jellyfin.playstate(player, command)) {
            is Result.Ok -> s(done).let { Step.Done(it, spoken = it) }
            is Result.Failed -> failed(result.why)
        }
    }

    private suspend fun seek(request: SeekRequest): Step {
        val (player, problem) = controlled(null)
        if (player == null) return problem!!
        val delta = request.seconds * Jellyfin.TICKS_PER_SECOND
        val runtime = player.playing?.runtime
        var to = if (request.relative) player.position + delta else delta
        to = to.coerceAtLeast(0)
        if (runtime != null) to = to.coerceAtMost(runtime - Jellyfin.TICKS_PER_SECOND)
        return when (val result = jellyfin.playstate(player, "Seek", to)) {
            is Result.Failed -> failed(result.why)
            is Result.Ok -> {
                val amount = duration(kotlin.math.abs(delta))
                val said = when {
                    !request.relative && request.seconds == 0 -> s(R.string.jf_from_start)
                    !request.relative -> s(R.string.jf_jumped, duration(to))
                    request.seconds > 0 -> s(R.string.jf_forward, amount)
                    else -> s(R.string.jf_back, amount)
                }
                Step.Done(said, spoken = said)
            }
        }
    }

    private suspend fun volume(request: VolumeRequest): Step {
        val (player, problem) = controlled(null)
        if (player == null) return problem!!
        val (command, arguments, done) = when (val change = request.change) {
            is VolumeChange.To -> Triple("SetVolume", mapOf("Volume" to change.percent.toString()), s(R.string.jf_volume_set, change.percent))
            VolumeChange.Up -> Triple("VolumeUp", emptyMap<String, String>(), s(R.string.jf_volume_up))
            VolumeChange.Down -> Triple("VolumeDown", emptyMap<String, String>(), s(R.string.jf_volume_down))
            VolumeChange.Mute -> Triple("Mute", emptyMap<String, String>(), s(R.string.jf_muted))
            VolumeChange.Unmute -> Triple("Unmute", emptyMap<String, String>(), s(R.string.jf_unmuted))
        }
        if (player.commands.isNotEmpty() && command !in player.commands) return Step.Failed(s(R.string.jf_cant_volume, playerName(player)))
        return when (val result = jellyfin.command(player, command, arguments)) {
            is Result.Ok -> Step.Done(done, spoken = done)
            is Result.Failed -> failed(result.why)
        }
    }

    private suspend fun subtitles(request: SubtitleRequest): Step {
        val (player, problem) = controlled(null)
        if (player == null) return problem!!
        if (!request.on) return track(player, "SetSubtitleStreamIndex", -1, s(R.string.jf_subtitles_off))
        val item = details(player) ?: return failed(lastProblem ?: Problem.UNREACHABLE)
        val subtitles = item.streams.filter { it.type == "Subtitle" }
        if (subtitles.isEmpty()) return Step.Failed(s(R.string.jf_no_subtitles, label(item)))
        val chosen = if (request.language != null) {
            inLanguage(subtitles, request.language).sortedBy { it.forced }.firstOrNull()
                ?: return Step.Failed(s(R.string.jf_no_subtitles_in, request.language.replaceFirstChar(Char::titlecase), languages(subtitles)))
        } else {
            subtitles.filterNot { it.forced }.let { full -> full.firstOrNull { it.isDefault } ?: inLanguage(full, "english").firstOrNull() ?: full.firstOrNull() }
                ?: subtitles.first()
        }
        return track(player, "SetSubtitleStreamIndex", chosen.index, s(R.string.jf_subtitles_on, streamName(chosen)))
    }

    private suspend fun audio(request: AudioRequest): Step {
        val (player, problem) = controlled(null)
        if (player == null) return problem!!
        val item = details(player) ?: return failed(lastProblem ?: Problem.UNREACHABLE)
        val tracks = item.streams.filter { it.type == "Audio" }
        if (tracks.size < 2) return Step.Failed(s(R.string.jf_one_audio, label(item)))
        val chosen = if (request.language == "original") {
            tracks.first()
        } else {
            inLanguage(tracks, request.language).firstOrNull()
                ?: return Step.Failed(s(R.string.jf_no_audio_in, request.language.replaceFirstChar(Char::titlecase), languages(tracks)))
        }
        return track(player, "SetAudioStreamIndex", chosen.index, s(R.string.jf_audio_set, streamName(chosen)))
    }

    private suspend fun track(player: Player, command: String, index: Int, done: String): Step =
        when (val result = jellyfin.command(player, command, mapOf("Index" to index.toString()))) {
            is Result.Ok -> Step.Done(done, spoken = done)
            is Result.Failed -> failed(result.why)
        }

    /** What [player] is playing, with its tracks. */
    private suspend fun details(player: Player): Item? {
        val playing = player.playing ?: return null
        return when (val result = jellyfin.get(listOf(playing.id))) {
            is Result.Ok -> result.value.firstOrNull() ?: playing
            is Result.Failed -> {
                lastProblem = result.why
                null
            }
        }
    }

    private fun inLanguage(streams: List<Jellyfin.Stream>, language: String): List<Jellyfin.Stream> {
        val codes = Spoken.languages[language].orEmpty()
        return streams.filter { stream ->
            stream.language?.lowercase() in codes || stream.title?.lowercase()?.contains(language) == true
        }
    }

    private fun streamName(stream: Jellyfin.Stream): String =
        stream.language?.lowercase()?.let { code -> Spoken.languages.entries.firstOrNull { code in it.value }?.key?.replaceFirstChar(Char::titlecase) }
            ?: stream.title ?: s(R.string.jf_track_number, stream.index)

    private fun languages(streams: List<Jellyfin.Stream>): String = streams.map(::streamName).distinct().joinToString(", ")

    // endregion

    // region Questions

    private suspend fun answer(request: QuestionRequest): Step {
        val said = when (request.asked) {
            Asked.NOW_PLAYING, Asked.TIME_LEFT -> {
                val (player, problem) = controlled(null)
                if (player == null) return problem!!
                val item = player.playing ?: return Step.Failed(s(R.string.jf_nothing_playing))
                val left = item.runtime?.let { it - player.position }?.takeIf { it > 0 }
                if (request.asked == Asked.TIME_LEFT) {
                    left ?: return Step.Failed(s(R.string.jf_no_length, label(item)))
                    val ends = LocalTime.now().plusSeconds(left / Jellyfin.TICKS_PER_SECOND)
                        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(host.strings.resources.configuration.locales[0]))
                    s(R.string.jf_time_left, duration(left), label(item), ends)
                } else {
                    val state = if (player.paused) s(R.string.jf_state_paused) else s(R.string.jf_state_playing)
                    val episodeName = item.name.takeIf { item.type == "Episode" }?.let { s(R.string.jf_episode_name, it) }.orEmpty()
                    val remaining = left?.let { s(R.string.jf_left_short, duration(it)) }.orEmpty()
                    s(R.string.jf_now_playing, state, label(item), episodeName, playerName(player), remaining).replace(Regex("\\s+"), " ").trim()
                }
            }
            Asked.NEW -> {
                val items = when (val result = jellyfin.latest()) {
                    is Result.Ok -> result.value
                    is Result.Failed -> return failed(result.why)
                }
                if (items.isEmpty()) s(R.string.jf_library_empty) else s(R.string.jf_new, andList(items.map(::titleWithYear)))
            }
            Asked.NEXT_UP -> {
                val partway = (jellyfin.resumable(3) as? Result.Ok)?.value.orEmpty()
                val next = when (val result = jellyfin.nextUp(limit = 4)) {
                    is Result.Ok -> result.value
                    is Result.Failed -> return failed(result.why)
                }
                val items = (partway + next).distinctBy { it.id }.take(5)
                if (items.isEmpty()) s(R.string.jf_nothing_next) else s(R.string.jf_next_up, andList(items.map(::label)))
            }
        }
        return Step.Done(said, spoken = said)
    }

    private suspend fun have(request: HaveRequest): Step {
        val items = when (val result = jellyfin.search(request.title, VIDEO_TYPES + MUSIC_TYPES)) {
            is Result.Ok -> result.value
            is Result.Failed -> return failed(result.why)
        }
        val found = items.filter { Titles.score(request.title, it.name, it.originalTitle) >= CLOSE }
        val said = if (found.isEmpty()) s(R.string.jf_dont_have, request.title) else s(R.string.jf_have, andList(found.take(3).map(::titleWithYear)))
        return Step.Done(said, spoken = said)
    }

    private suspend fun mark(request: MarkRequest): Step {
        val item = if (request.title == null) {
            val (player, problem) = controlled(null)
            if (player == null) return problem!!
            player.playing ?: return Step.Failed(s(R.string.jf_nothing_playing))
        } else {
            val items = when (val result = jellyfin.search(request.title, VIDEO_TYPES)) {
                is Result.Ok -> result.value
                is Result.Failed -> return failed(result.why)
            }
            best(request.title, items, VIDEO_TYPES)?.first() ?: return Step.Failed(s(R.string.jf_not_found, request.title))
        }
        return when (val result = jellyfin.setPlayed(item.id, request.watched)) {
            is Result.Ok -> s(if (request.watched) R.string.jf_marked_watched else R.string.jf_marked_unwatched, label(item)).let { Step.Done(it, spoken = it) }
            is Result.Failed -> failed(result.why)
        }
    }

    // endregion

    // region Words

    /** "Inception", "Breaking Bad, season 2 episode 3". */
    private fun label(item: Item): String {
        val series = item.seriesName
        return if (item.type == "Episode" && series != null && item.season != null && item.episode != null) {
            s(R.string.jf_episode_label, series, item.season, item.episode)
        } else {
            item.name
        }
    }

    private fun titleWithYear(item: Item): String = item.year?.takeIf { item.type != "Episode" }?.let { "${label(item)} ($it)" } ?: label(item)

    /** "1 hour 5 minutes", "23 minutes", "40 seconds". */
    private fun duration(ticks: Long): String {
        val seconds = (ticks / Jellyfin.TICKS_PER_SECOND).toInt()
        val res = host.strings.resources
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        val parts = buildList {
            if (hours > 0) add(res.getQuantityString(R.plurals.jf_hours, hours, hours))
            if (minutes > 0) add(res.getQuantityString(R.plurals.jf_minutes, minutes, minutes))
            if (hours == 0 && (rest > 0 || minutes == 0)) add(res.getQuantityString(R.plurals.jf_seconds, rest, rest))
        }
        return parts.joinToString(" ")
    }

    private fun orList(items: List<String>): String = joined(items, R.string.jf_or)

    private fun andList(items: List<String>): String = joined(items, R.string.jf_and)

    private fun joined(items: List<String>, last: Int): String =
        if (items.size <= 1) items.joinToString() else s(last, items.dropLast(1).joinToString(", "), items.last())

    private fun problem(why: Problem): String = s(
        when (why) {
            Problem.NOT_SET_UP -> R.string.jf_error_not_set_up
            Problem.UNAUTHORIZED -> R.string.jf_error_unauthorized
            Problem.UNREACHABLE -> R.string.jf_error_unreachable
            Problem.REFUSED -> R.string.jf_error_refused
        },
    )

    private fun failed(why: Problem): Step.Failed = Step.Failed(problem(why))

    // endregion

    companion object {
        const val ID = "jellyfin"
        private const val SESSION_ID = "jellyfin_session"

        const val KEY_URL = "url"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_DEFAULT_PLAYER = "default_player"

        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SIGNED_ADDRESS = "address"
        private const val KEY_SIGNED_USER = "user"
        private const val KEY_DEVICE_ID = "device_id"

        private const val TITLES_FILE = "jellyfin_titles.txt"
        private const val TITLES_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        /** How many episodes are queued after the one asked for. */
        private const val MAX_QUEUE = 60

        /** How many titles or players a question offers. */
        private const val MAX_CHOICES = 4

        private const val EXACT = 3
        private const val CLOSE = 2

        private val VIDEO_TYPES = listOf("Movie", "Series", "Episode", "BoxSet", "MusicVideo", "Video")
        private val MUSIC_TYPES = listOf("MusicAlbum", "MusicArtist", "Playlist", "Audio")

        private val PHONE_WORDS = setOf("phone", "this phone", "mobile", "here", "this device", "my device", "phone screen", "cell")
    }
}
