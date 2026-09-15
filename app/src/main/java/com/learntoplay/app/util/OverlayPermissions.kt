package com.learntoplay.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.learntoplay.app.accessibility.AppLockAccessibilityService

/**
 * The two permissions the enforcement loop needs — "draw over other apps" (for the lock
 * overlay) and the Accessibility Service toggle (for foreground-app detection) — are both
 * "special access" permissions Android won't grant through a normal runtime dialog. The user
 * has to flip them on in Settings. These helpers check current state and build the right
 * Settings intent so Admin Mode can walk the parent through it during setup.
 */
object OverlayPermissions {

    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun overlayPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AppLockAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
