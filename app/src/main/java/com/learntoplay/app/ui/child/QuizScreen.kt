package com.learntoplay.app.ui.child

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun QuizScreen(viewModel: QuizViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().padding(24.dp)) {
        when {
            state.isComplete -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Score: ${state.scorePercent}%", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.minutesAwarded > 0)
                        "Nice! You earned ${state.minutesAwarded} minutes of play time."
                    else
                        "Almost there — try again to earn play time."
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone) { Text("Done") }
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
