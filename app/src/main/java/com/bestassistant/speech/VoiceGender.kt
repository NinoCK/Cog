package com.bestassistant.speech

import androidx.annotation.StringRes
import com.fastsearch.R

/**
 * Whether the assistant sounds like a man or a woman, whatever voices the phone has: a voice of
 * that kind is used when there is one, otherwise the voice is reshaped (see [Pitch.shiftFor]).
 */
enum class VoiceGender(@param:StringRes val label: Int) {
    MALE(R.string.gender_male),
    FEMALE(R.string.gender_female),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: MALE
    }
}
