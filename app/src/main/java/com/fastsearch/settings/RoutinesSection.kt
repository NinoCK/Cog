package com.fastsearch.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bestassistant.commands.Command
import com.bestassistant.commands.CommandParser
import com.bestassistant.routines.Routine
import com.bestassistant.routines.Routines
import com.fastsearch.R

/** The intro to routines, and a button to start one. */
@Composable
internal fun RoutinesIntro(routines: List<Routine>, store: Routines) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.Checklist, contentDescription = null) },
        headlineContent = {
            Text(stringResource(R.string.routines_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (routines.none { it.phrases.any { phrase -> phrase.trim().equals("good morning", ignoreCase = true) } }) {
            FilledTonalButton(onClick = { store.save(Routine.goodMorning) }) {
                Icon(Icons.Rounded.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.routines_add_morning))
            }
        }
        FilledTonalButton(onClick = { store.save(Routine.new()) }) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.routines_add))
        }
    }
}

/**
 * One routine: the phrases that run it, and its steps, one request per line. Each step says
 * whether PocketSearch understands it, so a routine never silently does nothing.
 */
@Composable
internal fun RoutineEditor(routine: Routine, store: Routines) {
    var phrases by remember(routine.id) { mutableStateOf(routine.phrases.joinToString(", ")) }
    var steps by remember(routine.id) { mutableStateOf(routine.steps.joinToString("\n")) }
    fun save() = store.save(
        routine.copy(
            phrases = phrases.split(',').map(String::trim).filter(String::isNotEmpty),
            steps = steps.lines().map(String::trim).filter(String::isNotEmpty),
        ),
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = phrases,
            onValueChange = {
                phrases = it
                save()
            },
            label = { Text(stringResource(R.string.routine_phrases)) },
            supportingText = { Text(stringResource(R.string.routine_phrases_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = steps,
            onValueChange = {
                steps = it
                save()
            },
            label = { Text(stringResource(R.string.routine_steps)) },
            supportingText = { Text(stringResource(R.string.routine_steps_hint)) },
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        val lines = steps.lines().map(String::trim).filter(String::isNotEmpty)
        lines.forEach { line -> StepCheck(line) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { store.delete(routine.id) }) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.routine_delete))
            }
        }
    }
}

/** Whether one step is a request PocketSearch knows. */
@Composable
private fun StepCheck(line: String) {
    val colors = MaterialTheme.colorScheme
    val command = remember(line) { CommandParser.parse(line) }
    val ok = command != null && command !is Command.Routine
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = if (ok) colors.primary else colors.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (ok) line else stringResource(R.string.routine_step_unknown, line),
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) colors.onSurfaceVariant else colors.error,
        )
    }
}
