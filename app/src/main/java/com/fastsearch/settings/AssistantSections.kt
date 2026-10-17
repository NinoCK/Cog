package com.fastsearch.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bestassistant.language.AssistantLanguage
import com.bestassistant.speech.VoiceGender
import com.bestassistant.speech.VoiceOption
import com.bestassistant.speech.VoiceStyle
import com.bestassistant.wake.WakeControl
import com.bestassistant.wake.WakeModel
import com.bestassistant.wake.WakePhrase
import com.fastsearch.R
import com.fastsearch.data.AppSettings
import com.fastsearch.fastSearch

/** Language: what the assistant understands, says and shows. Changing it recreates the screen. */
@Composable
internal fun LanguagePicker(selected: AssistantLanguage, onSelect: (AssistantLanguage) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Language, contentDescription = null, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.pref_language_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.pref_language_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        val languages = AssistantLanguage.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            languages.forEachIndexed { index, language ->
                SegmentedButton(
                    selected = selected == language,
                    onClick = { onSelect(language) },
                    shape = SegmentedButtonDefaults.itemShape(index, languages.size),
                ) { Text(stringResource(language.label), maxLines = 1) }
            }
        }
    }
}

/** The voice styles, each previewed by speaking a line in it. */
@Composable
internal fun VoiceSection(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val preview = stringResource(R.string.voice_preview_text)
    var voiceDialog by remember { mutableStateOf(false) }
    val speak = { context.fastSearch.speaker.speak(preview) }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wc, contentDescription = null, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.pref_voice_gender_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.pref_voice_gender_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        val genders = VoiceGender.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            genders.forEachIndexed { index, gender ->
                SegmentedButton(
                    selected = settings.voiceGender == gender,
                    onClick = {
                        // A picked base voice of the other kind would only be reshaped: go back to automatic.
                        update { it.copy(voiceGender = gender, systemVoice = null) }
                        speak()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, genders.size),
                ) { Text(stringResource(gender.label)) }
            }
        }
    }

    VoiceStyle.entries.forEach { style ->
        ListItem(
            modifier = Modifier.selectable(selected = settings.voiceStyle == style, role = Role.RadioButton) {
                update { it.copy(voiceStyle = style) }
                speak()
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { RadioButton(selected = settings.voiceStyle == style, onClick = null) },
            headlineContent = { Text(stringResource(style.label)) },
            supportingContent = { Text(stringResource(style.description)) },
            trailingContent = if (style == VoiceStyle.COG) {
                { Icon(Icons.Rounded.SmartToy, contentDescription = null) }
            } else {
                null
            },
        )
    }
    ListItem(
        modifier = Modifier.clickable { voiceDialog = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.pref_base_voice_title)) },
        supportingContent = { Text(settings.systemVoice?.let { voiceLabel(it) } ?: stringResource(R.string.base_voice_auto)) },
        trailingContent = { FilledTonalButton(onClick = speak) { Text(stringResource(R.string.action_preview)) } },
    )

    if (voiceDialog) {
        BaseVoiceDialog(
            selected = settings.systemVoice,
            onSelect = { name ->
                update { it.copy(systemVoice = name) }
                voiceDialog = false
                speak()
            },
            onDismiss = { voiceDialog = false },
        )
    }
}

@Composable
private fun voiceLabel(name: String): String {
    val context = LocalContext.current
    val voices by produceState(emptyList<VoiceOption>()) { value = context.fastSearch.speaker.voices() }
    val index = voices.indexOfFirst { it.name == name }
    if (index < 0) return name
    return optionLabel(voices[index], index)
}

@Composable
private fun optionLabel(voice: VoiceOption, index: Int): String {
    val region = voice.locale.displayCountry.ifEmpty { voice.locale.displayName }
    val label = when (voice.isMale) {
        true -> R.string.base_voice_item_male
        false -> R.string.base_voice_item_female
        null -> R.string.base_voice_item
    }
    return stringResource(label, region, index + 1)
}

