package com.learntoplay.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Parent-oversight screen: every quiz attempt the child has made, most recent first, with
 * what was covered, the score, and the minutes it earned. Read-only — the point is letting a
 * parent see engagement without having to re-run the quiz themselves (see product doc,
 * "Solution" step 5).
 */
@Composable
fun QuizHistoryScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val history by viewModel.quizHistory.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Quiz History", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (history.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "No quizzes taken yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(history) { row -> QuizHistoryItem(row) }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun QuizHistoryItem(row: QuizHistoryRow) {
    val dateFormatter = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    ListItem(
        headlineContent = { Text(row.curriculumLabel) },
        supportingContent = {
            Text("${row.correctCount}/${row.totalCount} correct • ${dateFormatter.format(Date(row.takenAtEpochMillis))}")
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text("${row.scorePercent}%", style = MaterialTheme.typography.titleMedium)
                if (row.minutesAwarded > 0) {
                    Text(
                        "+${row.minutesAwarded} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    )
    HorizontalDivider()
}
