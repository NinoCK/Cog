package com.fastsearch.search

import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * A small, dependency-free expression evaluator for inline answers.
 *
 * Supports + - * / ^ (and × ÷ x), parentheses, implicit multiplication ("2(3+4)", "2pi"),
 * postfix % and !, the constants pi/π/e, and common functions. Trigonometry uses degrees,
 * which is what people typing "sin 30" into a search bar expect.
 */
object Calculator {

    data class Result(
        /** The expression as the user typed it, tidied for display. */
        val expression: String,
        val value: Double,
        /** Locale-formatted value, e.g. "1,234.5". */
        val formatted: String,
        /** Plain value suitable for copying, e.g. "1234.5". */
        val plain: String,
        val usesDegrees: Boolean,
    )

    private val phoneLike = Regex("^\\+?[\\d\\s()-]+$")

    fun evaluate(input: String, locale: Locale = Locale.getDefault()): Result? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 200) return null
        if (trimmed.count { it.isDigit() } >= 7 && phoneLike.matches(trimmed)) return null

        val decimalSeparator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val prepared = if (decimalSeparator == ',') {
            trimmed.replace(',', '.')
        } else {
            trimmed.replace(",", "")
        }

        val parser = Parser(prepared)
        val value = try {
            parser.parse()
        } catch (_: ParseException) {
            return null
        } catch (_: ArithmeticException) {
            return null
        }
        if (!parser.isCalculation || value.isNaN() || value.isInfinite()) return null

        return Result(
            expression = trimmed,
            value = value,
            formatted = format(value, locale),
            plain = plain(value),
            usesDegrees = parser.usedTrig,
        )
    }

    fun format(value: Double, locale: Locale = Locale.getDefault()): String {
        val v = if (value == 0.0) 0.0 else value // normalise -0.0
        val magnitude = abs(v)
        if (magnitude != 0.0 && (magnitude >= 1e15 || magnitude < 1e-6)) return scientific(v)
        val rounded = BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
        val format = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = true
            maximumFractionDigits = rounded.scale().coerceIn(0, 12)
            minimumFractionDigits = 0
        }
        return format.format(rounded)
    }

    private fun plain(value: Double): String {
        val v = if (value == 0.0) 0.0 else value
        val magnitude = abs(v)
        if (magnitude != 0.0 && (magnitude >= 1e15 || magnitude < 1e-6)) return scientific(v)
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun scientific(value: Double): String {
        val text = BigDecimal(value).round(MathContext(8)).stripTrailingZeros().toString()
        return text.replace("E+", "e").replace("E", "e")
    }

    private class ParseException : Exception()

    private data class Value(val number: Double, val isPercent: Boolean = false)

    private class Parser(private val s: String) {
        private var pos = 0
        private var operations = 0
        var usedTrig = false
            private set

        /** True when the input did some actual maths (so "42" or "pi" alone isn't shown as a result). */
        val isCalculation get() = operations > 0

        fun parse(): Double {
            val v = expression()
            skipSpaces()
            if (pos < s.length) throw ParseException()
            return v.number
        }

        private fun expression(): Value {
            var left = term()
            while (true) {
                skipSpaces()
                val c = peek() ?: break
                if (c != '+' && c != '-' && c != '−') break
                pos++
                val right = term()
                operations++
                // "200 + 10%" means 200 plus 10% of 200, as on a pocket calculator.
                val delta = if (right.isPercent) left.number * right.number else right.number
                left = Value(if (c == '+') left.number + delta else left.number - delta)
            }
            return left
        }

        private fun term(): Value {
            var left = unary()
            while (true) {
                skipSpaces()
                val c = peek() ?: break
                when {
                    c == '*' || c == '×' || c == '·' || isLetterTimes() -> {
                        pos++
                        val right = unary()
                        operations++
                        left = Value(left.number * right.number)
                    }
                    c == '/' || c == '÷' -> {
                        pos++
                        val right = unary()
                        operations++
                        if (right.number == 0.0) throw ArithmeticException()
                        left = Value(left.number / right.number)
                    }
                    c == '(' || c == 'π' || c == '√' || c.isLetter() -> {
                        // Implicit multiplication: 2(3+4), 2pi, 3sqrt(4)
                        val right = unary()
                        operations++
                        left = Value(left.number * right.number)
                    }
                    else -> break
                }
            }
            return left
        }

        /** Treat a lone "x" between operands as multiplication: "3x4", "3 x 4". */
        private fun isLetterTimes(): Boolean {
            val c = peek() ?: return false
            if (c != 'x' && c != 'X') return false
            var next = pos + 1
            while (next < s.length && s[next] == ' ') next++
            return next < s.length && (s[next].isDigit() || s[next] == '(' || s[next] == '.')
        }

        private fun unary(): Value {
            skipSpaces()
            return when (peek()) {
                '-', '−' -> {
                    pos++
                    val v = unary()
                    Value(-v.number, v.isPercent)
                }
                '+' -> {
                    pos++
                    unary()
                }
                else -> power()
            }
        }

        private fun power(): Value {
            val base = postfix()
            skipSpaces()
            if (peek() == '^') {
                pos++
                val exponent = unary()
                operations++
                return Value(base.number.pow(exponent.number))
            }
            if (s.startsWith("**", pos)) {
                pos += 2
                val exponent = unary()
                operations++
                return Value(base.number.pow(exponent.number))
            }
            return base
        }

        private fun postfix(): Value {
            var v = primary()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '%' -> {
                        pos++
                        operations++
                        v = Value(v.number / 100.0, isPercent = true)
                    }
                    '!' -> {
                        pos++
                        operations++
                        v = Value(factorial(v.number))
                    }
                    else -> return v
                }
            }
        }

        private fun primary(): Value {
            skipSpaces()
            val c = peek() ?: throw ParseException()
            return when {
                c == '(' -> {
                    pos++
                    val v = expression()
                    skipSpaces()
                    if (peek() == ')') pos++ // tolerate a missing closing paren while typing
                    Value(v.number)
                }
                c.isDigit() || c == '.' -> number()
                c == 'π' -> {
                    pos++
                    Value(Math.PI)
                }
                c == '√' -> {
                    pos++
                    operations++
                    Value(kotlin.math.sqrt(power().number))
                }
                c.isLetter() -> identifier()
                else -> throw ParseException()
            }
        }

        private fun number(): Value {
            val start = pos
            var dots = 0
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                if (s[pos] == '.') dots++
                pos++
            }
            if (dots > 1) throw ParseException()
            // Scientific notation: 1.5e3
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                val save = pos
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos < s.length && s[pos].isDigit()) {
                    while (pos < s.length && s[pos].isDigit()) pos++
                } else {
                    pos = save
                }
            }
            val text = s.substring(start, pos)
            if (text == ".") throw ParseException()
            return Value(text.toDoubleOrNull() ?: throw ParseException())
        }

        private fun identifier(): Value {
            val start = pos
            while (pos < s.length && s[pos].isLetterOrDigit()) pos++
            val name = s.substring(start, pos).lowercase()
            when (name) {
                "pi" -> return Value(Math.PI)
                "e" -> return Value(Math.E)
                "tau" -> return Value(2 * Math.PI)
            }
            val fn: (Double) -> Double = when (name) {
                "sqrt" -> { x -> kotlin.math.sqrt(x) }
                "cbrt" -> { x -> Math.cbrt(x) }
                "sin" -> trig { kotlin.math.sin(Math.toRadians(it)) }
                "cos" -> trig { kotlin.math.cos(Math.toRadians(it)) }
                "tan" -> trig { x ->
                    val r = kotlin.math.tan(Math.toRadians(x))
                    if (abs(r) > 1e15) Double.NaN else r
                }
                "asin" -> trig { Math.toDegrees(kotlin.math.asin(it)) }
                "acos" -> trig { Math.toDegrees(kotlin.math.acos(it)) }
                "atan" -> trig { Math.toDegrees(kotlin.math.atan(it)) }
                "ln" -> { x -> kotlin.math.ln(x) }
                "log" -> { x -> kotlin.math.log10(x) }
                "log2" -> { x -> kotlin.math.log2(x) }
                "abs" -> { x -> abs(x) }
                "exp" -> { x -> kotlin.math.exp(x) }
                "floor" -> { x -> floor(x) }
                "ceil" -> { x -> kotlin.math.ceil(x) }
                "round" -> { x -> kotlin.math.round(x) }
                else -> throw ParseException()
            }
            skipSpaces()
            val argument = if (peek() == '(') primary() else power()
            operations++
            return Value(fn(argument.number))
        }

        private inline fun trig(crossinline f: (Double) -> Double): (Double) -> Double {
            usedTrig = true
            return { x ->
                val r = f(x)
                // Snap floating-point noise so sin(180) is 0, not 1.2e-16.
                if (abs(r) < 1e-12) 0.0 else r
            }
        }

        private fun factorial(n: Double): Double {
            if (n < 0 || n != floor(n) || n > 170) return Double.NaN
            var result = 1.0
            for (i in 2..n.toInt()) result *= i
            return result
        }

        private fun skipSpaces() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null
    }
}
