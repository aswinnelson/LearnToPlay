package com.learntoplay.app.ui.child

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Default state of the app (no PIN needed). Kid-friendly, minimal text, one clear action:
 * answer a few quick questions to earn play time. This is the whole "child mode" surface —
 * everything else (curriculum, rules, gated apps) is configured by the parent in Admin Mode.
 */
@Composable
fun ChildHomeScreen(
    timeBankMinutesRemaining: Int,
    onStartQuiz: () -> Unit,
    onOpenAdmin: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎮 Learn to Play", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                if (timeBankMinutesRemaining > 0)
                    "You have $timeBankMinutesRemaining minutes of play time left!"
                else
                    "Answer a few quick questions to earn play time."
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onStartQuiz) {
                Text("Start Quiz")
            }
            Spacer(Modifier.height(48.dp))
            TextButton(onClick = onOpenAdmin) {
                Text("Parent Settings", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
