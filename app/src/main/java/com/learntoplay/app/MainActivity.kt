package com.learntoplay.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.learntoplay.app.ui.navigation.AppNavGraph
import com.learntoplay.app.util.PendingShare
import com.learntoplay.app.util.ProtectionMonitor

class MainActivity : ComponentActivity() {

    // Read once at launch/new-intent time; AppNavGraph consumes it and navigates to the quiz.
    private var openQuizOnStart by mutableStateOf(false)

    // Set when this activity was launched (or re-launched) by another app's share sheet — e.g.
    // a parent long-pressing a teacher's message in a school WhatsApp group and sharing it into
    // LearnToPlay. AppNavGraph routes the parent through the PIN gate and a curriculum picker
    // before handing this off to ManageQuestionsScreen, same as any other admin-side
    // question-entry flow — see PendingShare's own doc for the full path.
    private var pendingShare by mutableStateOf<PendingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openQuizOnStart = intent?.getBooleanExtra(EXTRA_OPEN_QUIZ, false) ?: false
        pendingShare = intent?.let { extractPendingShare(it) }
        val db = (application as LearnToPlayApp).database
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph(
                        db = db,
                        openQuizOnStart = openQuizOnStart,
                        onOpenQuizConsumed = { openQuizOnStart = false },
                        pendingShare = pendingShare,
                        onPendingShareConsumed = { pendingShare = null }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catches the Accessibility/overlay permission having been turned off since we were
        // last in the foreground — by the child, or by Android's own unused-permission
        // auto-reset — and tells the parent instead of silently failing open.
        ProtectionMonitor.checkAndNotifyIfRevoked(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The overlay's "Start quiz" button relaunches this activity with singleTask, which
        // routes here instead of onCreate() if MainActivity is already in the back stack — the
        // same is true for a share-sheet launch arriving while the app is already running.
        setIntent(intent)
        openQuizOnStart = intent.getBooleanExtra(EXTRA_OPEN_QUIZ, false)
        extractPendingShare(intent)?.let { pendingShare = it }
    }

    /** Reads a share-sheet `ACTION_SEND` intent (text/plain or an image MIME type) into a
     * [PendingShare], or null if this intent isn't a share at all (the normal launcher-icon /
     * overlay-relaunch case) or its content couldn't be read. Deliberately tolerant: a share
     * intent with neither usable text nor a readable image stream just becomes null, same as
     * if nothing was shared, rather than crashing the app a parent just tried to hand content
     * to. */
    private fun extractPendingShare(intent: Intent): PendingShare? {
        if (intent.action != Intent.ACTION_SEND) return null
        return when {
            intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                if (text.isNullOrBlank()) null else PendingShare.Text(text)
            }
            intent.type?.startsWith("image/") == true -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { PendingShare.Image(it) }
            }
            else -> null
        }
    }

    companion object {
        const val EXTRA_OPEN_QUIZ = "com.learntoplay.app.EXTRA_OPEN_QUIZ"
    }
}
