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
import com.learntoplay.app.util.rememberPhotoScanLauncher

/** Parent-facing question bank for one curriculum: add, edit, or remove multiple-choice
 * questions — either typed by hand, or started from a photo of a textbook/worksheet page
 * (Stage C: on-device OCR pre-fills the prompt field with the recognized text, which the
 * parent then reviews, trims, and corrects — the photo/OCR pass is a starting point, not an
 * unsupervised auto-fill). */
@Composable
fun ManageQuestionsScreen(viewModel: AdminViewModel, curriculumId: String, onBack: () -> Unit) {
    val questions by viewModel.observeQuestionsForCurriculum(curriculumId).collectAsState(initial = emptyList())
    var editingQuestion by remember { mutableStateOf<QuestionEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var scannedText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val launchScan = rememberPhotoScanLauncher(
        onTextRecognized = { text ->
            scannedText = text
            showAddDialog = true
        },
        onError = { message -> errorMessage = message }
    )

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Questions", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { launchScan() }) { Text("Scan Photo") }
                IconButton(onClick = { scannedText = null; showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add question")
                }
            }
        }

        errorMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { errorMessage = null }) { Text("Dismiss") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (questions.isEmpty()) {
            Text(
                "No questions yet. Tap + to type one, or Scan Photo to start from a picture " +
                    "of a textbook page — a handful is enough for a quiz to run.",
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
            scannedText = scannedText,
            onSave = { viewModel.upsertQuestion(it); showAddDialog = false; scannedText = null },
            onDismiss = { showAddDialog = false; scannedText = null }
        )
    }
    editingQuestion?.let { q ->
        QuestionEditDialog(
            curriculumId = curriculumId,
            existing = q,
            scannedText = null,
            onSave = { viewModel.upsertQuestion(it); editingQuestion = null },
            onDismiss = { editingQuestion = null }
        )
    }
}

@Composable
private fun QuestionEditDialog(
    curriculumId: String,
    existing: QuestionEntity?,
    scannedText: String?,
    onSave: (QuestionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf(existing?.prompt ?: scannedText ?: "") }
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
                if (scannedText != null) {
                    Text(
                        "Recognized from your photo — trim it down to just the question, fix " +
                            "any misread words, then fill in the four options below.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
