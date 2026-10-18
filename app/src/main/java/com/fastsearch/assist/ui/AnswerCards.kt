package com.fastsearch.assist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bestassistant.calendar.CalendarEvent
import com.bestassistant.commands.AlarmTarget
import com.bestassistant.commands.BrightnessChange
import com.bestassistant.commands.ChanceKind
import com.bestassistant.commands.Command
import com.bestassistant.commands.EventTime
import com.bestassistant.commands.RingerMode
import com.bestassistant.device.Battery
import com.bestassistant.device.Brightness
import com.bestassistant.device.TimeText
import com.bestassistant.phone.PhoneCall
import com.fastsearch.R
import com.fastsearch.assist.AgendaUi
import com.fastsearch.assist.CallsUi
import com.fastsearch.assist.CurrencyUi
import com.fastsearch.assist.DefinitionUi
import com.fastsearch.fastSearch
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** The rows for the commands added with the clock controls, chance, calendar, phone settings, routines and calls. */
@Composable
internal fun describeMore(command: Command): CommandText {
    val context = LocalContext.current
    val app = context.fastSearch
    return when (command) {
        is Command.DismissAlarm -> CommandText(
            Icons.Rounded.AlarmOff,
            when (val target = command.target) {
                AlarmTarget.Ringing -> stringResource(R.string.cmd_alarm_dismiss)
                AlarmTarget.Next -> stringResource(R.string.cmd_alarm_next_off)
                AlarmTarget.All -> stringResource(R.string.cmd_alarms_off)
                is AlarmTarget.At -> stringResource(R.string.cmd_alarm_off, app.commands.alarmTime(target))
                is AlarmTarget.Labeled -> stringResource(R.string.cmd_alarm_off, target.label.replaceFirstChar(Char::titlecase))
            },
            stringResource(R.string.cmd_clock_app),
        )
        is Command.SnoozeAlarm -> CommandText(
            Icons.Rounded.Snooze,
            command.minutes?.let { stringResource(R.string.cmd_snooze_for, TimeText.duration(context, it * 60)) } ?: stringResource(R.string.cmd_snooze),
            stringResource(R.string.cmd_clock_app),
        )
        Command.StopTimer -> CommandText(Icons.Rounded.TimerOff, stringResource(R.string.cmd_stop_timer), stringResource(R.string.cmd_clock_app))
        is Command.Chance -> when (val kind = command.kind) {
            ChanceKind.Coin -> CommandText(Icons.Rounded.Toll, stringResource(R.string.cmd_coin), stringResource(R.string.cmd_coin_note))
            is ChanceKind.Dice -> CommandText(
                Icons.Rounded.Casino,
                if (kind.count == 1) stringResource(R.string.cmd_die) else stringResource(R.string.cmd_dice, kind.count),
                stringResource(R.string.cmd_range, 1, kind.sides),
            )
            is ChanceKind.Number -> CommandText(Icons.Rounded.Casino, stringResource(R.string.cmd_number), stringResource(R.string.cmd_range, kind.min, kind.max))
        }
        is Command.AddEvent -> {
            val time = command.time
            val subtitle = when {
                time == null -> stringResource(R.string.cmd_event_ask)
                !app.calendar.canWrite() -> listOf(eventWhen(time), stringResource(R.string.cmd_event_access)).joinToString(" · ")
                else -> eventWhen(time)
            }
            CommandText(
                Icons.Rounded.EventAvailable,
                command.title?.let { stringResource(R.string.cmd_add_event, it) } ?: stringResource(R.string.cmd_new_event),
                subtitle,
            )
        }
        is Command.Brightness -> {
            val change = command.change
            val title = when (change) {
                BrightnessChange.Up -> stringResource(R.string.cmd_brighter)
                BrightnessChange.Down -> stringResource(R.string.cmd_dimmer)
                is BrightnessChange.Set -> stringResource(R.string.cmd_brightness, change.percent)
                is BrightnessChange.Auto -> stringResource(if (change.on) R.string.cmd_brightness_auto_on else R.string.cmd_brightness_auto_off)
            }
            CommandText(
                when (change) {
                    BrightnessChange.Down -> Icons.Rounded.BrightnessLow
                    is BrightnessChange.Auto -> Icons.Rounded.BrightnessAuto
                    is BrightnessChange.Set -> if (change.percent < 40) Icons.Rounded.BrightnessLow else Icons.Rounded.BrightnessHigh
                    BrightnessChange.Up -> Icons.Rounded.BrightnessHigh
                },
                title,
                stringResource(if (Brightness.canChange(context)) R.string.cmd_screen else R.string.cmd_brightness_permission),
            )
        }
        is Command.Ringer -> when (command.mode) {
            RingerMode.SILENT -> CommandText(Icons.Rounded.NotificationsOff, stringResource(R.string.cmd_silent), stringResource(R.string.cmd_ringer))
            RingerMode.VIBRATE -> CommandText(Icons.Rounded.Vibration, stringResource(R.string.cmd_vibrate), stringResource(R.string.cmd_ringer))
            RingerMode.NORMAL -> CommandText(Icons.Rounded.NotificationsActive, stringResource(R.string.cmd_ring), stringResource(R.string.cmd_ringer))
        }
        is Command.Routine -> {
            val steps = app.routines.find(command.name)?.steps?.filter { it.isNotBlank() }.orEmpty()
            CommandText(
                Icons.Rounded.Checklist,
                stringResource(R.string.cmd_routine, command.name),
                pluralStringResource(R.plurals.cmd_routine_steps, steps.size, steps.size, steps.joinToString(", ")),
            )
        }
        is Command.CallBack -> {
            val call = remember(command) { if (command.redial) app.calls.lastOutgoing() else app.calls.toCallBack() }
            CommandText(
                Icons.Rounded.Call,
                when {
                    call == null -> stringResource(if (command.redial) R.string.cmd_redial_plain else R.string.cmd_call_back_plain)
                    command.redial -> stringResource(R.string.cmd_redial, call.who)
                    else -> stringResource(R.string.cmd_call_back, call.who)
                },
                when {
                    !app.calls.canRead() -> stringResource(R.string.cmd_calls_access)
                    call == null -> stringResource(R.string.cmd_no_calls)
                    else -> listOf(call.shownNumber, app.responder.moment(time(call.at))).distinct().joinToString(" · ")
                },
            )
        }
        else -> CommandText(Icons.Rounded.Event, "", "")
    }
}