@Composable
private fun BaseVoiceDialog(selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    var measuring by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val speaker = context.fastSearch.speaker
        voices = speaker.voices()
        // Engines don't say which voices are male: listen to each one once (it's remembered).
        speaker.measureVoices { voices = it }
        measuring = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null) },
        title = { Text(stringResource(R.string.pref_base_voice_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.pref_base_voice_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                if (measuring && !voices.isNullOrEmpty()) {
                    Text(stringResource(R.string.base_voice_measuring), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                val list = voices
                when {
                    list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    list.isEmpty() -> Text(stringResource(R.string.base_voice_none))
                    else -> {
                        RadioRow(stringResource(R.string.base_voice_auto), selected == null) { onSelect(null) }
                        list.forEachIndexed { index, voice ->
                            RadioRow(optionLabel(voice, index), selected == voice.name) { onSelect(voice.name) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Everything the wake word needs, in the order the user sets it up. */
@Composable
internal fun WakeSection(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    model: WakeModel.State,
    status: WakeControl.Status,
    unknownWords: List<String>,
    micGranted: Boolean,
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    onEnable: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var phraseDialog by remember { mutableStateOf(false) }

    val statusText = when {
        !settings.wakeWord -> stringResource(R.string.pref_wake_body, settings.wakePhrase)
        status == WakeControl.Status.LISTENING -> stringResource(R.string.wake_status_listening, settings.wakePhrase)
        status == WakeControl.Status.LOADING -> stringResource(R.string.wake_status_loading)
        status == WakeControl.Status.PAUSED -> stringResource(R.string.wake_status_paused)
        status == WakeControl.Status.FAILED -> stringResource(R.string.wake_status_failed)
        else -> stringResource(R.string.pref_wake_body, settings.wakePhrase)
    }
    ListItem(
        modifier = Modifier.clickable { if (settings.wakeWord) update { it.copy(wakeWord = false) } else onEnable() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.Hearing, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.pref_wake_title)) },
        supportingContent = { Text(statusText) },
        trailingContent = {
            androidx.compose.material3.Switch(
                checked = settings.wakeWord,
                onCheckedChange = { on -> if (on) onEnable() else update { it.copy(wakeWord = false) } },
            )
        },
    )
    ListItem(
        modifier = Modifier.clickable { phraseDialog = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.pref_wake_phrase_title)) },
        supportingContent = {
            val unknown = unknownWords.firstOrNull()
            if (unknown != null) {
                Text(stringResource(R.string.wake_problem_unknown, unknown), color = colors.error)
            } else {
                Text("“${settings.wakePhrase}”")
            }
        },
    )
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.wake_model_title)) },
        supportingContent = {
            Column {
                Text(
                    when (model) {
                        WakeModel.State.Missing -> stringResource(R.string.wake_model_missing)
                        is WakeModel.State.Downloading -> stringResource(R.string.wake_model_downloading, (model.progress * 100).toInt())
                        WakeModel.State.Ready -> stringResource(R.string.wake_model_ready)
                        WakeModel.State.Failed -> stringResource(R.string.wake_model_failed)
                    },
                )
                if (model is WakeModel.State.Downloading) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        trailingContent = when (model) {
            WakeModel.State.Missing -> {
                { FilledTonalButton(onClick = { context.fastSearch.wakeModel.download() }) { Text(stringResource(R.string.action_download)) } }
            }
            WakeModel.State.Failed -> {
                { FilledTonalButton(onClick = { context.fastSearch.wakeModel.download() }) { Text(stringResource(R.string.action_retry)) } }
            }
            else -> null
        },
    )
    if (settings.wakeWord) {
        if (!micGranted) {
            PermissionRow(R.string.step_mic_title, R.string.step_mic_body, onEnable)
        }
        if (!overlayGranted) {
            PermissionRow(R.string.step_overlay_title, R.string.step_overlay_body) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        if (!notificationsGranted) {
            PermissionRow(R.string.step_notifications_title, R.string.step_notifications_body, onRequestNotifications)
        }
    }
    Text(
        stringResource(R.string.wake_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    if (phraseDialog) {
        WakePhraseDialog(
            current = settings.wakePhrase,
            onSave = { phrase ->
                update { it.copy(wakePhrase = phrase) }
                phraseDialog = false
            },
            onDismiss = { phraseDialog = false },
        )
    }
}

@Composable
private fun PermissionRow(title: Int, body: Int, onAllow: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(body)) },
        trailingContent = { FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.action_allow)) } },
    )
}

@Composable
private fun WakePhraseDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val problem = WakePhrase.validate(text)
    val message = when (problem) {
        WakePhrase.Problem.EMPTY -> R.string.wake_problem_empty
        WakePhrase.Problem.TOO_SHORT -> R.string.wake_problem_short
        WakePhrase.Problem.TOO_LONG -> R.string.wake_problem_long
        WakePhrase.Problem.NOT_ENGLISH -> R.string.wake_problem_english
        null -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Hearing, contentDescription = null) },
        title = { Text(stringResource(R.string.pref_wake_phrase_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    singleLine = true,
                    isError = message != null,
                    supportingText = { Text(stringResource(message ?: R.string.wake_phrase_help)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = problem == null) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
