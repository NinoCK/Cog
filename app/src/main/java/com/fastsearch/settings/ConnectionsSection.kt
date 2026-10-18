package com.fastsearch.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fastsearch.R
import com.fastsearch.data.AppSettings
import com.fastsearch.data.NavigationChoice

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