@Composable
private fun eventWhen(time: EventTime): String {
    val context = LocalContext.current
    val today = LocalDate.now()
    val day = when (ChronoUnit.DAYS.between(today, time.date)) {
        0L -> stringResource(R.string.weather_today)
        1L -> stringResource(R.string.weather_tomorrow)
        else -> time.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    return when (time) {
        is EventTime.At -> "$day, ${TimeText.clock(context, time.hour, time.minute)}"
        is EventTime.AllDay -> stringResource(R.string.cmd_all_day, day)
    }
}

private fun time(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

@Composable
internal fun BatteryRow(command: Command.Battery, highlighted: Boolean, topHitBounds: BoundsHolder, actions: SearchActions) {
    val context = LocalContext.current
    // Kept current while the overlay is open: plugging in shows straight away.
    val state by produceState(Battery.read(context)) {
        while (true) {
            delay(2_000L)
            value = Battery.read(context)
        }
    }
    val battery = state
    val footnote = when {
        battery == null -> null
        battery.full -> stringResource(R.string.battery_full)
        battery.charging -> battery.minutesToFull?.let { stringResource(R.string.battery_charging_until, TimeText.duration(context, it * 60)) }
            ?: stringResource(R.string.battery_charging)
        else -> stringResource(R.string.battery_not_charging)
    }
    AnswerValueRow(
        icon = when {
            battery == null -> Icons.Rounded.BatteryFull
            battery.charging -> Icons.Rounded.BatteryChargingFull
            battery.percent <= 15 -> Icons.Rounded.BatteryAlert
            else -> Icons.Rounded.BatteryFull
        },
        caption = stringResource(R.string.battery_caption),
        value = battery?.let { "${it.percent}%" } ?: "—",
        footnote = footnote,
        highlighted = highlighted,
        topHitBounds = topHitBounds,
        onSpeak = { actions.runCommand(command, null, null) },
        onCopy = null,
    )
}

@Composable
internal fun DateRow(command: Command, highlighted: Boolean, topHitBounds: BoundsHolder, actions: SearchActions) {
    val context = LocalContext.current
    val responder = context.fastSearch.responder
    val today = LocalDate.now()
    val (caption, value, footnote) = when (command) {
        is Command.DaysUntil -> {
            val days = abs(ChronoUnit.DAYS.between(today, command.date)).toInt()
            val what = command.name ?: responder.shortDate(command.date, today)
            Triple(
                stringResource(if (command.since) R.string.days_since else R.string.days_until, what),
                if (days == 0) stringResource(R.string.weather_today) else pluralStringResource(R.plurals.days_count, days, days),
                responder.longDate(command.date, today),
            )
        }
        is Command.WhatDay -> Triple(
            command.name ?: responder.shortDate(command.date, today),
            command.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            responder.longDate(command.date, today),
        )
        else -> return
    }
    AnswerValueRow(
        icon = Icons.Rounded.Event,
        caption = caption,
        value = value,
        footnote = footnote,
        highlighted = highlighted,
        topHitBounds = topHitBounds,
        onSpeak = { actions.runCommand(command, null, null) },
        onCopy = null,
    )
}

@Composable
internal fun SpellRow(command: Command.Spell, highlighted: Boolean, topHitBounds: BoundsHolder, actions: SearchActions) {
    val letters = command.word.uppercase(Locale.getDefault()).filter { !it.isWhitespace() }.toList().joinToString(" ")
    AnswerValueRow(
        icon = Icons.Rounded.Spellcheck,
        caption = stringResource(R.string.spell_caption, command.word),
        value = letters,
        footnote = null,
        highlighted = highlighted,
        topHitBounds = topHitBounds,
        onSpeak = { actions.runCommand(command, null, null) },
        onCopy = { actions.copy(command.word) },
        valueLines = 3,
    )
}

@Composable
internal fun CurrencyCard(command: Command.Currency, ui: CurrencyUi, highlighted: Boolean, topHitBounds: BoundsHolder, actions: SearchActions) {
    val responder = LocalContext.current.fastSearch.responder
    when (ui) {
        is CurrencyUi.Ready -> AnswerValueRow(
            icon = Icons.Rounded.CurrencyExchange,
            caption = "${responder.money(command.amount)} ${command.from} =",
            value = "${responder.money(ui.result)} ${command.to}",
            footnote = stringResource(R.string.currency_attribution, ui.ratesDate.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))),
            highlighted = highlighted,
            topHitBounds = topHitBounds,
            onSpeak = { actions.runCommand(command, null, null) },
            onCopy = { actions.copy(responder.money(ui.result).replace(",", "")) },
        )
        is CurrencyUi.Loading -> LoadingCard(stringResource(R.string.currency_loading))
        is CurrencyUi.Unsupported -> NoteCard(stringResource(R.string.currency_unsupported, command.from, command.to))
        CurrencyUi.Unavailable -> NoteCard(stringResource(R.string.currency_unavailable))
    }
}

