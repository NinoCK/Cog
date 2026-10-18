package com.bestassistant.integration

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.flow.Flow

/**
 * One part of an integration's settings page. PocketSearch draws them all the same way, so an integration
 * describes what to ask for and never draws anything itself.
 */
sealed interface Setting {

    /** Starts a new group under [title]; settings before the first one form a group of their own. */
    data class Section(val title: String) : Setting

    /** A paragraph: what this does, or a step to follow. [small] for a footnote. */
    data class Note(val text: String, val small: Boolean = false) : Setting

    /**
     * A text field, saved under [key] in the integration's [SettingsStore] as it's typed.
     * [error] says what's wrong with a value (null: nothing); [changed] follows each edit.
     */
    data class Field(
        val key: String,
        val label: String,
        val hint: String? = null,
        val kind: FieldKind = FieldKind.TEXT,
        val error: (String) -> String? = { null },
        val changed: (String) -> Unit = {},
    ) : Setting

    /** A value to paste into another app or a website, with a copy button. */
    data class Copy(val label: String, val value: String) : Setting

    /** A button that opens [url] in the browser. */
    data class Link(val label: String, val url: String) : Setting

    /** A button that tests something ("Test connection"); what [run] reports shows below it. */
    data class Check(val label: String, val ready: () -> Boolean, val running: String, val run: suspend () -> Report) : Setting

    /** A button for a step that happens on another screen. */
    data class Action(val label: String, val enabled: () -> Boolean = { true }, val run: (Actions) -> Unit) : Setting

    /** How things stand, kept up to date. */
    data class Status(val report: Flow<Report?>) : Setting

    /** Something Android must allow first: done when [allowed], else a button to [open] where it's allowed. */
    data class Permission(val title: String, val body: String, val allowed: () -> Boolean, val open: () -> Intent) : Setting

    /**
     * Things saved under a name to say ("road trip": a playlist link), with a way to add more:
     * a dialog asks for the [value] (which [parse] reads, null when it isn't one) and the name.
     */
    data class Entries(
        val intro: String,
        val add: String,
        val items: Flow<List<Entry>>,
        val value: String,
        val valueHint: String,
        val name: String,
        val parse: (String) -> String?,
        val save: (name: String, value: String) -> Unit,
        val remove: (Entry) -> Unit,
    ) : Setting
}

enum class FieldKind { TEXT, ADDRESS, SECRET }

/** One saved thing: its [name], a line about it, and whatever the integration needs to remove it. */
data class Entry(val name: String, val detail: String, val key: Any)

/** A line saying how something went or stands. */
data class Report(val text: String, val tone: Tone = Tone.NEUTRAL)

enum class Tone { NEUTRAL, GOOD, BAD }

/** What a settings button may do: open another screen, and hear back from it. */
interface Actions {
    /** The settings screen, for libraries that build their own screen's intent from it. */
    val activity: Activity

    fun start(intent: Intent)

    /** Opens [intent]; [result] runs with what it answered, even if the settings screen is gone by then. */
    fun startForResult(intent: Intent, result: suspend (resultCode: Int, data: Intent?) -> Unit)
}
