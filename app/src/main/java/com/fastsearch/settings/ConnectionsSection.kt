package com.fastsearch.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bestassistant.automation.HomeAssistant
import com.bestassistant.automation.Tasker
import com.fastsearch.R
import com.fastsearch.data.AppSettings
import com.fastsearch.data.NavigationChoice
import com.fastsearch.fastSearch
import kotlinx.coroutines.launch

/** "Take me home": which app gives directions, and where home and work are. */
@Composable
internal fun NavigationSection(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var home by remember { mutableStateOf(settings.homeAddress) }
    var work by remember { mutableStateOf(settings.workAddress) }
    Intro(Icons.Rounded.Directions, R.string.navigation_intro)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.pref_navigation_app), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        val choices = NavigationChoice.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = settings.navigationApp == choice,
                    onClick = { update { it.copy(navigationApp = choice) } },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                ) {
                    Text(
                        stringResource(
                            when (choice) {
                                NavigationChoice.AUTO -> R.string.navigation_auto
                                NavigationChoice.GOOGLE_MAPS -> R.string.navigation_google_maps
                                NavigationChoice.WAZE -> R.string.navigation_waze
                            },
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AddressField(home, R.string.pref_home_address) { text ->
            home = text
            update { it.copy(homeAddress = text) }
        }
        AddressField(work, R.string.pref_work_address) { text ->
            work = text
            update { it.copy(workAddress = text) }
        }
    }
}

@Composable
private fun AddressField(value: String, label: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(R.string.pref_address_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Whether Cog can run the user's Tasker tasks, and the one thing to do next if not. */
@Composable
internal fun TaskerSection() {
    val context = LocalContext.current
    val tasker = context.fastSearch.tasker
    var status by remember { mutableStateOf(tasker.status()) }
    LifecycleResumeEffect(Unit) {
        status = tasker.status()
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { status = tasker.status() }
    val colors = MaterialTheme.colorScheme

    Intro(Icons.Rounded.AutoMode, R.string.tasker_intro)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(
                when (status) {
                    Tasker.Status.READY -> R.string.tasker_status_ready
                    Tasker.Status.NOT_INSTALLED -> R.string.tasker_status_missing
                    Tasker.Status.NO_PERMISSION -> R.string.tasker_status_permission
                    Tasker.Status.DISABLED -> R.string.tasker_status_disabled
                    Tasker.Status.NO_EXTERNAL_ACCESS -> R.string.tasker_status_access
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == Tasker.Status.READY) colors.primary else colors.onSurfaceVariant,
        )
        when (status) {
            Tasker.Status.NO_PERMISSION -> {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val activity = context as ComponentActivity
                        val prefs = context.fastSearch.preferences
                        if (prefs.askedForTasker && !activity.shouldShowRequestPermissionRationale(Tasker.PERMISSION_RUN_TASKS)) {
                            // Denied for good: the app's permission page is the only place left to allow it.
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } else {
                            prefs.askedForTasker = true
                            permission.launch(Tasker.PERMISSION_RUN_TASKS)
                        }
                    },
                ) { Text(stringResource(R.string.action_allow)) }
            }
            Tasker.Status.NO_EXTERNAL_ACCESS, Tasker.Status.DISABLED -> {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        runCatching { context.startActivity(tasker.externalAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .recoverCatching {
                                tasker.installedPackage()?.let { pkg ->
                                    context.packageManager.getLaunchIntentForPackage(pkg)?.let(context::startActivity)
                                }
                            }
                    },
                ) { Text(stringResource(R.string.tasker_open_prefs)) }
            }
            else -> Unit
        }
    }
}

/** The user's own Home Assistant: its address, a long-lived token, and a check that both work. */
@Composable
internal fun HomeAssistantSection(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var url by remember { mutableStateOf(settings.homeAssistantUrl) }
    var token by remember { mutableStateOf(settings.homeAssistantToken) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HomeAssistant.Reply?>(null) }
    var seen by remember { mutableStateOf<HomeAssistant.Seen?>(null) }

    Intro(Icons.Rounded.Lightbulb, R.string.ha_intro)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { text ->
                url = text.trim()
                result = null
                update { it.copy(homeAssistantUrl = url) }
            },
            label = { Text(stringResource(R.string.ha_url)) },
            placeholder = { Text(stringResource(R.string.ha_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { text ->
                token = text.trim()
                result = null
                update { it.copy(homeAssistantToken = token) }
            },
            label = { Text(stringResource(R.string.ha_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            enabled = !checking && url.isNotBlank() && token.isNotBlank(),
            onClick = {
                checking = true
                scope.launch {
                    seen = null
                    result = context.fastSearch.homeAssistant.check()
                    if (result is HomeAssistant.Reply.Done) seen = context.fastSearch.homeAssistant.seen()
                    checking = false
                }
            },
        ) { Text(stringResource(R.string.ha_test)) }
        val message = when {
            checking -> R.string.ha_status_checking
            url.isBlank() || token.isBlank() -> R.string.ha_status_not_set
            else -> when (result) {
                is HomeAssistant.Reply.Done -> R.string.ha_status_ok
                HomeAssistant.Reply.Unauthorized -> R.string.ha_status_unauthorized
                HomeAssistant.Reply.Unreachable, HomeAssistant.Reply.NotSetUp -> R.string.ha_status_unreachable
                else -> null
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = when (result) {
                    is HomeAssistant.Reply.Done -> colors.primary
                    HomeAssistant.Reply.Unauthorized, HomeAssistant.Reply.Unreachable -> colors.error
                    else -> colors.onSurfaceVariant
                },
            )
        }
        seen?.takeIf { result is HomeAssistant.Reply.Done && !checking }?.let { found ->
            // What Cog can control, so a device it doesn't find can be told apart from one it misheard.
            val kinds = found.counts.entries.take(10).joinToString(" · ") { (domain, count) -> "$domain $count" }
            val acs = if (found.airConditioners.isEmpty()) stringResource(R.string.ha_seen_no_acs)
            else stringResource(R.string.ha_seen_acs, found.airConditioners.take(8).joinToString(", "))
            val areas = found.areas?.let { stringResource(R.string.ha_seen_areas, it) } ?: stringResource(R.string.ha_seen_no_areas)
            Spacer(Modifier.height(8.dp))
            Text(
                listOf(stringResource(R.string.ha_seen, kinds), acs, areas).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ha_fallback_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Intro(icon: androidx.compose.ui.graphics.vector.ImageVector, text: Int) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
