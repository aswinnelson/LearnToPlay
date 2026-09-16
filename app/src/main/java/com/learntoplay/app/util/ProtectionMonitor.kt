package com.learntoplay.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Neither the Accessibility toggle nor the overlay permission can be locked down by the app
 * itself — that needs Device Owner enrollment (see the roadmap's tamper-resistance item) — but
 * the app CAN notice when a previously-granted one gets turned off and tell the parent, instead
 * of silently failing open. Call checkAndNotifyIfRevoked() from a lifecycle point that runs
 * whenever the app comes to the foreground; MainActivity.onResume covers both Child and Admin
 * screens without needing a background poller.
 */
object ProtectionMonitor {
    private const val PREFS = "protection_monitor"
    private const val KEY_WAS_PROTECTED = "was_protected"
    private const val CHANNEL_ID = "protection_alerts"
    private const val NOTIFICATION_ID = 2001

    fun checkAndNotifyIfRevoked(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isProtectedNow = OverlayPermissions.hasOverlayPermission(context) &&
            OverlayPermissions.isAccessibilityServiceEnabled(context)
        val wasProtected = prefs.getBoolean(KEY_WAS_PROTECTED, false)

        if (wasProtected && !isProtectedNow) {
            notifyRevoked(context)
        }
        prefs.edit().putBoolean(KEY_WAS_PROTECTED, isProtectedNow).apply()
    }

    private fun notifyRevoked(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // Can't post without the runtime permission; nothing to fall back to here.
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Protection alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Learn to Play protection turned off")
            .setContentText("Accessibility or overlay permission was disabled — gated apps are no longer locked.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
