package com.fastsearch.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bestassistant.integration.Actions
import com.bestassistant.integration.FieldKind
import com.bestassistant.integration.Integration
import com.bestassistant.integration.Report
import com.bestassistant.integration.Setting
import com.bestassistant.integration.SettingsStore
import com.bestassistant.integration.Tone
import com.fastsearch.R
import com.fastsearch.fastSearch
import kotlinx.coroutines.launch

/**
 * Who hears what the screen a settings button opened answered. Kept for the whole process, not
 * the screen: turning the phone while Spotify asks for the user's OK recreates the screen.
 */
private var pendingResult: (suspend (Int, Intent?) -> Unit)? = null

/** Every integration that comes with PocketSearch: installed or not, and how each is set up. */
@Composable
internal fun IntegrationList(onOpen: (Integration) -> Unit) {
    val integrations = LocalContext.current.fastSearch.integrations
    val installed by integrations.installed.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.integrations_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    integrations.all.forEach { integration ->
        val isInstalled = integration in installed
        val summary by integration.summary.collectAsStateWithLifecycle(initialValue = "")
        ListItem(
            modifier = Modifier.clickable { onOpen(integration) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(ImageVector.vectorResource(integration.icon), contentDescription = null) },
            headlineContent = { Text(integration.name) },
            supportingContent = { Text(if (isInstalled) summary else stringResource(R.string.integration_not_installed)) },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        )
    }
}

/** One integration's page: whether it's installed, and once it is, its own settings, group by group. */
internal fun LazyListScope.integrationPage(integration: Integration) {
    item(key = "install") { Group { InstallRow(integration) } }
    item(key = "settings") { IntegrationSettings(integration) }
}

@Composable
private fun InstallRow(integration: Integration) {
    val integrations = LocalContext.current.fastSearch.integrations
    val installed by integrations.installed.collectAsStateWithLifecycle()
    val isInstalled = integration in installed
    ListItem(
        modifier = Modifier.clickable { integrations.install(integration.id, !isInstalled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(ImageVector.vectorResource(integration.icon), contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.integration_installed)) },
        supportingContent = { Text(integration.description) },
        trailingContent = { Switch(checked = isInstalled, onCheckedChange = { integrations.install(integration.id, it) }) },
    )
}

@Composable
private fun IntegrationSettings(integration: Integration) {
    val context = LocalContext.current
    val integrations = context.fastSearch.integrations
    val installed by integrations.installed.collectAsStateWithLifecycle()
    if (integration !in installed) return
    val store = remember(integration) { integrations.store(integration.id) }
    val settings = remember(integration) { integration.settings() }

    // What settings' own checks depend on (what's typed, what Android allows, how it stands) can
    // change at any time: each of these redraws them.
    val version by store.changes.collectAsStateWithLifecycle()
    val summary by integration.summary.collectAsStateWithLifecycle(initialValue = "")
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val tick = listOf(version, summary, resumed).hashCode()

    // A settings button's screen, answered in the app's scope so leaving this one doesn't lose it.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val answer = pendingResult ?: return@rememberLauncherForActivityResult
        pendingResult = null
        context.fastSearch.appScope.launch { answer(result.resultCode, result.data) }
    }
    val actions = remember(context) {
        object : Actions {
            override val activity: Activity get() = context as Activity
            override fun start(intent: Intent) = context.startActivity(intent)
            override fun startForResult(intent: Intent, result: suspend (Int, Intent?) -> Unit) {
                pendingResult = result
                launcher.launch(intent)
            }
        }
    }

    // Settings before the first section, then each section, each in a group of its own.
    val groups = remember(settings) {
        buildList {
            var title: String? = null
            var current = mutableListOf<Setting>()
            for (setting in settings) {
                if (setting is Setting.Section) {
                    if (current.isNotEmpty() || title != null) add(title to current.toList())
                    title = setting.title
                    current = mutableListOf()
                } else {
                    current += setting
                }
            }
            if (current.isNotEmpty() || title != null) add(title to current.toList())
        }
    }
    Column {
        groups.forEach { (title, items) ->
            title?.let { SectionTitle(it) }
            Spacer(Modifier.height(12.dp))
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    items.forEach { setting -> SettingView(setting, store, actions, tick) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingView(setting: Setting, store: SettingsStore, actions: Actions, tick: Int) {
    val colors = MaterialTheme.colorScheme
    when (setting) {
        is Setting.Section -> Unit
        is Setting.Note -> Text(
            setting.text,
            style = if (setting.small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        is Setting.Field -> FieldView(setting, store)
        is Setting.Copy -> CopyRow(setting.label, setting.value)
        is Setting.Link -> {
            val context = LocalContext.current
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setting.url)).addCategory(Intent.CATEGORY_BROWSABLE)) },
                modifier = Modifier.padding(vertical = 4.dp),
            ) { Text(setting.label) }
        }
        is Setting.Check -> CheckView(setting, store, tick)
        is Setting.Action -> FilledTonalButton(
            onClick = { setting.run(actions) },
            enabled = remember(tick) { setting.enabled() },
            modifier = Modifier.padding(vertical = 4.dp),
        ) { Text(setting.label) }
        is Setting.Status -> {
            val report by setting.report.collectAsStateWithLifecycle(initialValue = null)
            report?.let { ReportText(it) }
        }
        is Setting.Permission -> PermissionRow(setting, actions, tick)
        is Setting.Entries -> EntriesView(setting)
    }
}

