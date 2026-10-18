package com.fastsearch.assist

import android.Manifest
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bestassistant.commands.Command
import com.bestassistant.commands.MediaAction
import com.bestassistant.integration.Step
import com.bestassistant.language.AppLanguage
import com.bestassistant.listen.ListenState
import com.bestassistant.listen.VoiceInput
import com.bestassistant.media.MusicPause
import com.bestassistant.wake.WakeControl
import com.bestassistant.wake.WakePhrase
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.fastsearch.data.SpeechMode
import com.fastsearch.R
import com.fastsearch.assist.ui.ImageSource
import com.fastsearch.assist.ui.LocalImageSource
import com.fastsearch.assist.ui.SearchActions
import com.fastsearch.assist.ui.SearchScreen
import com.fastsearch.data.AppEntry
import com.fastsearch.data.Contact
import com.fastsearch.fastSearch
import com.fastsearch.settings.SettingsActivity
import com.fastsearch.ui.theme.FastSearchTheme
import com.fastsearch.ui.theme.isDarkTheme
import java.util.function.Consumer
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect as ComposeRect

/**
 * The assistant overlay. Started by the system for ACTION_ASSIST (long-press home, power-button
 * hold or corner swipe, depending on the device), and also by the Quick Settings tile, the
 * launcher shortcut and the text-selection "FastSearch" action.
 *
 * It's a translucent, self-dismissing window: it finishes as soon as it is no longer visible,
 * so every invocation starts fresh with the keyboard up.
 */
class AssistActivity : ComponentActivity(), SearchActions {

    private val viewModel: SearchViewModel by viewModels { SearchViewModel.Factory }
    private val textState = TextFieldState()

    private var session by mutableIntStateOf(0)
    private var dismissSignal by mutableIntStateOf(0)
    private var systemBlurEnabled by mutableStateOf(false)

    /** A spoken query waiting for its results, so a recognised command can run hands-free. */
    private var pendingSpoken: String? = null
    private var pendingSpokenAt = 0L
    private var blurListener: Consumer<Boolean>? = null
    private var blurApplied = false
    private var lastRevealProgress = 0f
    private var lastBlurRadius = -1
    private val maxBlurRadiusPx by lazy { (BLUR_RADIUS_DP * resources.displayMetrics.density).roundToInt() }

    /** True while another activity is open for a result; stops the overlay finishing itself. */
    private var awaitingResult = false

    private var listen by mutableStateOf<ListenState>(ListenState.Idle)

    /** The text the recognizer last put in the search bar; any other change means the user typed. */
    private var voiceText: String? = null

    private val voiceAvailable by lazy { SpeechRecognizer.isRecognitionAvailable(this) }

    private val voice by lazy {
        VoiceInput(
            context = this,
            onState = { listen = it },
            onPartial = ::showHeard,
            onFinal = ::onSpoken,
            onNothing = ::onNothingHeard,
        )
    }

