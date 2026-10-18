package com.bestassistant.integrations.tasker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.util.Log
import java.util.UUID

/**
 * Runs the user's Tasker tasks by name, the way Tasker documents for other apps (its TaskerIntent):
 * a broadcast that Tasker accepts once the user allows Cog to run tasks (a runtime permission) and
 * turns on Preferences › Misc › Allow External Access in Tasker.
 */
internal class Tasker(private val context: Context) {

    enum class Status { READY, NOT_INSTALLED, NO_PERMISSION, DISABLED, NO_EXTERNAL_ACCESS }

    /** The installed Tasker's package: the Play Store build, or the direct-download one. */
    fun installedPackage(): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    fun hasPermission(): Boolean =
        context.checkPermission(PERMISSION_RUN_TASKS, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED

    fun status(): Status {
        if (installedPackage() == null) return Status.NOT_INSTALLED
        if (!hasPermission()) return Status.NO_PERMISSION
        // Tasker answers these from its own provider; when it can't be asked, let the broadcast try.
        if (pref(COLUMN_ENABLED) == false) return Status.DISABLED
        if (pref(COLUMN_EXTERNAL_ACCESS) == false) return Status.NO_EXTERNAL_ACCESS
        return Status.READY
    }

    /** Tasker's preferences on the Misc tab, where Allow External Access is. */
    fun externalAccessIntent(): Intent = Intent(ACTION_OPEN_PREFS).putExtra(EXTRA_OPEN_PREFS_TAB, MISC_PREFS_TAB)

    /**
     * The name of the user's task that best matches what they said ("lights out" -> "Lights Out"),
     * when Tasker lists its tasks; otherwise what they said, as Tasker matches names exactly.
     */
    fun resolve(spoken: String): String = TaskNames.best(spoken, taskNames()) ?: spoken

    /** Asks Tasker to run [task]; false when no Tasker took the broadcast. */
    fun run(task: String): Boolean {
        val pkg = installedPackage() ?: return false
        val intent = Intent(ACTION_TASK)
            .setPackage(pkg)
            // Tasker tells requests apart by their data: a fresh id each time, like TaskerIntent does.
            .setData(Uri.parse("$TASK_ID_SCHEME:${UUID.randomUUID()}"))
            .putExtra(EXTRA_VERSION, INTENT_VERSION)
            .putExtra(EXTRA_TASK_NAME, task)
        return runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "Tasker refused the request", it) }
            .isSuccess
    }

    private fun taskNames(): List<String> {
        val pkg = installedPackage() ?: return emptyList()
        return runCatching {
            context.contentResolver.query(Uri.parse("content://$pkg/tasks"), null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex("name")
                if (column < 0) return@use emptyList()
                buildList { while (cursor.moveToNext()) cursor.getString(column)?.let(::add) }
            }.orEmpty()
        }.onFailure { Log.i(TAG, "Tasker's task list isn't available", it) }.getOrDefault(emptyList())
    }

    /** One of Tasker's yes/no preferences; null when it couldn't be read. */
    private fun pref(column: String): Boolean? {
        val pkg = installedPackage() ?: return null
        return runCatching {
            context.contentResolver.query(Uri.parse("content://$pkg/prefs"), arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) == "true" else null
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Tasker"
        private val PACKAGES = listOf("net.dinglisch.android.taskerm", "net.dinglisch.android.tasker")
        const val PERMISSION_RUN_TASKS = "net.dinglisch.android.tasker.PERMISSION_RUN_TASKS"
        private const val ACTION_TASK = "net.dinglisch.android.tasker.ACTION_TASK"
        private const val ACTION_OPEN_PREFS = "net.dinglisch.android.tasker.ACTION_OPEN_PREFS"
        private const val EXTRA_OPEN_PREFS_TAB = "tno"
        private const val MISC_PREFS_TAB = 3
        private const val EXTRA_VERSION = "version_number"
        private const val INTENT_VERSION = "1.1"
        private const val EXTRA_TASK_NAME = "task_name"
        private const val TASK_ID_SCHEME = "id"
        private const val COLUMN_ENABLED = "enabled"
        private const val COLUMN_EXTERNAL_ACCESS = "ext_access"
    }
}
