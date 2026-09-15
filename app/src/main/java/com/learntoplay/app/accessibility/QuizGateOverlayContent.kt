package com.learntoplay.app.accessibility

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The content drawn over a gated app once the time bank is empty. Deliberately plain and
 * kid-friendly: one message, one button. It never shows the gated app underneath, and — since
 * this is a "wanted loop, not a bypass-proof lock" per the pre-MVP scope — it doesn't need
 * elaborate anti-dismiss logic, just to be the only thing on screen.
 */
@Composable
fun QuizGateOverlayContent(onStartQuiz: () -> Unit) {
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
                Text("Time to learn something first!", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Answer a few quick questions to earn more play time.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onStartQuiz) {
                    Text("Start quiz")
                }
            }
        }
    }
}
