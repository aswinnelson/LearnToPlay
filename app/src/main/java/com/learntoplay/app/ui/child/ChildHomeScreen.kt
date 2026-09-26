package com.learntoplay.app.ui.child

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.learntoplay.app.util.AllowedWindowChecker
import com.learntoplay.app.util.PlayTimeFormat
import kotlinx.coroutines.delay

/**
 * Default state of the app (no PIN needed). Kid-friendly, minimal text, one clear action:
 * answer a few quick questions to earn play time. This is the whole "child mode" surface —
 * everything else (curriculum, rules, gated apps, allowed hours) is configured by the parent in
 * Admin Mode.
 *
 * @param allowedHours (startMinute, endMinute) when a parent has Allowed Hours switched on,
 * else null — used to tell the child up front when apps are locked, instead of them only
 * finding out from the "Not right now" overlay.
 */
@Composable
fun ChildHomeScreen(
    timeBankSecondsRemaining: Long,
    allowedHours: Pair<Int, Int>?,
    onStartQuiz: () -> Unit,
    onOpenAdmin: () -> Unit
) {
    // Re-read every 30 s so "locked until 4:00 PM" flips on its own if this screen is left open
    // across the start or end of the window.
    var nowMinute by remember { mutableIntStateOf(AllowedWindowChecker.currentMinuteOfDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMinute = AllowedWindowChecker.currentMinuteOfDay()
        }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎮 Learn to Play", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                if (timeBankSecondsRemaining > 0)
                    "You have ${PlayTimeFormat.describe(timeBankSecondsRemaining)} of play time left!"
                else
                    "Answer a few quick questions to earn play time.",
                textAlign = TextAlign.Center
            )

            if (allowedHours != null) {
                val (start, end) = allowedHours
                val inside = AllowedWindowChecker.isWithinWindow(nowMinute, start, end)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (inside)
                        "Play time is open until ${AllowedWindowChecker.format12Hour(end)}."
                    else
                        "Apps are locked until ${AllowedWindowChecker.format12Hour(start)}. " +
                            "You can still do quizzes to save up time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (inside) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

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
