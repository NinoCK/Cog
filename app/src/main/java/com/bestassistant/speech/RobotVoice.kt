package com.bestassistant.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Classic signal processing that turns a plain text-to-speech voice into a robot: phase-vocoder
 * robotisation for a flat electronic buzz, a flanger for electronic doubling, ring modulation for
 * the metallic buzz, and a short feedback comb filter for the "voice inside a tin can" resonance. Deterministic, offline, and fast enough to run on a
 * sentence before it's played.
 */
object RobotVoice {

    fun process(input: ShortArray, sampleRate: Int, fx: RobotEffect): ShortArray {
        if (input.isEmpty()) return input
        val rate = sampleRate.toFloat()
        var x = FloatArray(input.size) { input[it] / 32768f }

        if (fx.robotMix > 0f) {
            val hz = fx.robotHz.takeIf { it > 0f } ?: Pitch.median(input, sampleRate) ?: DEFAULT_ROBOT_HZ
            x = robotize(x, rate, hz.coerceIn(70f, 400f), fx.robotMix)
        }
        if (fx.highPassHz > 0f) x = highPass(x, rate, fx.highPassHz)
        if (fx.flangeMix > 0f) x = flange(x, rate, fx)
        if (fx.ringMix > 0f) ringModulate(x, rate, fx.ringHz, fx.ringMix)
        if (fx.combMix > 0f) x = comb(x, rate, fx.combMs, fx.combFeedback, fx.combMix)
        if (fx.crushBits in 2..15) crush(x, fx.crushBits)
        return toPcm(x)
    }

    /**
     * Phase-vocoder robotisation: each short frame keeps the magnitudes of its spectrum but loses
     * its phases, which turns it into a single buzz pulse with the same formants. A pulse every
     * 1/[hz] seconds makes the voice speak the same words, at the same speed, on one flat note.
     * Mixed under the dry voice it keeps the natural tone and adds an electronic edge.
     */
    private fun robotize(x: FloatArray, rate: Float, hz: Float, mix: Float): FloatArray {
        val hop = (rate / hz).roundToInt().coerceAtLeast(1)
        // About 10 ms, short enough not to resolve the voice's own harmonics, and two hops at least.
        val n = maxOf(Integer.highestOneBit((rate * 0.012f).toInt()), Integer.highestOneBit(2 * hop - 1) shl 1, 64)
        val window = FloatArray(n) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / n) }
        val wet = FloatArray(x.size)
        val re = FloatArray(n)
        val im = FloatArray(n)
        var start = -n / 2
        while (start < x.size) {
            for (i in 0 until n) {
                val j = start + i
                re[i] = if (j in x.indices) x[j] * window[i] else 0f
                im[i] = 0f
            }
            fft(re, im)
            for (k in 0 until n) {
                re[k] = hypot(re[k], im[k])
                im[k] = 0f
            }
            // The transform of a real, symmetric spectrum is a zero-phase pulse at index 0.
            fft(re, im)
            for (i in 0 until n) {
                val j = start + i
                if (j in x.indices) wet[j] += re[(i + n / 2) % n] * window[i]
            }
            start += hop
        }
        val wetLevel = rms(wet)
        val gain = if (wetLevel > 0f) rms(x) / wetLevel else 0f
        return FloatArray(x.size) { (1f - mix) * x[it] + mix * gain * wet[it] }
    }

    /** In-place radix-2 FFT; the size must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            for (start in 0 until n step length) {
                for (k in 0 until length / 2) {
                    val c = cos(angle * k).toFloat()
                    val s = sin(angle * k).toFloat()
                    val a = start + k
                    val b = a + length / 2
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            length = length shl 1
        }
    }

    private fun rms(x: FloatArray): Float {
        var sum = 0.0
        for (v in x) sum += v * v
        return if (x.isEmpty()) 0f else sqrt(sum / x.size).toFloat()
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
    private const val DEFAULT_ROBOT_HZ = 200f
}
