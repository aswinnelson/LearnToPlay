package com.learntoplay.app.ui.child

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun QuizScreen(viewModel: QuizViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().padding(24.dp)) {
        when {
            state.isComplete -> QuizCompleteContent(state, onDone)

            // No curriculum selected: give the child a way out instead of a dead end with no
            // navigation (this used to strand the child here with only the system back button).
            state.noCurriculumSelected -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(state.curriculumLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone) { Text("Back") }
            }

            state.questions.isEmpty() -> Text(
                state.curriculumLabel.ifBlank { "Loading..." },
                Modifier.align(Alignment.Center)
            )

            else -> {
                val q = state.questions[state.currentIndex]
                Column(Modifier.fillMaxSize()) {
                    LinearProgressIndicator(
                        progress = { (state.currentIndex).toFloat() / state.questions.size },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(state.curriculumLabel, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(24.dp))
                    Text(q.prompt, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(24.dp))
                    listOf("A" to q.optionA, "B" to q.optionB, "C" to q.optionC, "D" to q.optionD)
                        .forEach { (key, text) ->
                            OutlinedButton(
                                onClick = { viewModel.answer(key) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) { Text(text) }
                        }
                }
            }
        }
    }
}

/**
 * The screen shown right after a quiz finishes. This is the piece that used to leave the child
 * stranded in Learn to Play with no obvious next step after earning time — now it shows the
 * running time-bank total and, if any apps are unlocked, launches them directly. Tapping a
 * "Play now" button starts that app's own launch Intent, which naturally brings it to the
 * foreground and sends Learn to Play to the background — no extra "close app" logic needed.
 */
@Composable
private fun QuizCompleteContent(state: QuizUiState, onDone: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Score: ${state.scorePercent}%", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.minutesAwarded > 0)
                "Nice! You earned ${state.minutesAwarded} minutes of play time."
            else
                "Almost there — try again to earn play time."
        )

        if (state.totalMinutesRemaining > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Time remaining: ${state.totalMinutesRemaining} min",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(24.dp))

        if (state.unlockedApps.isNotEmpty()) {
            Text("Play now:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            state.unlockedApps.forEach { app ->
                Button(
                    onClick = {
                        context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                            context.startActivity(it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f).padding(vertical = 4.dp)
                ) {
                    Text(app.displayName)
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDone) { Text("Not now") }
        } else if (state.totalMinutesRemaining > 0) {
            // Time was earned but the parent hasn't gated any apps yet — nothing to launch, so
            // just send the child to the home screen rather than leaving them here.
            Spacer(Modifier.height(8.dp))
            Button(onClick = { (context as? Activity)?.moveTaskToBack(true) ?: onDone() }) {
                Text("Go to home screen")
            }
        } else {
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
