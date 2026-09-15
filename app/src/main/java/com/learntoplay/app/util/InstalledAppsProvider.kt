package com.learntoplay.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/** A single launchable app, as shown in the Gated Apps picker. */
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

/**
 * Lists launchable, user-facing apps for the Gated Apps picker — the piece flagged as a
 * device-specific TODO in the original scaffold. Uses PackageManager.queryIntentActivities
 * against ACTION_MAIN/CATEGORY_LAUNCHER (the same query the OS uses to build the home-screen
 * app drawer), which only needs the <queries> declaration in the manifest, not the
 * QUERY_ALL_PACKAGES special permission.
 */
object InstalledAppsProvider {

    fun getLaunchableApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

        val resolveInfos = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)

        return resolveInfos
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName } // don't let the parent gate this app itself
            .mapNotNull { pkg ->
                runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    InstalledAppInfo(
                        packageName = pkg,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

/** Small convenience so the picker UI can go straight from Drawable to a Compose ImageBitmap. */
fun Drawable.toSafeBitmap() = runCatching { toBitmap(width = 96, height = 96) }.getOrNull()
