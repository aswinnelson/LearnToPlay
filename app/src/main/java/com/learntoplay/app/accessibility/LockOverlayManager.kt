package com.learntoplay.app.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.learntoplay.app.MainActivity

/**
 * Draws a full-screen SYSTEM_ALERT_WINDOW overlay over a gated app. Two flavors share the same
 * window plumbing (see [showOverlay]): [showQuizGate] (time bank is empty — answer questions to
 * earn more) and [showOutsideHoursGate] (outside the parent's Allowed Hours schedule — no quiz
 * offered, since earning time doesn't help outside the window). Kept deliberately simple for
 * the MVP: the goal is a frictionless quiz-then-play loop, not a bypass-proof lock (see
 * architecture notes — that's an explicit non-goal for pre-MVP).
 *
 * Must be called from the main thread (AppLockAccessibilityService hops back to Main before
 * calling show/hide — see its onAccessibilityEvent).
 */
class LockOverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var currentKind: OverlayKind? = null

    private enum class OverlayKind { QUIZ_GATE, OUTSIDE_HOURS }

    /** True once the user has granted "draw over other apps" for this app — required before showQuizGate() can do anything. */
    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * True while a gate overlay (either flavor) is currently on screen. AppLockAccessibilityService
     * uses this to tell apart a genuine "child switched to our own app" accessibility event from
     * the self-generated window-state-changed event that adding this very overlay produces (the
     * overlay is focusable — see the comment on FLAG_NOT_FOCUSABLE below — so the platform
     * reports it as a new window belonging to our package the instant it's added).
     */
    fun isShowing(): Boolean = overlayView != null

    fun showQuizGate() {
        showOverlay(OverlayKind.QUIZ_GATE) {
            QuizGateOverlayContent(onStartQuiz = ::openQuizAndHide)
        }
    }

    /** Shown instead of [showQuizGate] when a parent-set Allowed Hours window is on and the
     * current time falls outside it — see AppLockAccessibilityService. [startMinute] and
     * [endMinute] are minutes-since-midnight, formatted for display by the content itself. */
    fun showOutsideHoursGate(startMinute: Int, endMinute: Int) {
        showOverlay(OverlayKind.OUTSIDE_HOURS) {
            OutsideHoursOverlayContent(startMinute = startMinute, endMinute = endMinute)
        }
    }

    private fun showOverlay(kind: OverlayKind, content: @Composable () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "showOverlay() must be called from the main thread" }
        if (!hasOverlayPermission()) return // Nothing we can draw without the permission; AdminDashboard prompts for it.

        if (overlayView != null) {
            if (currentKind == kind) return // already showing the right thing
            hide() // switch flavor (e.g. quiz gate -> outside-hours gate mid-session): drop the old one first
        }

        val owner = OverlayLifecycleOwner().also { it.create(); it.start(); it.resume() }
        lifecycleOwner = owner

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            // The overlay window has no natural "view tree destroyed" moment to key off, so we
            // dispose the composition manually in hide() instead of on an automatic strategy.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                androidx.compose.material3.MaterialTheme {
                    content()
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // Deliberately NOT FLAG_NOT_TOUCHABLE — the overlay must intercept every touch on
            // the gated app below it. It also omits FLAG_NOT_FOCUSABLE so the "Start quiz"
            // button is reliably clickable across OEM skins. Being focusable means the platform
            // fires a window-state-changed accessibility event for THIS window the moment it's
            // added, reported under our own package name — AppLockAccessibilityService has to
            // know to ignore that specific echo (see isShowing() above).
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(composeView, params)
        overlayView = composeView
        currentKind = kind
    }

    fun hide() {
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        overlayView = null
        currentKind = null
    }

    /** "Start quiz" tap: bring MainActivity to the front on the quiz screen, then drop the overlay. */
    private fun openQuizAndHide() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_QUIZ, true)
        }
        context.startActivity(intent)
        hide()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
