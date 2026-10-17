package com.bestassistant.speech

import androidx.annotation.StringRes
import com.fastsearch.R

/**
 * How the assistant sounds: the phone's own text-to-speech voice, at a given pitch and speed,
 * optionally run through [RobotVoice] effects, or the downloadable [SupertonicVoice].
 * Everything is computed on the device.
 */
enum class VoiceStyle(
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    val pitch: Float,
    val rate: Float,
    val effect: RobotEffect?,
    /** For English, prefer a British voice when the user hasn't picked one. */
    val prefersBritish: Boolean = false,
) {
    /** The natural Supertonic voice, once downloaded (pitch, rate and effect don't apply). */
    SUPERTONIC(R.string.voice_supertonic, R.string.voice_supertonic_body, pitch = 1f, rate = 1f, effect = null),

    /** A polite little robot: brighter pitch, a metallic shimmer and a light electronic doubling. */
    COG(
        R.string.voice_cog, R.string.voice_cog_body, pitch = 1.14f, rate = 0.97f,
        effect = RobotEffect(
            highPassHz = 170f,
            flangeMs = 1.6f, flangeDepthMs = 0.9f, flangeHz = 0.45f, flangeMix = 0.45f,
            ringHz = 62f, ringMix = 0.16f,
            combMs = 2.9f, combFeedback = 0.52f, combMix = 0.32f,
        ),
        prefersBritish = true,
    ),
    NATURAL(R.string.voice_natural, R.string.voice_natural_body, pitch = 1f, rate = 1f, effect = null),

    /**
     * The natural voice at its own pitch and speed, with a robotised copy on one flat note mixed
     * underneath and a faint metallic resonance: still clearly the phone's voice, slightly synthetic.
     */
    ANDROID(
        R.string.voice_android, R.string.voice_android_body, pitch = 1f, rate = 1f,
        effect = RobotEffect(
            robotMix = 0.35f,
            combMs = 3f, combFeedback = 0.3f, combMix = 0.15f,
        ),
    ),

    /** The 1950s film robot: flat, buzzy, heavily ring-modulated. */
    CLASSIC_ROBOT(
        R.string.voice_classic, R.string.voice_classic_body, pitch = 0.9f, rate = 0.9f,
        effect = RobotEffect(
            highPassHz = 120f,
            ringHz = 33f, ringMix = 0.85f,
            combMs = 6.5f, combFeedback = 0.6f, combMix = 0.4f,
            crushBits = 9,
        ),
    ),
    TINY_BOT(
        R.string.voice_tiny, R.string.voice_tiny_body, pitch = 1.65f, rate = 1.1f,
        effect = RobotEffect(
            highPassHz = 220f,
            ringHz = 95f, ringMix = 0.14f,
            combMs = 1.7f, combFeedback = 0.5f, combMix = 0.3f,
        ),
    ),
    BIG_BOT(
        R.string.voice_big, R.string.voice_big_body, pitch = 0.62f, rate = 0.9f,
        effect = RobotEffect(
            highPassHz = 55f,
            flangeMs = 3.5f, flangeDepthMs = 1.5f, flangeHz = 0.25f, flangeMix = 0.3f,
            ringHz = 24f, ringMix = 0.28f,
            combMs = 9f, combFeedback = 0.62f, combMix = 0.42f,
        ),
    ),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: COG
    }
}

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

/** Parameters for [RobotVoice.process]; a mix of 0 switches a stage off. */
data class RobotEffect(
    val robotMix: Float = 0f,
    /** The robotised voice's flat note; 0 uses the voice's own median pitch. */
    val robotHz: Float = 0f,
    val highPassHz: Float = 0f,
    val flangeMs: Float = 0f,
    val flangeDepthMs: Float = 0f,
    val flangeHz: Float = 0f,
    val flangeMix: Float = 0f,
    val ringHz: Float = 0f,
    val ringMix: Float = 0f,
    val combMs: Float = 0f,
    val combFeedback: Float = 0f,
    val combMix: Float = 0f,
    /** Below 16, quantises the signal for a lo-fi, buzzy edge. */
    val crushBits: Int = 16,
)
