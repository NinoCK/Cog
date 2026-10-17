package com.fastsearch.assist.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bestassistant.commands.Command
import com.bestassistant.commands.MediaAction
import com.bestassistant.commands.SettingsPanel
import com.bestassistant.commands.Switch
import com.bestassistant.commands.VolumeChange
import com.bestassistant.commands.WeatherDay
import com.bestassistant.device.TimeText
import com.bestassistant.speech.WeatherWords
import com.bestassistant.weather.DayForecast
import com.bestassistant.weather.Forecast
import com.fastsearch.R
import com.fastsearch.assist.WeatherUi
import com.fastsearch.assist.WikiUi
import com.fastsearch.data.Contact
import com.fastsearch.fastSearch
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a command reads in the results: what it will do, and where. */
private data class CommandText(val icon: ImageVector, val title: String, val subtitle: String)

@Composable
private fun describe(command: Command, contact: Contact?): CommandText {
    val context = LocalContext.current
    return when (command) {
        is Command.SetAlarm -> {
            val now = LocalDateTime.now()
            var at = now.withHour(command.hour).withMinute(command.minute).withSecond(0).withNano(0)
            if (!at.isAfter(now)) at = at.plusDays(1)
            val minutes = Duration.between(now, at).toMinutes().toInt().coerceAtLeast(1)
            CommandText(
                Icons.Rounded.Alarm,
                stringResource(R.string.cmd_alarm, TimeText.clock(context, command.hour, command.minute)),
                listOfNotNull(command.label?.replaceFirstChar(Char::titlecase), stringResource(R.string.cmd_alarm_in, TimeText.duration(context, minutes * 60)))
                    .joinToString(" · "),
            )
        }
        Command.ShowAlarms -> CommandText(Icons.Rounded.Alarm, stringResource(R.string.cmd_show_alarms), stringResource(R.string.cmd_clock_app))
        is Command.SetTimer -> CommandText(
            Icons.Rounded.Timer,
            stringResource(R.string.cmd_timer, TimeText.duration(context, command.seconds)),
            command.label?.replaceFirstChar(Char::titlecase) ?: stringResource(R.string.cmd_clock_app),
        )
        Command.ShowTimers -> CommandText(Icons.Rounded.Timer, stringResource(R.string.cmd_show_timers), stringResource(R.string.cmd_clock_app))
        is Command.Flashlight -> {
            val turnOn = when (command.state) {
                Switch.ON -> true
                Switch.OFF -> false
                Switch.TOGGLE -> !context.fastSearch.commands.isTorchOn
            }
            CommandText(
                if (turnOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                stringResource(if (turnOn) R.string.cmd_torch_on else R.string.cmd_torch_off),
                stringResource(R.string.cmd_device),
            )
        }
        is Command.Volume -> when (val change = command.change) {
            VolumeChange.Up -> CommandText(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.cmd_volume_up), stringResource(R.string.cmd_media_volume))
            VolumeChange.Down -> CommandText(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.cmd_volume_down), stringResource(R.string.cmd_media_volume))
            VolumeChange.Mute -> CommandText(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.cmd_mute), stringResource(R.string.cmd_media_volume))
            VolumeChange.Unmute -> CommandText(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.cmd_unmute), stringResource(R.string.cmd_media_volume))
            is VolumeChange.Set -> CommandText(
                if (change.percent == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                stringResource(R.string.cmd_volume_set, change.percent),
                stringResource(R.string.cmd_media_volume),
            )
        }
        is Command.Media -> when (command.action) {
            MediaAction.PLAY -> CommandText(Icons.Rounded.PlayArrow, stringResource(R.string.cmd_play), stringResource(R.string.cmd_media))
            MediaAction.PAUSE -> CommandText(Icons.Rounded.Pause, stringResource(R.string.cmd_pause), stringResource(R.string.cmd_media))
            MediaAction.NEXT -> CommandText(Icons.Rounded.SkipNext, stringResource(R.string.cmd_next), stringResource(R.string.cmd_media))
            MediaAction.PREVIOUS -> CommandText(Icons.Rounded.SkipPrevious, stringResource(R.string.cmd_previous), stringResource(R.string.cmd_media))
        }
        is Command.PlayMusic -> CommandText(
            Icons.Rounded.LibraryMusic,
            stringResource(R.string.cmd_play_music, command.query.split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::titlecase) }),
            command.app?.label ?: context.fastSearch.commands.defaultMusicApp()?.label ?: stringResource(R.string.cmd_music_app),
        )
        Command.NowPlaying, Command.LikeSong, is Command.Shuffle, is Command.Repeat -> {
            val media = context.fastSearch.media
            val subtitle = when {
                !media.hasAccess() -> stringResource(R.string.cmd_media_needs_access)
                else -> media.nowPlaying()?.let { listOfNotNull(it.title, it.artist).joinToString(" · ") }
                    ?: stringResource(R.string.cmd_media)
            }
            when (command) {
                Command.LikeSong -> CommandText(Icons.Rounded.Favorite, stringResource(R.string.cmd_like), subtitle)
                is Command.Shuffle -> CommandText(
                    if (command.on) Icons.Rounded.ShuffleOn else Icons.Rounded.Shuffle,
                    stringResource(if (command.on) R.string.cmd_shuffle_on else R.string.cmd_shuffle_off),
                    subtitle,
                )
                is Command.Repeat -> CommandText(
                    if (command.on) Icons.Rounded.RepeatOn else Icons.Rounded.Repeat,
                    stringResource(if (command.on) R.string.cmd_repeat_on else R.string.cmd_repeat_off),
                    subtitle,
                )
                else -> CommandText(Icons.Rounded.MusicNote, stringResource(R.string.cmd_now_playing), subtitle)
            }
        }
        is Command.Navigate -> CommandText(
            Icons.Rounded.Directions,
            stringResource(R.string.cmd_navigate, command.destination.replaceFirstChar(Char::titlecase)),
            stringResource(R.string.cmd_maps_app),
        )
        is Command.Camera -> CommandText(
            if (command.video) Icons.Rounded.Videocam else Icons.Rounded.PhotoCamera,
            stringResource(if (command.video) R.string.cmd_video else R.string.cmd_photo),
            stringResource(R.string.cmd_camera_app),
        )
        is Command.OpenPanel -> {
            val (icon, name) = panelInfo(command.panel)
            val label = stringResource(name)
            val title = when (command.requested) {
                Switch.ON -> stringResource(R.string.cmd_panel_on, label)
                Switch.OFF -> stringResource(R.string.cmd_panel_off, label)
                else -> stringResource(R.string.cmd_panel, label)
            }
            val subtitle = if (command.requested != null) R.string.cmd_panel_note else R.string.cmd_settings_app
            CommandText(icon, title, stringResource(subtitle))
        }
        is Command.DialNumber -> CommandText(Icons.Rounded.Call, stringResource(R.string.cmd_call, command.number), stringResource(R.string.cmd_phone_app))
        is Command.Call -> CommandText(
            Icons.Rounded.Call,
            stringResource(R.string.cmd_call, contact?.name ?: command.who),
            contact?.number.orEmpty(),
        )
        is Command.Message -> CommandText(
            Icons.Rounded.Sms,
            stringResource(R.string.cmd_message, contact?.name ?: command.who),
            command.body?.let { "“$it”" } ?: contact?.number.orEmpty(),
        )
        // Shown by their own rows (answers, weather card, Wikipedia card, app grid).
        is Command.Convert, is Command.Clock, is Command.OpenApp, is Command.Weather, is Command.Lookup ->
            CommandText(Icons.Rounded.Schedule, "", "")
    }
}

