package com.bestassistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The screen brightness, through the system setting. Changing it needs "Modify system settings",
 * a special permission the user grants once ([permissionIntent]).
 *
 * Percentages are the brightness slider's, not the raw value: Android's slider follows a
 * perceptual (gamma) curve, so half way on the slider is far below half the raw maximum. The
 * conversion is the platform's own (BrightnessUtils, the HLG curve).
 */
object Brightness {

    fun canChange(context: Context): Boolean = Settings.System.canWrite(context)

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))

    /** Where the slider is, 0 to 100. */
    fun percent(context: Context): Int? = runCatching {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        toPercent(raw)
    }.getOrNull()

    fun isAdaptive(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    /**
     * Moves the slider to [percent]. Adaptive brightness would move it straight back, so it's
     * turned off first; returns whether it was on (null when the change was refused).
     */
    fun set(context: Context, percent: Int): Boolean? = runCatching {
        val resolver = context.contentResolver
        val wasAdaptive = isAdaptive(context)
        if (wasAdaptive) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, toRaw(percent.coerceIn(0, 100)))
        wasAdaptive
    }.getOrNull()

    fun setAdaptive(context: Context, on: Boolean): Boolean = runCatching {
        val mode = if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
    }.getOrDefault(false)

    /** The raw setting (1 to 255) for a slider position. */
    fun toRaw(percent: Int): Int {
        val normalized = percent / 100.0
        val ret = if (normalized <= R) (normalized / R).let { it * it } else exp((normalized - C) / A) + B
        return (MIN + (MAX - MIN) * (ret / 12.0)).roundToInt().coerceIn(MIN, MAX)
    }

    /** The slider position for a raw setting. */
    fun toPercent(raw: Int): Int {
        val normalized = (raw.coerceIn(MIN, MAX) - MIN).toDouble() / (MAX - MIN) * 12.0
        val ret = if (normalized <= 1.0) sqrt(normalized) * R else A * ln(normalized - B) + C
        return (ret * 100).roundToInt().coerceIn(0, 100)
    }

    private const val R = 0.5
    private const val A = 0.17883277
    private const val B = 0.28466892
    private const val C = 0.55991073
    private const val MIN = 1
    private const val MAX = 255

    /** How far "brighter" and "dimmer" move the slider. */
    const val STEP = 15
}
