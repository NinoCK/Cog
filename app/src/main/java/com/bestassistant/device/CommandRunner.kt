package com.bestassistant.device

import android.app.KeyguardManager
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
import com.bestassistant.automation.GroupPlanner
import com.bestassistant.automation.HomeAssistant
import com.bestassistant.automation.QueryPlanner
import com.bestassistant.automation.SettingPlanner
import com.bestassistant.automation.Tasker
import com.bestassistant.commands.Command
import com.bestassistant.commands.CommandParser
import com.bestassistant.commands.Condition
import com.bestassistant.commands.DeviceKind
import com.bestassistant.commands.DeviceQuery
import com.bestassistant.commands.DeviceSetting
import com.bestassistant.commands.DeviceVerb
import com.bestassistant.commands.HomeAction
import com.bestassistant.commands.HomeGroup
import com.bestassistant.commands.HomeSettings
import com.bestassistant.commands.NumberUnit
import com.bestassistant.commands.NavigationApp
import com.bestassistant.commands.Quantity
import com.bestassistant.commands.ReminderTime
import com.bestassistant.commands.SavedPlace
import com.bestassistant.commands.MediaAction
import com.bestassistant.commands.MusicApp
import com.bestassistant.commands.MusicKind
import com.bestassistant.commands.SettingsPanel
import com.bestassistant.commands.Switch
import com.bestassistant.commands.VolumeChange
import com.bestassistant.media.MediaControl
import com.bestassistant.media.SavedPlaylists
import com.bestassistant.media.SpotifyCatalog
import com.bestassistant.media.SpotifyPress
import com.bestassistant.media.SpotifyRemote
import com.bestassistant.parking.Parking
import com.bestassistant.reminders.Reminders
import com.bestassistant.speech.Person
import com.bestassistant.weather.DeviceLocation
import com.fastsearch.R
import com.fastsearch.data.AppSettings
import com.fastsearch.data.Contact
import com.fastsearch.data.NavigationChoice
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** What running a command needs from the UI. */
sealed interface Step {
    /**
     * Start the first of [intents] that resolves, then confirm with [confirmation]. [then] runs in
     * the background afterwards (e.g. starting the song once the music app is up).
     */
    data class Launch(
        val intents: List<Intent>,
        val confirmation: String? = null,
        val then: (suspend () -> Unit)? = null,
        /** Better intents found online (e.g. the artist's Spotify page), tried before [intents]. */
        val lookup: (suspend () -> List<Intent>)? = null,
        /** Replaces the usual reply, for one that depends on what was found ("You parked 2 hours ago"). */
        val spoken: String? = null,
    ) : Step

    /** Already done in-process. [spoken] replaces the usual reply (e.g. Home Assistant's own answer). */
    data class Done(val confirmation: String?, val spoken: String? = null) : Step

    /** Needs a moment over the network (Home Assistant); [run] says how it went. */
    data class Remote(val run: suspend () -> Step) : Step

    /**
     * A question back ("Which one? Living room, bedroom."): [question] is said, and what the user
     * says next goes to [answer]; null from it means the reply wasn't an answer, but a new request.
     */
    data class Ask(val question: String, val answer: suspend (String) -> Step?) : Step

    data class Failed(val message: String) : Step
}

/**
 * Runs commands with public platform APIs only: intents that the clock, camera, maps and
 * dialer apps understand, CameraManager for the torch, and AudioManager for volume and media.
 * No root; the one accessibility service ([SpotifyPress]) is optional and only taps Spotify's play button.
 */