@Composable
internal fun DefinitionCard(command: Command.Define, ui: DefinitionUi, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    val bounds = remember { BoundsHolder() }
    when (ui) {
        is DefinitionUi.Loading -> LoadingCard(stringResource(R.string.definition_loading, ui.word))
        is DefinitionUi.NotFound -> NoteCard(stringResource(R.string.definition_not_found, ui.word))
        DefinitionUi.Unavailable -> NoteCard(stringResource(R.string.definition_unavailable))
        is DefinitionUi.Ready -> CardSurface(Modifier.clickable { actions.openUrl(ui.definition.pageUrl, bounds.rect) }.trackBounds(bounds)) {
            Text(
                ui.definition.word.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            ui.definition.senses.forEach { sense ->
                Spacer(Modifier.height(6.dp))
                Text(sense.partOfSpeech.lowercase(Locale.getDefault()), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Text(sense.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Attribution(Icons.Rounded.AutoStories, stringResource(R.string.definition_attribution)) { actions.runCommand(command, null, null) }
        }
    }
}

@Composable
internal fun AgendaCard(command: Command, ui: AgendaUi, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val responder = context.fastSearch.responder
    val today = LocalDate.now()
    when (ui) {
        AgendaUi.NeedsAccess -> AllowCard(stringResource(R.string.calendar_access_title), stringResource(R.string.calendar_access_body)) {
            actions.runCommand(command, null, null)
        }
        is AgendaUi.Ready -> CardSurface(Modifier) {
            val title = when (command) {
                is Command.Agenda -> when {
                    command.days > 1 && command.from == today -> stringResource(R.string.agenda_card_week)
                    command.days > 1 -> stringResource(R.string.agenda_card_from, responder.longDate(command.from, today))
                    command.from == today -> stringResource(R.string.weather_today)
                    command.from == today.plusDays(1) -> stringResource(R.string.weather_tomorrow)
                    else -> responder.longDate(command.from, today)
                }
                else -> stringResource(R.string.agenda_card_next)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            if (ui.events.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (command is Command.Agenda) R.string.agenda_card_empty else R.string.agenda_card_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            val manyDays = command is Command.Agenda && command.days > 1 || command == Command.NextEvent
            ui.events.take(12).forEach { event -> EventLine(event, manyDays, today) }
            Spacer(Modifier.height(8.dp))
            Attribution(Icons.Rounded.Event, stringResource(R.string.agenda_attribution)) { actions.runCommand(command, null, null) }
        }
    }
}

@Composable
private fun EventLine(event: CalendarEvent, showDay: Boolean, today: LocalDate) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.widthIn(min = 64.dp)) {
            if (showDay && event.day != today) {
                Text(
                    event.day.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                event.start?.let { TimeText.clock(context, it.hour, it.minute) } ?: stringResource(R.string.agenda_all_day),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            event.location?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun CallsCard(command: Command, ui: CallsUi, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    when (ui) {
        CallsUi.NeedsAccess -> AllowCard(stringResource(R.string.calls_access_title), stringResource(R.string.calls_access_body)) {
            actions.runCommand(command, null, null)
        }
        is CallsUi.Ready -> CardSurface(Modifier) {
            Text(
                if (ui.missed.isEmpty()) stringResource(R.string.calls_card_none) else pluralStringResource(R.plurals.calls_card_missed, ui.missed.size, ui.missed.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            val shown = ui.missed.ifEmpty { listOfNotNull(ui.last) }
            if (ui.missed.isEmpty() && ui.last != null) {
                Text(stringResource(R.string.calls_card_last), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            shown.forEach { call -> CallLine(call, actions) }
            Spacer(Modifier.height(8.dp))
            Attribution(Icons.AutoMirrored.Rounded.CallMissed, stringResource(R.string.calls_attribution)) { actions.runCommand(command, null, null) }
        }
    }
}

@Composable
private fun CallLine(call: PhoneCall, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    val responder = LocalContext.current.fastSearch.responder
    val bounds = remember { BoundsHolder() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { actions.openUrl("tel:${call.number}", bounds.rect) }
            .trackBounds(bounds)
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(call.who, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(responder.moment(time(call.at)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.action_call), tint = colors.primary, modifier = Modifier.size(20.dp))
    }
}

/** The rounded panel answer cards sit on. */
@Composable
private fun CardSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .then(modifier)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun LoadingCard(text: String) = CardSurface(Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoteCard(text: String) = CardSurface(Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AllowCard(title: String, body: String, onAllow: () -> Unit) = CardSurface(Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onAllow) { Text(stringResource(R.string.action_allow)) }
    }
}

@Composable
private fun Attribution(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onSpeak: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.weight(1f))
        ReadAloudButton(onSpeak)
    }
}
