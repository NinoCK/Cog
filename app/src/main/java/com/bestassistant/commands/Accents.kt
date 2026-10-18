package com.bestassistant.commands

import java.text.Normalizer

/** Text without accents, for matching what was said against names: "Café" is "Cafe". */
object Accents {

    private val marks = Regex("\\p{Mn}+")

    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "")
}
