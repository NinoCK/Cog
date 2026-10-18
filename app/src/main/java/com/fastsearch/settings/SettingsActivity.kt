package com.fastsearch.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Assistant
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.MenuBook
import com.fastsearch.data.SpeechMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import android.app.NotificationManager
import com.bestassistant.device.Brightness
import com.bestassistant.language.AppLanguage
import com.bestassistant.wake.WakeControl
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastsearch.BuildConfig
import com.fastsearch.R
import com.fastsearch.assist.AssistActivity
import com.fastsearch.data.AppSettings
import com.fastsearch.data.SearchEngine
import com.fastsearch.data.ThemeMode
import com.fastsearch.data.UnitSystem
import com.fastsearch.fastSearch
import com.fastsearch.ui.theme.FastSearchTheme
import com.fastsearch.ui.theme.isDarkTheme

/** Launcher entry point: first-run setup plus preferences. */
class SettingsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        // A visible screen may start microphone access: bring the wake word up if it's on.
        WakeControl.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by fastSearch.preferences.settings.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings.theme)
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            FastSearchTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                SettingsScreen(
                    settings = settings,
                    update = fastSearch.preferences::update,
                    onTryIt = {
                        startActivity(
                            Intent(this, AssistActivity::class.java)
                                .setAction(ACTION_SEARCH)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
        }
    }

    companion object {
        const val ACTION_SEARCH = "com.fastsearch.action.SEARCH"
    }
}

