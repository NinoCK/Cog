package com.bestassistant.speech

import android.content.Context
import com.bestassistant.commands.Command
import com.bestassistant.commands.MediaAction
import com.bestassistant.commands.SettingsPanel
import com.bestassistant.commands.Switch
import com.bestassistant.commands.UnitConverter
import com.bestassistant.commands.VolumeChange
import com.bestassistant.commands.WeatherDay
import com.bestassistant.device.TimeText
import com.bestassistant.language.AppLanguage
import com.bestassistant.media.MediaControl
import com.bestassistant.weather.Forecast
import com.bestassistant.wiki.Article
import com.bestassistant.wiki.WikipediaClient
import com.fastsearch.R
import com.fastsearch.data.Contact
import com.fastsearch.search.Calculator
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the assistant says back. Every reply is a fixed sentence template filled in with the
 * facts at hand, so the same request always gets the same kind of answer: no generated text.
 */
class Responder(private val strings: () -> Context, private val media: MediaControl) {

    /** Resources in the assistant's language. */
    private val context: Context get() = strings()

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** The reply for a command that has just run. */
    fun forCommand(command: Command, contact: Contact? = null, torchOn: Boolean = false): String? = when (command) {
        is Command.SetAlarm -> {
            val time = TimeText.clock(context, command.hour, command.minute)
            val until = untilAlarm(command.hour, command.minute)
            if (command.label != null) s(R.string.say_alarm_label, time, command.label, until) else s(R.string.say_alarm, time, until)
        }
        Command.ShowAlarms -> s(R.string.say_show_alarms)
        is Command.SetTimer -> {
            val length = TimeText.spokenDuration(context, command.seconds)
            if (command.label != null) s(R.string.say_timer_label, length, command.label) else s(R.string.say_timer, length)
        }
        Command.ShowTimers -> s(R.string.say_show_timers)
        is Command.Flashlight -> s(if (torchOn) R.string.say_torch_on else R.string.say_torch_off)
        is Command.Volume -> when (val change = command.change) {
            VolumeChange.Up -> s(R.string.say_volume_up)
            VolumeChange.Down -> s(R.string.say_volume_down)
            VolumeChange.Mute -> s(R.string.say_mute)
            VolumeChange.Unmute -> s(R.string.say_unmute)
            is VolumeChange.Set -> s(R.string.say_volume_set, change.percent)
        }
        is Command.Media -> s(
            when (command.action) {
                MediaAction.PLAY -> R.string.say_play
                MediaAction.PAUSE -> R.string.say_pause
                MediaAction.NEXT -> R.string.say_next
                MediaAction.PREVIOUS -> R.string.say_previous
            },
        )
        is Command.PlayMusic -> {
            val what = titleCase(command.query)
            if (command.app != null) s(R.string.say_play_music_app, what, command.app.label) else s(R.string.say_play_music, what)
        }
        Command.NowPlaying -> {
            val playing = media.nowPlaying()
            when {
                playing == null -> s(R.string.say_nothing_playing)
                playing.artist != null -> s(R.string.say_now_playing_artist, playing.title, playing.artist)
                else -> s(R.string.say_now_playing, playing.title)
            }
        }
        Command.LikeSong -> s(R.string.say_liked)
        is Command.Shuffle -> s(if (command.on) R.string.say_shuffle_on else R.string.say_shuffle_off)
        is Command.Repeat -> s(if (command.on) R.string.say_repeat_on else R.string.say_repeat_off)
        is Command.Navigate -> s(R.string.say_navigate, command.destination)
        is Command.Camera -> s(if (command.video) R.string.say_video else R.string.say_photo)
        is Command.OpenPanel -> {
            val name = context.getString(panelName(command.panel))
            when (command.requested) {
                Switch.ON -> s(R.string.say_panel_on, name)
                Switch.OFF -> s(R.string.say_panel_off, name)
                else -> s(R.string.say_panel, name)
            }
        }
        is Command.DialNumber -> s(R.string.say_dial, command.number.toCharArray().joinToString(" "))
        is Command.Call -> s(R.string.say_call, contact?.name ?: command.who)
        is Command.Message -> {
            val name = contact?.name ?: command.who
            if (command.body != null) s(R.string.say_message_body, name, command.body) else s(R.string.say_message, name)
        }
        is Command.OpenApp -> s(R.string.say_open_app, command.name)
        is Command.Convert -> forConversion(command.conversion)
        is Command.Clock -> forClock(command)
        is Command.Weather, is Command.Lookup -> null // answered once their data arrives
    }

