package com.learntoplay.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.learntoplay.app.data.db.entities.QuestionEntity

/** Parent-facing question bank for one curriculum: add, edit, or remove multiple-choice
 * questions. This is what makes "upload questions" real for the MVP — typed in here rather
 * than only the bundled preset. A future photo/OCR flow would land its best-effort extracted
 * text in this same edit dialog for the parent to review and correct, rather than trying to
 * fully automate parsing without any human check. */
@Composable
fun ManageQuestionsScreen(viewModel: AdminViewModel, curriculumId: String, onBack: () -> Unit) {
    val questions by viewModel.observeQuestionsForCurriculum(curriculumId).collectAsState(initial = emptyList())
    var editingQuestion by remember { mutableStateOf<QuestionEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Questions", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add question")
            }
        }
        Spacer(Modifier.height(16.dp))

        if (questions.isEmpty()) {
            Text(
                "No questions yet. Tap + to add one — a handful is enough for a quiz to run.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(questions, key = { it.id }) { q ->
                    ListItem(
                        headlineContent = { Text(q.prompt, maxLines = 2) },
                        supportingContent = {
                            Text(
                                if (q.timesAsked > 0) "Asked ${q.timesAsked}× • ${q.timesCorrect}/${q.timesAsked} correct"
                                else "Not asked yet"
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteQuestion(q.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete question")
                            }
                        },
                        modifier = Modifier.clickable { editingQuestion = q }
                    )
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    if (showAddDialog) {
        QuestionEditDialog(
            curriculumId = curriculumId,
            existing = null,
            onSave = { viewModel.upsertQuestion(it); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }
    editingQuestion?.let { q ->
        QuestionEditDialog(
            curriculumId = curriculumId,
            existing = q,
            onSave = { viewModel.upsertQuestion(it); editingQuestion = null },
            onDismiss = { editingQuestion = null }
        )
    }
}

@Composable
private fun QuestionEditDialog(
    curriculumId: String,
    existing: QuestionEntity?,
    onSave: (QuestionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    var optionA by remember { mutableStateOf(existing?.optionA ?: "") }
    var optionB by remember { mutableStateOf(existing?.optionB ?: "") }
    var optionC by remember { mutableStateOf(existing?.optionC ?: "") }
    var optionD by remember { mutableStateOf(existing?.optionD ?: "") }
    var correctOption by remember { mutableStateOf(existing?.correctOption ?: "A") }

    val isValid = prompt.isNotBlank() && optionA.isNotBlank() && optionB.isNotBlank() &&
        optionC.isNotBlank() && optionD.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Question" else "Edit Question") },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                OutlinedTextField(
                    prompt, { prompt = it }, label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OptionRow("A", optionA, { optionA = it }, correctOption == "A") { correctOption = "A" }
                OptionRow("B", optionB, { optionB = it }, correctOption == "B") { correctOption = "B" }
                OptionRow("C", optionC, { optionC = it }, correctOption == "C") { correctOption = "C" }
                OptionRow("D", optionD, { optionD = it }, correctOption == "D") { correctOption = "D" }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap the circle next to the correct answer.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onSave(
                        QuestionEntity(
                            id = existing?.id ?: 0,
                            curriculumId = curriculumId,
                            prompt = prompt.trim(),
                            optionA = optionA.trim(),
                            optionB = optionB.trim(),
                            optionC = optionC.trim(),
                            optionD = optionD.trim(),
                            correctOption = correctOption,
                            timesAsked = existing?.timesAsked ?: 0,
                            timesCorrect = existing?.timesCorrect ?: 0,
                            lastAskedAtEpochMillis = existing?.lastAskedAtEpochMillis ?: 0L
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun OptionRow(
    letter: String,
    value: String,
    onValueChange: (String) -> Unit,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onSelect)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Option $letter") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}
