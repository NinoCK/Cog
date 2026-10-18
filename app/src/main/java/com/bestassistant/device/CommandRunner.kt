package com.bestassistant.device

import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.bestassistant.calendar.CalendarStore
import com.bestassistant.commands.AlarmTarget
import com.bestassistant.commands.BrightnessChange
import com.bestassistant.commands.ChanceKind
import com.bestassistant.commands.Command
import com.bestassistant.commands.CommandParser
import com.bestassistant.commands.EventTime
import com.bestassistant.commands.NavigationApp
import com.bestassistant.commands.RingerMode
import com.bestassistant.phone.CallHistory
import com.bestassistant.commands.ReminderTime
import com.bestassistant.commands.SavedPlace
import com.bestassistant.commands.MediaAction
import com.bestassistant.commands.MusicApp
import com.bestassistant.commands.MusicKind
import com.bestassistant.commands.SettingsPanel
import com.bestassistant.commands.Switch
import com.bestassistant.commands.VolumeChange
import com.bestassistant.integration.MusicPage
import com.bestassistant.integration.Step
import com.bestassistant.integrations.Integrations
import com.bestassistant.media.MediaControl
import com.bestassistant.parking.Parking
import com.bestassistant.reminders.Reminders
import com.bestassistant.speech.Person
import com.bestassistant.weather.DeviceLocation
import com.fastsearch.R
import com.fastsearch.data.AppSettings
import com.fastsearch.data.Contact
import com.fastsearch.data.NavigationChoice
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Runs commands with public platform APIs only: intents that the clock, camera, maps and
 * dialer apps understand, CameraManager for the torch, and AudioManager for volume and media.
 * No root. Requests for an integration ("turn on the kitchen light") are run by that integration.
 */
