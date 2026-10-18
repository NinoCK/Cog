package com.bestassistant.integrations.tasker

import com.bestassistant.integration.Request
import com.bestassistant.integration.RequestParser
import com.bestassistant.integration.Slot

/** "run the task called lights out": the Tasker task named [name], as said. */
data class TaskRequest(val name: String) : Request

/**
 * Finds requests to run a Tasker task, late: after everything PocketSearch does itself, so "start the
 * timer" or "run spotify" stay PocketSearch's own.
 */
object TaskRequests : RequestParser {

    private val taskPattern = Regex("^(?:run|start|launch|trigger|execute|perform|fire|do)(?: the| my)?(?: tasker)? task(?: called| named)? (.+)$")
    private val namedTask = Regex("^(?:run|start|launch|trigger|execute|perform|fire)(?: the| my)? (.+?)(?: tasker)? task$")
    private val taskInTasker = Regex("^(?:run|start|launch|trigger|execute|perform|fire)(?: the| my)? (.+?) (?:in|on|with|using|through) tasker$")
    private val taskerFirst = Regex("^tasker(?: run| task| start)* (.+)$")

    override fun parse(text: String, slot: Slot): TaskRequest? {
        if (slot != Slot.LATE) return null
        val name = taskPattern.matchEntire(text)?.groupValues?.get(1)
            ?: namedTask.matchEntire(text)?.groupValues?.get(1)
            ?: taskInTasker.matchEntire(text)?.groupValues?.get(1)
            ?: taskerFirst.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val cleaned = name.removePrefix("task ").removeSuffix(" task").trim()
        return cleaned.takeIf { it.isNotEmpty() && it != "tasker" }?.let(::TaskRequest)
    }
}
