package com.bestassistant.speech

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The speaking pitch of a voice, measured from its audio, and the resampling that turns a voice
 * male or female. Text-to-speech engines don't say which of their voices are male, so the
 * assistant listens to each one instead.
 */
object Pitch {

    /** Typical speaking pitch, in Hz. Voices below [MALE_BELOW] sound male. */
    const val MALE_TARGET = 118f
    const val FEMALE_TARGET = 205f
    const val MALE_BELOW = 160f

    /**
     * The median fundamental frequency of the voiced parts of [samples], or null when too little
     * of it is voiced. Normalised autocorrelation per 40 ms frame; when several lags correlate
     * almost equally, the shortest wins, which avoids reading a voice an octave too low.
     */
    fun median(samples: ShortArray, rate: Int): Float? {
        val frame = (0.04 * rate).toInt()
        val hop = frame / 2
        val minLag = rate / MAX_HZ
        val maxLag = rate / MIN_HZ
        if (samples.size < frame + maxLag) return null
        val x = FloatArray(samples.size) { samples[it] / 32768f }
        var total = 0.0
        for (v in x) total += v * v
        val loudness = total / x.size
        val estimates = ArrayList<Float>()
        val scores = FloatArray(maxLag + 1)
        var start = 0
        while (start + frame + maxLag < x.size) {
            var energy = 0.0
            for (i in start until start + frame) energy += x[i] * x[i]
            // Skip silence and breaths: frames well below the clip's average loudness.
            if (energy / frame >= loudness * 0.3) {
                var best = 0f
                for (lag in minLag..maxLag) {
                    var cross = 0.0
                    var shifted = 0.0
                    for (i in start until start + frame) {
                        cross += x[i] * x[i + lag]
                        shifted += x[i + lag] * x[i + lag]
                    }
                    val score = if (shifted > 0) (cross / sqrt(energy * shifted)).toFloat() else 0f
                    scores[lag] = score
                    if (score > best) best = score
                }
                if (best > VOICED) {
                    // The first peak close to the best one is the true period.
                    var lag = minLag
                    while (lag < maxLag) {
                        val peak = scores[lag] >= best * 0.9f && scores[lag] >= scores[lag - 1] && scores[lag] >= scores[lag + 1]
                        if (peak) break
                        lag++
                    }
                    estimates += rate.toFloat() / lag
                }
            }
            start += hop
        }
        if (estimates.size < MIN_FRAMES) return null
        estimates.sort()
        return estimates[estimates.size / 2]
    }

    /**
     * Stretches [input] by [factor] with linear interpolation. Played back at the same sample
     * rate, factor > 1 lowers pitch and formants together (a bigger, deeper voice), and < 1 raises
     * them; the speech also slows or quickens by the same factor, which the caller compensates by
     * asking the engine to speak faster or slower.
     */
    fun resample(input: ShortArray, factor: Float): ShortArray {
        if (input.isEmpty() || factor == 1f) return input
        val length = (input.size * factor).roundToInt().coerceAtLeast(1)
        val last = input.size - 1
        return ShortArray(length) { i ->
            val position = i / factor
            val j = position.toInt().coerceAtMost(last)
            val frac = position - j
            val next = if (j < last) input[j + 1] else input[j]
            (input[j] * (1 - frac) + next * frac).roundToInt().toShort()
        }
    }

    /** How a voice measured at [measured] Hz becomes [male] or female: resample factor and engine pitch. */
    data class Shift(val factor: Float, val pitch: Float) {
        val changesSound: Boolean get() = factor != 1f
    }

    fun shiftFor(measured: Float?, male: Boolean): Shift {
        if (measured == null) return Shift(1f, 1f)
        val isMale = measured < MALE_BELOW
        if (isMale == male) return Shift(1f, 1f)
        // Formants differ by roughly 15–20% between men and women; the engine's pitch makes up the rest.
        val factor = if (male) MALE_FORMANTS else FEMALE_FORMANTS
        val target = if (male) MALE_TARGET else FEMALE_TARGET
        val pitch = (target * factor / measured).coerceIn(0.5f, 2f)
        return Shift(factor, pitch)
    }

    private const val MIN_HZ = 70
    private const val MAX_HZ = 400
    private const val VOICED = 0.6f
    private const val MIN_FRAMES = 4
    private const val MALE_FORMANTS = 1.22f
    private const val FEMALE_FORMANTS = 0.84f
}
