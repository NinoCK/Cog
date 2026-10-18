package com.bestassistant.routines

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.bestassistant.commands.CommandParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * A routine the user set up: saying one of its [phrases] ("good morning") runs its [steps] in
 * order, each an ordinary request ("weather today", "what's on my calendar today").
 */
@Immutable
data class Routine(val id: String, val phrases: List<String>, val steps: List<String>) {
    /** What it's called: its first phrase. */
    val name: String
        get() = phrases.firstOrNull { it.isNotBlank() }?.trim()?.replaceFirstChar { it.titlecase(Locale.ROOT) }.orEmpty()

    val isUsable: Boolean get() = name.isNotEmpty() && steps.any { it.isNotBlank() }

    companion object {
        fun new(phrases: List<String> = emptyList(), steps: List<String> = emptyList()) =
            Routine(UUID.randomUUID().toString(), phrases, steps)

        /** A starting point for "good morning". */
        val goodMorning: Routine
            get() = new(
                listOf("good morning"),
                listOf("weather today", "what's on my calendar today", "show my reminders"),
            )
    }
}

/** The user's routines, kept on the phone; their phrases are taught to the [CommandParser]. */
class Routines(context: Context) {

    private val prefs = context.getSharedPreferences("routines", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val all: StateFlow<List<Routine>> = state.asStateFlow()

    init {
        publish()
    }

    fun find(name: String): Routine? = state.value.firstOrNull { it.isUsable && it.name.equals(name, ignoreCase = true) }

    @Synchronized
    fun save(routine: Routine) {
        val list = state.value
        state.value = if (list.any { it.id == routine.id }) list.map { if (it.id == routine.id) routine else it } else list + routine
        write()
    }

    @Synchronized
    fun delete(id: String) {
        state.value = state.value.filter { it.id != id }
        write()
    }

    private fun write() {
        val array = JSONArray()
        for (routine in state.value) {
            array.put(
                JSONObject()
                    .put("id", routine.id)
                    .put("phrases", JSONArray(routine.phrases))
                    .put("steps", JSONArray(routine.steps)),
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
        publish()
    }

    private fun read(): List<Routine> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Routine(o.getString("id"), o.getJSONArray("phrases").strings(), o.getJSONArray("steps").strings())
        }
    }.getOrDefault(emptyList())

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun publish() {
        CommandParser.routines = state.value.filter { it.isUsable }.map { routine ->
            CommandParser.RoutineTrigger(routine.name, routine.phrases.map(CommandParser::normalize).filter { it.isNotEmpty() }.toSet())
        }
    }

    private companion object {
        const val KEY = "routines"
    }
}