private fun panelInfo(panel: SettingsPanel): Pair<ImageVector, Int> = when (panel) {
    SettingsPanel.WIFI -> Icons.Rounded.Wifi to R.string.panel_wifi
    SettingsPanel.BLUETOOTH -> Icons.Rounded.Bluetooth to R.string.panel_bluetooth
    SettingsPanel.INTERNET -> Icons.Rounded.NetworkCell to R.string.panel_internet
    SettingsPanel.AIRPLANE -> Icons.Rounded.AirplanemodeActive to R.string.panel_airplane
    SettingsPanel.NFC -> Icons.Rounded.Nfc to R.string.panel_nfc
    SettingsPanel.HOTSPOT -> Icons.Rounded.WifiTethering to R.string.panel_hotspot
    SettingsPanel.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp to R.string.panel_volume
    SettingsPanel.BATTERY -> Icons.Rounded.BatterySaver to R.string.panel_battery
    SettingsPanel.DISPLAY -> Icons.Rounded.BrightnessMedium to R.string.panel_display
    SettingsPanel.LOCATION -> Icons.Rounded.LocationOn to R.string.panel_location
    SettingsPanel.DND -> Icons.Rounded.DoNotDisturbOn to R.string.panel_dnd
}

@Composable
internal fun CommandRow(
    command: Command,
    contact: Contact?,
    highlighted: Boolean,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
) {
    when (command) {
        is Command.Convert -> return ConversionRow(command, highlighted, topHitBounds, actions)
        is Command.Clock -> return ClockRow(command, highlighted, topHitBounds, actions)
        else -> Unit
    }
    val colors = MaterialTheme.colorScheme
    val text = describe(command, contact)
    val bounds = remember { BoundsHolder() }
    val holder = if (highlighted) topHitBounds else bounds
    ListRow(
        highlighted = highlighted,
        bounds = holder,
        onClick = { actions.runCommand(command, contact, holder.rect) },
        leading = {
            if (contact != null) ContactAvatar(contact) else IconBadge(text.icon, colors.primary, colors.onPrimary)
        },
        trailing = {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    ) {
        Column {
            Text(
                text = text.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (text.subtitle.isNotEmpty()) {
                Text(
                    text = text.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small "read it to me" button for answer cards. */
@Composable
private fun ReadAloudButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Rounded.RecordVoiceOver,
            contentDescription = stringResource(R.string.action_read_aloud),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Shared layout for inline answers: a small caption above a large value. Tap reads it out; copy is a button. */
@Composable
private fun AnswerValueRow(
    icon: ImageVector,
    caption: String,
    value: String,
    footnote: String?,
    highlighted: Boolean,
    topHitBounds: BoundsHolder,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ListRow(
        highlighted = highlighted,
        bounds = if (highlighted) topHitBounds else null,
        onClick = onSpeak,
        leading = { IconBadge(icon, colors.primary, colors.onPrimary) },
        trailing = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
        },
    ) {
        Column {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            footnote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ConversionRow(command: Command.Convert, highlighted: Boolean, topHitBounds: BoundsHolder, actions: SearchActions) {
    val c = command.conversion
    AnswerValueRow(
        icon = Icons.Rounded.Straighten,
        caption = "${c.formattedAmount()} ${c.from.symbol} =",
        value = "${c.formattedResult()} ${c.to.symbol}",
        footnote = null,
        highlighted = highlighted,
        topHitBounds = topHitBounds,
        onSpeak = { actions.runCommand(command, null, null) },
        onCopy = { actions.copy(c.plainResult) },
    )
}

@Composable
private fun ClockRow(command: Command.Clock, highlighted: Boolean, topHitBounds: BoundsHolder, actions: SearchActions) {
    val context = LocalContext.current
    val zone = command.zone ?: ZoneId.systemDefault()
    // Tick so the time on screen stays right while the overlay is open.
    val now by produceState(ZonedDateTime.now(zone), zone) {
        while (true) {
            value = ZonedDateTime.now(zone)
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    val offset = if (command.zone != null) {
        val hours = (zone.rules.getOffset(now.toInstant()).totalSeconds -
            ZoneId.systemDefault().rules.getOffset(now.toInstant()).totalSeconds) / 3600.0
        when {
            hours == 0.0 -> stringResource(R.string.clock_same_time)
            else -> stringResource(R.string.clock_offset, (if (hours > 0) "+" else "−") + formatHours(abs(hours)))
        }
    } else {
        null
    }
    val time = TimeText.clock(context, now)
    val date = TimeText.date(now)
    AnswerValueRow(
        icon = if (command.zone == null) Icons.Rounded.Schedule else Icons.Rounded.Public,
        caption = command.place ?: stringResource(R.string.clock_here),
        value = time,
        footnote = listOfNotNull(date, offset).joinToString(" · "),
        highlighted = highlighted,
        topHitBounds = topHitBounds,
        onSpeak = { actions.runCommand(command, null, null) },
        onCopy = { actions.copy("$time, $date") },
    )
}

private fun formatHours(hours: Double): String =
    if (hours % 1.0 == 0.0) "${hours.toInt()} h" else String.format(Locale.getDefault(), "%.1f h", hours)

// region Wikipedia

@Composable
internal fun WikiCard(command: Command.Lookup, wiki: WikiUi, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    val bounds = remember { BoundsHolder() }
    val article = (wiki as? WikiUi.Ready)?.article
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainerHighest.copy(alpha = 0.6f))
            .then(if (article?.pageUrl != null) Modifier.clickable { actions.openUrl(article.pageUrl, bounds.rect) } else Modifier)
            .trackBounds(bounds)
            .padding(16.dp),
    ) {
        when (wiki) {
            is WikiUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.wiki_loading, wiki.topic), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            is WikiUi.NotFound -> Text(stringResource(R.string.wiki_not_found, wiki.topic), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            WikiUi.Unavailable -> Text(stringResource(R.string.wiki_unavailable), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            is WikiUi.Ready -> {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(
                            wiki.article.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        wiki.article.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            wiki.article.extract,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    wiki.article.imageUrl?.let { url ->
                        Spacer(Modifier.width(12.dp))
                        WebImage(url, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.wiki_attribution),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    ReadAloudButton { actions.runCommand(command, null, null) }
                }
            }
        }
    }
}

@Composable
private fun WebImage(url: String, modifier: Modifier) {
    val images = LocalImageSource.current
    val bitmap by produceState(images.cachedWebImage(url), url) { if (value == null) value = images.webImage(url) }
    bitmap?.let {
        androidx.compose.foundation.Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

// endregion

// region Weather

private fun degrees(value: Double) = "${value.roundToInt()}°"

@Composable
internal fun WeatherCard(command: Command.Weather, weather: WeatherUi, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainerHighest.copy(alpha = 0.6f))
            .then(if (weather is WeatherUi.Ready) Modifier.clickable { actions.runCommand(command, null, null) } else Modifier)
            .padding(16.dp),
    ) {
        when (weather) {
            is WeatherUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = weather.place?.let { stringResource(R.string.weather_loading_place, it.replaceFirstChar(Char::titlecase)) }
                        ?: stringResource(R.string.weather_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            is WeatherUi.NeedsLocation -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.weather_location_title), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    Text(stringResource(R.string.weather_location_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = actions::requestLocationPermission) { Text(stringResource(R.string.action_allow)) }
            }
            is WeatherUi.NotFound -> Text(
                stringResource(R.string.weather_not_found, weather.place),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            is WeatherUi.NoFix -> Text(
                stringResource(R.string.weather_no_fix),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            is WeatherUi.Unavailable -> Text(
                stringResource(R.string.weather_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            is WeatherUi.Ready -> ForecastContent(weather.forecast, weather.day) { actions.runCommand(command, null, null) }
        }
    }
}

@Composable
private fun ForecastContent(forecast: Forecast, day: WeatherDay, onSpeak: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val place = forecast.place ?: stringResource(R.string.weather_here)
    val tomorrow = forecast.days.getOrNull(1).takeIf { day == WeatherDay.TOMORROW }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (tomorrow != null) WeatherWords.emoji(tomorrow.code) else WeatherWords.emoji(forecast.code, forecast.isDay),
            fontSize = 44.sp,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (tomorrow != null) "${degrees(tomorrow.max)} / ${degrees(tomorrow.min)}" else degrees(forecast.temperature),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(WeatherWords.condition(tomorrow?.code ?: forecast.code)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 160.dp)) {
            Text(
                text = if (tomorrow != null) stringResource(R.string.weather_tomorrow) else stringResource(R.string.weather_now),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
            Text(place, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(8.dp))
    val details = if (tomorrow != null) {
        listOfNotNull(tomorrow.precipitation?.let { stringResource(R.string.weather_rain_chance, it) })
    } else {
        listOfNotNull(
            stringResource(R.string.weather_feels_like, degrees(forecast.feelsLike)),
            stringResource(R.string.weather_wind, forecast.wind.roundToInt(), forecast.windUnit),
            stringResource(R.string.weather_humidity, forecast.humidity),
            forecast.days.firstOrNull()?.precipitation?.let { stringResource(R.string.weather_rain_chance, it) },
        )
    }
    if (details.isNotEmpty()) {
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        forecast.days.take(if (day == WeatherDay.WEEK) 7 else 5).forEachIndexed { index, d -> DayCell(d, index) }
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.weather_attribution),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            modifier = Modifier.weight(1f),
        )
        ReadAloudButton(onSpeak)
    }
}

@Composable
private fun DayCell(day: DayForecast, index: Int) {
    val colors = MaterialTheme.colorScheme
    val label = when (index) {
        0 -> stringResource(R.string.weather_today)
        else -> day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(WeatherWords.emoji(day.code), fontSize = 22.sp)
        Text(degrees(day.max), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        Text(degrees(day.min), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

// endregion

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ExamplesRow(onPick: (String) -> Unit) {
    val examples = listOf(
        R.string.example_music, R.string.example_alarm, R.string.example_weather, R.string.example_wiki,
        R.string.example_timer, R.string.example_torch, R.string.example_time, R.string.example_convert,
        R.string.example_call, R.string.example_media,
    ).map { stringResource(it) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        examples.forEach { example ->
            SuggestionChip(onClick = { onPick(example) }, label = { Text(example) })
        }
    }
}
