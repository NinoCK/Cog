package com.fastsearch.assist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bestassistant.commands.Command
import com.bestassistant.commands.CommandParser
import com.bestassistant.commands.GreekCommandParser
import com.bestassistant.commands.WeatherDay
import com.bestassistant.weather.Forecast
import com.bestassistant.wiki.Article
import com.fastsearch.FastSearchApp
import com.fastsearch.data.AppEntry
import com.fastsearch.data.AppSettings
import com.fastsearch.data.Contact
import com.fastsearch.data.InstantAnswer
import com.fastsearch.data.SearchEngine
import com.fastsearch.search.Calculator
import com.fastsearch.search.FuzzyMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** What pressing the keyboard's action key does, highlighted in the results. */
sealed interface TopHit {
    data class Calculation(val result: Calculator.Result) : TopHit
    data class Url(val url: String) : TopHit
    data class App(val entry: AppEntry) : TopHit
    data class ContactHit(val contact: Contact) : TopHit
    data class Web(val query: String) : TopHit

    /** An assistant command that runs on its own (alarm, timer, torch, music...). */
    data class Run(val command: Command) : TopHit

    /** "call mom" / "text alex": a command resolved to a contact. */
    data class Reach(val command: Command, val contact: Contact) : TopHit

    /** An answer on screen (weather, Wikipedia, world clock, conversion) that Enter reads out. */
    data class Info(val command: Command) : TopHit
}

/** The weather card for a weather command. */
@Immutable
sealed interface WeatherUi {
    val day: WeatherDay

    data class Loading(val place: String?, override val day: WeatherDay) : WeatherUi
    data class Ready(val forecast: Forecast, override val day: WeatherDay) : WeatherUi
    data class NeedsLocation(override val day: WeatherDay) : WeatherUi
    data class NotFound(val place: String, override val day: WeatherDay) : WeatherUi
    data class Unavailable(override val day: WeatherDay) : WeatherUi

    /** Permission is granted but the device has no recent fix (location off, or never located). */
    data class NoFix(override val day: WeatherDay) : WeatherUi
}

/** The Wikipedia card for a "what is ..." question. */
@Immutable
sealed interface WikiUi {
    data class Loading(val topic: String) : WikiUi
    data class Ready(val article: Article) : WikiUi
    data class NotFound(val topic: String) : WikiUi
    data object Unavailable : WikiUi

