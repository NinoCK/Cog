package com.bestassistant.commands

import androidx.compose.runtime.Immutable
import com.fastsearch.search.Calculator
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/**
 * Offline unit conversion: "5 km to miles", "100f in c", "how many grams in a pound".
 * Factors are exact definitions where one exists (inch, pound, US customary volumes).
 */
object UnitConverter {

    enum class Dimension { LENGTH, MASS, VOLUME, TEMPERATURE, SPEED, AREA, DATA, TIME, ENERGY }

    data class Unit(val symbol: String, val dimension: Dimension, val factor: Double)

    @Immutable
    data class Conversion(
        val amount: Double,
        val from: Unit,
        val to: Unit,
        val result: Double,
    ) {
        fun formattedAmount(locale: Locale = Locale.getDefault()) = Calculator.format(amount, locale)
        fun formattedResult(locale: Locale = Locale.getDefault()) = Calculator.format(round(result), locale)

        /** For the clipboard: no grouping separators. */
        val plainResult: String get() = rounded(result).stripTrailingZeros().toPlainString()
    }

    private fun rounded(value: Double): BigDecimal =
        if (value == 0.0) BigDecimal.ZERO else BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS))

    private fun round(value: Double): Double = rounded(value).toDouble()

    private fun unit(dimension: Dimension, symbol: String, factor: Double, vararg aliases: String) =
        Unit(symbol, dimension, factor).let { u -> (aliases.toList() + symbol.lowercase()).map { it to u } }

    private val units: Map<String, Unit> = buildList {
        val l = Dimension.LENGTH
        addAll(unit(l, "mm", 0.001, "millimeter", "millimetre"))
        addAll(unit(l, "cm", 0.01, "centimeter", "centimetre"))
        addAll(unit(l, "m", 1.0, "meter", "metre", "mtr"))
        addAll(unit(l, "km", 1000.0, "kilometer", "kilometre", "kms", "klick"))
        addAll(unit(l, "in", 0.0254, "inch", "inches", "\""))
        addAll(unit(l, "ft", 0.3048, "foot", "feet", "'"))
        addAll(unit(l, "yd", 0.9144, "yard", "yds"))
        addAll(unit(l, "mi", 1609.344, "mile"))
        addAll(unit(l, "nmi", 1852.0, "nautical mile"))

        val w = Dimension.MASS
        addAll(unit(w, "mg", 1e-6, "milligram", "milligramme"))
        addAll(unit(w, "g", 1e-3, "gram", "gramme", "gr"))
        addAll(unit(w, "kg", 1.0, "kilogram", "kilogramme", "kilo", "kgs"))
        addAll(unit(w, "t", 1000.0, "tonne", "metric ton", "ton"))
        addAll(unit(w, "oz", 0.028349523125, "ounce"))
        addAll(unit(w, "lb", 0.45359237, "pound", "lbs"))
        addAll(unit(w, "st", 6.35029318, "stone"))

        val v = Dimension.VOLUME
        addAll(unit(v, "ml", 0.001, "milliliter", "millilitre", "cc"))
        addAll(unit(v, "cl", 0.01, "centiliter", "centilitre"))
        addAll(unit(v, "dl", 0.1, "deciliter", "decilitre"))
        addAll(unit(v, "L", 1.0, "l", "liter", "litre", "ltr"))
        addAll(unit(v, "m³", 1000.0, "m3", "cubic meter", "cubic metre"))
        addAll(unit(v, "tsp", 0.00492892159375, "teaspoon"))
        addAll(unit(v, "tbsp", 0.01478676478125, "tablespoon"))
        addAll(unit(v, "fl oz", 0.0295735295625, "floz", "fluid ounce"))
        addAll(unit(v, "cup", 0.2365882365))
        addAll(unit(v, "pt", 0.473176473, "pint"))
        addAll(unit(v, "qt", 0.946352946, "quart"))
        addAll(unit(v, "gal", 3.785411784, "gallon"))

        val t = Dimension.TEMPERATURE
        addAll(unit(t, "°C", 1.0, "c", "celsius", "centigrade"))
        addAll(unit(t, "°F", 1.0, "f", "fahrenheit"))
        addAll(unit(t, "K", 1.0, "k", "kelvin"))

        val s = Dimension.SPEED
        addAll(unit(s, "m/s", 1.0, "mps", "meters per second", "metres per second"))
        addAll(unit(s, "km/h", 1 / 3.6, "kmh", "kph", "kmph", "kilometers per hour", "kilometres per hour"))
        addAll(unit(s, "mph", 0.44704, "miles per hour", "mi/h"))
        addAll(unit(s, "kn", 1852.0 / 3600, "knot", "kt", "kts"))

        val a = Dimension.AREA
        addAll(unit(a, "m²", 1.0, "m2", "sqm", "square meter", "square metre", "sq m"))
        addAll(unit(a, "km²", 1e6, "km2", "square kilometer", "square kilometre", "sq km"))
        addAll(unit(a, "ft²", 0.09290304, "ft2", "sqft", "square foot", "square feet", "sq ft"))
        addAll(unit(a, "mi²", 2589988.110336, "mi2", "square mile", "sq mi"))
        addAll(unit(a, "ac", 4046.8564224, "acre"))
        addAll(unit(a, "ha", 10000.0, "hectare"))

        val d = Dimension.DATA
        addAll(unit(d, "bit", 0.125))
        addAll(unit(d, "B", 1.0, "b", "byte"))
        addAll(unit(d, "kB", 1e3, "kb", "kilobyte"))
        addAll(unit(d, "MB", 1e6, "mb", "megabyte"))
        addAll(unit(d, "GB", 1e9, "gb", "gigabyte", "gig"))
        addAll(unit(d, "TB", 1e12, "tb", "terabyte"))
        addAll(unit(d, "KiB", 1024.0, "kib", "kibibyte"))
        addAll(unit(d, "MiB", 1048576.0, "mib", "mebibyte"))
        addAll(unit(d, "GiB", 1073741824.0, "gib", "gibibyte"))

        val time = Dimension.TIME
        addAll(unit(time, "ms", 0.001, "millisecond"))
        addAll(unit(time, "s", 1.0, "sec", "second", "secs"))
        addAll(unit(time, "min", 60.0, "minute", "mins"))
        addAll(unit(time, "h", 3600.0, "hr", "hour", "hrs"))
        addAll(unit(time, "days", 86400.0, "day", "d"))
        addAll(unit(time, "weeks", 604800.0, "week", "wk"))
        addAll(unit(time, "years", 31556952.0, "year", "yr"))

        val e = Dimension.ENERGY
        addAll(unit(e, "J", 1.0, "j", "joule"))
        addAll(unit(e, "kJ", 1000.0, "kj", "kilojoule"))
        addAll(unit(e, "cal", 4.184, "calorie"))
        addAll(unit(e, "kcal", 4184.0, "kilocalorie"))
        addAll(unit(e, "kWh", 3.6e6, "kwh", "kilowatt hour"))
    }.toMap()

    fun findUnit(raw: String): Unit? {
        val text = raw.trim().lowercase(Locale.ROOT)
            .removePrefix("degrees ").removePrefix("degree ").removePrefix("deg ").removePrefix("°")
            .removePrefix("a ").removePrefix("an ").removePrefix("one ")
            .trim().trimEnd('.')
        if (text.isEmpty()) return null
        units[text]?.let { return it }
        // Plurals: "miles", "inches", "grams".
        if (text.endsWith("es")) units[text.dropLast(2)]?.let { return it }
        if (text.endsWith("s")) units[text.dropLast(1)]?.let { return it }
        return null
    }

    private const val NUMBER = "(-?\\d+(?:[.,]\\d+)?|a|an|one)"
    private val forward = Regex("^(?:convert |what is |what's )?$NUMBER ?(.+?) (?:to|in|into|as|in to|=|->|→) (.+)$")
    private val howMany = Regex("^how many (.+?) (?:are )?(?:in|per|to) (?:a |an |one )?(?:(-?\\d+(?:[.,]\\d+)?) ?)?(.+)$")

    fun parse(text: String): Conversion? {
        forward.matchEntire(text)?.let { m ->
            return convert(amount(m.groupValues[1]) ?: return null, m.groupValues[2], m.groupValues[3])
        }
        howMany.matchEntire(text)?.let { m ->
            val amount = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::amount) ?: 1.0
            return convert(amount, m.groupValues[3], m.groupValues[1])
        }
        return null
    }

    private fun amount(text: String): Double? = when (text) {
        "a", "an", "one" -> 1.0
        else -> text.replace(',', '.').toDoubleOrNull()
    }

    fun convert(amount: Double, fromText: String, toText: String): Conversion? {
        val from = findUnit(fromText) ?: return null
        val to = findUnit(toText) ?: return null
        if (from.dimension != to.dimension || from == to) return null
        val result = if (from.dimension == Dimension.TEMPERATURE) {
            fromKelvin(toKelvin(amount, from.symbol), to.symbol)
        } else {
            amount * from.factor / to.factor
        }
        return Conversion(amount, from, to, result)
    }

    private fun toKelvin(value: Double, symbol: String) = when (symbol) {
        "°C" -> value + 273.15
        "°F" -> (value - 32) * 5 / 9 + 273.15
        else -> value
    }

    private fun fromKelvin(value: Double, symbol: String) = when (symbol) {
        "°C" -> value - 273.15
        "°F" -> (value - 273.15) * 9 / 5 + 32
        else -> value
    }

    private const val SIGNIFICANT_DIGITS = 6
}
