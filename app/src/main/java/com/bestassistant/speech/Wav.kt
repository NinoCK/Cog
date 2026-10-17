package com.bestassistant.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono 16-bit samples at [rate] Hz. */
class Pcm(val samples: ShortArray, val rate: Int)

/** Reads the PCM WAV files text-to-speech engines write with synthesizeToFile. */
object Wav {

    fun parse(bytes: ByteArray): Pcm? {
        if (bytes.size < 12) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        var position = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (position + 8 <= bytes.size) {
            val id = tag(bytes, position)
            val declared = buffer.getInt(position + 4)
            val start = position + 8
            val remaining = bytes.size - start
            when (id) {
                "fmt " -> {
                    if (remaining < 16 || buffer.getShort(start).toInt() != PCM_FORMAT) return null
                    channels = buffer.getShort(start + 2).toInt()
                    rate = buffer.getInt(start + 4)
                    bits = buffer.getShort(start + 14).toInt()
                }
                "data" -> {
                    if (channels !in 1..2 || bits != 16 || rate <= 0) return null
                    // Streaming writers leave the size as 0 (or garbage): read to the end instead.
                    val size = if (declared in 1..remaining) declared else remaining
                    val frames = size / (2 * channels)
                    val samples = ShortArray(frames) { i ->
                        val at = start + i * 2 * channels
                        if (channels == 1) {
                            buffer.getShort(at)
                        } else {
                            ((buffer.getShort(at) + buffer.getShort(at + 2)) / 2).toShort()
                        }
                    }
                    return Pcm(samples, rate)
                }
            }
            if (declared < 0 || declared > remaining) return null
            position = start + declared + (declared and 1) // chunks are word-aligned
        }
        return null
    }

    private fun tag(bytes: ByteArray, at: Int) =
        if (at + 4 <= bytes.size) String(bytes, at, 4, Charsets.US_ASCII) else ""

    private const val PCM_FORMAT = 1
}
