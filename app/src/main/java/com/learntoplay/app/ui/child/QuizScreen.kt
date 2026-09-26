package com.learntoplay.app.ui.child

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.db.entities.QuestionType
import com.learntoplay.app.data.db.entities.imagePathList
import com.learntoplay.app.util.PlayTimeFormat
import com.learntoplay.app.util.ScannedImage

@Composable
fun QuizScreen(viewModel: QuizViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().padding(24.dp)) {
        val blockedMessage = state.blockedMessage
        when {
            state.isComplete -> QuizCompleteContent(state, onDone)

            // No curriculum selected, or the selected one has no questions yet: give the child a
            // way out instead of a dead end with only the system back button.
            blockedMessage != null -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(blockedMessage, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone) { Text("Back") }
            }

            state.questions.isEmpty() -> Text("Loading...", Modifier.align(Alignment.Center))

            else -> {
                val q = state.questions[state.currentIndex]
                val answersEnabled = !state.isSubmitting
                Column(Modifier.fillMaxSize()) {
                    LinearProgressIndicator(
                        progress = { (state.currentIndex).toFloat() / state.questions.size },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(state.curriculumLabel, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(24.dp))
                    Text(q.prompt, style = MaterialTheme.typography.headlineSmall)

                    // The scanned page photo(s) this question was drafted from, if any — shown
                    // right under the prompt so the child can look at the actual table/diagram/
                    // figure a comprehension question is asking about instead of having to work
                    // from a possibly ambiguous text description of it alone.
                    val images = q.imagePathList()
                    if (images.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        images.forEach { path ->
                            ScannedImage(
                                path = path,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(bottom = 8.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    if (q.questionType == QuestionType.FILL_IN) {
                        // Free-text entry — no options to guess from, so getting this one right
                        // means the child actually knew the answer.
                        var typedAnswer by remember(q.id) { mutableStateOf("") }
                        OutlinedTextField(
                            value = typedAnswer,
                            onValueChange = { typedAnswer = it },
                            label = { Text("Type your answer") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            // q.id is passed along so a stray second tap that lands after the
                            // screen has moved on is ignored rather than answering the next one.
                            onClick = { viewModel.answerFillIn(q.id, typedAnswer) },
                            enabled = answersEnabled && typedAnswer.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Submit") }
                    } else {
                        val order = state.optionOrder[q.id] ?: listOf("A", "B", "C", "D")
                        order.forEach { key ->
                            OutlinedButton(
                                onClick = { viewModel.answer(q.id, key) },
                                enabled = answersEnabled,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) { Text(optionText(q, key)) }
                        }
                    }
                }
            }
        }
    }
}

private fun optionText(q: QuestionEntity, key: String): String = when (key) {
    "A" -> q.optionA
    "B" -> q.optionB
    "C" -> q.optionC
    else -> q.optionD
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

        if (state.secondsRemaining > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Time saved up: ${PlayTimeFormat.describe(state.secondsRemaining)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        state.allowedHoursMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
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
        } else if (state.secondsRemaining > 0 && state.allowedHoursMessage == null) {
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
