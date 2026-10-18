package com.bestassistant.integrations.homeassistant

import android.app.KeyguardManager
import com.bestassistant.integration.Card
import com.bestassistant.integration.FieldKind
import com.bestassistant.integration.Integration
import com.bestassistant.integration.IntegrationHost
import com.bestassistant.integration.Report
import com.bestassistant.integration.Request
import com.bestassistant.integration.RequestParser
import com.bestassistant.integration.Setting
import com.bestassistant.integration.Step
import com.bestassistant.integration.Tone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.NumberFormat

/**
 * The user's own Home Assistant, reached directly with a long-lived token they created: devices
 * switched, set and asked about by voice ("turn off all the lights", "set the bedroom AC to 22",
 * "is the front door locked?"), and spoken requests nothing else understood offered to its Assist.
 */
class HomeAssistantIntegration(private val host: IntegrationHost) : Integration {

    override val id: String = ID

    private val store = host.store(ID)
    private val home = HomeAssistant({ store.get(KEY_URL) }, { store.get(KEY_TOKEN, secret = true) })

    private fun s(id: Int, vararg args: Any): String = host.strings.getString(id, *args)

    private fun locale() = host.strings.resources.configuration.locales[0]

    override val name: String get() = s(R.string.ha_name)
    override val description: String get() = s(R.string.ha_description)
    override val icon: Int = R.drawable.ic_integration_home_assistant

    override val summary: Flow<String> = store.changes.map { s(if (home.isSetUp) R.string.ha_summary_set_up else R.string.ha_summary_not_set_up) }

    override val parser: RequestParser = HomeRequests

    override fun plan(request: Request): Step {
        val asked = request as? HomeRequest ?: return Step.Failed(s(R.string.error_ha_not_understood))
        return if (home.isSetUp) Step.Remote { smartHome(asked) } else Step.Failed(s(R.string.error_ha_not_set_up))
    }

    override fun card(request: Request): Card? = (request as? HomeRequest)?.let {
        Card(it.text.replaceFirstChar(Char::titlecase), s(if (home.isSetUp) R.string.cmd_smart_home else R.string.cmd_smart_home_not_set_up))
    }

    /** Its address, a long-lived token, and a check that both work. */
    override fun settings(): List<Setting> = listOf(
        Setting.Note(s(R.string.ha_intro)),
        Setting.Field(KEY_URL, s(R.string.ha_url), hint = s(R.string.ha_url_hint), kind = FieldKind.ADDRESS),
        Setting.Field(KEY_TOKEN, s(R.string.ha_token), kind = FieldKind.SECRET),
        Setting.Check(
            label = s(R.string.ha_test),
            ready = { store.get(KEY_URL).isNotBlank() && store.get(KEY_TOKEN, secret = true).isNotBlank() },
            running = s(R.string.ha_status_checking),
            run = ::check,
        ),
        Setting.Status(store.changes.map { if (home.isSetUp) null else Report(s(R.string.ha_status_not_set)) }),
        Setting.Note(s(R.string.ha_fallback_note), small = true),
    )

    /** Whether the address and token work and, when they do, what Cog sees: so a device it doesn't find can be told apart from one it misheard. */
    private suspend fun check(): Report = when (home.check()) {
        is HomeAssistant.Reply.Done -> {
            val seen = home.seen()
            val lines = listOfNotNull(
                s(R.string.ha_status_ok),
                seen?.let { found -> s(R.string.ha_seen, found.counts.entries.take(10).joinToString(" · ") { (domain, count) -> "$domain $count" }) },
                seen?.let { found ->
                    if (found.airConditioners.isEmpty()) s(R.string.ha_seen_no_acs)
                    else s(R.string.ha_seen_acs, found.airConditioners.take(8).joinToString(", "))
                },
                seen?.let { found -> found.areas?.let { s(R.string.ha_seen_areas, it) } ?: s(R.string.ha_seen_no_areas) },
            )
            Report(lines.joinToString("\n"), Tone.GOOD)
        }
        HomeAssistant.Reply.Unauthorized -> Report(s(R.string.ha_status_unauthorized), Tone.BAD)
        else -> Report(s(R.string.ha_status_unreachable), Tone.BAD)
    }

    private suspend fun smartHome(command: HomeRequest): Step {
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
                val count = host.strings.resources.getQuantityString(kindCount(group.kind), plan.count, plan.count)
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
        if (host.context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return Step.Failed(s(R.string.ha_phone_locked))
        val question = s(if (verb == DeviceVerb.UNLOCK) R.string.ha_confirm_unlock else R.string.ha_confirm_disarm, names)
        return Step.Ask(question) { answer ->
            val said = answer.trim().lowercase(locale()).trimEnd('.', '!')
            when {
                said in YES_WORDS -> act(plan)
                host.isRequest(answer) -> null
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
        if (host.isRequest(answer)) return null
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
        val format = NumberFormat.getNumberInstance(host.strings.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
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

    override val takesUnknown: Boolean get() = home.isSetUp

    /**
     * A request nothing else understood, offered to Home Assistant ("close the living room blinds").
     * Null when it isn't set up or doesn't understand it either, so the usual "beyond my programming" follows.
     */
    override suspend fun fallback(said: String): Step? {
        if (!home.isSetUp) return null
        return when (val reply = home.process(said, "en")) {
            is HomeAssistant.Reply.Done -> assistDone(reply)
            // A device with a name Cog doesn't know as one ("set Kevin to sleep mode") may still have that setting.
            is HomeAssistant.Reply.Refused -> HomeSettings.loose(host.normalize(said))?.let { homeSetting(it) }
                ?: Step.Failed(reply.speech ?: s(R.string.error_ha_not_understood))
            HomeAssistant.Reply.NotUnderstood -> HomeSettings.loose(host.normalize(said))?.let { homeSetting(it) }
            HomeAssistant.Reply.Unauthorized -> Step.Failed(s(R.string.error_ha_unauthorized))
            else -> null
        }
    }

    companion object {
        const val ID = "home_assistant"

        /** Where its address and token are kept: what Cog's settings used to keep them under is moved here once. */
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"

        /** How many readings are said one by one; more are averaged. */
        private const val MAX_READINGS = 3

        /** How many devices an answer names. */
        private const val MAX_NAMED = 5

        /** "Yes" to "Unlock the front door?". */
        private val YES_WORDS = setOf("yes", "yeah", "yep", "yes please", "sure", "confirm", "confirmed", "do it", "go ahead", "unlock it", "disarm it", "affirmative", "ok", "okay")
    }
}