class CommandRunner(
    private val context: Context,
    /** Resources in the assistant's language. */
    private val strings: () -> Context,
    private val media: MediaControl,
    private val catalog: SpotifyCatalog,
    private val playlists: SavedPlaylists,
    private val spotify: SpotifyRemote,
    private val settings: () -> AppSettings,
    private val tasker: Tasker,
    private val home: HomeAssistant,
    private val location: DeviceLocation,
    private val reminders: Reminders,
    private val parking: Parking,
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
        is Command.RunTask -> runTask(command)
        is Command.SmartHome -> if (home.isSetUp) Step.Remote { smartHome(command) } else Step.Failed(s(R.string.error_ha_not_set_up))
        is Command.Camera -> Step.Launch(
            listOf(
                Intent(if (command.video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                Intent(if (command.video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE),
            ),
        )
        is Command.OpenPanel -> Step.Launch(panelIntents(command.panel))
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
        is Command.NameDays, is Command.NameDay,
        -> Step.Done(null)
    }

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

    // region Tasker and Home Assistant

    private fun runTask(command: Command.RunTask): Step = when (tasker.status()) {
        Tasker.Status.NOT_INSTALLED -> Step.Failed(s(R.string.error_tasker_missing))
        Tasker.Status.NO_PERMISSION -> Step.Failed(s(R.string.error_tasker_permission))
        Tasker.Status.DISABLED -> Step.Failed(s(R.string.error_tasker_disabled))
        Tasker.Status.NO_EXTERNAL_ACCESS -> Step.Failed(s(R.string.error_tasker_access))
        Tasker.Status.READY -> {
            val name = tasker.resolve(command.name)
            if (tasker.run(name)) Step.Done(s(R.string.done_task, name), spoken = s(R.string.say_task, name)) else Step.Failed(s(R.string.error_tasker_missing))
        }
    }

    private suspend fun smartHome(command: Command.SmartHome): Step {
        command.query?.let { query -> homeQuery(query)?.let { return it } }
        command.group?.let { group -> homeGroup(group)?.let { return it } }
        // "Turn on the desk AC", "set it to cooling": Cog's own, from the devices Home Assistant
        // lists and what each says it can do. Assist only gets what none of them fits.
        command.setting?.let { setting -> homeSetting(setting)?.let { return it } }
        return when (val reply = home.process(command.text, "en")) {
            is HomeAssistant.Reply.Done -> assistDone(reply)
            else -> homeFailure(reply)
        }
    }

    /** Assist's own sentence, with the devices it acted on when the sentence doesn't name them. */
    private fun assistDone(reply: HomeAssistant.Reply.Done): Step.Done {
        val speech = reply.speech ?: s(R.string.say_ha_done)
        val unnamed = reply.targets.filterNot { speech.contains(it, ignoreCase = true) }
        val said = if (unnamed.isEmpty()) speech else s(R.string.ha_assist_did, speech, unnamed.take(5).joinToString(", "))
        return Step.Done(said, spoken = said)
    }

    private fun homeFailure(reply: HomeAssistant.Reply): Step.Failed = when (reply) {
        is HomeAssistant.Reply.Refused -> Step.Failed(reply.speech ?: s(R.string.error_ha_not_understood))
        HomeAssistant.Reply.NotSetUp -> Step.Failed(s(R.string.error_ha_not_set_up))
        HomeAssistant.Reply.Unauthorized -> Step.Failed(s(R.string.error_ha_unauthorized))
        HomeAssistant.Reply.Unreachable -> Step.Failed(s(R.string.error_ha_unreachable))
        is HomeAssistant.Reply.Done, HomeAssistant.Reply.NotUnderstood -> Step.Failed(s(R.string.error_ha_not_understood))
    }

    /**
     * "Turn off all the lights (in the kitchen)": every such device Home Assistant lists, switched
     * through its services. Null leaves the sentence to Assist instead (an area it doesn't list).
     */
    private suspend fun homeGroup(group: HomeGroup): Step? {
        val devices = when (val found = home.devices()) {
            is HomeAssistant.Devices.Found -> found
            is HomeAssistant.Devices.Failed -> return homeFailure(found.reply)
        }
        fun inArea(area: HomeAssistant.Area?, sentence: String) = area?.let { s(R.string.ha_in_area, it.name, sentence) } ?: sentence
        return when (val plan = GroupPlanner.plan(group, devices.entities, devices.areas)) {
            GroupPlanner.Plan.UnknownArea -> null
            is GroupPlanner.Plan.NoDevices -> Step.Failed(inArea(plan.area, s(R.string.ha_no_devices, s(kindName(group.kind)))))
            is GroupPlanner.Plan.AlreadyDone -> inArea(plan.area, s(R.string.ha_already)).let { Step.Done(it, spoken = it) }
            is GroupPlanner.Plan.Act -> {
                for (call in plan.calls) {
                    val reply = home.call(call.domain, call.service, call.entityIds)
                    if (reply !is HomeAssistant.Reply.Done) return homeFailure(reply)
                }
                val count = strings().resources.getQuantityString(kindCount(group.kind), plan.count, plan.count)
                inArea(plan.area, s(didAction(group.action), count)).let { Step.Done(it, spoken = it) }
            }
        }
    }

    /**
     * "Set the bedroom AC to 22", "put the air purifier in sleep mode": the device and setting
     * matched against what Home Assistant says each device can do. Null leaves it to Assist.
     */
    private suspend fun homeSetting(setting: DeviceSetting): Step? {
        val devices = when (val found = home.devices()) {
            is HomeAssistant.Devices.Found -> found
            is HomeAssistant.Devices.Failed -> return homeFailure(found.reply)
        }
        return homePlan(setting, SettingPlanner.plan(setting, devices.entities, devices.areas), asked = false)
    }

    /** What a [plan] for [setting] comes to: done, a question back, or why not. Null leaves it to Assist. */
    private suspend fun homePlan(setting: DeviceSetting, plan: SettingPlanner.Plan, asked: Boolean): Step? {
        return when (plan) {
            SettingPlanner.Plan.NotMine -> null
            is SettingPlanner.Plan.NotFound -> Step.Failed(s(R.string.ha_not_found, plan.said))
            is SettingPlanner.Plan.Ambiguous -> {
                // Rooms are easier to say than device names, when each is in a different one.
                val areas = plan.areas.filterNotNull()
                val choices = if (areas.size == plan.devices.size && areas.toSet().size == areas.size) areas else plan.devices
                val question = s(R.string.ha_which, choices.take(5).joinToString(", "))
                if (asked) Step.Failed(question) else Step.Ask(question) { answer -> whichOne(setting, plan.devices, answer) }
            }
            is SettingPlanner.Plan.NoSuchSetting -> Step.Failed(
                if (plan.offers.isEmpty()) s(R.string.ha_no_settings, plan.device)
                else s(R.string.ha_no_setting, plan.device, plan.offers.joinToString("; ", transform = ::offerText)),
            )
            is SettingPlanner.Plan.OutOfRange -> Step.Failed(
                s(R.string.ha_out_of_range, plan.device, settingName(plan.aspect, plan.label), number(plan.min, plan.unit), number(plan.max, plan.unit)),
            )
            is SettingPlanner.Plan.CantDo -> Step.Failed(s(R.string.ha_cant_do, plan.device))
            is SettingPlanner.Plan.NeedsCode -> Step.Failed(s(R.string.ha_needs_code, plan.device))
            is SettingPlanner.Plan.Act -> {
                val verb = (plan.value as? SettingPlanner.Shown.Did)?.verb
                if (verb == DeviceVerb.UNLOCK || verb == DeviceVerb.DISARM) return confirmUnsafe(plan, verb)
                act(plan)
            }
        }
    }

    /**
     * Unlocking a door or disarming the alarm: only with the phone itself unlocked (the wake word
     * works on the lock screen, for anyone in earshot), and only after a spoken "yes".
     */
    private fun confirmUnsafe(plan: SettingPlanner.Plan.Act, verb: DeviceVerb): Step {
        val names = plan.devices.joinToString(", ")
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return Step.Failed(s(R.string.ha_phone_locked))
        val question = s(if (verb == DeviceVerb.UNLOCK) R.string.ha_confirm_unlock else R.string.ha_confirm_disarm, names)
        return Step.Ask(question) { answer ->
            val said = answer.trim().lowercase(locale()).trimEnd('.', '!')
            when {
                said in YES_WORDS -> act(plan)
                CommandParser.parse(answer) != null -> null
                else -> s(if (verb == DeviceVerb.UNLOCK) R.string.ha_kept_locked else R.string.ha_kept_armed, names).let { Step.Done(it, spoken = it) }
            }
        }
    }

    /** Runs a [plan]'s calls and says what changed. */
    private suspend fun act(plan: SettingPlanner.Plan.Act): Step {
        for (call in plan.calls) {
            val reply = home.call(call.domain, call.service, call.entityIds, call.data)
            if (reply !is HomeAssistant.Reply.Done) return homeFailure(reply)
        }
        val names = plan.devices.joinToString(", ")
        val shown = when (val value = plan.value) {
            is SettingPlanner.Shown.Number -> number(value.value, value.unit)
            is SettingPlanner.Shown.Option -> option(value.option)
            is SettingPlanner.Shown.Switch -> s(if (value.on) R.string.ha_value_on else R.string.ha_value_off)
            is SettingPlanner.Shown.Step -> s(if (value.up) R.string.ha_value_up else R.string.ha_value_down)
            is SettingPlanner.Shown.Power, is SettingPlanner.Shown.Did -> null
        }
        val said = when (val value = plan.value) {
            is SettingPlanner.Shown.Power -> s(if (value.on) R.string.ha_power_on else R.string.ha_power_off, names)
            is SettingPlanner.Shown.Did -> did(value, names)
            else -> s(R.string.ha_set, names, settingName(plan.aspect, plan.label), shown.orEmpty())
        }
        return Step.Done(said, spoken = said)
    }

    private fun did(value: SettingPlanner.Shown.Did, names: String): String = when (value.verb) {
        DeviceVerb.START -> s(R.string.ha_did_start, names)
        DeviceVerb.STOP -> s(R.string.ha_did_stop, names)
        DeviceVerb.PAUSE -> s(R.string.ha_did_pause, names)
        DeviceVerb.DOCK -> s(R.string.ha_did_dock, names)
        DeviceVerb.LOCATE -> s(R.string.ha_did_locate, names)
        DeviceVerb.PRESS -> s(R.string.ha_did_press, names)
        DeviceVerb.LOCK -> s(R.string.ha_did_lock, names)
        DeviceVerb.UNLOCK -> s(R.string.ha_did_unlock, names)
        DeviceVerb.OPEN -> s(R.string.ha_did_open, names)
        DeviceVerb.CLOSE -> s(R.string.ha_did_close, names)
        DeviceVerb.ARM -> s(R.string.ha_did_arm, names, armedWay(value.mode))
        DeviceVerb.DISARM -> s(R.string.ha_did_disarm, names)
    }

    private fun armedWay(mode: String?): String = when (mode) {
        "home" -> s(R.string.ha_arm_home)
        "night" -> s(R.string.ha_arm_night)
        "vacation" -> s(R.string.ha_arm_vacation)
        else -> s(R.string.ha_arm_away)
    }

    /**
     * "What's the temperature in the bedroom?", "is the front door locked?": answered from the
     * devices and sensors Home Assistant lists. Null leaves the question to Assist.
     */
    private suspend fun homeQuery(query: DeviceQuery): Step? {
        val found = when (val result = home.devices(sensors = true)) {
            is HomeAssistant.Devices.Found -> result
            is HomeAssistant.Devices.Failed -> return homeFailure(result.reply)
        }
        fun inArea(area: HomeAssistant.Area?, sentence: String) = area?.let { s(R.string.ha_in_area, it.name, sentence) } ?: sentence
        val said = when (val answer = QueryPlanner.answer(query, found.entities, found.areas)) {
            QueryPlanner.Answer.NotMine -> return null
            is QueryPlanner.Answer.Measured -> inArea(answer.area, readings(answer.quantity, answer.readings))
            is QueryPlanner.Answer.Checked -> inArea(answer.area, checked(answer))
            is QueryPlanner.Answer.Status -> {
                val state = stateText(answer.device)
                answer.battery?.let { s(R.string.ha_status_battery, answer.device.name, state, readingText(it)) }
                    ?: s(R.string.ha_status, answer.device.name, state)
            }
        }
        return Step.Done(said, spoken = said)
    }

    private fun readings(quantity: Quantity, readings: List<QueryPlanner.Reading>): String {
        val numbers = readings.mapNotNull { it.value }
        val units = readings.map { it.unit }.distinct()
        // Many sensors in the house: their average.
        if (readings.size > MAX_READINGS && numbers.size == readings.size && units.size == 1) {
            val average = QueryPlanner.Reading("", numbers.average(), "", units.single())
            return s(R.string.ha_reading_average, s(quantityName(quantity)), readingText(average), readings.size)
        }
        if (readings.size == 1) return s(R.string.ha_reading, readings.single().device, readingText(readings.single()))
        return s(R.string.ha_readings, readings.take(MAX_READINGS).joinToString("; ") { "${it.device}: ${readingText(it)}" })
    }

    /** "22.5 °C", "45%", "120 W"; text as it is when it isn't a number. */
    private fun readingText(reading: QueryPlanner.Reading): String {
        val value = reading.value ?: return listOfNotNull(reading.text.replace('_', ' '), reading.unit).joinToString(" ")
        val text = NumberFormat.getNumberInstance(locale()).apply { maximumFractionDigits = 1 }.format(value)
        val unit = reading.unit ?: return text
        return if (unit == "%" || unit == "°") "$text$unit" else "$text $unit"
    }

    private fun quantityName(quantity: Quantity) = when (quantity) {
        Quantity.TEMPERATURE -> R.string.ha_quantity_temperature
        Quantity.HUMIDITY -> R.string.ha_quantity_humidity
        Quantity.BATTERY -> R.string.ha_quantity_battery
        Quantity.POWER -> R.string.ha_quantity_power
        Quantity.ENERGY -> R.string.ha_quantity_energy
        Quantity.CO2 -> R.string.ha_quantity_co2
        Quantity.AIR_QUALITY -> R.string.ha_quantity_air_quality
        Quantity.PRESSURE -> R.string.ha_quantity_pressure
        Quantity.ILLUMINANCE -> R.string.ha_quantity_illuminance
    }

    private fun checked(answer: QueryPlanner.Answer.Checked): String {
        val all = answer.yes + answer.no
        val condition = conditionText(answer.condition)
        if (all.size == 1) {
            val one = all.single()
            return s(if (answer.yes.isNotEmpty()) R.string.ha_answer_yes else R.string.ha_answer_no, one.name, stateText(one))
        }
        return when {
            answer.no.isEmpty() -> s(R.string.ha_answer_all, all.size, condition)
            answer.yes.isEmpty() -> s(R.string.ha_answer_none, all.size, condition)
            else -> s(R.string.ha_answer_some, answer.yes.size, all.size, condition, answer.yes.take(MAX_NAMED).joinToString(", ") { it.name })
        }
    }

    private fun conditionText(condition: Condition) = s(
        when (condition) {
            Condition.ON -> R.string.ha_value_on
            Condition.OFF -> R.string.ha_value_off
            Condition.OPEN -> R.string.ha_state_open
            Condition.CLOSED -> R.string.ha_state_closed
            Condition.LOCKED -> R.string.ha_state_locked
            Condition.UNLOCKED -> R.string.ha_state_unlocked
            Condition.ARMED -> R.string.ha_state_armed
            Condition.DISARMED -> R.string.ha_state_disarmed
            Condition.RUNNING -> R.string.ha_state_running
            Condition.PLAYING -> R.string.ha_state_playing
            Condition.CHARGING -> R.string.ha_state_charging
            Condition.DOCKED -> R.string.ha_state_docked
            Condition.HOME -> R.string.ha_state_home
        },
    )

    /** A device's state in words: a door contact that is "on" is open, a person "not_home" is away. */
    private fun stateText(seen: QueryPlanner.Seen): String {
        val state = seen.state
        if (seen.domain == "binary_sensor") {
            val on = state == "on"
            val id = when (seen.deviceClass) {
                "door", "window", "garage_door", "opening" -> if (on) R.string.ha_state_open else R.string.ha_state_closed
                "lock" -> if (on) R.string.ha_state_unlocked else R.string.ha_state_locked
                "motion", "occupancy", "presence" -> if (on) R.string.ha_state_motion else R.string.ha_state_clear
                "moisture" -> if (on) R.string.ha_state_wet else R.string.ha_state_dry
                "smoke", "gas", "carbon_monoxide", "safety", "problem" -> if (on) R.string.ha_state_alert else R.string.ha_state_clear
                "battery_charging" -> if (on) R.string.ha_state_charging else R.string.ha_state_not_charging
                "plug" -> if (on) R.string.ha_state_plugged else R.string.ha_state_unplugged
                "connectivity" -> if (on) R.string.ha_state_connected else R.string.ha_state_disconnected
                "running" -> if (on) R.string.ha_state_running else R.string.ha_state_not_running
                else -> if (on) R.string.ha_value_on else R.string.ha_value_off
            }
            return s(id)
        }
        return when {
            seen.domain == "person" && state == "home" -> s(R.string.ha_state_home)
            seen.domain == "person" && state == "not_home" -> s(R.string.ha_state_away)
            state.startsWith("armed_") -> s(R.string.ha_state_armed_way, armedWay(state.removePrefix("armed_")))
            state == "returning" -> s(R.string.ha_state_returning)
            else -> state.replace('_', ' ')
        }
    }

    /**
     * The answer to "Which one?": "the living room", "all of them", "the second one", among the
     * devices offered. Null when it's a request of its own instead, which then runs as usual.
     */
    private suspend fun whichOne(setting: DeviceSetting, devices: List<String>, answer: String): Step? {
        if (CommandParser.parse(answer) != null) return null
        val found = when (val result = home.devices()) {
            is HomeAssistant.Devices.Found -> result
            is HomeAssistant.Devices.Failed -> return homeFailure(result.reply)
        }
        return homePlan(setting, SettingPlanner.choose(setting, devices, answer, found.entities, found.areas), asked = true)
    }

    private fun settingName(aspect: SettingPlanner.Aspect?, label: String?): String = when (aspect) {
        SettingPlanner.Aspect.TEMPERATURE -> s(R.string.ha_aspect_temperature)
        SettingPlanner.Aspect.MODE -> s(R.string.ha_aspect_mode)
        SettingPlanner.Aspect.PRESET -> s(R.string.ha_aspect_preset)
        SettingPlanner.Aspect.FAN -> s(R.string.ha_aspect_fan)
        SettingPlanner.Aspect.SWING -> s(R.string.ha_aspect_swing)
        SettingPlanner.Aspect.SPEED -> s(R.string.ha_aspect_speed)
        SettingPlanner.Aspect.OSCILLATION -> s(R.string.ha_aspect_oscillation)
        SettingPlanner.Aspect.HUMIDITY -> s(R.string.ha_aspect_humidity)
        SettingPlanner.Aspect.BRIGHTNESS -> s(R.string.ha_aspect_brightness)
        SettingPlanner.Aspect.POSITION -> s(R.string.ha_aspect_position)
        SettingPlanner.Aspect.COLOR -> s(R.string.ha_aspect_color)
        SettingPlanner.Aspect.COLOR_TEMPERATURE -> s(R.string.ha_aspect_color_temperature)
        SettingPlanner.Aspect.EFFECT -> s(R.string.ha_aspect_effect)
        SettingPlanner.Aspect.VOLUME -> s(R.string.ha_aspect_volume)
        SettingPlanner.Aspect.MUTE -> s(R.string.ha_aspect_mute)
        SettingPlanner.Aspect.SOURCE -> s(R.string.ha_aspect_source)
        SettingPlanner.Aspect.SOUND_MODE -> s(R.string.ha_aspect_sound_mode)
        SettingPlanner.Aspect.SUCTION -> s(R.string.ha_aspect_suction)
        null -> label.orEmpty()
    }

    private fun number(value: Double, unit: NumberUnit?): String {
        val format = NumberFormat.getNumberInstance(strings().resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
        val text = format.format(value)
        return when (unit) {
            NumberUnit.DEGREES -> s(R.string.ha_value_degrees, text)
            NumberUnit.PERCENT -> s(R.string.ha_value_percent, text)
            NumberUnit.KELVIN -> s(R.string.ha_value_kelvin, NumberFormat.getIntegerInstance(locale()).format(value))
            null -> text
        }
    }

    /** A device's own option in the user's words where Home Assistant has a standard name for it. */
    private fun option(raw: String): String = when (raw.lowercase()) {
        "cool" -> s(R.string.ha_option_cool)
        "heat" -> s(R.string.ha_option_heat)
        "dry" -> s(R.string.ha_option_dry)
        "fan_only" -> s(R.string.ha_option_fan_only)
        "auto" -> s(R.string.ha_option_auto)
        "heat_cool" -> s(R.string.ha_option_heat_cool)
        "off" -> s(R.string.ha_value_off)
        "on" -> s(R.string.ha_value_on)
        "sleep" -> s(R.string.ha_option_sleep)
        "eco" -> s(R.string.ha_option_eco)
        "boost" -> s(R.string.ha_option_boost)
        "low" -> s(R.string.ha_option_low)
        "medium", "middle" -> s(R.string.ha_option_medium)
        "high" -> s(R.string.ha_option_high)
        "silent", "quiet" -> s(R.string.ha_option_silent)
        "vertical" -> s(R.string.ha_option_vertical)
        "horizontal" -> s(R.string.ha_option_horizontal)
        "both" -> s(R.string.ha_option_both)
        else -> raw.replace('_', ' ')
    }

    private fun offerText(offer: SettingPlanner.Offer): String {
        val name = settingName(offer.aspect, offer.label)
        val options = offer.options
        return if (options != null) "$name (${options.take(8).joinToString(", ", transform = ::option)})"
        else "$name (${s(R.string.ha_range, number(offer.min ?: 0.0, offer.unit), number(offer.max ?: 0.0, offer.unit))})"
    }

    private fun didAction(action: HomeAction) = when (action) {
        HomeAction.ON -> R.string.ha_did_on
        HomeAction.OFF -> R.string.ha_did_off
        HomeAction.OPEN -> R.string.ha_did_open
        HomeAction.CLOSE -> R.string.ha_did_close
        HomeAction.LOCK -> R.string.ha_did_lock
    }

    private fun kindCount(kind: DeviceKind?) = when (kind) {
        DeviceKind.LIGHT -> R.plurals.ha_count_light
        DeviceKind.SWITCH -> R.plurals.ha_count_switch
        DeviceKind.FAN -> R.plurals.ha_count_fan
        DeviceKind.COVER -> R.plurals.ha_count_cover
        DeviceKind.MEDIA -> R.plurals.ha_count_media
        DeviceKind.CLIMATE -> R.plurals.ha_count_climate
        DeviceKind.LOCK -> R.plurals.ha_count_lock
        DeviceKind.VALVE -> R.plurals.ha_count_valve
        null -> R.plurals.ha_count_device
    }

    private fun kindName(kind: DeviceKind?) = when (kind) {
        DeviceKind.LIGHT -> R.string.ha_kind_light
        DeviceKind.SWITCH -> R.string.ha_kind_switch
        DeviceKind.FAN -> R.string.ha_kind_fan
        DeviceKind.COVER -> R.string.ha_kind_cover
        DeviceKind.MEDIA -> R.string.ha_kind_media
        DeviceKind.CLIMATE -> R.string.ha_kind_climate
        DeviceKind.LOCK -> R.string.ha_kind_lock
        DeviceKind.VALVE -> R.string.ha_kind_valve
        null -> R.string.ha_kind_device
    }

    val hasHomeAssistant: Boolean get() = home.isSetUp

    /**
     * A request nothing else understood, offered to Home Assistant ("close the living room blinds").
     * Null when it isn't set up or doesn't understand it either, so the usual "beyond my programming" follows.
     */
    suspend fun tryHomeAssistant(text: String): Step? {
        if (!home.isSetUp) return null
        return when (val reply = home.process(text, "en")) {
            is HomeAssistant.Reply.Done -> assistDone(reply)
            // A device with a name Cog doesn't know as one ("set Kevin to sleep mode") may still have that setting.
            is HomeAssistant.Reply.Refused -> HomeSettings.loose(text)?.let { homeSetting(it) }
                ?: Step.Failed(reply.speech ?: s(R.string.error_ha_not_understood))
            HomeAssistant.Reply.NotUnderstood -> HomeSettings.loose(text)?.let { homeSetting(it) }
            HomeAssistant.Reply.Unauthorized -> Step.Failed(s(R.string.error_ha_unauthorized))
            else -> null
        }
    }

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
     * Always opens the music app, so the user sees it react. For Spotify, the artist's, song's,
     * album's or saved playlist's own page is opened (found on Wikidata or in the user's saved
     * playlists) and started: through Spotify's App Remote when the user added their Client ID,
     * else by [SpotifyPress] tapping its play button when allowed (Spotify doesn't start pages
     * opened by other apps on its own). Otherwise, and for other apps, "search and play"
     * (MEDIA_PLAY_FROM_SEARCH, what Google Assistant used) with what kind of thing was asked for.
     * If the app opens but doesn't start anything, its media session is asked directly.
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
        val intents = listOfNotNull(
            Intent(search).setPackage(app.packageName),
            // If the app ignores the play intent, at least land on its search.
            if (app == MusicApp.SPOTIFY) spotifyIntent("spotify:search:" + Uri.encode(query)) else null,
            search,
        )
        val before = media.snapshot(app.packageName)
        // Set by the lookup, read by the follow-up: the page that was opened, if one was found.
        var page: SpotifyPage? = null
        val followUp: suspend () -> Unit = {
            val opened = page
            val link = opened?.link?.takeIf { it.startsWith("spotify:") }
            val started = link != null && spotify.play(link)
            if (!started) {
                // App Remote not set up or refused: tap play instead, if allowed (already armed when not set up).
                if (opened != null && spotify.isSetUp && SpotifyPress.isEnabled(context)) SpotifyPress.arm(opened.name)
                media.makeSurePlaying(app.packageName, before, link?.let(Uri::parse), query, search.extras ?: Bundle())
            }
        }
        if (app != MusicApp.SPOTIFY) return Step.Launch(intents, confirmation, then = followUp)
        return Step.Launch(
            intents,
            confirmation,
            then = followUp,
            lookup = {
                val found = spotifyPage(command)
                page = found
                listOfNotNull(
                    found?.let {
                        // Without App Remote the page's button is the only way to start it: arm right away.
                        if (!spotify.isSetUp && SpotifyPress.isEnabled(context)) SpotifyPress.arm(it.name)
                        spotifyIntent(it.link)
                    },
                )
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

    /** A Spotify page to open, and the name it shows (when known) so the right page gets started. */
    private class SpotifyPage(val link: String, val name: String?)

    /**
     * The Spotify page for the request: a playlist the user saved under that name, Liked Songs, or
     * the artist, song or album from Wikidata. Null when there's none, so search takes over.
     */
    private suspend fun spotifyPage(command: Command.PlayMusic): SpotifyPage? {
        val saved = when (command.kind) {
            MusicKind.PLAYLIST -> playlists.find(command.query, exactOnly = false)
            // A playlist's name wins over an artist's: the user saved it under that name.
            MusicKind.ANY -> playlists.find(command.query, exactOnly = true)
            else -> null
        }
        if (saved != null) return SpotifyPage(saved.link, saved.name)
        return when (command.kind) {
            // Spotify shows it in the phone's language, so there's no name to check.
            MusicKind.LIKED -> SpotifyPage(SPOTIFY_LIKED_SONGS, null)
            MusicKind.PLAYLIST -> null
            else -> catalog.find(command.query, command.kind, command.artist)?.let { SpotifyPage(it, command.query) }
        }
    }

    private fun spotifyIntent(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setPackage(MusicApp.SPOTIFY.packageName)
        // Spotify asks apps that link into it to say who they are.
        .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + context.packageName))

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

        /** How many readings are said one by one; more are averaged. */
        const val MAX_READINGS = 3

        /** How many devices an answer names. */
        const val MAX_NAMED = 5

        /** "Yes" to "Unlock the front door?". */
        val YES_WORDS = setOf("yes", "yeah", "yep", "yes please", "sure", "confirm", "confirmed", "do it", "go ahead", "unlock it", "disarm it", "affirmative", "ok", "okay")

        /** "Never mind" in answer to "When should I remind you?". */
        val CANCEL_WORDS = setOf("cancel", "never mind", "nevermind", "forget it", "no", "nothing", "stop")


        /** What Spotify and most players call the user's likes, for "search and play". */
        const val LIKED_SONGS = "Liked Songs"
        const val SPOTIFY_LIKED_SONGS = "spotify:collection:tracks"

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