    fun forApp(label: String) = s(R.string.say_open_app, label)

    fun forCalculation(result: Calculator.Result) = s(R.string.say_calculation, result.formatted)

    fun forNothing(query: String) = s(R.string.say_nothing, query)

    fun forFailure(message: String) = message

    // region Weather

    fun forWeather(forecast: Forecast, day: WeatherDay): String {
        val place = forecast.place?.substringBefore(',') ?: ""
        val where = if (place.isNotEmpty()) s(R.string.say_in_place, place) else ""
        val unit = context.getString(if (forecast.imperial) R.string.say_unit_f else R.string.say_unit_c)
        val today = forecast.days.firstOrNull()
        return when (day) {
            WeatherDay.TOMORROW -> forecast.days.getOrNull(1)?.let { d ->
                listOfNotNull(
                    s(R.string.say_weather_tomorrow, where, condition(d.code), d.max.roundToInt(), d.min.roundToInt(), unit),
                    rain(d.precipitation),
                ).joinToString(" ")
            } ?: now(forecast, where, unit)
            WeatherDay.WEEK -> {
                val days = forecast.days.take(WEEK_DAYS).drop(1)
                val warmest = days.maxByOrNull { it.max }
                val wettest = days.filter { (it.precipitation ?: 0) >= RAINY_PERCENT }.map { dayName(it.date.dayOfWeek) }
                listOfNotNull(
                    now(forecast, where, unit),
                    warmest?.let { s(R.string.say_weather_warmest, dayName(it.date.dayOfWeek), it.max.roundToInt(), unit) },
                    if (wettest.isEmpty()) s(R.string.say_weather_dry) else s(R.string.say_weather_rainy_days, list(wettest)),
                ).joinToString(" ")
            }
            else -> listOfNotNull(
                now(forecast, where, unit),
                today?.let { s(R.string.say_weather_range, it.max.roundToInt(), it.min.roundToInt()) },
                rain(today?.precipitation),
            ).joinToString(" ")
        }
    }

    fun forWeatherNotFound(place: String) = s(R.string.say_weather_not_found, place)
    fun forWeatherUnavailable() = s(R.string.say_weather_unavailable)
    fun forWeatherNeedsLocation() = s(R.string.say_weather_needs_location)

    private fun now(f: Forecast, where: String, unit: String): String {
        val feels = f.feelsLike.roundToInt()
        val temperature = f.temperature.roundToInt()
        val base = s(R.string.say_weather_now, where, temperature, unit, condition(f.code)).replace("  ", " ").trim()
        return if (abs(feels - temperature) >= FEELS_LIKE_GAP) "$base ${s(R.string.say_weather_feels, feels)}" else base
    }

    private fun rain(percent: Int?): String? = when {
        percent == null -> null
        percent >= RAINY_PERCENT -> s(R.string.say_weather_rain, percent)
        percent <= DRY_PERCENT -> s(R.string.say_weather_no_rain)
        else -> s(R.string.say_weather_some_rain, percent)
    }

    private fun condition(code: Int): String = context.getString(WeatherWords.condition(code)).lowercase(Locale.getDefault())

    // endregion

    // region Wikipedia

    fun forArticle(article: Article) = s(R.string.say_wikipedia, WikipediaClient.spokenSummary(article.extract))
    fun forArticleMissing(topic: String) = s(R.string.say_wikipedia_missing, topic)
    fun forArticleUnavailable() = s(R.string.say_wikipedia_unavailable)

    // endregion

    private fun forConversion(c: UnitConverter.Conversion): String {
        val amount = c.formattedAmount()
        val result = c.formattedResult()
        return s(R.string.say_conversion, amount, unitName(c.from, c.amount), result, unitName(c.to, c.result))
    }