private fun hasContacts(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun hasMicrophone(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun hasCalendar(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun hasCallLog(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

private fun hasDndAccess(context: Context) =
    context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

private fun hasNotifications(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onTryIt: () -> Unit,
) {
    val context = LocalContext.current
    var assistantHeld by remember { mutableStateOf(AssistantRole.isHeld(context)) }
    var contactsGranted by remember { mutableStateOf(hasContacts(context)) }
    var locationGranted by remember { mutableStateOf(hasLocation(context)) }
    var micGranted by remember { mutableStateOf(hasMicrophone(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotifications(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var mediaGranted by remember { mutableStateOf(context.fastSearch.media.hasAccess()) }
    var calendarGranted by remember { mutableStateOf(hasCalendar(context)) }
    var callLogGranted by remember { mutableStateOf(hasCallLog(context)) }
    var settingsGranted by remember { mutableStateOf(Brightness.canChange(context)) }
    var dndGranted by remember { mutableStateOf(hasDndAccess(context)) }
    val routines by context.fastSearch.routines.all.collectAsStateWithLifecycle()
    var engineDialog by remember { mutableStateOf(false) }
    val wakeModel by context.fastSearch.wakeModel.state.collectAsStateWithLifecycle()
    val wakeStatus by WakeControl.status.collectAsStateWithLifecycle()
    val unknownWords by WakeControl.unknownWords.collectAsStateWithLifecycle()


    LifecycleResumeEffect(Unit) {
        assistantHeld = AssistantRole.isHeld(context)
        contactsGranted = hasContacts(context)
        locationGranted = hasLocation(context)
        micGranted = hasMicrophone(context)
        notificationsGranted = hasNotifications(context)
        overlayGranted = Settings.canDrawOverlays(context)
        mediaGranted = context.fastSearch.media.hasAccess()
        calendarGranted = hasCalendar(context)
        callLogGranted = hasCallLog(context)
        settingsGranted = Brightness.canChange(context)
        dndGranted = hasDndAccess(context)
        onPauseOrDispose { }
    }

    // Once the model has downloaded (or the phrase changed), start listening if the wake word is on.
    LaunchedEffect(wakeModel, settings.wakeWord, settings.wakePhrase) { WakeControl.sync(context) }

    val wakePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micGranted = hasMicrophone(context)
        notificationsGranted = hasNotifications(context)
        WakeControl.sync(context)
    }
    val enableWake = {
        update { it.copy(wakeWord = true) }
        context.fastSearch.wakeModel.download()
        val missing = buildList {
            if (!hasMicrophone(context)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifications(context)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) wakePermissions.launch(missing.toTypedArray()) else WakeControl.sync(context)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        WakeControl.sync(context)
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
    }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calendarGranted = hasCalendar(context)
    }
    val callLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        callLogGranted = hasCallLog(context)
    }
    val requestLocation = {
        val activity = context as ComponentActivity
        val prefs = context.fastSearch.preferences
        if (prefs.askedForLocation && !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        } else {
            prefs.askedForLocation = true
            locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsGranted = granted
        if (granted) update { it.copy(searchContacts = true) }
    }
    val requestContacts = {
        val activity = context as ComponentActivity
        val prefs = context.fastSearch.preferences
        if (prefs.askedForContacts && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        } else {
            prefs.askedForContacts = true
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    // The integration whose page is open (SettingsPage.INTEGRATION), by its id.
    var integrationId by rememberSaveable { mutableStateOf<String?>(null) }
    val integration = context.fastSearch.integrations.all.firstOrNull { it.id == integrationId }
    BackHandler(enabled = page != SettingsPage.HOME) { page = page.parent }
    val colors = MaterialTheme.colorScheme
    val installed by context.fastSearch.integrations.installed.collectAsStateWithLifecycle()
    // The optional "press play" helper isn't counted: nothing is missing without it.
    val required = listOf(assistantHeld, contactsGranted, locationGranted, micGranted, mediaGranted)

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState.depth > initialState.depth
            (slideInHorizontally { width -> if (forward) width / 4 else -width / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> if (forward) -width / 4 else width / 4 } + fadeOut())
        },
        label = "settings page",
    ) { current ->
        SettingsPageScaffold(
            title = if (current == SettingsPage.INTEGRATION) integration?.name.orEmpty() else stringResource(current.title),
            onBack = if (current == SettingsPage.HOME) null else ({ page = current.parent }),
            onTryIt = if (current == SettingsPage.HOME) onTryIt else null,
        ) {
            when (current) {
                SettingsPage.HOME -> {
                    item { HeroCard(assistantHeld = assistantHeld, onSetDefault = { AssistantRole.openSettings(context) }) }
                    item {
                        Group {
                            PageRow(
                                Icons.Rounded.VerifiedUser,
                                R.string.page_permissions,
                                if (required.all { it }) {
                                    stringResource(R.string.summary_permissions_all)
                                } else {
                                    stringResource(R.string.summary_permissions_count, required.count { it }, required.size)
                                },
                            ) { page = SettingsPage.PERMISSIONS }
                            PageRow(
                                Icons.Rounded.Extension,
                                R.string.page_integrations,
                                if (installed.isEmpty()) {
                                    stringResource(R.string.summary_integrations_none)
                                } else {
                                    installed.joinToString(", ") { it.name }
                                },
                            ) { page = SettingsPage.INTEGRATIONS }
                            PageRow(Icons.Rounded.Hub, R.string.page_connections, stringResource(R.string.summary_connections)) {
                                page = SettingsPage.CONNECTIONS
                            }
                            PageRow(
                                Icons.Rounded.Checklist,
                                R.string.page_routines,
                                routines.filter { it.isUsable }.joinToString(", ") { it.name }.ifEmpty { stringResource(R.string.summary_routines_none) },
                            ) { page = SettingsPage.ROUTINES }
                            PageRow(
                                Icons.Rounded.Hearing,
                                R.string.section_wake,
                                if (settings.wakeWord) {
                                    stringResource(R.string.summary_wake_on, settings.wakePhrase)
                                } else {
                                    stringResource(R.string.summary_wake_off)
                                },
                            ) { page = SettingsPage.WAKE }
                        }
                    }
                    item {
                        Group {
                            PageRow(Icons.Rounded.RecordVoiceOver, R.string.section_voice, stringResource(settings.voiceGender.label)) {
                                page = SettingsPage.VOICE
                            }
                            PageRow(Icons.Rounded.Assistant, R.string.section_assistant_settings, stringResource(R.string.summary_assistant_settings)) {
                                page = SettingsPage.ASSISTANT
                            }
                            PageRow(Icons.Rounded.Search, R.string.section_search_settings, stringResource(settings.engine.label)) {
                                page = SettingsPage.SEARCH
                            }
                            PageRow(
                                Icons.Rounded.Palette,
                                R.string.section_appearance,
                                stringResource(
                                    when (settings.theme) {
                                        ThemeMode.SYSTEM -> R.string.theme_system
                                        ThemeMode.LIGHT -> R.string.theme_light
                                        ThemeMode.DARK -> R.string.theme_dark
                                    },
                                ),
                            ) { page = SettingsPage.APPEARANCE }
                        }
                    }
                    item {
                        Group {
                            PageRow(Icons.Rounded.Info, R.string.page_about, stringResource(R.string.summary_about, BuildConfig.VERSION_NAME)) {
                                page = SettingsPage.ABOUT
                            }
                        }
                    }
                }

                SettingsPage.PERMISSIONS -> item {
                    Group {
                        StepRow(
                            icon = Icons.Rounded.Assistant,
                            title = stringResource(R.string.step_assistant_title),
                            body = stringResource(R.string.step_assistant_body),
                            done = assistantHeld,
                            action = stringResource(R.string.action_set),
                            onAction = { AssistantRole.openSettings(context) },
                        )
                        StepRow(
                            icon = Icons.Rounded.Contacts,
                            title = stringResource(R.string.step_contacts_title),
                            body = stringResource(R.string.step_contacts_body),
                            done = contactsGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = requestContacts,
                        )
                        StepRow(
                            icon = Icons.Rounded.LocationOn,
                            title = stringResource(R.string.step_location_title),
                            body = stringResource(R.string.step_location_body),
                            done = locationGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = requestLocation,
                        )
                        StepRow(
                            icon = Icons.Rounded.Mic,
                            title = stringResource(R.string.step_mic_title),
                            body = stringResource(R.string.step_mic_body),
                            done = micGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        )
                        StepRow(
                            icon = Icons.Rounded.LibraryMusic,
                            title = stringResource(R.string.step_media_title),
                            body = stringResource(R.string.step_media_body),
                            done = mediaGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = { context.startActivity(context.fastSearch.media.accessIntent()) },
                        )
                        StepRow(
                            icon = Icons.Rounded.CalendarMonth,
                            title = stringResource(R.string.step_calendar_title),
                            body = stringResource(R.string.step_calendar_body),
                            done = calendarGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = { calendarLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                        )
                        StepRow(
                            icon = Icons.Rounded.History,
                            title = stringResource(R.string.step_calls_title),
                            body = stringResource(R.string.step_calls_body),
                            done = callLogGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = { callLogLauncher.launch(Manifest.permission.READ_CALL_LOG) },
                        )
                        StepRow(
                            icon = Icons.Rounded.BrightnessMedium,
                            title = stringResource(R.string.step_system_settings_title),
                            body = stringResource(R.string.step_system_settings_body),
                            done = settingsGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = { context.startActivity(Brightness.permissionIntent(context)) },
                        )
                        StepRow(
                            icon = Icons.Rounded.DoNotDisturbOn,
                            title = stringResource(R.string.step_dnd_title),
                            body = stringResource(R.string.step_dnd_body),
                            done = dndGranted,
                            action = stringResource(R.string.action_allow),
                            onAction = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                        )
                        StepRow(
                            icon = Icons.Rounded.Gesture,
                            title = stringResource(R.string.step_gesture_title),
                            body = stringResource(R.string.step_gesture_body),
                            done = null,
                            action = null,
                            onAction = {},
                        )
                    }
                }

                SettingsPage.INTEGRATIONS -> item {
                    Group {
                        IntegrationList { opened ->
                            integrationId = opened.id
                            page = SettingsPage.INTEGRATION
                        }
                    }
                }

                SettingsPage.INTEGRATION -> integration?.let { integrationPage(it) }

                SettingsPage.CONNECTIONS -> {
                    item { SectionTitle(R.string.section_navigation) }
                    item { Group { NavigationSection(settings, update) } }
                }

                SettingsPage.ROUTINES -> {
                    val store = context.fastSearch.routines
                    item { Group { RoutinesIntro(routines, store) } }
                    routines.forEach { routine ->
                        item(key = routine.id) {
                            SectionTitle(routine.name.ifEmpty { stringResource(R.string.routine_unnamed) })
                            Spacer(Modifier.height(8.dp))
                            Group { RoutineEditor(routine, store) }
                        }
                    }
                }

                SettingsPage.WAKE -> item {
                    Group {
                        WakeSection(
                            settings = settings,
                            update = update,
                            model = wakeModel,
                            status = wakeStatus,
                            unknownWords = unknownWords,
                            micGranted = micGranted,
                            overlayGranted = overlayGranted,
                            notificationsGranted = notificationsGranted,
                            onEnable = enableWake,
                            onRequestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    wakePermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                }
                            },
                        )
                    }
                }

                SettingsPage.VOICE -> item { Group { VoiceSection(settings, update) } }

                SettingsPage.ASSISTANT -> item {
                    Group {
                        SwitchRow(Icons.Rounded.GraphicEq, R.string.pref_listen_title, R.string.pref_listen_body, settings.listenOnOpen) { on ->
                            update { it.copy(listenOnOpen = on) }
                            if (on && !micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        SwitchRow(Icons.Rounded.WbSunny, R.string.pref_weather_title, R.string.pref_weather_body, settings.weather) { on ->
                            update { it.copy(weather = on) }
                        }
                        SwitchRow(Icons.Rounded.MenuBook, R.string.pref_wikipedia_title, R.string.pref_wikipedia_body, settings.wikipedia) { on ->
                            update { it.copy(wikipedia = on) }
                        }
                        SwitchRow(Icons.Rounded.RecordVoiceOver, R.string.pref_spoken_title, R.string.pref_spoken_body, settings.runSpokenCommands) { on ->
                            update { it.copy(runSpokenCommands = on) }
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Campaign, contentDescription = null, tint = colors.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(stringResource(R.string.pref_speech_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.pref_speech_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            val modes = SpeechMode.entries
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                modes.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = settings.speech == mode,
                                        onClick = { update { it.copy(speech = mode) } },
                                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    ) {
                                        Text(
                                            stringResource(
                                                when (mode) {
                                                    SpeechMode.ALWAYS -> R.string.speech_always
                                                    SpeechMode.VOICE_ONLY -> R.string.speech_voice_only
                                                    SpeechMode.OFF -> R.string.speech_off
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Straighten, contentDescription = null, tint = colors.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text(stringResource(R.string.pref_units_title), style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.height(12.dp))
                            val systems = UnitSystem.entries
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                systems.forEachIndexed { index, system ->
                                    SegmentedButton(
                                        selected = settings.units == system,
                                        onClick = { update { it.copy(units = system) } },
                                        shape = SegmentedButtonDefaults.itemShape(index, systems.size),
                                    ) {
                                        Text(
                                            stringResource(
                                                when (system) {
                                                    UnitSystem.AUTO -> R.string.units_auto
                                                    UnitSystem.METRIC -> R.string.units_metric
                                                    UnitSystem.IMPERIAL -> R.string.units_imperial
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsPage.SEARCH -> item {
                    Group {
                        SwitchRow(Icons.Rounded.TravelExplore, R.string.pref_web_title, R.string.pref_web_body, settings.webSuggestions) { on ->
                            update { it.copy(webSuggestions = on) }
                        }
                        SwitchRow(Icons.Rounded.Lightbulb, R.string.pref_answers_title, R.string.pref_answers_body, settings.instantAnswers) { on ->
                            update { it.copy(instantAnswers = on) }
                        }
                        SwitchRow(Icons.Rounded.Contacts, R.string.pref_contacts_title, R.string.pref_contacts_body, settings.searchContacts && contactsGranted) { on ->
                            if (on && !contactsGranted) requestContacts() else update { it.copy(searchContacts = on) }
                        }
                        SwitchRow(Icons.Rounded.Apps, R.string.pref_suggested_title, R.string.pref_suggested_body, settings.suggestedApps) { on ->
                            update { it.copy(suggestedApps = on) }
                        }
                        ListItem(
                            modifier = Modifier.clickable { engineDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.pref_engine_title)) },
                            supportingContent = { Text(stringResource(settings.engine.label)) },
                        )
                    }
                }

                SettingsPage.APPEARANCE -> item {
                    Group {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Palette, contentDescription = null, tint = colors.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text(stringResource(R.string.pref_theme_title), style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.height(12.dp))
                            val modes = ThemeMode.entries
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                modes.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = settings.theme == mode,
                                        onClick = { update { it.copy(theme = mode) } },
                                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    ) {
                                        Text(
                                            stringResource(
                                                when (mode) {
                                                    ThemeMode.SYSTEM -> R.string.theme_system
                                                    ThemeMode.LIGHT -> R.string.theme_light
                                                    ThemeMode.DARK -> R.string.theme_dark
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SwitchRow(Icons.Rounded.AutoAwesome, R.string.pref_dynamic_title, R.string.pref_dynamic_body, settings.dynamicColor) { on ->
                                update { it.copy(dynamicColor = on) }
                            }
                            SwitchRow(Icons.Rounded.BlurOn, R.string.pref_blur_title, R.string.pref_blur_body, settings.blurBackground) { on ->
                                update { it.copy(blurBackground = on) }
                            }
                        }
                    }
                }

                SettingsPage.ABOUT -> {
                    item { SectionTitle(R.string.section_privacy) }
                    item {
                        Group {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { Icon(Icons.Rounded.PrivacyTip, contentDescription = null) },
                                headlineContent = { Text(stringResource(R.string.privacy_title)) },
                                supportingContent = { Text(stringResource(R.string.privacy_body)) },
                            )
                        }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    if (engineDialog) {
        EngineDialog(
            selected = settings.engine,
            onSelect = { engine ->
                update { it.copy(engine = engine) }
                engineDialog = false
            },
            onDismiss = { engineDialog = false },
        )
    }
}

/**
 * The settings screen's pages: a menu, and one page per group of settings. Back goes to [parent];
 * [depth] says which way pages slide.
 */
private enum class SettingsPage(@param:StringRes val title: Int, val depth: Int = 1) {
    HOME(R.string.app_name, depth = 0),
    PERMISSIONS(R.string.page_permissions),
    INTEGRATIONS(R.string.page_integrations),

    /** One integration's page, titled with its name. */
    INTEGRATION(R.string.page_integrations, depth = 2),
    CONNECTIONS(R.string.page_connections),
    ROUTINES(R.string.page_routines),
    WAKE(R.string.section_wake),
    VOICE(R.string.section_voice),
    ASSISTANT(R.string.section_assistant_settings),
    SEARCH(R.string.section_search_settings),
    APPEARANCE(R.string.section_appearance),
    ABOUT(R.string.page_about),
    ;

    val parent: SettingsPage get() = if (this == INTEGRATION) INTEGRATIONS else HOME
}

/** One page: a collapsing title bar (with a back arrow below the menu) and its scrolling settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    onTryIt: (() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    scrolledContainerColor = colors.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            if (onTryIt != null) {
                ExtendedFloatingActionButton(
                    onClick = onTryIt,
                    icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    text = { Text(stringResource(R.string.try_it)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + if (onTryIt != null) 96.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A menu row that opens a page, with a one-line summary of what's set there. */
@Composable
private fun PageRow(icon: ImageVector, title: Int, summary: String, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
private fun HeroCard(assistantHeld: Boolean, onSetDefault: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val gradient = Brush.linearGradient(listOf(colors.primary, colors.tertiary))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .padding(24.dp),
    ) {
        Column {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.onPrimary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = colors.onPrimary)
            }
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                targetState = assistantHeld,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                label = "hero",
            ) { held ->
                Column {
                    Text(
                        text = stringResource(if (held) R.string.hero_ready_title else R.string.hero_setup_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(if (held) R.string.hero_ready_body else R.string.hero_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimary.copy(alpha = 0.9f),
                    )
                    if (!held) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onSetDefault,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.onPrimary, contentColor = colors.primary),
                        ) {
                            Text(stringResource(R.string.hero_setup_action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Group(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(title: Int) = SectionTitle(stringResource(title))

@Composable
internal fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp),
    )
}

@Composable
private fun StepRow(
    icon: ImageVector,
    title: String,
    body: String,
    done: Boolean?,
    action: String?,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val trailing: (@Composable () -> Unit)? = when {
        done == true -> {
            { Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.done), tint = colors.primary) }
        }
        action != null -> {
            { FilledTonalButton(onClick = onAction) { Text(action) } }
        }
        else -> null
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = trailing,
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: Int,
    body: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(body)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun EngineDialog(selected: SearchEngine, onSelect: (SearchEngine) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        title = { Text(stringResource(R.string.pref_engine_title)) },
        text = {
            Column {
                SearchEngine.entries.forEach { engine ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = engine == selected, role = Role.RadioButton) { onSelect(engine) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = engine == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(engine.label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
