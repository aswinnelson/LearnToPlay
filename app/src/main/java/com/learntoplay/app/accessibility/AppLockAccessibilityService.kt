package com.learntoplay.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.util.AllowedWindowChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches for foreground-app changes. If the foreground app is one the parent gated, two
 * independent checks can lock it: an optional daily "Allowed Hours" schedule (a curfew — see
 * [AllowedWindowChecker]), checked first since it overrides everything else, and then the
 * time-bank balance (the quiz-to-earn-time mechanic). This is the "enforcement" half of the
 * loop; TimeBankTrackerService is the "spend the earned time" half.
 *
 * MVP note: only WINDOW_STATE_CHANGED events are observed and canRetrieveWindowContent
 * is false (see accessibility_service_config.xml) — this service never reads on-screen
 * content, only which package is in the foreground.
 */
class AppLockAccessibilityService : AccessibilityService() {

    // SupervisorJob so one failed check can't cancel every later one, and a scope we can
    // actually cancel in onDestroy (the old bare CoroutineScope(Dispatchers.IO) leaked).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var overlayManager: LockOverlayManager
    private var debounceJob: Job? = null

    /** The last *real* app the child was in — never a transient system window (see
     * [isTransientSystemWindow]). This is what [recheckForegroundApp] re-evaluates when the
     * time bank runs out or Allowed Hours ends while the child is still inside that app. */
    @Volatile private var lastForegroundPackage: String? = null

    private var cachedImePackages: Set<String> = emptySet()
    private var imePackagesFetchedAt = 0L

    override fun onServiceConnected() {
        overlayManager = LockOverlayManager(applicationContext)
        instance = this
        Log.d(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // The keyboard, the notification shade / quick settings, and system dialogs all fire
        // WINDOW_STATE_CHANGED under their own package while the child is still, in reality,
        // inside the same app underneath. Treating them as "left the gated app" used to stop
        // the time-bank clock whenever the child typed (e.g. into Chrome's address bar) and
        // dismiss the lock overlay whenever they pulled down the notification shade over it —
        // both confirmed in device logs. Ignore them entirely: nothing about which app is in
        // front has actually changed. Checked BEFORE the debounce cancel so a keyboard popping
        // up can't cancel a genuine pending app-switch either.
        if (isTransientSystemWindow(packageName)) {
            Log.d(TAG, "ignoring transient system window: $packageName")
            return
        }

        // Task-switcher spam can fire several WINDOW_STATE_CHANGED events within
        // milliseconds of each other as the user flips through recents. Debounce so we
        // only act on where they actually land, instead of flickering the lock overlay
        // on and off for every app that flashed past on the way.
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            handleForegroundChange(packageName)
        }
    }

    private fun isTransientSystemWindow(packageName: String): Boolean =
        packageName in ALWAYS_TRANSIENT_PACKAGES || packageName in imePackages()

