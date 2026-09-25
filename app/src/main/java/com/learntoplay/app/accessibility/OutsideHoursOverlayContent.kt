package com.learntoplay.app.accessibility

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.learntoplay.app.util.AllowedWindowChecker

/**
 * The content drawn over a gated app when it's outside the parent-set "Allowed Hours" window
 * (see AdminSettingsEntity.allowedWindow* and AppLockAccessibilityService) — a curfew that
 * applies regardless of time-bank balance. Unlike [QuizGateOverlayContent] there's no "answer
 * questions to unlock" action here: earning more time doesn't help outside the window, so this
 * is deliberately just a message, no button.
 */
@Composable
fun OutsideHoursOverlayContent(startMinute: Int, endMinute: Int) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.9f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(32.dp)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
                    .padding(24.dp)
            ) {
                Text("Not right now", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "This app is only allowed from ${AllowedWindowChecker.format12Hour(startMinute)} " +
                        "to ${AllowedWindowChecker.format12Hour(endMinute)}.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ask a parent if you think this should change.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
