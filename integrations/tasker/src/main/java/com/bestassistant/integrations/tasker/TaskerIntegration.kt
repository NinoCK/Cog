package com.bestassistant.integrations.tasker

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.bestassistant.integration.Actions
import com.bestassistant.integration.Card
import com.bestassistant.integration.Integration
import com.bestassistant.integration.IntegrationHost
import com.bestassistant.integration.Report
import com.bestassistant.integration.Request
import com.bestassistant.integration.RequestParser
import com.bestassistant.integration.Setting
import com.bestassistant.integration.Step
import com.bestassistant.integration.Tone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user's Tasker tasks, run by name ("run the task called lights out"), once Tasker lets PocketSearch:
 * PocketSearch asks for its run-tasks permission here, and Tasker's own Allow External Access must be on.
 */
class TaskerIntegration(private val host: IntegrationHost) : Integration {

    override val id: String = ID

    private val store = host.store(ID)
    private val tasker = Tasker(host.context)

    private fun s(id: Int, vararg args: Any): String = host.strings.getString(id, *args)

    override val name: String get() = s(R.string.tasker_name)
    override val description: String get() = s(R.string.tasker_description)
    override val icon: Int = R.drawable.ic_integration_tasker

    // Collected again each time the settings come back into view, so it follows what changed in Tasker meanwhile.
    override val summary: Flow<String> = store.changes.map {
        s(
            when (tasker.status()) {
                Tasker.Status.READY -> R.string.tasker_summary_ready
                Tasker.Status.NOT_INSTALLED -> R.string.tasker_summary_missing
                Tasker.Status.NO_PERMISSION -> R.string.tasker_summary_permission
                Tasker.Status.DISABLED -> R.string.tasker_summary_disabled
                Tasker.Status.NO_EXTERNAL_ACCESS -> R.string.tasker_summary_access
            },
        )
    }

    override val parser: RequestParser = TaskRequests

    override fun plan(request: Request): Step {
        val task = request as? TaskRequest ?: return Step.Failed(s(R.string.error_tasker_missing))
        return when (tasker.status()) {
            Tasker.Status.NOT_INSTALLED -> Step.Failed(s(R.string.error_tasker_missing))
            Tasker.Status.NO_PERMISSION -> Step.Failed(s(R.string.error_tasker_permission))
            Tasker.Status.DISABLED -> Step.Failed(s(R.string.error_tasker_disabled))
            Tasker.Status.NO_EXTERNAL_ACCESS -> Step.Failed(s(R.string.error_tasker_access))
            Tasker.Status.READY -> {
                val name = tasker.resolve(task.name)
                if (tasker.run(name)) Step.Done(s(R.string.done_task, name), spoken = s(R.string.say_task, name))
                else Step.Failed(s(R.string.error_tasker_missing))
            }
        }
    }

    override fun card(request: Request): Card? = (request as? TaskRequest)?.let { Card(s(R.string.cmd_task, it.name), s(R.string.tasker_name)) }

    /** How things stand, and a button for each of the two things that may be left to do. */
    override fun settings(): List<Setting> = listOf(
        Setting.Note(s(R.string.tasker_intro)),
        Setting.Status(store.changes.map { status() }),
        Setting.Action(
            label = s(R.string.tasker_allow),
            enabled = { tasker.installedPackage() != null && !tasker.hasPermission() },
            run = ::allow,
        ),
        Setting.Action(
            label = s(R.string.tasker_open_prefs),
            enabled = { tasker.status().let { it == Tasker.Status.DISABLED || it == Tasker.Status.NO_EXTERNAL_ACCESS } },
            run = ::openTasker,
        ),
    )

    private fun status(): Report = when (tasker.status()) {
        Tasker.Status.READY -> Report(s(R.string.tasker_status_ready), Tone.GOOD)
        Tasker.Status.NOT_INSTALLED -> Report(s(R.string.tasker_status_missing))
        Tasker.Status.NO_PERMISSION -> Report(s(R.string.tasker_status_permission))
        Tasker.Status.DISABLED -> Report(s(R.string.tasker_status_disabled))
        Tasker.Status.NO_EXTERNAL_ACCESS -> Report(s(R.string.tasker_status_access))
    }

    /** Android's own "Allow PocketSearch to run Tasker tasks?", or, once that's been refused for good, PocketSearch's page in Android's settings. */
    private fun allow(actions: Actions) {
        val activity = actions.activity
        if (store.get(KEY_ASKED) == "true" && !activity.shouldShowRequestPermissionRationale(Tasker.PERMISSION_RUN_TASKS)) {
            actions.start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
            return
        }
        // AndroidX's own request for a runtime permission, which a settings screen answers like any other.
        val request = Intent(ACTION_REQUEST_PERMISSIONS).putExtra(EXTRA_PERMISSIONS, arrayOf(Tasker.PERMISSION_RUN_TASKS))
        actions.startForResult(request) { _, _ -> store.set(KEY_ASKED, "true") }
    }

    /** Tasker's preferences, where it's turned on and External Access allowed; Tasker itself if they won't open. */
    private fun openTasker(actions: Actions) {
        runCatching { actions.start(tasker.externalAccessIntent()) }
            .recoverCatching {
                tasker.installedPackage()?.let { pkg -> host.context.packageManager.getLaunchIntentForPackage(pkg)?.let(actions::start) }
            }
    }

    companion object {
        const val ID = "tasker"

        /** Whether PocketSearch has asked for the run-tasks permission before: refused again after that, it's refused for good. */
        private const val KEY_ASKED = "asked_permission"

        // ActivityResultContracts.RequestMultiplePermissions' own intent, without depending on androidx.activity for two names.
        private const val ACTION_REQUEST_PERMISSIONS = "androidx.activity.result.contract.action.REQUEST_PERMISSIONS"
        private const val EXTRA_PERMISSIONS = "androidx.activity.result.contract.extra.PERMISSIONS"
    }
}
