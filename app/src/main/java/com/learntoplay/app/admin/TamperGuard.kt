package com.learntoplay.app.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Wraps the DevicePolicyManager calls that make the app tamper-resistant once it's Device
 * Owner — blocking uninstall of this app, and blocking the two most common ways a determined
 * kid works around a parental-control app: booting into Safe Mode (which disables all
 * third-party apps, including this one and its Accessibility service) and factory-resetting
 * the phone from Settings.
 *
 * Deliberately does NOT touch USB debugging (UserManager.DISALLOW_DEBUGGING_FEATURES) — that
 * would also block adb, which is how new builds get installed on this phone during development
 * and testing.
 *
 * Device Owner status itself can't be granted from inside the app — see AppDeviceAdminReceiver's
 * doc comment. Every function here is a no-op (returns Failed, never throws) until that's
 * already been done outside the app.
 */
object TamperGuard {

    sealed interface ApplyResult {
        data object Applied : ApplyResult
        data class Failed(val reason: String) : ApplyResult
    }

    fun isDeviceOwner(context: Context): Boolean =
        devicePolicyManager(context).isDeviceOwnerApp(context.packageName)

    /** Locks down uninstall, Safe Mode, and factory reset. */
    fun applyProtections(context: Context): ApplyResult {
        if (!isDeviceOwner(context)) {
            return ApplyResult.Failed(
                "Not set up as Device Owner yet. Run the adb command from setup on a freshly " +
                    "wiped phone with no accounts added, then try again."
            )
        }
        return try {
            val dpm = devicePolicyManager(context)
            val admin = adminComponent(context)
            dpm.setUninstallBlocked(admin, context.packageName, true)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            ApplyResult.Applied
        } catch (e: Exception) {
            ApplyResult.Failed("Couldn't apply protections (${e.message ?: "unknown error"}).")
        }
    }

    /** Reverses applyProtections — a parent-only escape hatch, e.g. before handing the phone
     * back or repurposing it. Removing Device Owner status itself still requires a factory
     * reset; this only undoes the specific restrictions above. */
    fun removeProtections(context: Context): ApplyResult {
        if (!isDeviceOwner(context)) {
            return ApplyResult.Failed("Not set up as Device Owner.")
        }
        return try {
            val dpm = devicePolicyManager(context)
            val admin = adminComponent(context)
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            ApplyResult.Applied
        } catch (e: Exception) {
            ApplyResult.Failed("Couldn't remove protections (${e.message ?: "unknown error"}).")
        }
    }

    private fun adminComponent(context: Context) =
        ComponentName(context.applicationContext, AppDeviceAdminReceiver::class.java)

    private fun devicePolicyManager(context: Context) =
        context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
}