    val isSettled: Boolean get() = this !is Loading
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val suggestedApps: List<AppEntry> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val calculation: Calculator.Result? = null,
    val url: String? = null,
    val answer: InstantAnswer? = null,
    val webSuggestions: List<String> = emptyList(),
    val engine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val showContactsPrompt: Boolean = false,
    val command: Command? = null,
    /** The contact a call/message command resolved to. */
    val commandContact: Contact? = null,
    val weather: WeatherUi? = null,
    val wiki: WikiUi? = null,
    val showExamples: Boolean = false,
    val topHit: TopHit? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(private val app: FastSearchApp) : ViewModel() {

    private val query = MutableStateFlow("")
    private val contactsPermission = MutableStateFlow(app.contacts.hasPermission())
    private val locationPermission = MutableStateFlow(app.location.hasPermission())
    private val settings = app.preferences.settings

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun refreshPermissions() {
        contactsPermission.value = app.contacts.hasPermission()
        locationPermission.value = app.location.hasPermission()
        if (app.apps.apps.value.isEmpty()) app.apps.refresh()
    }

    fun recordLaunch(entry: AppEntry) {
        app.usage.recordLaunch(entry.key, FuzzyMatcher.normalize(appQuery(query.value)).replace(" ", ""))
    }

    private data class Local(
        val query: String,
        val apps: List<AppEntry> = emptyList(),
        val suggested: List<AppEntry> = emptyList(),
        val bestAppScore: Int = 0,
        val calculation: Calculator.Result? = null,
        val url: String? = null,
        val command: Command? = null,
    )

    private val local = combine(query, app.apps.apps, settings) { q, apps, s -> Triple(q, apps, s) }
        .mapLatest { (q, apps, s) -> computeLocal(q, apps, s) }
        .flowOn(Dispatchers.Default)

    private val contacts = combine(query, contactsPermission, settings.map { it.searchContacts }.distinctUntilChanged()) { q, granted, enabled ->
        Triple(contactQuery(q.trim()), granted, enabled)
    }.transformLatest { (q, granted, enabled) ->
        if (q.isEmpty() || !granted || !enabled) emit(emptyList()) else emit(app.contacts.search(q))
    }

    private val suggestions = combine(query, settings.map { it.webSuggestions }.distinctUntilChanged()) { q, enabled ->
        q.trim() to enabled
    }.transformLatest { (q, enabled) ->
        if (q.isEmpty() || !enabled || Calculator.evaluate(q) != null || CommandParser.parse(q)?.isConfident == true) {
            emit(emptyList())
        } else {
            delay(90) // coalesce fast typing
            emit(app.web.suggestions(q))
        }
    }

    private val answer = combine(query, settings.map { it.instantAnswers }.distinctUntilChanged()) { q, enabled ->
        q.trim() to enabled
    }.transformLatest { (q, enabled) ->
        emit(null)
        if (enabled && q.length >= 3 && Calculator.evaluate(q) == null && CommandParser.parse(q) == null) {
            delay(450) // only ask once the user pauses
            emit(app.web.instantAnswer(q))
        }
    }

    private data class WeatherRequest(val command: Command.Weather?, val enabled: Boolean, val imperial: Boolean, val located: Boolean)

    private val weather = combine(
        query.map { CommandParser.parse(it) as? Command.Weather }.distinctUntilChanged(),
        settings.map { it.weather to it.units.isImperial() }.distinctUntilChanged(),
        locationPermission,
    ) { command, (enabled, imperial), located -> WeatherRequest(command, enabled, imperial, located) }
        .transformLatest { request ->
            val command = request.command
            if (command == null || !request.enabled) {
                emit(null)
                return@transformLatest
            }
            val day = command.day
            val place = command.place
            if (place == null) {
                if (!request.located) {
                    emit(WeatherUi.NeedsLocation(day))
                    return@transformLatest
                }
                emit(WeatherUi.Loading(null, day))
                val position = app.location.current()
                if (position == null) {
                    emit(WeatherUi.NoFix(day))
                    return@transformLatest
                }
                val forecast = app.weather.forecast(position.latitude, position.longitude, null, request.imperial)
                emit(forecast?.let { WeatherUi.Ready(it, day) } ?: WeatherUi.Unavailable(day))
            } else {
                emit(WeatherUi.Loading(place, day))
                delay(350) // don't geocode every keystroke of "weather in lon..."
                val found = app.weather.geocode(place)
                val geo = found.getOrNull()
                when {
                    found.isFailure -> emit(WeatherUi.Unavailable(day))
                    geo == null -> emit(WeatherUi.NotFound(place, day))
                    else -> {
                        val forecast = app.weather.forecast(geo.latitude, geo.longitude, geo.name, request.imperial)
                        emit(forecast?.let { WeatherUi.Ready(it, day) } ?: WeatherUi.Unavailable(day))
                    }
                }
            }
        }

    private val wiki = combine(
        query.map { CommandParser.parse(it) as? Command.Lookup }.distinctUntilChanged(),
        settings.map { it.wikipedia }.distinctUntilChanged(),
    ) { command, enabled -> command.takeIf { enabled } }
        .transformLatest { command ->
            if (command == null) {
                emit(null)
                return@transformLatest
            }
            emit(WikiUi.Loading(command.topic))
            delay(400) // wait for the user to finish the question
            val result = app.wikipedia.lookup(command.topic)
            val article = result.getOrNull()
            emit(
                when {
                    result.isFailure -> WikiUi.Unavailable
                    article == null -> WikiUi.NotFound(command.topic)
                    else -> WikiUi.Ready(article)
                },
            )
        }

    val uiState: StateFlow<SearchUiState> = combine(
        local,
        contacts,
        suggestions,
        answer,
        combine(settings, weather, wiki) { s, w, k -> Extras(s, w, k) },
    ) { l, c, s, a, extras ->
        val st = extras.settings
        val normalizedQuery = l.query.lowercase()
        // Autocomplete for a maths expression is noise; the inline answer already covers it.
        val webSuggestions = if (l.calculation != null) {
            emptyList()
        } else {
            s.filter { it.lowercase() != normalizedQuery }.take(MAX_SUGGESTIONS)
        }
        val command = l.command
        val commandContact = if (command?.needsContact == true) c.firstOrNull { it.number != null } else null
        SearchUiState(
            query = l.query,
            suggestedApps = l.suggested,
            apps = l.apps,
            contacts = c,
            calculation = l.calculation,
            url = l.url,
            // When the query clearly names an app ("sett" -> Settings), a Wikipedia card is noise.
            answer = a.takeIf { l.bestAppScore < FuzzyMatcher.WORD_PREFIX - 20 },
            webSuggestions = webSuggestions,
            engine = st.engine,
            // "timer 5 min" isn't looking for a person; "call mom" is.
            showContactsPrompt = st.searchContacts && !contactsPermission.value && l.query.length >= 2 &&
                command?.isConfident != true,
            command = command,
            commandContact = commandContact,
            weather = extras.weather,
            wiki = extras.wiki,
            showExamples = l.query.isEmpty(),
            topHit = topHit(l, c, commandContact),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    private data class Extras(val settings: AppSettings, val weather: WeatherUi?, val wiki: WikiUi?)

    private fun computeLocal(rawQuery: String, apps: List<AppEntry>, settings: AppSettings): Local {
        val q = rawQuery.trim()
        if (q.isEmpty()) {
            val suggested = if (settings.suggestedApps) app.apps.suggestions(apps, app.usage, SUGGESTED_APPS) else emptyList()
            return Local(query = q, suggested = suggested)
        }

        val command = CommandParser.parse(q)
        // "open spotify" ranks apps by "spotify".
        val normalized = FuzzyMatcher.normalize((command as? Command.OpenApp)?.name ?: q)
        val compact = normalized.replace(" ", "")
        val scored = apps.mapNotNull { entry ->
            val base = FuzzyMatcher.score(normalized, entry.searchKey)
            if (base <= 0) null else Scored(entry, base, base + app.usage.boost(entry.key, compact))
        }
        val bestBase = scored.maxOfOrNull { it.base } ?: 0
        // When there are strong matches, drop the fuzzy noise.
        val floor = if (bestBase >= FuzzyMatcher.SUBSTRING) FuzzyMatcher.TYPO - 50 else 0
        val ranked = scored
            .filter { it.base >= floor }
            .sortedWith(compareByDescending<Scored> { it.total }.thenBy { it.entry.label.lowercase() })
            .take(MAX_APPS)

        return Local(
            query = q,
            apps = ranked.map { it.entry },
            bestAppScore = ranked.firstOrNull()?.base ?: 0,
            calculation = Calculator.evaluate(q) ?: GreekCommandParser.arithmetic(q)?.let { Calculator.evaluate(it) },
            url = detectUrl(q) ?: (command as? Command.OpenApp)?.let { detectUrl(it.name) },
            command = command,
        )
    }

    private class Scored(val entry: AppEntry, val base: Int, val total: Int)

    private fun topHit(local: Local, contacts: List<Contact>, commandContact: Contact?): TopHit? {
        if (local.query.isEmpty()) return null
        val command = local.command
        return when {
            command != null && command.isSelfContained -> TopHit.Run(command)
            command != null && commandContact != null -> TopHit.Reach(command, commandContact)
            command != null && command.isInformation -> TopHit.Info(command)
            local.calculation != null -> TopHit.Calculation(local.calculation)
            local.url != null -> TopHit.Url(local.url)
            local.apps.isNotEmpty() && local.bestAppScore >= FuzzyMatcher.TYPO -> TopHit.App(local.apps.first())
            contacts.isNotEmpty() -> TopHit.ContactHit(contacts.first())
            else -> TopHit.Web(local.query)
        }
    }

    companion object {
        private const val SUGGESTED_APPS = 10
        private const val MAX_APPS = 10
        private const val MAX_SUGGESTIONS = 5

        /** What contacts are searched for: the name in "call mom", otherwise the query. */
        private fun contactQuery(q: String): String = when (val command = CommandParser.parse(q)) {
            is Command.Call -> command.who
            is Command.Message -> command.who
            else -> q
        }

        private fun appQuery(q: String): String = (CommandParser.parse(q) as? Command.OpenApp)?.name ?: q

        private val urlPattern = Regex(
            "^(https?://)?(localhost|([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,24})(:\\d{1,5})?(/\\S*)?$",
            RegexOption.IGNORE_CASE,
        )

        private val commonTlds = setOf(
            "com", "org", "net", "edu", "gov", "mil", "int", "io", "dev", "app", "ai", "info", "biz",
            "xyz", "me", "co", "tv", "gg", "ly", "sh", "page", "blog", "shop", "store", "tech",
            "online", "site", "news", "wiki", "cloud", "eu",
        )

        fun detectUrl(query: String): String? {
            val q = query.trim()
            if (q.contains(' ') || !urlPattern.matches(q)) return null
            val hasScheme = q.startsWith("http://", true) || q.startsWith("https://", true)
            if (hasScheme) return q
            // Without a scheme, only accept real-looking domains so "file.txt" isn't treated as a link.
            val host = q.substringBefore('/').substringBefore(':')
            val tld = host.substringAfterLast('.', "").lowercase()
            return if (tld.length == 2 || tld in commonTlds) "https://$q" else null
        }

        val Factory = viewModelFactory {
            initializer { SearchViewModel(this[APPLICATION_KEY] as FastSearchApp) }
        }
    }
}
