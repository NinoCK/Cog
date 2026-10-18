package com.bestassistant.speech

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

object Chirp {

    /**
     * A short "bee-boop" acknowledgment: two tones gliding up, with a soft attack and release.
     * Played when the wake word is heard, so it's clear the assistant is listening.
     */
    fun samples(sampleRate: Int): ShortArray {
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
}
