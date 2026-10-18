package com.bestassistant.routines

import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.bestassistant.commands.Command
import com.bestassistant.commands.CommandParser
import com.bestassistant.integration.Step
import com.fastsearch.FastSearchApp
import com.fastsearch.R
import com.fastsearch.data.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Runs a routine's steps one after another, each the way PocketSearch runs that request on its
 * own: answers are read out (and waited for) before the next step starts, actions run in place,
 * and a step that opens an app opens it. A step PocketSearch doesn't understand, or that would
 * need a question answered, is skipped.
 */
class RoutineRunner(private val app: FastSearchApp) {

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts [routine]. [speak]: replies are read aloud (otherwise shown). [launch] starts the first
     * intent an app takes; [done] runs on the main thread at the end, with whether a step opened an
     * app and whether the routine got to its end (rather than being cancelled by a new request).
     */
    fun start(routine: Routine, speak: Boolean, launch: (List<Intent>) -> Boolean, done: (openedApp: Boolean, finished: Boolean) -> Unit) {
        job?.cancel()
        job = app.appScope.launch {
            var opened = false
            var finished = false
            try {
                for (text in routine.steps.map(String::trim).filter(String::isNotEmpty)) {
                    coroutineContext.ensureActive()
                    opened = runCatchingStep { runStep(text, speak, launch) } || opened
                }
                finished = true
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { done(opened, finished) }
            }
        }
    }

    /** One step that fails doesn't stop the others. */
    private suspend fun runCatchingStep(step: suspend () -> Boolean): Boolean = try {
        step()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Routine step failed", e)
        false
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Runs one step; true when it opened an app. */
    private suspend fun runStep(text: String, speak: Boolean, launch: (List<Intent>) -> Boolean): Boolean {
        val command = CommandParser.parse(text)
        if (command == null || command is Command.Routine) {
            Log.i(TAG, "Skipped a step that isn't a request: $text")
            return false
        }
        if (command.isInformation) {
            say(app.answers.answer(command), speak)
            return false
        }
        var contact: Contact? = null
        if (command.needsContact) {
            contact = contactFor(command)
            if (contact == null) {
                say(app.localized.getString(R.string.error_no_number), speak)
                return false
            }
        }
        return handle(app.commands.plan(command, contact), command, contact, speak, launch)
    }

    private suspend fun handle(step: Step, command: Command, contact: Contact?, speak: Boolean, launch: (List<Intent>) -> Boolean): Boolean {
        val responder = app.responder
        when (step) {
            is Step.Launch -> {
                val found = step.lookup?.let { lookup -> withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup() } }.orEmpty()
                val started = withContext(Dispatchers.Main) { launch(found + step.intents) }
                if (!started) {
                    say(app.localized.getString(R.string.error_no_app), speak)
                    return false
                }
                step.then?.let { then -> app.appScope.launch { then() } }
                say(step.spoken ?: responder.forCommand(command, contact), speak)
                return true
            }
            is Step.Done -> say(step.spoken ?: responder.forCommand(command, contact, torchOn = app.commands.isTorchOn), speak)
            is Step.Remote -> return handle(step.run(), command, contact, speak, launch)
            is Step.Failed -> say(step.message, speak)
            // A routine can't wait for an answer: "remind me" without a time is skipped.
            is Step.Ask -> Log.i(TAG, "Skipped a step that asks a question")
        }
        return false
    }

    private suspend fun contactFor(command: Command): Contact? {
        val who = when (command) {
            is Command.Call -> command.who
            is Command.Message -> command.who
            is Command.ShareLocation -> command.who
            else -> return null
        }
        return app.contacts.search(who).firstOrNull { it.number != null }
    }

    /** Says [text] and waits until it has been said; shows it instead when replies aren't spoken. */
    private suspend fun say(text: String?, speak: Boolean) {
        if (text.isNullOrBlank()) return
        withContext(Dispatchers.Main) {
            if (!speak) {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                return@withContext
            }
            // A reply that gets interrupted never reports back: don't wait for it forever.
            withTimeoutOrNull(SPEECH_BASE_MS + text.length * SPEECH_PER_CHAR_MS) {
                suspendCancellableCoroutine { done -> app.speaker.speak(text) { if (done.isActive) done.resume(Unit) } }
            }
        }
    }

    private companion object {
        const val TAG = "Routine"
        const val LOOKUP_TIMEOUT_MS = 3_000L
        const val SPEECH_BASE_MS = 6_000L
        const val SPEECH_PER_CHAR_MS = 110L
    }
}
