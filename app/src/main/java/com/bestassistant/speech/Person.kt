package com.bestassistant.speech

import java.util.Locale

/** A note in the user's words, said back to them: "call my mom" -> "call your mom". Only pronouns change. */
object Person {

    private val words = mapOf(
        "i" to "you", "me" to "you", "my" to "your", "mine" to "yours", "myself" to "yourself", "i'm" to "you're",
        "i've" to "you've", "i'll" to "you'll", "i'd" to "you'd", "am" to "are",
    )

    fun second(text: String): String {
        val said = text.split(' ')
        return said.mapIndexed { i, word ->
            val lower = word.lowercase(Locale.ROOT)
            when {
                // "am" only after "i": "8 am" stays.
                lower == "am" && said.getOrNull(i - 1)?.lowercase(Locale.ROOT) != "i" -> word
                lower in words -> words.getValue(lower)
                else -> word
            }
        }.joinToString(" ")
    }
}
