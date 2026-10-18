package com.bestassistant.integration

import android.content.Intent

/** What running a command needs from the UI. */
sealed interface Step {
    /**
     * Start the first of [intents] that resolves, then confirm with [confirmation]. [then] runs in
     * the background afterwards (e.g. starting the song once the music app is up).
     */
    data class Launch(
        val intents: List<Intent>,
        val confirmation: String? = null,
        val then: (suspend () -> Unit)? = null,
        /** Better intents found online (e.g. the artist's Spotify page), tried before [intents]. */
        val lookup: (suspend () -> List<Intent>)? = null,
        /** Replaces the usual reply, for one that depends on what was found ("You parked 2 hours ago"). */
        val spoken: String? = null,
    ) : Step

    /** Already done in-process. [spoken] replaces the usual reply (e.g. Home Assistant's own answer). */
    data class Done(val confirmation: String?, val spoken: String? = null) : Step

    /** Needs a moment over the network (Home Assistant); [run] says how it went. */
    data class Remote(val run: suspend () -> Step) : Step

    /**
     * A question back ("Which one? Living room, bedroom."): [question] is said, and what the user
     * says next goes to [answer]; null from it means the reply wasn't an answer, but a new request.
     */
    data class Ask(val question: String, val answer: suspend (String) -> Step?) : Step

    data class Failed(val message: String) : Step
}
