package com.fastsearch.settings

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * The assistant role can't be requested programmatically (it's user-only by design), so the
 * best we can do is check it and deep-link to the "Digital assistant app" screen.
 */
object AssistantRole {

    fun isHeld(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    fun openSettings(context: Context) {
        val candidates = listOf(
            // Opens "Digital assistant app" on Pixel, Samsung and most AOSP-based builds.
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // try the next, more generic screen
            } catch (_: SecurityException) {
                // some OEM builds protect specific screens
            }
        }
    }
}