    private fun forClock(command: Command.Clock): String {
        val zone = command.zone ?: ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val time = TimeText.clock(context, now)
        if (command.zone == null) return s(R.string.say_time_here, time, TimeText.date(now))
        val hours = (zone.rules.getOffset(now.toInstant()).totalSeconds -
            ZoneId.systemDefault().rules.getOffset(now.toInstant()).totalSeconds) / 3600.0
        val place = command.place ?: zone.id
        val difference = when {
            hours == 0.0 -> s(R.string.say_time_same)
            hours > 0 -> s(R.string.say_time_ahead, spokenHours(hours))
            else -> s(R.string.say_time_behind, spokenHours(-hours))
        }
        return "${s(R.string.say_time_in, place, time)} $difference"
    }

    private fun untilAlarm(hour: Int, minute: Int): String {
        val now = LocalDateTime.now()
        var at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val minutes = Duration.between(now, at).toMinutes().toInt().coerceAtLeast(1)
        return TimeText.spokenDuration(context, minutes * 60)
    }

    private fun spokenHours(hours: Double): String = TimeText.spokenDuration(context, (hours * 3600).roundToInt())

    private fun dayName(day: java.time.DayOfWeek) = day.getDisplayName(TextStyle.FULL, Locale.getDefault())

    private fun list(items: List<String>): String = when (items.size) {
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " ${context.getString(R.string.say_and)} " + items.last()
    }

