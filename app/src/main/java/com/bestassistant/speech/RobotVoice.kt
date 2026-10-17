package com.bestassistant.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Classic signal processing that turns a plain text-to-speech voice into a robot: a flanger for
 * electronic doubling, ring modulation for the metallic buzz, and a short feedback comb filter for
 * the "voice inside a tin can" resonance. Deterministic, offline, and fast enough to run on a
 * sentence before it's played.
 */
object RobotVoice {

    fun process(input: ShortArray, sampleRate: Int, fx: RobotEffect): ShortArray {
        if (input.isEmpty()) return input
        val rate = sampleRate.toFloat()
        var x = FloatArray(input.size) { input[it] / 32768f }

        if (fx.highPassHz > 0f) x = highPass(x, rate, fx.highPassHz)
        if (fx.flangeMix > 0f) x = flange(x, rate, fx)
        if (fx.ringMix > 0f) ringModulate(x, rate, fx.ringHz, fx.ringMix)
        if (fx.combMix > 0f) x = comb(x, rate, fx.combMs, fx.combFeedback, fx.combMix)
        if (fx.crushBits in 2..15) crush(x, fx.crushBits)
        return toPcm(x)
    }

    /** One-pole high-pass: thins the voice like a small speaker. */
    private fun highPass(x: FloatArray, rate: Float, cutoff: Float): FloatArray {
        val rc = 1f / (2f * PI.toFloat() * cutoff)
        val alpha = rc / (rc + 1f / rate)
        val y = FloatArray(x.size)
        var prevX = 0f
        var prevY = 0f
        for (i in x.indices) {
            val out = alpha * (prevY + x[i] - prevX)
            y[i] = out
            prevX = x[i]
            prevY = out
        }
        return y
    }

    /** A copy of the signal on a slowly swept short delay, mixed back in. */
    private fun flange(x: FloatArray, rate: Float, fx: RobotEffect): FloatArray {
        val base = fx.flangeMs * rate / 1000f
        val depth = fx.flangeDepthMs * rate / 1000f
        val step = 2f * PI.toFloat() * fx.flangeHz / rate
        val y = FloatArray(x.size)
        for (i in x.indices) {
            val delay = base + depth * sin(step * i)
            val position = i - delay
            val j = position.toInt()
            val frac = position - j
            val delayed = if (j >= 0 && j + 1 < x.size) x[j] * (1 - frac) + x[j + 1] * frac else 0f
            y[i] = (x[i] + fx.flangeMix * delayed) / (1f + fx.flangeMix)
        }
        return y
    }

    /** Multiplies the voice with a low sine: the buzzy, metallic robot timbre. */
    private fun ringModulate(x: FloatArray, rate: Float, hz: Float, mix: Float) {
        val step = 2f * PI.toFloat() * hz / rate
        for (i in x.indices) x[i] *= (1f - mix) + mix * sin(step * i)
    }

    /** Feedback comb filter: a few milliseconds of resonance, like a voice in a metal body. */
    private fun comb(x: FloatArray, rate: Float, ms: Float, feedback: Float, mix: Float): FloatArray {
        val delay = (ms * rate / 1000f).roundToInt().coerceAtLeast(1)
        val wet = FloatArray(x.size)
        for (i in x.indices) {
            wet[i] = x[i] + if (i >= delay) feedback * wet[i - delay] else 0f
        }
        // The comb's gain at its peaks is 1 / (1 - feedback); scale the wet part back to unity.
        val norm = 1f - feedback
        return FloatArray(x.size) { (1f - mix) * x[it] + mix * wet[it] * norm }
    }

    private fun crush(x: FloatArray, bits: Int) {
        val levels = (1 shl (bits - 1)).toFloat()
        for (i in x.indices) x[i] = (x[i] * levels).roundToInt() / levels
    }

    /** Normalises to a safe peak with a gentle soft clip, then back to 16-bit PCM. */
    private fun toPcm(x: FloatArray): ShortArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, abs(v))
        val gain = if (peak > 0f) TARGET_PEAK / peak else 1f
        return ShortArray(x.size) { i ->
            val v = tanh(x[i] * gain * 1.1f) / tanh(1.1f)
            (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * A short "bee-boop" acknowledgment: two tones gliding up, with a soft attack and release.
     * Played when the wake word is heard, so it's clear the assistant is listening.
     */
    fun chirp(sampleRate: Int): ShortArray {
        val notes = listOf(Tone(880f, 1320f, 0.075f), Tone(1320f, 1760f, 0.09f))
        val gap = (0.035f * sampleRate).toInt()
        val out = ArrayList<Short>()
        for ((index, tone) in notes.withIndex()) {
            val length = (tone.seconds * sampleRate).toInt()
            var phase = 0.0
            for (i in 0 until length) {
                val t = i / length.toFloat()
                val hz = tone.from + (tone.to - tone.from) * t
                phase += 2 * PI * hz / sampleRate
                // Square-ish timbre (fundamental plus a little third harmonic) sounds more electronic.
                val wave = sin(phase) + 0.25 * sin(3 * phase)
                val envelope = (1 - exp(-t * 40.0)) * exp(-t * 2.5) * (1 - t)
                out += (wave * envelope * 0.32 * 32767).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            if (index < notes.lastIndex) repeat(gap) { out += 0 }
        }
        return out.toShortArray()
    }

    private data class Tone(val from: Float, val to: Float, val seconds: Float)

    private const val TARGET_PEAK = 0.85f
}