class CommandRunner(
    private val context: Context,
    /** Resources in the assistant's language. */
    private val strings: () -> Context,
    private val media: MediaControl,
    private val settings: () -> AppSettings,
    private val integrations: Integrations,
    private val location: DeviceLocation,
    private val reminders: Reminders,
    private val parking: Parking,
    private val calendar: CalendarStore,
    private val calls: CallHistory,
) {

    private fun s(id: Int, vararg args: Any): String = strings().getString(id, *args)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val torch = Torch(context)

    fun plan(command: Command, contact: Contact? = null): Step = when (command) {
        is Command.SetAlarm -> Step.Launch(
            listOf(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.replaceFirstChar(Char::titlecase)) } },
            ),
            s(R.string.done_alarm, TimeText.clock(context, command.hour, command.minute)),
        )
        is Command.SetTimer -> Step.Launch(
            listOf(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.replaceFirstChar(Char::titlecase)) } },
            ),
            s(R.string.done_timer, TimeText.duration(strings(), command.seconds)),
        )
        Command.ShowAlarms -> Step.Launch(listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        Command.ShowTimers -> Step.Launch(listOf(Intent(AlarmClock.ACTION_SHOW_TIMERS), Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        is Command.DismissAlarm -> dismissAlarm(command.target)
        is Command.SnoozeAlarm -> Step.Launch(
            listOf(Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply { command.minutes?.let { putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, it) } }),
            s(R.string.done_snooze),
        )
        // Only a ringing timer can be stopped from outside the clock app; otherwise its timers screen.
        Command.StopTimer -> Step.Launch(
            listOf(Intent(AlarmClock.ACTION_DISMISS_TIMER), Intent(AlarmClock.ACTION_SHOW_TIMERS)),
            s(R.string.done_timer_stopped),
        )
        is Command.Chance -> chance(command.kind)
        is Command.Brightness -> brightness(command.change)
        is Command.Ringer -> ringer(command.mode)
        is Command.AddEvent -> addEvent(command)
        is Command.CallBack -> callBack(command)
        // Run by the overlay, step by step.
        is Command.Routine -> Step.Done(null)
        is Command.Flashlight -> flashlight(command.state)
        is Command.Volume -> volume(command.change)
        is Command.Media -> media(command.action)
        is Command.Navigate -> navigate(command)
        is Command.WebSearch -> Step.Launch(
            listOf(Intent(Intent.ACTION_VIEW, Uri.parse(settings().engine.searchUrl(command.query))).addCategory(Intent.CATEGORY_BROWSABLE)),
        )
        is Command.YouTubeSearch -> Step.Launch(youTubeSearch(command.query))
        is Command.Remind -> remind(command)
        Command.ShowReminders -> showReminders()
        Command.CancelReminders -> {
            val count = reminders.cancelAll()
            val said = if (count == 0) s(R.string.say_no_reminders) else strings().resources.getQuantityString(R.plurals.say_reminders_cancelled, count, count)
            Step.Done(said, spoken = said)
        }
        Command.SaveParking -> if (location.hasPermission()) Step.Remote { saveParking() } else Step.Failed(s(R.string.error_location_permission))
        is Command.FindCar -> findCar(command)
        is Command.ShareLocation -> {
            val number = contact?.number
            when {
                contact == null || number == null -> Step.Failed(s(R.string.error_no_number))
                !location.hasPermission() -> Step.Failed(s(R.string.error_location_permission))
                else -> Step.Remote { shareLocation(contact.name, number) }
            }
        }
        is Command.Integration -> integrations[command.integration]?.plan(command.request) ?: Step.Failed(s(R.string.error_integration_removed))
        is Command.Camera -> Step.Launch(
            listOf(
                Intent(if (command.video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                Intent(if (command.video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE),
            ),
        )
        is Command.OpenPanel -> doNotDisturb(command) ?: Step.Launch(panelIntents(command.panel))
        is Command.DialNumber -> Step.Launch(listOf(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", command.number, null))))
        is Command.Call -> contact?.number?.let { number ->
            Step.Launch(listOf(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))))
        } ?: Step.Failed(s(R.string.error_no_number))
        is Command.Message -> contact?.number?.let { number ->
            Step.Launch(
                listOf(
                    Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                        .apply { command.body?.let { putExtra("sms_body", it) } },
                ),
            )
        } ?: Step.Failed(s(R.string.error_no_number))
        is Command.PlayMusic -> playMusic(command)
        Command.NowPlaying -> withPlayer { Step.Done(null) }
        Command.LikeSong -> withPlayer { if (media.like()) Step.Done(s(R.string.done_liked)) else Step.Failed(s(R.string.error_like)) }
        is Command.Shuffle -> withPlayer { media.shuffle(command.on); Step.Done(null) }
        is Command.Repeat -> withPlayer { media.repeat(command.on); Step.Done(null) }
        // Shown inline or launched as an app result: nothing to run here.
        is Command.OpenApp, is Command.Weather, is Command.Clock, is Command.Convert, is Command.Lookup,
        is Command.NameDays, is Command.NameDay, is Command.Battery, is Command.DaysUntil, is Command.WhatDay,
        is Command.Currency, is Command.Define, is Command.Spell, is Command.Agenda, Command.NextEvent, Command.MissedCalls,
        -> Step.Done(null)
    }

    // region Clock, chance

    /** The clock app's own "dismiss": it finds the alarm (or asks which one) and turns it off. */
    private fun dismissAlarm(target: AlarmTarget): Step {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        val confirmation = when (target) {
            AlarmTarget.Ringing -> s(R.string.done_alarm_dismissed)
            AlarmTarget.Next -> {
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                s(R.string.done_alarm_next_off)
            }
            AlarmTarget.All -> {
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                s(R.string.done_alarms_off)
            }
            is AlarmTarget.At -> {
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    .putExtra(AlarmClock.EXTRA_HOUR, target.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, target.minute)
                target.pm?.let { intent.putExtra(AlarmClock.EXTRA_IS_PM, it) }
                s(R.string.done_alarm_off, alarmTime(target))
            }
            is AlarmTarget.Labeled -> {
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, target.label)
                s(R.string.done_alarm_off, target.label)
            }
        }
        return Step.Launch(listOf(intent), confirmation)
    }

    /** "7:00 PM", or "7:00" when the user didn't say which. */
    fun alarmTime(target: AlarmTarget.At): String =
        if (target.pm == null) String.format(Locale.ROOT, "%d:%02d", target.hour, target.minute) else TimeText.clock(context, target.hour, target.minute)

    private fun chance(kind: ChanceKind): Step {
        val random = kotlin.random.Random.Default
        val (shown, said) = when (kind) {
            ChanceKind.Coin -> {
                val heads = random.nextBoolean()
                s(if (heads) R.string.coin_heads else R.string.coin_tails) to s(if (heads) R.string.say_coin_heads else R.string.say_coin_tails)
            }
            is ChanceKind.Dice -> {
                val rolls = List(kind.count) { random.nextInt(1, kind.sides + 1) }
                val total = rolls.sum()
                val shown = if (rolls.size == 1) "$total" else rolls.joinToString(" + ") + " = $total"
                shown to if (rolls.size == 1) {
                    s(R.string.say_dice, total)
                } else {
                    s(R.string.say_dice_many, list(rolls.map(Int::toString)), total)
                }
            }
            is ChanceKind.Number -> {
                val number = random.nextInt(kind.min, kind.max + 1)
                "$number" to s(R.string.say_number, number)
            }
        }
        return Step.Done(shown, spoken = said)
    }

    private fun list(items: List<String>): String =
        if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " ${s(R.string.say_and)} " + items.last()

    // endregion

    // region Phone settings

    /**
     * The brightness slider, through "Modify system settings". Without that permission yet, its
     * settings screen opens so the user can grant it once.
     */
    private fun brightness(change: BrightnessChange): Step {
        if (!Brightness.canChange(context)) {
            return Step.Launch(listOf(Brightness.permissionIntent(context)), spoken = s(R.string.say_brightness_permission))
        }
        if (change is BrightnessChange.Auto) {
            if (!Brightness.setAdaptive(context, change.on)) return Step.Failed(s(R.string.error_brightness))
            val said = s(if (change.on) R.string.say_brightness_auto_on else R.string.say_brightness_auto_off)
            return Step.Done(said, spoken = said)
        }
        val current = Brightness.percent(context) ?: 50
        val target = when (change) {
            BrightnessChange.Up -> (current + Brightness.STEP).coerceAtMost(100)
            BrightnessChange.Down -> (current - Brightness.STEP).coerceAtLeast(0)
            is BrightnessChange.Set -> change.percent
            is BrightnessChange.Auto -> current
        }
        val wasAdaptive = Brightness.set(context, target) ?: return Step.Failed(s(R.string.error_brightness))
        val said = listOfNotNull(
            s(R.string.say_brightness, target),
            if (wasAdaptive) s(R.string.say_brightness_adaptive_off) else null,
        ).joinToString(" ")
        return Step.Done(s(R.string.done_brightness, target), spoken = said)
    }

    private val notifications = context.getSystemService(NotificationManager::class.java)

    /**
     * Silent, vibrate or ring. Android lets apps with Do Not Disturb access (which media control
     * already gives) switch to silent; without it, that permission's screen opens.
     */
    private fun ringer(mode: RingerMode): Step {
        val wanted = when (mode) {
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
        val changed = runCatching { audio.ringerMode = wanted }.isSuccess && audio.ringerMode == wanted
        if (!changed) {
            return Step.Launch(listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)), spoken = s(R.string.say_ringer_permission))
        }
        val said = s(
            when (mode) {
                RingerMode.NORMAL -> R.string.say_ringer_normal
                RingerMode.VIBRATE -> R.string.say_ringer_vibrate
                RingerMode.SILENT -> R.string.say_ringer_silent
            },
        )
        return Step.Done(said, spoken = said)
    }

    /** "Turn on do not disturb": switched directly when PocketSearch has Do Not Disturb access; null opens the switch instead. */
    private fun doNotDisturb(command: Command.OpenPanel): Step? {
        if (command.panel != SettingsPanel.DND || command.requested == null) return null
        if (!notifications.isNotificationPolicyAccessGranted) return null
        val on = when (command.requested) {
            Switch.ON -> true
            Switch.OFF -> false
            Switch.TOGGLE -> notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        }
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        if (runCatching { notifications.setInterruptionFilter(filter) }.isFailure) return null
        val said = s(if (on) R.string.say_dnd_on else R.string.say_dnd_off)
        return Step.Done(said, spoken = said)
    }

    // endregion

    // region Calendar and calls

    /**
     * Adds the event to the user's main calendar, or asks what's missing ("What is it called?",
     * "When is it?"). Without calendar access, the calendar app's own new-event screen opens filled in.
     */
    private fun addEvent(command: Command.AddEvent): Step {
        val title = command.title ?: return Step.Ask(s(R.string.ask_event_what)) { answer ->
            when {
                isCancel(answer) -> cancelledEvent()
                else -> addEvent(command.copy(title = answer.trim().replaceFirstChar { it.titlecase(locale()) }))
            }
        }
        val time = command.time ?: return Step.Ask(s(R.string.ask_event_when)) { answer ->
            val at = CommandParser.eventTime(answer)
            when {
                at != null -> addEvent(command.copy(time = at))
                isCancel(answer) -> cancelledEvent()
                CommandParser.parse(answer) != null -> null
                else -> Step.Failed(s(R.string.error_event_time))
            }
        }
        val minutes = command.minutes ?: DEFAULT_EVENT_MINUTES
        val start = (time as? EventTime.At)?.let { it.date.atTime(it.hour, it.minute) }
        val day = (time as? EventTime.AllDay)?.date
        val whenText = eventWhen(time)
        if (!calendar.canWrite()) {
            return Step.Launch(listOf(calendar.insertIntent(title, start, day, minutes)), spoken = s(R.string.say_event_editor, title, whenText))
        }
        calendar.add(title, start, day, minutes)
            ?: return Step.Launch(listOf(calendar.insertIntent(title, start, day, minutes)), spoken = s(R.string.say_event_editor, title, whenText))
        return Step.Done(s(R.string.done_event, title), spoken = s(R.string.say_event_added, title, whenText))
    }

    private fun cancelledEvent(): Step = s(R.string.say_event_cancelled).let { Step.Done(it, spoken = it) }

    /** "tomorrow at 15:00", "on Friday", "on 3 March at 10:00". */
    fun eventWhen(time: EventTime): String {
        val now = LocalDateTime.now()
        return when (time) {
            is EventTime.At -> reminderWhen(null, time.date.atTime(time.hour, time.minute), now)
            is EventTime.AllDay -> {
                val days = ChronoUnit.DAYS.between(now.toLocalDate(), time.date)
                when {
                    days == 0L -> s(R.string.day_today)
                    days == 1L -> s(R.string.day_tomorrow)
                    days in 2..6 -> s(R.string.day_on, strings().resources.getStringArray(R.array.reminder_weekdays)[time.date.dayOfWeek.value - 1])
                    else -> s(R.string.day_on, time.date.format(DateTimeFormatter.ofPattern("d MMMM", locale())))
                }
            }
        }
    }

    /** Dials the last missed call (or the last one that came in), or with "redial" the last number called. */
    private fun callBack(command: Command.CallBack): Step {
        if (!calls.canRead()) return Step.Failed(s(R.string.error_call_log_permission))
        val call = (if (command.redial) calls.lastOutgoing() else calls.toCallBack())
            ?: return Step.Failed(s(if (command.redial) R.string.error_no_outgoing else R.string.error_no_calls))
        return Step.Launch(
            listOf(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", call.number, null))),
            spoken = s(if (command.redial) R.string.say_redial else R.string.say_call_back, call.who),
        )
    }

    // endregion

    /** YouTube's own search, or its results page (which the app opens too). */
    private fun youTubeSearch(query: String) = listOf(
        Intent(Intent.ACTION_SEARCH).setPackage(MusicApp.YOUTUBE.packageName).putExtra(SearchManager.QUERY, query),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))),
    )

    // region Reminders

    /**
     * Sets the reminder, or asks what's missing: "When should I remind you?" (and, for a bare
     * "remind me", what about first). The answer is read the way the whole request would be.
     */
    private fun remind(command: Command.Remind): Step {
        if (!reminders.canNotify()) return Step.Failed(s(R.string.error_reminder_notifications))
        val what = command.what
        val time = command.time ?: return if (what == null) {
            Step.Ask(s(R.string.ask_reminder_what)) { answer ->
                if (isCancel(answer)) cancelled() else remind(CommandParser.reminderAbout(answer))
            }
        } else {
            Step.Ask(s(R.string.ask_reminder_when)) { answer ->
                val at = CommandParser.reminderTime(answer)
                when {
                    at != null -> remind(Command.Remind(what, at))
                    isCancel(answer) -> cancelled()
                    // A new request instead of an answer.
                    CommandParser.parse(answer) != null -> null
                    else -> Step.Failed(s(R.string.error_reminder_time))
                }
            }
        }
        val now = LocalDateTime.now()
        val at = time.resolve(now)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val shown = what?.replaceFirstChar { it.titlecase(locale()) }
        reminders.add(millis, shown)
        val whenText = reminderWhen(time, at, now)
        val said = when {
            what == null -> s(R.string.say_reminder_plain, whenText)
            else -> s(R.string.say_reminder, whenText, Person.second(what))
        }
        val confirmation = s(R.string.done_reminder, TimeText.clock(context, at.hour, at.minute))
        return Step.Done(confirmation, spoken = said)
    }

    private fun showReminders(): Step {
        val upcoming = reminders.upcoming()
        if (upcoming.isEmpty()) return s(R.string.say_no_reminders).let { Step.Done(it, spoken = it) }
        val now = LocalDateTime.now()
        val items = upcoming.take(MAX_LISTED_REMINDERS).map { reminder ->
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminder.at), ZoneId.systemDefault())
            val whenText = reminderWhen(null, at, now)
            reminder.text?.let { s(R.string.say_reminder_item, whenText, Person.second(it)) } ?: whenText
        }
        val count = strings().resources.getQuantityString(R.plurals.say_reminders_count, upcoming.size, upcoming.size)
        val said = "$count ${items.joinToString("; ")}."
        return Step.Done(count.trimEnd(':'), spoken = said)
    }

    /** "in 20 minutes", "today at 17:00", "tomorrow at 9:00", "on Monday at 9:00", "on 21 May at 9:00". */
    private fun reminderWhen(time: ReminderTime?, at: LocalDateTime, now: LocalDateTime): String {
        if (time is ReminderTime.After && time.seconds < SECONDS_PER_DAY) {
            return s(R.string.reminder_when_in, TimeText.spokenDuration(strings(), time.seconds))
        }
        val clock = TimeText.clock(context, at.hour, at.minute)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        return when {
            days == 0L -> s(R.string.reminder_when_today, clock)
            days == 1L -> s(R.string.reminder_when_tomorrow, clock)
            days in 2..6 -> s(R.string.reminder_when_day, strings().resources.getStringArray(R.array.reminder_weekdays)[at.dayOfWeek.value - 1], clock)
            else -> s(R.string.reminder_when_date, at.format(DateTimeFormatter.ofPattern("d MMMM", locale())), clock)
        }
    }

    private fun isCancel(answer: String) = answer.trim().lowercase(locale()).trimEnd('.', '!') in CANCEL_WORDS

    private fun cancelled(): Step = s(R.string.say_reminder_cancelled).let { Step.Done(it, spoken = it) }

    private fun locale() = strings().resources.configuration.locales[0]

    // endregion

    // region Parking and location

    private suspend fun saveParking(): Step {
        val here = location.precise() ?: return Step.Failed(s(R.string.error_no_fix))
        parking.save(here)
        val said = if (here.hasAccuracy() && here.accuracy > ROUGH_METERS) s(R.string.say_parking_rough, here.accuracy.roundToInt()) else s(R.string.say_parking)
        return Step.Done(s(R.string.done_parking), spoken = said)
    }

    /** Walking directions back to the car in Google Maps, or the spot on a map. */
    private fun findCar(command: Command.FindCar): Step {
        val spot = parking.spot() ?: return Step.Failed(s(R.string.error_no_parking))
        val at = String.format(Locale.ROOT, "%.6f,%.6f", spot.latitude, spot.longitude)
        val map = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$at?q=$at(" + Uri.encode(s(R.string.parking_label)) + ")"))
        val minutes = ((System.currentTimeMillis() - spot.time) / 60_000L).toInt()
        val ago = when {
            minutes < 1 -> s(R.string.say_parked_just_now)
            minutes < 120 -> s(R.string.say_parked_ago, TimeText.spokenDuration(strings(), minutes * 60))
            // Hours only, after the first two.
            minutes < 24 * 60 -> s(R.string.say_parked_ago, TimeText.spokenDuration(strings(), minutes / 60 * 3600))
            else -> s(R.string.say_parked_on, Instant.ofEpochMilli(spot.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM", locale())))
        }
        if (!command.directions) return Step.Launch(listOf(map), spoken = s(R.string.say_car_here, ago))
        return Step.Launch(
            listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$at&mode=w")).setPackage(NavigationApp.GOOGLE_MAPS.packageName),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$at&travelmode=walking")),
                map,
            ),
            spoken = s(R.string.say_find_car, ago),
        )
    }

    /** A text message to [number] with a map link to where the phone is; the user sends it. */
    private suspend fun shareLocation(name: String, number: String): Step {
        val here = location.precise() ?: location.current() ?: return Step.Failed(s(R.string.error_no_fix))
        val link = String.format(Locale.ROOT, "https://maps.google.com/?q=%.5f,%.5f", here.latitude, here.longitude)
        val body = s(R.string.share_location_body, link)
        return Step.Launch(
            listOf(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body)),
            spoken = s(R.string.say_share_location, name),
        )
    }

    // endregion

    // region Directions

    /**
     * Turn-by-turn directions in the navigation app the user named or picked in settings (Google
     * Maps' "google.navigation:" link, Waze's deep link), or a plain map for "show ... on the map".
     * Home and work are the addresses saved in settings; without one, Waze's own saved Home or Work.
     */
    private fun navigate(command: Command.Navigate): Step {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(command.destination)))
        if (command.map) return Step.Launch(listOf(geo))
        val app = navigationApp(command.app)
        val place = command.place
        val address = place?.let(::savedAddress)
        if (place != null && address == null) {
            val waze = app == NavigationApp.WAZE || isInstalled(NavigationApp.WAZE.packageName)
            if (!waze) return Step.Failed(s(if (place == SavedPlace.HOME) R.string.error_no_home else R.string.error_no_work))
            val favorite = if (place == SavedPlace.HOME) "home" else "work"
            return Step.Launch(wazeIntents("favorite=$favorite&navigate=yes"))
        }
        val destination = Uri.encode(address ?: command.destination)
        val intents = when (app) {
            NavigationApp.WAZE -> wazeIntents("q=$destination&navigate=yes")
            NavigationApp.GOOGLE_MAPS -> listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$destination")).setPackage(app.packageName),
            )
            null -> emptyList()
        }
        return Step.Launch(intents + Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$destination")) + geo)
    }

    /** Waze's own link first, then its web link (which Waze also opens). */
    private fun wazeIntents(query: String) = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("waze://?$query")).setPackage(NavigationApp.WAZE.packageName),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?$query")).setPackage(NavigationApp.WAZE.packageName),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?$query")),
    )

    /** The app named in the request, else the one picked in settings, else the first installed. */
    private fun navigationApp(requested: NavigationApp?): NavigationApp? {
        val chosen = requested ?: when (settings().navigationApp) {
            NavigationChoice.GOOGLE_MAPS -> NavigationApp.GOOGLE_MAPS
            NavigationChoice.WAZE -> NavigationApp.WAZE
            NavigationChoice.AUTO -> null
        }
        if (chosen != null && isInstalled(chosen.packageName)) return chosen
        return listOf(NavigationApp.GOOGLE_MAPS, NavigationApp.WAZE).firstOrNull { isInstalled(it.packageName) }
    }

    private fun savedAddress(place: SavedPlace): String? = when (place) {
        SavedPlace.HOME -> settings().homeAddress
        SavedPlace.WORK -> settings().workAddress
    }.trim().takeIf { it.isNotEmpty() }

    // endregion

    // region Integrations

    /** Whether a spoken request nothing else understood may be offered to an integration ([tryIntegrations]). */
    val takesUnknown: Boolean get() = integrations.takesUnknown

    /**
     * A spoken request nothing else understood, offered to the installed integrations ("close the
     * living room blinds" for Home Assistant). Null when none understands it either.
     */
    suspend fun tryIntegrations(said: String): Step? = integrations.fallback(said)

    // endregion

    /** The music app to use when the user didn't name one: the first of the known ones that's installed. */
    fun defaultMusicApp(): MusicApp? = MUSIC_PREFERENCE.firstOrNull { isInstalled(it.packageName) }

    private fun isInstalled(packageName: String) =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false)

    /** Commands that act on the current player need media control and a player. */
    private inline fun withPlayer(run: () -> Step): Step = when {
        !media.hasAccess() -> Step.Failed(s(R.string.error_media_access))
        media.active() == null -> Step.Failed(s(R.string.error_no_player))
        else -> run()
    }

    /**
     * Always opens the music app, so the user sees it react. When an installed integration plays
     * music in that app (Spotify), it finds the app's own page for what was asked for (the artist,
     * song, album or a saved playlist), which is opened and started. Otherwise, and for other
     * apps, "search and play" (MEDIA_PLAY_FROM_SEARCH, what Google Assistant used) with what kind
     * of thing was asked for. If the app opens but doesn't start anything, its media session is asked directly.
     */
    private fun playMusic(command: Command.PlayMusic): Step {
        val app = command.app ?: defaultMusicApp()
        val search = searchIntent(command)
        val query = search.getStringExtra(SearchManager.QUERY).orEmpty()
        val shown = listOfNotNull(command.query, command.artist).joinToString(" ")
        val confirmation = if (app != null) s(R.string.done_play_music_app, shown, app.label) else s(R.string.done_play_music, shown)
        when (app) {
            null -> return Step.Launch(listOf(search), confirmation)
            // YouTube itself is video: open its search instead.
            MusicApp.YOUTUBE -> return Step.Launch(
                listOf(
                    Intent(Intent.ACTION_SEARCH).setPackage(app.packageName).putExtra(SearchManager.QUERY, query),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))),
                ),
                confirmation,
            )
            else -> Unit
        }
        val service = integrations.music(app.packageName)
        val intents = listOfNotNull(
            Intent(search).setPackage(app.packageName),
            // If the app ignores the play intent, at least land on its search.
            service?.search(query),
            search,
        )
        val before = media.snapshot(app.packageName)
        // Set by the lookup, read by the follow-up: the page that was opened, if one was found.
        var page: MusicPage? = null
        val followUp: suspend () -> Unit = {
            val opened = page
            if (service?.start(opened) != true) {
                media.makeSurePlaying(app.packageName, before, opened?.media?.let(Uri::parse), query, search.extras ?: Bundle())
            }
        }
        if (service == null) return Step.Launch(intents, confirmation, then = followUp)
        return Step.Launch(
            intents,
            confirmation,
            then = followUp,
            lookup = {
                val found = service.find(command.query, command.kind, command.artist)
                page = found
                listOfNotNull(found?.let(service::open))
            },
        )
    }

    /** "Search and play", with the kind of thing asked for so the app can pick an artist over a song. */
    private fun searchIntent(command: Command.PlayMusic): Intent {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        val (focus, query) = when (command.kind) {
            MusicKind.ANY -> "vnd.android.cursor.item/*" to command.query
            MusicKind.ARTIST -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, command.query)
                "vnd.android.cursor.item/artist" to command.query
            }
            MusicKind.SONG -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_TITLE, command.query)
                command.artist?.let { intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                "vnd.android.cursor.item/audio" to listOfNotNull(command.query, command.artist).joinToString(" ")
            }
            MusicKind.ALBUM -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_ALBUM, command.query)
                command.artist?.let { intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                "vnd.android.cursor.item/album" to listOfNotNull(command.query, command.artist).joinToString(" ")
            }
            MusicKind.PLAYLIST -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, command.query)
                "vnd.android.cursor.item/playlist" to command.query
            }
            MusicKind.LIKED -> {
                intent.putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, LIKED_SONGS)
                "vnd.android.cursor.item/playlist" to LIKED_SONGS
            }
        }
        return intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus).putExtra(SearchManager.QUERY, query)
    }

    val isTorchOn: Boolean get() = torch.isOn

    private fun flashlight(state: Switch): Step {
        val on = when (state) {
            Switch.ON -> true
            Switch.OFF -> false
            Switch.TOGGLE -> !torch.isOn
        }
        return if (torch.set(on)) {
            Step.Done(s(if (on) R.string.done_torch_on else R.string.done_torch_off))
        } else {
            Step.Failed(s(R.string.error_torch))
        }
    }

    private fun volume(change: VolumeChange): Step {
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        return try {
            when (change) {
                VolumeChange.Up -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
                VolumeChange.Down -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
                VolumeChange.Mute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                VolumeChange.Unmute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                is VolumeChange.Set -> {
                    val min = audio.getStreamMinVolume(stream)
                    val max = audio.getStreamMaxVolume(stream)
                    val index = (min + (max - min) * change.percent / 100.0).roundToInt().coerceIn(min, max)
                    audio.setStreamVolume(stream, index, flags)
                }
            }
            // The system volume panel is the confirmation.
            Step.Done(null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Volume change refused", e)
            Step.Failed(s(R.string.error_volume))
        }
    }

    private fun media(action: MediaAction): Step {
        // The active player's own controls when allowed; otherwise a media key, which Android
        // routes to the last player.
        val transport = when (action) {
            MediaAction.PLAY -> MediaControl.Transport.PLAY
            MediaAction.PAUSE -> MediaControl.Transport.PAUSE
            MediaAction.NEXT -> MediaControl.Transport.NEXT
            MediaAction.PREVIOUS -> MediaControl.Transport.PREVIOUS
        }
        val (code, message) = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY to R.string.done_play
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE to R.string.done_pause
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT to R.string.done_next
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to R.string.done_previous
        }
        if (!media.transport(transport)) {
            val time = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
        }
        return Step.Done(s(message))
    }

    private fun panelIntents(panel: SettingsPanel): List<Intent> = when (panel) {
        SettingsPanel.WIFI -> listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
        SettingsPanel.BLUETOOTH -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        SettingsPanel.INTERNET -> listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        SettingsPanel.AIRPLANE -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        SettingsPanel.NFC -> listOf(Intent(Settings.Panel.ACTION_NFC), Intent(Settings.ACTION_NFC_SETTINGS))
        SettingsPanel.HOTSPOT -> listOf(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        SettingsPanel.VOLUME -> listOf(Intent(Settings.Panel.ACTION_VOLUME), Intent(Settings.ACTION_SOUND_SETTINGS))
        SettingsPanel.BATTERY -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        SettingsPanel.DISPLAY -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        SettingsPanel.LOCATION -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        SettingsPanel.DND -> listOf(Intent("android.settings.ZEN_MODE_SETTINGS"), Intent(Settings.ACTION_SOUND_SETTINGS))
    }

    private companion object {
        const val TAG = "CommandRunner"
        const val MAX_LISTED_REMINDERS = 5
        const val SECONDS_PER_DAY = 24 * 3600

        /** A fix less exact than this is mentioned when saving where the car is. */
        const val ROUGH_METERS = 100f

        /** "Never mind" in answer to "When should I remind you?". */
        val CANCEL_WORDS = setOf("cancel", "never mind", "nevermind", "forget it", "no", "nothing", "stop")

        /** How long an event lasts when the user didn't say. */
        const val DEFAULT_EVENT_MINUTES = 60


        /** What Spotify and most players call the user's likes, for "search and play". */
        const val LIKED_SONGS = "Liked Songs"

        val MUSIC_PREFERENCE = listOf(
            MusicApp.SPOTIFY, MusicApp.YOUTUBE_MUSIC, MusicApp.DEEZER, MusicApp.APPLE_MUSIC,
            MusicApp.TIDAL, MusicApp.AMAZON_MUSIC, MusicApp.SOUNDCLOUD,
        )
    }
}

/** The back camera's flash, tracked through the system callback so "toggle" knows the state. */
private class Torch(context: Context) {
    private val camera = context.getSystemService(CameraManager::class.java)

    private val cameraId: String? by lazy {
        runCatching {
            val withFlash = camera.cameraIdList.filter {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        }.getOrNull()
    }

    @Volatile
    var isOn = false
        private set

    init {
        runCatching {
            camera.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(id: String, enabled: Boolean) {
                        if (id == cameraId) isOn = enabled
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    fun set(on: Boolean): Boolean {
        val id = cameraId ?: return false
        return runCatching { camera.setTorchMode(id, on) }
            .onSuccess { isOn = on }
            .onFailure { Log.w("Torch", "setTorchMode failed", it) }
            .isSuccess
    }
}