@Composable
private fun FieldView(setting: Setting.Field, store: SettingsStore) {
    val secret = setting.kind == FieldKind.SECRET
    var text by remember(setting.key) { mutableStateOf(store.get(setting.key, secret)) }
    val error = setting.error(text)
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.trim()
            store.set(setting.key, text, secret)
            setting.changed(text)
        },
        label = { Text(setting.label) },
        placeholder = setting.hint?.let { { Text(it) } },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = when (setting.kind) {
                FieldKind.ADDRESS -> KeyboardType.Uri
                FieldKind.SECRET -> KeyboardType.Password
                FieldKind.TEXT -> KeyboardType.Ascii
            },
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** The button, and what the check found: cleared as soon as anything it checked is edited. */
@Composable
private fun CheckView(setting: Setting.Check, store: SettingsStore, tick: Int) {
    val scope = rememberCoroutineScope()
    val version by store.changes.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<Report?>(null) }
    LaunchedEffect(version) { report = null }
    FilledTonalButton(
        enabled = !running && remember(tick) { setting.ready() },
        onClick = {
            running = true
            report = null
            scope.launch {
                report = setting.run()
                running = false
            }
        },
        modifier = Modifier.padding(vertical = 4.dp),
    ) { Text(setting.label) }
    when {
        running -> ReportText(Report(setting.running))
        else -> report?.let { ReportText(it) }
    }
}

@Composable
private fun ReportText(report: Report) {
    val colors = MaterialTheme.colorScheme
    Text(
        report.text,
        style = MaterialTheme.typography.bodyMedium,
        color = when (report.tone) {
            Tone.GOOD -> colors.primary
            Tone.BAD -> colors.error
            Tone.NEUTRAL -> colors.onSurfaceVariant
        },
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** A value to paste into another app or a website, with a copy button. */
@Composable
private fun CopyRow(label: String, value: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(
            onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
            },
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.integration_copy, label))
        }
    }
}

@Composable
private fun PermissionRow(setting: Setting.Permission, actions: Actions, tick: Int) {
    val allowed = remember(tick) { setting.allowed() }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(setting.title) },
        supportingContent = { Text(setting.body) },
        trailingContent = {
            if (allowed) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.done), tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = { actions.start(setting.open()) }) { Text(stringResource(R.string.action_allow)) }
            }
        },
    )
}

/** Things saved by name, each with a way to remove it, and a way to add more. */
@Composable
private fun EntriesView(setting: Setting.Entries) {
    val saved by setting.items.collectAsStateWithLifecycle(initialValue = emptyList())
    var adding by remember { mutableStateOf(false) }
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    Text(
        setting.intro,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    saved.forEach { entry ->
        ListItem(
            colors = transparent,
            headlineContent = { Text(entry.name) },
            supportingContent = { Text(entry.detail) },
            trailingContent = {
                IconButton(onClick = { setting.remove(entry) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.integration_entry_remove, entry.name))
                }
            },
        )
    }
    ListItem(
        modifier = Modifier.clickable { adding = true },
        colors = transparent,
        leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
        headlineContent = { Text(setting.add) },
    )
    if (adding) {
        AddEntryDialog(
            setting = setting,
            onSave = { name, value ->
                setting.save(name, value)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun AddEntryDialog(setting: Setting.Entries, onSave: (name: String, value: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    val value = setting.parse(valueText)
    val canSave = name.isNotBlank() && value != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
        title = { Text(setting.add) },
        text = {
            Column {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text(setting.value) },
                    supportingText = { Text(setting.valueHint) },
                    isError = valueText.isNotBlank() && value == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    trailingIcon = {
                        // Read only when tapped: the clipboard is the user's business otherwise.
                        IconButton(
                            onClick = {
                                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { valueText = it.trim() }
                            },
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.integration_entry_paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(setting.name) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value != null) onSave(name, value) }, enabled = canSave) {
                Text(stringResource(R.string.integration_entry_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.integration_entry_cancel)) } },
    )
}
