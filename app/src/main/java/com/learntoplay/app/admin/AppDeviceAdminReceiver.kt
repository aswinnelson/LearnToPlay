package com.learntoplay.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Registers this app as a device administrator — the required hook Android calls into, and
 * the prerequisite for Device Owner mode. Device Owner (not this receiver by itself) is what
 * actually blocks the child from uninstalling or disabling the app; see TamperGuard for what
 * gets locked down once it's active. Device Owner status can't be granted from inside the app —
 * it's set once via `adb shell dpm set-device-owner` on a freshly wiped phone with no accounts,
 * an Android platform requirement, not a choice made here. */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    /** Called if someone tries to deactivate device admin from Settings. Once Device Owner mode
     * is fully active, Android normally hides that option entirely, so reaching this callback
     * would mean protection isn't complete yet — the message explains why it's blocked. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Turning this off removes LearnToPlay's parental controls. Ask your parent for the PIN in Settings instead."
    }
}
