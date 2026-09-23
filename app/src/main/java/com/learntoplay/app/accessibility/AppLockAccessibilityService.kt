package com.learntoplay.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.learntoplay.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches for foreground-app changes. If the foreground app is one the parent gated
 * and the time bank is empty, shows the lock overlay (quiz gate) instead of letting
 * the child use it. This is the "enforcement" half of the loop; TimeBankTrackerService
 * is the "spend the earned time" half.
 *
 * MVP note: only WINDOW_STATE_CHANGED events are observed and canRetrieveWindowContent
 * is false (see accessibility_service_config.xml) — this service never reads on-screen
 * content, only which package is in the foreground.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var overlayManager: LockOverlayManager
    private var debounceJob: Job? = null

    override fun onServiceConnected() {
        overlayManager = LockOverlayManager(applicationContext)
        Log.d(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

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
                Log.d(TAG, "ignoring self-generated window event from our own gate overlay")
                return
            }
            withContext(Dispatchers.Main) {
                overlayManager.hide()
                stopTimeBankTracking()
            }
            return
        }

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

        val balance = db.adminSettingsDao().getOnce()?.timeBankSecondsRemaining ?: 0L
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

    private fun stopTimeBankTracking() {
        stopService(Intent(applicationContext, com.learntoplay.app.service.TimeBankTrackerService::class.java))
    }

    override fun onInterrupt() {
        overlayManager.hide()
    }

    override fun onDestroy() {
        debounceJob?.cancel()
        overlayManager.hide()
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val TAG = "AppLockAccessibility"
    }
}