    /** Music that was playing is paused while listening, and resumed once the request is handled. */
    private val music by lazy { MusicPause(this, fastSearch.media) }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingResult = false
        if (granted) startListening() else toast(R.string.error_mic_permission)
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        awaitingResult = false
        viewModel.refreshPermissions()
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        awaitingResult = false
        viewModel.refreshPermissions()
    }

    /** A command waiting for the permissions it asked for ("remember where I parked" needs location); runs either way. */
    private var afterPermissions: (() -> Unit)? = null

    private val commandPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        awaitingResult = false
        viewModel.refreshPermissions()
        afterPermissions?.invoke()
        afterPermissions = null
    }

    private val imageSource = object : ImageSource {
        override fun cachedAppIcon(entry: AppEntry) = fastSearch.apps.cachedIcon(entry.key)
        override suspend fun appIcon(entry: AppEntry) = fastSearch.apps.icon(entry)
        override fun cachedPhoto(uri: Uri) = fastSearch.contacts.cachedPhoto(uri)
        override suspend fun photo(uri: Uri) = fastSearch.contacts.photo(uri)
        override fun cachedWebImage(url: String) = fastSearch.web.cachedImage(url)
        override suspend fun webImage(url: String) = fastSearch.web.image(url)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        suppressWindowAnimations()
        window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            systemBlurEnabled = windowManager.isCrossWindowBlurEnabled
        }
        applyIntent(intent)
        fastSearch.speaker.stop()
        if (fastSearch.preferences.settings.value.speech != SpeechMode.OFF) fastSearch.speaker.warmUp()
        // The overlay owns the microphone while it's open; also (re)start the wake word if it's on.
        WakeControl.overlayOpen.value = true
        WakeControl.sync(this)
        listenOnOpen(intent)

        setContent {
            val settings by fastSearch.preferences.settings.collectAsStateWithLifecycle()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings.theme)
            val blurActive = settings.blurBackground && systemBlurEnabled

            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            LaunchedEffect(blurActive) { setBlurEnabled(blurActive) }
            LaunchedEffect(state) { onStateChanged(state, settings.runSpokenCommands) }
            LaunchedEffect(Unit) {
                // Typing while it listens means the user chose the keyboard: stop listening.
                snapshotFlow { textState.text.toString() }.collect { text ->
                    if (voice.isActive && text != voiceText) {
                        voice.cancel()
                        music.resume()
                    }
                }
            }

            FastSearchTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                CompositionLocalProvider(LocalImageSource provides imageSource) {
                    SearchScreen(
                        state = state,
                        textState = textState,
                        session = session,
                        dismissSignal = dismissSignal,
                        darkTheme = dark,
                        blurActive = blurActive,
                        voiceAvailable = voiceAvailable,
                        listen = listen,
                        onQueryChange = viewModel::onQueryChange,
                        onRevealProgress = ::onRevealProgress,
                        onDismissed = ::finishSilently,
                        onSubmit = ::performTopHit,
                        actions = this,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new request interrupts whatever was still being said.
        fastSearch.speaker.stop()
        setIntent(intent)
        applyIntent(intent)
        session++
        WakeControl.overlayOpen.value = true
        listenOnOpen(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onStop() {
        super.onStop()
        // An assistant shouldn't linger in the background: once something covers it, it's done.
        if (!isChangingConfigurations && !awaitingResult && !isFinishing) finishSilently()
    }

    override fun onDestroy() {
        voice.cancel()
        music.resume()
        if (!isChangingConfigurations) WakeControl.overlayOpen.value = false
        super.onDestroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = Consumer<Boolean> { enabled -> systemBlurEnabled = enabled }
            windowManager.addCrossWindowBlurEnabledListener(mainExecutor, listener)
            blurListener = listener
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { windowManager.removeCrossWindowBlurEnabledListener(it) }
            blurListener = null
        }
        super.onDetachedFromWindow()
    }

    /** Prefills the query from "Search with FastSearch" text selection or a search intent. */
    private fun applyIntent(intent: Intent?) {
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getStringExtra(SearchManager.QUERY)
        if (text.isNullOrBlank()) textState.clearText() else textState.setTextAndPlaceCursorAtEnd(text.trim().take(MAX_PREFILL))
    }

    // region Window effects

    private fun suppressWindowAnimations() {
        // The content animates itself; a window animation on top would double up the motion.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun finishSilently() {
        voice.cancel()
        music.resume()
        WakeControl.overlayOpen.value = false
        finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun setBlurEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        blurApplied = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            lastBlurRadius = -1
            applyBlur(lastRevealProgress)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    /** Called every frame of the reveal animation; the background blur tracks the scrim. */
    private fun onRevealProgress(progress: Float) {
        lastRevealProgress = progress
        applyBlur(progress)
    }

    private fun applyBlur(progress: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !blurApplied) return
        val radius = (progress * maxBlurRadiusPx).roundToInt()
        if (radius == lastBlurRadius) return
        lastBlurRadius = radius
        window.attributes = window.attributes.apply { blurBehindRadius = radius }
    }

    // endregion

    // region Actions

    private fun performTopHit(hit: TopHit, bounds: ComposeRect?) {
        when (hit) {
            is TopHit.App -> launchApp(hit.entry, bounds)
            is TopHit.Calculation -> {
                copy(hit.result.plain)
                say(fastSearch.responder.forCalculation(hit.result))
            }
            is TopHit.Url -> openUrl(hit.url, bounds)
            is TopHit.ContactHit -> openContact(hit.contact, bounds)
            is TopHit.Web -> webSearch(hit.query, bounds)
            is TopHit.Run -> runCommand(hit.command, null, bounds)
            is TopHit.Reach -> runCommand(hit.command, hit.contact, bounds)
            is TopHit.Info -> runCommand(hit.command, null, bounds)
        }
    }

    // region Speech

    /** The current results, for replies that read from them (weather, Wikipedia). */
    private var latestState = SearchUiState()

    /** The text of the last voice request: replies to it are spoken even in "voice only" mode. */
    private var voiceQuery: String? = null

    /** An answer that was asked for (Enter, or voice) but whose data is still loading. */
    private var pendingAnswer: Command? = null

    /** A question Cog asked back ("Which one?"): what's heard next answers it. */
    private var question: Step.Ask? = null
    private var questionAt = 0L

    private val isVoiceRequest: Boolean get() = voiceQuery != null && latestState.query == voiceQuery

    /** Reads [text] aloud if the user's speech setting allows it for this request; [then] runs after it either way. */
    private fun say(text: String?, then: (() -> Unit)? = null) {
        val allowed = when (fastSearch.preferences.settings.value.speech) {
            SpeechMode.ALWAYS -> true
            SpeechMode.VOICE_ONLY -> isVoiceRequest
            SpeechMode.OFF -> false
        }
        if (text != null && allowed) fastSearch.speaker.speak(text, then) else then?.invoke()
    }

    private fun onStateChanged(state: SearchUiState, runSpoken: Boolean) {
        latestState = state
        answerWhenReady(state)
        runPendingSpoken(state, runSpoken)
    }

    /** Speaks a weather or Wikipedia answer once its data has arrived, if the user asked for it. */
    private fun answerWhenReady(state: SearchUiState) {
        val pending = pendingAnswer ?: return
        if (state.command != pending) {
            pendingAnswer = null // the question changed
            return
        }
        val reply = replyFor(pending, state) ?: return
        pendingAnswer = null
        say(reply)
    }

    /** The spoken answer for an information command, or null while its data is still loading. */
    private fun replyFor(command: Command, state: SearchUiState): String? {
        val responder = fastSearch.responder
        return when (command) {
            is Command.Weather -> when (val weather = state.weather) {
                is WeatherUi.Ready -> responder.forWeather(weather.forecast, weather.day)
                is WeatherUi.NotFound -> responder.forWeatherNotFound(weather.place)
                is WeatherUi.NeedsLocation -> responder.forWeatherNeedsLocation()
                is WeatherUi.Unavailable, is WeatherUi.NoFix -> responder.forWeatherUnavailable()
                is WeatherUi.Loading, null -> null
            }
            is Command.Lookup -> when (val wiki = state.wiki) {
                is WikiUi.Ready -> responder.forArticle(wiki.article)
                is WikiUi.NotFound -> responder.forArticleMissing(wiki.topic)
                WikiUi.Unavailable -> responder.forArticleUnavailable()
                is WikiUi.Loading, null -> null
            }
            else -> responder.forCommand(command)
        }
    }

    // endregion

    /**
     * After voice input, act on the request without waiting for Enter: run a recognised command,
     * read out an answer, or open the app that was asked for. Call and message commands wait until
     * their contact has been found.
     */
    private fun runPendingSpoken(state: SearchUiState, enabled: Boolean) {
        val pending = pendingSpoken ?: return
        if (SystemClock.uptimeMillis() - pendingSpokenAt > SPOKEN_TIMEOUT_MS) {
            pendingSpoken = null
            music.resume()
            return
        }
        if (state.query != pending) return
        val hit = state.topHit
        when {
            hit is TopHit.Run || hit is TopHit.Reach -> {
                pendingSpoken = null
                if (enabled) performTopHit(hit, null)
            }
            hit is TopHit.Info -> {
                pendingSpoken = null
                performTopHit(hit, null)
            }
            state.command?.needsContact == true -> Unit // still looking up the person
            hit is TopHit.App && state.command is Command.OpenApp -> {
                pendingSpoken = null
                if (enabled) {
                    say(fastSearch.responder.forApp(hit.entry.label))
                    launchApp(hit.entry, null)
                }
            }
            // Right after a cold start (the wake word) the app list may still be loading.
            state.command is Command.OpenApp && fastSearch.apps.apps.value.isEmpty() -> Unit
            state.command is Command.OpenApp -> {
                pendingSpoken = null
                val name = (state.command as Command.OpenApp).name
                // "open the garage door" may be for Home Assistant rather than an app.
                if (enabled) offerToIntegrations(state.query) { say(fastSearch.responder.forNoApp(name)) }
            }
            hit is TopHit.Calculation -> {
                pendingSpoken = null
                say(fastSearch.responder.forCalculation(hit.result))
            }
            else -> {
                // Nothing this assistant knows how to do: an integration (Home Assistant) may, else say so and leave the results on screen.
                pendingSpoken = null
                if (state.command == null) {
                    if (enabled) {
                        offerToIntegrations(state.query) { say(fastSearch.responder.forNothing(state.query)) }
                    } else {
                        say(fastSearch.responder.forNothing(state.query))
                    }
                }
            }
        }
        // Handled without running a command (a command already decided about the music itself).
        if (pendingSpoken == null) music.resume()
    }

    override fun runCommand(command: Command, contact: Contact?, bounds: ComposeRect?) = run(command, contact, bounds, askFirst = true)

    /** What [command] needs and doesn't have yet: location for the car and "where I am", notifications for reminders. */
    private fun missingPermissions(command: Command): List<String> = buildList {
        // Exact location is asked for, but approximate will do (the reply then says how rough the spot is).
        if (command.needsLocation && !fastSearch.location.hasPermission()) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val notifies = command is Command.Remind
        if (notifies && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this@AssistActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun run(command: Command, contact: Contact?, bounds: ComposeRect?, askFirst: Boolean) {
        if (askFirst) {
            val missing = missingPermissions(command)
            if (missing.isNotEmpty()) {
                voice.cancel()
                // Asked once; after that the command says what's missing itself.
                afterPermissions = { run(command, contact, bounds, askFirst = false) }
                awaitingResult = true
                commandPermissions.launch(missing.toTypedArray())
                return
            }
        }
        voice.cancel()
        // "pause" and "play ..." decide what plays; anything else gets the music back first.
        if (command is Command.PlayMusic || command == Command.Media(MediaAction.PAUSE)) music.forget() else music.resume()
        if (command.usesMediaSession && !fastSearch.media.hasAccess()) {
            // One-time setup: Android's notification-access screen is what grants media control.
            say(fastSearch.localized.getString(R.string.error_media_access))
            start(fastSearch.media.accessIntent(), bounds)
            return
        }
        if (command.isInformation) {
            // Answers stay on screen; Enter or a tap reads them out (once they've loaded).
            haptic()
            val reply = replyFor(command, latestState)
            if (reply != null) say(reply) else pendingAnswer = command
            return
        }
        when (val step = fastSearch.commands.plan(command, contact)) {
            is Step.Launch -> {
                haptic()
                val lookup = step.lookup
                if (lookup == null) {
                    launchStep(step, step.intents, command, contact, bounds)
                } else {
                    // A quick online lookup (the artist's Spotify page); slow or offline falls back to the plain intents.
                    lifecycleScope.launch {
                        val found = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup() }.orEmpty()
                        launchStep(step, found + step.intents, command, contact, bounds)
                    }
                }
            }
            is Step.Done, is Step.Failed, is Step.Ask -> finishStep(step, command, contact)
            is Step.Remote -> {
                haptic()
                lifecycleScope.launch {
                    // Some open an app with what they found ("send alex my location" opens Messages).
                    when (val result = step.run()) {
                        is Step.Launch -> launchStep(result, result.intents, command, contact, bounds)
                        else -> finishStep(result, command, contact)
                    }
                }
            }
        }
    }

    /** Confirms a command that ran in-process (or failed), aloud and on screen, or asks what it needs to know. */
    private fun finishStep(step: Step, command: Command?, contact: Contact?) {
        when (step) {
            is Step.Done -> {
                haptic()
                step.confirmation?.let(::toastText)
                say(step.spoken ?: command?.let { fastSearch.responder.forCommand(it, contact, torchOn = fastSearch.commands.isTorchOn) })
                dismissSignal++
            }
            is Step.Failed -> {
                toastText(step.message)
                say(step.message)
            }
            is Step.Ask -> ask(step)
            is Step.Launch, is Step.Remote -> Unit
        }
    }

    /** Asks [step]'s question, then listens for the answer. */
    private fun ask(step: Step.Ask) {
        toastText(step.question)
        question = step
        questionAt = SystemClock.uptimeMillis()
        say(step.question) { if (question === step && !isFinishing) startListening() }
    }

    /** What was heard after a question: its answer, or (when it isn't one) a new request. */
    private fun answer(asked: Step.Ask, text: String) {
        voiceQuery = text
        lifecycleScope.launch {
            val step = asked.answer(text)
            if (step == null) {
                heard(text)
            } else {
                music.resume()
                finishStep(step, null, null)
            }
        }
    }

    /**
     * Offers a spoken request this assistant didn't understand to the installed integrations that
     * take such requests (the user's Home Assistant, when set up); [otherwise] runs when none does,
     * or none understands it either.
     */
    private fun offerToIntegrations(text: String, otherwise: () -> Unit) {
        if (!fastSearch.commands.takesUnknown) {
            otherwise()
            return
        }
        lifecycleScope.launch {
            when (val step = fastSearch.commands.tryIntegrations(text)) {
                is Step.Done, is Step.Failed, is Step.Ask -> finishStep(step, null, null)
                else -> otherwise()
            }
        }
    }

    override fun requestLocationPermission() {
        val prefs = fastSearch.preferences
        if (prefs.askedForLocation && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)), null)
        } else {
            prefs.askedForLocation = true
            awaitingResult = true
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun launchStep(step: Step.Launch, intents: List<Intent>, command: Command, contact: Contact?, bounds: ComposeRect?) {
        if (!startFirst(intents, bounds)) {
            toast(R.string.error_no_app)
            say(fastSearch.localized.getString(R.string.error_no_app))
            return
        }
        say(step.spoken ?: fastSearch.responder.forCommand(command, contact))
        step.then?.let { then -> fastSearch.appScope.launch { then() } }
        step.confirmation?.let { confirmation ->
            // Clock intents with EXTRA_SKIP_UI show nothing; confirm and close ourselves.
            toastText(confirmation)
            dismissSignal++
        }
    }

    /** Starts the first intent some app can handle; true when one started. */
    private fun startFirst(intents: List<Intent>, bounds: ComposeRect?): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), launchOptions(bounds))
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next, more generic screen
            } catch (_: SecurityException) {
                // some OEM builds protect specific screens
            }
        }
        return false
    }

    override fun launchApp(entry: AppEntry, bounds: ComposeRect?) {
        haptic()
        viewModel.recordLaunch(entry)
        if (!fastSearch.apps.launch(entry, bounds.toScreenRect(), launchOptions(bounds))) {
            toast(R.string.error_launch)
        }
    }

    override fun appInfo(entry: AppEntry, bounds: ComposeRect?) {
        if (!fastSearch.apps.openAppInfo(entry, bounds.toScreenRect())) toast(R.string.error_launch)
    }

    override fun uninstall(entry: AppEntry) = start(fastSearch.apps.uninstallIntent(entry), null)

    override fun openContact(contact: Contact, bounds: ComposeRect?) =
        start(Intent(Intent.ACTION_VIEW, contact.lookupUri), bounds)

    override fun call(contact: Contact, bounds: ComposeRect?) {
        val number = contact.number ?: return
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)), bounds)
    }

    override fun message(contact: Contact, bounds: ComposeRect?) {
        val number = contact.number ?: return
        start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)), bounds)
    }

    override fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label), text))
        haptic()
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.copied)
    }

    override fun openUrl(url: String, bounds: ComposeRect?) =
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE), bounds)

    override fun webSearch(query: String, bounds: ComposeRect?) {
        val url = fastSearch.preferences.settings.value.engine.searchUrl(query)
        openUrl(url, bounds)
    }

    override fun requestContactsPermission() {
        val prefs = fastSearch.preferences
        if (prefs.askedForContacts && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
            // Denied twice: the system won't ask again, so send the user to the app's permission page.
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)), null)
        } else {
            prefs.askedForContacts = true
            awaitingResult = true
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // region Listening

    override fun toggleListening() {
        if (voice.isActive) voice.stop() else startListening(userAsked = true)
    }

    /**
     * Opens ready for both: the keyboard is up and the microphone is listening. Listens when the
     * wake word opened it, or when "listen when opened" is on and nothing was prefilled.
     */
    private fun listenOnOpen(intent: Intent?) {
        val woke = intent?.action == ACTION_WAKE
        if (woke) {
            showOverLockScreen()
            if (isLocked()) return // listening starts once the user has unlocked
        }
        val settings = fastSearch.preferences.settings.value
        if (!woke && (!settings.listenOnOpen || textState.text.isNotEmpty())) return
        if (!voiceAvailable) return
        if (hasMicrophone()) {
            startListening()
        } else if (!fastSearch.preferences.askedForMicrophone) {
            fastSearch.preferences.askedForMicrophone = true
            awaitingResult = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasMicrophone() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListening(userAsked: Boolean = false) {
        if (!voiceAvailable) {
            if (userAsked) toast(R.string.error_no_recognizer)
            return
        }
        if (!hasMicrophone()) {
            if (!userAsked) return
            awaitingResult = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        fastSearch.speaker.stop()
        music.pause()
        voiceText = textState.text.toString()
        voice.start(AppLanguage.locale)
    }

    /** Live transcript in the search bar, so results update as the user speaks. */
    private fun showHeard(partial: String) {
        val text = withoutWakePhrase(partial)
        voiceText = text
        textState.setTextAndPlaceCursorAtEnd(text)
    }

    private fun onSpoken(spoken: String) {
        val text = withoutWakePhrase(spoken).trim()
        if (text.isEmpty()) {
            music.resume()
            return
        }
        val asked = question
        question = null
        if (asked != null && SystemClock.uptimeMillis() - questionAt < QUESTION_TIMEOUT_MS) {
            voiceText = text
            textState.setTextAndPlaceCursorAtEnd(text)
            answer(asked, text)
            return
        }
        heard(text)
    }

    /** Runs a spoken request. */
    private fun heard(text: String) {
        voiceText = text
        textState.setTextAndPlaceCursorAtEnd(text)
        pendingSpoken = text
        voiceQuery = text
        pendingSpokenAt = SystemClock.uptimeMillis()
        // The live transcript usually already put this exact text in the bar, so the results for it
        // may be on screen and no new state will arrive: act on them now. If they're still being
        // worked out, the next state update runs the command instead.
        runPendingSpoken(latestState, fastSearch.preferences.settings.value.runSpokenCommands)
    }

    private fun onNothingHeard(code: Int) {
        music.resume()
        when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> toast(R.string.error_mic_permission)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                toast(R.string.error_voice_network)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                toast(R.string.error_voice_language)
            // Silence or no match: just stop; the keyboard is still there.
            else -> Unit
        }
    }

    /** "ok Cog, set a timer" -> "set a timer". */
    private fun withoutWakePhrase(text: String): String {
        val phrase = WakePhrase.words(fastSearch.preferences.settings.value.wakePhrase)
        val words = text.trim().split(Regex("\\s+"))
        if (phrase.isEmpty() || words.size <= phrase.size) return text
        val head = words.take(phrase.size).joinToString(" ")
        return if (WakePhrase.find(head, phrase) != null) words.drop(phrase.size).joinToString(" ") else text
    }

    private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** A wake word heard on the lock screen: light up, and ask to unlock before listening. */
    private fun showOverLockScreen() {
        if (!isLocked()) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    setShowWhenLocked(false)
                    startListening()
                }

                override fun onDismissCancelled() = finishSilently()
                override fun onDismissError() = finishSilently()
            },
        )
    }

    // endregion

    override fun openSettings() = start(Intent(this, SettingsActivity::class.java), null)

    private fun start(intent: Intent, bounds: ComposeRect?) {
        haptic()
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), launchOptions(bounds))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.error_no_app)
        } catch (_: SecurityException) {
            toast(R.string.error_launch)
        }
    }

    /** The launched app grows out of the tapped icon or row, like launching from a home screen. */
    private fun launchOptions(bounds: ComposeRect?): Bundle? {
        bounds ?: return null
        return ActivityOptions.makeScaleUpAnimation(
            window.decorView,
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.width.roundToInt().coerceAtLeast(1),
            bounds.height.roundToInt().coerceAtLeast(1),
        ).toBundle()
    }

    private fun ComposeRect?.toScreenRect(): Rect? {
        this ?: return null
        val origin = IntArray(2)
        window.decorView.getLocationOnScreen(origin)
        return Rect(
            left.roundToInt() + origin[0],
            top.roundToInt() + origin[1],
            right.roundToInt() + origin[0],
            bottom.roundToInt() + origin[1],
        )
    }

    private fun haptic() {
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        window.decorView.performHapticFeedback(feedback)
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // Shown with the application context so it survives the overlay closing right after.
    private fun toastText(message: String) = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()

    // endregion

    companion object {
        /** Started by the wake-word service: listen straight away. */
        const val ACTION_WAKE = "com.bestassistant.action.WAKE"

        private const val BLUR_RADIUS_DP = 28
        private const val MAX_PREFILL = 200
        private const val SPOKEN_TIMEOUT_MS = 4_000L

        /** How long a question ("Which one?") waits for its answer. */
        private const val QUESTION_TIMEOUT_MS = 30_000L

        /** How long "play ..." waits for the artist's Spotify page before using search instead. */
        private const val LOOKUP_TIMEOUT_MS = 3_000L
    }
}