    /** Package names of every enabled keyboard (Gboard, SwiftKey, an OEM keyboard, ...),
     * refreshed at most once a minute — enough to pick up a newly installed keyboard without
     * a binder call on every single window event. */
    private fun imePackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - imePackagesFetchedAt > IME_CACHE_MS) {
            val imm = getSystemService(InputMethodManager::class.java)
            cachedImePackages = runCatching {
                imm?.enabledInputMethodList?.map { it.packageName }?.toSet()
            }.getOrNull() ?: cachedImePackages
            imePackagesFetchedAt = now
        }
        return cachedImePackages
    }

    private suspend fun handleForegroundChange(packageName: String) {
        Log.d(TAG, "foreground changed: $packageName")

        // Never gate our own UI (e.g. the child would get stuck if the overlay covered
        // MainActivity while it's showing the quiz).
        //
        // IMPORTANT: the gate overlay window itself is focusable (see LockOverlayManager —
        // FLAG_NOT_FOCUSABLE is deliberately omitted so "Start quiz" is reliably clickable
        // across OEM skins), so the instant showQuizGate() adds it, the platform fires its
        // own WINDOW_STATE_CHANGED event for that new window under OUR package name. Without
        // the isShowing() check below, that self-generated event lands right here and hides
        // the overlay a moment after it appears — a "flash" of the gate message before the
        // gated app becomes fully usable again. Only treat this as a genuine switch back to
        // our own app when we're not the one who just put a window of ours on screen.
        if (packageName == applicationContext.packageName) {
            if (::overlayManager.isInitialized && overlayManager.isShowing()) {
                // Not a real switch: the gated app is still what's underneath the overlay, so
                // lastForegroundPackage deliberately stays pointing at it.
                Log.d(TAG, "ignoring self-generated window event from our own gate overlay")
                return
            }
            lastForegroundPackage = packageName
            withContext(Dispatchers.Main) {
                overlayManager.hide()
                stopTimeBankTracking()
            }
            return
        }

        lastForegroundPackage = packageName

        val db = AppDatabase.getInstance(applicationContext)
        val gatedPackages = db.gatedAppDao().getEnabledPackageNames()

        if (packageName !in gatedPackages) {
            // Left the gated app for something else (or the home screen) — stop
            // spending time-bank seconds; only actual use of a gated app should count.
            withContext(Dispatchers.Main) {
                overlayManager.hide()
                stopTimeBankTracking()
            }
            return
        }

        val settings = db.adminSettingsDao().getOnce()

        // Allowed Hours check FIRST, ahead of the time-bank balance: a curfew window is meant
        // to override how much time is banked, not compete with it. A child with a full bank
        // still can't play outside the window; there's no quiz to take here that would help.
        if (settings != null && settings.allowedWindowEnabled &&
            !AllowedWindowChecker.isWithinWindow(
                AllowedWindowChecker.currentMinuteOfDay(),
                settings.allowedWindowStartMinute,
                settings.allowedWindowEndMinute
            )
        ) {
            Log.d(
                TAG,
                "$packageName is gated but outside allowed hours " +
                    "(${settings.allowedWindowStartMinute}-${settings.allowedWindowEndMinute}) — locking"
            )
            withContext(Dispatchers.Main) {
                stopTimeBankTracking()
                overlayManager.showOutsideHoursGate(
                    settings.allowedWindowStartMinute,
                    settings.allowedWindowEndMinute
                )
            }
            return
        }

        val balance = settings?.timeBankSecondsRemaining ?: 0L
        Log.d(TAG, "$packageName is gated, balance=${balance}s")
        withContext(Dispatchers.Main) {
            if (balance > 0) {
                overlayManager.hide()
                val trackerIntent =
                    Intent(applicationContext, com.learntoplay.app.service.TimeBankTrackerService::class.java)
                // startForegroundService (not startService): the calling process isn't
                // in the foreground here (a *different* app is), so a plain startService
                // would be rejected outright, or the service would be killed for never
                // reaching startForeground() in time. ContextCompat picks the right call
                // for the running API level.
                ContextCompat.startForegroundService(applicationContext, trackerIntent)
            } else {
                Log.d(TAG, "showing quiz gate over $packageName")
                overlayManager.showQuizGate()
            }
        }
    }

    /** See [Companion.recheckForegroundApp]. */
    private fun recheck() {
        val packageName = lastForegroundPackage ?: return
        Log.d(TAG, "recheck requested for $packageName")
        scope.launch { handleForegroundChange(packageName) }
    }

    private fun stopTimeBankTracking() {
        stopService(Intent(applicationContext, com.learntoplay.app.service.TimeBankTrackerService::class.java))
    }

    override fun onInterrupt() {
        // Android can call this before onServiceConnected on some devices — guard the lateinit.
        if (::overlayManager.isInitialized) overlayManager.hide()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        debounceJob?.cancel()
        scope.cancel()
        if (::overlayManager.isInitialized) overlayManager.hide()
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val IME_CACHE_MS = 60_000L
        private const val TAG = "AppLockAccessibility"

        /** Windows that sit ON TOP of the current app without the child actually leaving it:
         * the notification shade / quick settings / lock screen (systemui), framework dialogs
         * such as the "Open with" chooser ("android"), and runtime-permission prompts. Enabled
         * keyboards are added dynamically — see imePackages(). */
        private val ALWAYS_TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"
        )

        /** The running service, while connected. Same process as TimeBankTrackerService, so
         * a plain in-memory reference is all the "IPC" needed. */
        @Volatile private var instance: AppLockAccessibilityService? = null

        /**
         * Re-evaluates the app the child is in right now, without waiting for the next window
         * event. Called by TimeBankTrackerService the moment it stops counting because the
         * balance hit zero or Allowed Hours ended. Previously the lock only appeared on the
         * NEXT app switch, so a child who simply stayed put (e.g. watching one long video)
         * could keep going indefinitely after their time ran out. No-op if the service isn't
         * connected.
         */
        fun recheckForegroundApp() {
            instance?.recheck()
        }
    }
}
