package com.learntoplay.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.learntoplay.app.FeatureFlags
import com.learntoplay.app.data.seed.PresetCurriculumSeed

/** Lets a parent pick which curriculum is active for the child (bundled preset or one they
 * created themselves), start a brand-new curriculum from scratch, and jump into managing that
 * curriculum's question bank — the actual "upload questions" surface for the MVP. */
@Composable
fun CurriculumSelectScreen(
    viewModel: AdminViewModel,
    onManageQuestions: (String) -> Unit,
    onBack: () -> Unit
) {
    val allCurricula by viewModel.curricula.collectAsState(initial = emptyList())
    // MVP build hides the bundled sample curricula (FeatureFlags.CURRICULUM_PRESETS) — except
    // one that's already the active curriculum on an existing install, so the child's current
    // quiz source never vanishes from the parent's view without explanation.
    val presetIds = remember { PresetCurriculumSeed.curricula.map { it.id }.toSet() }
    val curricula = if (FeatureFlags.CURRICULUM_PRESETS) allCurricula
        else allCurricula.filter { it.id !in presetIds || it.isSelectedByAdmin }
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Select Curriculum", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New curriculum")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (curricula.isEmpty()) {
            Text(
                "No curriculum yet. Tap + to create one (e.g. Maths — Fractions), then tap it to " +
                    "make it active and tap Questions to add your own.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                "Tap a curriculum to make it the one quizzes use. Tap Questions to add or edit its questions.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(curricula, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text("${c.subject} — ${c.chapterTitle}") },
                    supportingContent = { Text("${c.board} • Grade ${c.grade}") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.isSelectedByAdmin) {
                                Text("Selected", modifier = Modifier.padding(end = 8.dp))
                            }
                            TextButton(onClick = { onManageQuestions(c.id) }) { Text("Questions") }
                        }
                    },
                    modifier = Modifier.clickable { viewModel.selectCurriculum(c.id) }
                )
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    if (showCreateDialog) {
        CreateCurriculumDialog(
            onCreate = { board, grade, subject, chapter ->
                viewModel.createCurriculum(board, grade, subject, chapter)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun CreateCurriculumDialog(
    onCreate: (board: String, grade: Int, subject: String, chapterTitle: String) -> Unit,
    onDismiss: () -> Unit
) {
    var board by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var chapterTitle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Curriculum") },
        text = {
            Column {
                OutlinedTextField(
                    board, { board = it }, label = { Text("Board (e.g. CBSE)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    grade, { grade = it.filter(Char::isDigit) }, label = { Text("Grade") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    subject, { subject = it }, label = { Text("Subject") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    chapterTitle, { chapterTitle = it }, label = { Text("Chapter title") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = board.isNotBlank() && subject.isNotBlank() &&
                    chapterTitle.isNotBlank() && grade.toIntOrNull() != null,
                onClick = { onCreate(board.trim(), grade.toInt(), subject.trim(), chapterTitle.trim()) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
