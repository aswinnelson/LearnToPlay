package com.learntoplay.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.learntoplay.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    override fun onServiceConnected() {
        overlayManager = LockOverlayManager(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Never gate our own UI (e.g. the child would get stuck if the overlay covered
        // MainActivity while it's showing the quiz).
        if (packageName == applicationContext.packageName) {
            scope.launch { withContext(Dispatchers.Main) { overlayManager.hide() } }
            return
        }

        scope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val gatedPackages = db.gatedAppDao().getEnabledPackageNames()

            if (packageName !in gatedPackages) {
                withContext(Dispatchers.Main) { overlayManager.hide() }
                return@launch
            }

            val balance = db.adminSettingsDao().getOnce()?.timeBankSecondsRemaining ?: 0L
            withContext(Dispatchers.Main) {
                if (balance > 0) {
                    overlayManager.hide()
                    startService(Intent(applicationContext, com.learntoplay.app.service.TimeBankTrackerService::class.java))
                } else {
                    overlayManager.showQuizGate()
                }
            }
        }
    }

    override fun onInterrupt() {
        overlayManager.hide()
    }

    override fun onDestroy() {
        overlayManager.hide()
        super.onDestroy()
    }
}