    private fun titleCase(text: String) = text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }

    /** Units read as words ("3.1 miles"), singular for exactly one. */
    private fun unitName(unit: UnitConverter.Unit, amount: Double): String {
        val names = if (AppLanguage.isGreek) greekUnitNames else unitNames
        val (one, many) = names[unit.symbol] ?: return unit.symbol
        return if (amount == 1.0) one else many
    }

    private fun panelName(panel: SettingsPanel): Int = when (panel) {
        SettingsPanel.WIFI -> R.string.panel_wifi
        SettingsPanel.BLUETOOTH -> R.string.panel_bluetooth
        SettingsPanel.INTERNET -> R.string.panel_internet
        SettingsPanel.AIRPLANE -> R.string.panel_airplane
        SettingsPanel.NFC -> R.string.panel_nfc
        SettingsPanel.HOTSPOT -> R.string.panel_hotspot
        SettingsPanel.VOLUME -> R.string.panel_volume
        SettingsPanel.BATTERY -> R.string.panel_battery
        SettingsPanel.DISPLAY -> R.string.panel_display
        SettingsPanel.LOCATION -> R.string.panel_location
        SettingsPanel.DND -> R.string.panel_dnd
    }

    private companion object {
        const val RAINY_PERCENT = 50
        const val DRY_PERCENT = 10
        const val FEELS_LIKE_GAP = 3
        const val WEEK_DAYS = 7

        val unitNames = mapOf(
            "mm" to ("millimeter" to "millimeters"), "cm" to ("centimeter" to "centimeters"), "m" to ("meter" to "meters"),
            "km" to ("kilometer" to "kilometers"), "in" to ("inch" to "inches"), "ft" to ("foot" to "feet"),
            "yd" to ("yard" to "yards"), "mi" to ("mile" to "miles"), "nmi" to ("nautical mile" to "nautical miles"),
            "mg" to ("milligram" to "milligrams"), "g" to ("gram" to "grams"), "kg" to ("kilogram" to "kilograms"),
            "t" to ("tonne" to "tonnes"), "oz" to ("ounce" to "ounces"), "lb" to ("pound" to "pounds"), "st" to ("stone" to "stone"),
            "ml" to ("milliliter" to "milliliters"), "cl" to ("centiliter" to "centiliters"), "dl" to ("deciliter" to "deciliters"),
            "L" to ("liter" to "liters"), "m³" to ("cubic meter" to "cubic meters"), "tsp" to ("teaspoon" to "teaspoons"),
            "tbsp" to ("tablespoon" to "tablespoons"), "fl oz" to ("fluid ounce" to "fluid ounces"), "cup" to ("cup" to "cups"),
            "pt" to ("pint" to "pints"), "qt" to ("quart" to "quarts"), "gal" to ("gallon" to "gallons"),
            "°C" to ("degree Celsius" to "degrees Celsius"), "°F" to ("degree Fahrenheit" to "degrees Fahrenheit"),
            "K" to ("kelvin" to "kelvin"), "m/s" to ("meter per second" to "meters per second"),
            "km/h" to ("kilometer per hour" to "kilometers per hour"), "mph" to ("mile per hour" to "miles per hour"),
            "kn" to ("knot" to "knots"), "m²" to ("square meter" to "square meters"), "km²" to ("square kilometer" to "square kilometers"),
            "ft²" to ("square foot" to "square feet"), "mi²" to ("square mile" to "square miles"), "ac" to ("acre" to "acres"),
            "ha" to ("hectare" to "hectares"), "bit" to ("bit" to "bits"), "B" to ("byte" to "bytes"), "kB" to ("kilobyte" to "kilobytes"),
            "MB" to ("megabyte" to "megabytes"), "GB" to ("gigabyte" to "gigabytes"), "TB" to ("terabyte" to "terabytes"),
            "KiB" to ("kibibyte" to "kibibytes"), "MiB" to ("mebibyte" to "mebibytes"), "GiB" to ("gibibyte" to "gibibytes"),
            "ms" to ("millisecond" to "milliseconds"), "s" to ("second" to "seconds"), "min" to ("minute" to "minutes"),
            "h" to ("hour" to "hours"), "days" to ("day" to "days"), "weeks" to ("week" to "weeks"), "years" to ("year" to "years"),
            "J" to ("joule" to "joules"), "kJ" to ("kilojoule" to "kilojoules"), "cal" to ("calorie" to "calories"),
            "kcal" to ("kilocalorie" to "kilocalories"), "kWh" to ("kilowatt hour" to "kilowatt hours"),
        )

        val greekUnitNames = mapOf(
            "mm" to ("χιλιοστό" to "χιλιοστά"), "cm" to ("εκατοστό" to "εκατοστά"), "m" to ("μέτρο" to "μέτρα"),
            "km" to ("χιλιόμετρο" to "χιλιόμετρα"), "in" to ("ίντσα" to "ίντσες"), "ft" to ("πόδι" to "πόδια"),
            "yd" to ("γιάρδα" to "γιάρδες"), "mi" to ("μίλι" to "μίλια"), "nmi" to ("ναυτικό μίλι" to "ναυτικά μίλια"),
            "mg" to ("χιλιοστόγραμμο" to "χιλιοστόγραμμα"), "g" to ("γραμμάριο" to "γραμμάρια"), "kg" to ("κιλό" to "κιλά"),
            "t" to ("τόνος" to "τόνοι"), "oz" to ("ουγγιά" to "ουγγιές"), "lb" to ("λίβρα" to "λίβρες"),
            "ml" to ("χιλιοστόλιτρο" to "χιλιοστόλιτρα"), "L" to ("λίτρο" to "λίτρα"), "gal" to ("γαλόνι" to "γαλόνια"),
            "cup" to ("φλιτζάνι" to "φλιτζάνια"), "tsp" to ("κουταλάκι" to "κουταλάκια"),
            "tbsp" to ("κουταλιά της σούπας" to "κουταλιές της σούπας"),
            "°C" to ("βαθμός Κελσίου" to "βαθμοί Κελσίου"), "°F" to ("βαθμός Φαρενάιτ" to "βαθμοί Φαρενάιτ"),
            "K" to ("κέλβιν" to "κέλβιν"), "km/h" to ("χιλιόμετρο την ώρα" to "χιλιόμετρα την ώρα"),
            "mph" to ("μίλι την ώρα" to "μίλια την ώρα"), "kn" to ("κόμβος" to "κόμβοι"),
            "m²" to ("τετραγωνικό μέτρο" to "τετραγωνικά μέτρα"), "ha" to ("εκτάριο" to "εκτάρια"),
            "s" to ("δευτερόλεπτο" to "δευτερόλεπτα"), "min" to ("λεπτό" to "λεπτά"), "h" to ("ώρα" to "ώρες"),
            "days" to ("μέρα" to "μέρες"), "weeks" to ("εβδομάδα" to "εβδομάδες"), "years" to ("χρόνος" to "χρόνια"),
            "kcal" to ("θερμίδα" to "θερμίδες"), "MB" to ("μεγαμπάιτ" to "μεγαμπάιτ"), "GB" to ("γιγαμπάιτ" to "γιγαμπάιτ"),
        )
    }
}
