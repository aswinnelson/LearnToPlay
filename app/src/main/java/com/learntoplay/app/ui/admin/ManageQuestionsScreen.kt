package com.learntoplay.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.learntoplay.app.ai.QuestionAiGenerator
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.util.rememberPhotoScanLauncher
import kotlinx.coroutines.launch

/** Parent-facing question bank for one curriculum: add, edit, or remove multiple-choice
 * questions — typed by hand, started from a photo of a textbook/worksheet page (Stage C OCR),
 * and/or drafted with on-device AI, either one question at a time or as a whole batch (from a
 * topic, or split out of a multi-question scan). Every path lands in a review step; nothing is
 * saved to the question bank without the parent explicitly saving it. */
@Composable
fun ManageQuestionsScreen(viewModel: AdminViewModel, curriculumId: String, onBack: () -> Unit) {
    val questions by viewModel.observeQuestionsForCurriculum(curriculumId).collectAsState(initial = emptyList())
    var editingQuestion by remember { mutableStateOf<QuestionEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var scannedText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanBusy by remember { mutableStateOf(false) }

    var showTopicDialog by remember { mutableStateOf(false) }

    val draftReview = remember { mutableStateListOf<DraftQuestionState>() }
    var showBatchReview by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun openBatchReview(drafts: List<QuestionAiGenerator.DraftQuestion>) {
        draftReview.clear()
        draftReview.addAll(drafts.map { DraftQuestionState(it) })
        showBatchReview = true
    }

    val launchScan = rememberPhotoScanLauncher(
        onTextRecognized = { text ->
            scanBusy = true
            coroutineScope.launch {
                when (val result = QuestionAiGenerator.splitScannedTextIntoQuestions(context, text)) {
                    is QuestionAiGenerator.BatchResult.Success -> openBatchReview(result.questions)
                    is QuestionAiGenerator.BatchResult.Unavailable -> {
                        // No AI model on this device (e.g. the emulator) — fall back to the
                        // original flow so scanning still works: one question, raw OCR text,
                        // the parent trims it down by hand in the existing edit dialog.
                        scannedText = text
                        showAddDialog = true
                    }
                }
                scanBusy = false
            }
        },
        onError = { message -> errorMessage = message }
    )

    if (showBatchReview) {
        QuestionBatchReviewContent(
            drafts = draftReview,
            onRemove = { index -> draftReview.removeAt(index) },
            onDiscardAll = { draftReview.clear(); showBatchReview = false },
            onSaveAll = {
                draftReview.forEach { d ->
                    if (d.prompt.isNotBlank() && d.optionA.isNotBlank() && d.optionB.isNotBlank() &&
                        d.optionC.isNotBlank() && d.optionD.isNotBlank()
                    ) {
                        viewModel.upsertQuestion(
                            QuestionEntity(
                                id = 0,
                                curriculumId = curriculumId,
                                prompt = d.prompt.trim(),
                                optionA = d.optionA.trim(),
                                optionB = d.optionB.trim(),
                                optionC = d.optionC.trim(),
                                optionD = d.optionD.trim(),
                                correctOption = d.correctOption,
                                timesAsked = 0,
                                timesCorrect = 0,
                                lastAskedAtEpochMillis = 0L
                            )
                        )
                    }
                }
                draftReview.clear()
                showBatchReview = false
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Questions", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { launchScan() }, enabled = !scanBusy) { Text("Scan Photo") }
                TextButton(onClick = { showTopicDialog = true }) { Text("From Topic") }
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
                "No questions yet. Tap + to type one, Scan Photo to start from a picture of a " +
                    "textbook page, or From Topic to have the AI draft a whole batch — a " +
                    "handful is enough for a quiz to run.",
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
    if (showTopicDialog) {
        TopicBatchDialog(
            onGenerate = { topic, count ->
                showTopicDialog = false
                coroutineScope.launch {
                    when (val result = QuestionAiGenerator.generateBatchFromTopic(context, topic, count)) {
                        is QuestionAiGenerator.BatchResult.Success -> openBatchReview(result.questions)
                        is QuestionAiGenerator.BatchResult.Unavailable -> errorMessage = result.reason
                    }
                }
            },
            onDismiss = { showTopicDialog = false }
        )
    }
}

/** One AI-drafted question's editable fields, backed by Compose state so the review list below
 * can be edited in place before saving — mirrors [QuestionEntity]'s fields minus the ones that
 * only make sense for an already-saved question (id, ask stats). */
private class DraftQuestionState(seed: QuestionAiGenerator.DraftQuestion) {
    var prompt by mutableStateOf(seed.prompt)
    var optionA by mutableStateOf(seed.options.getOrElse(0) { "" })
    var optionB by mutableStateOf(seed.options.getOrElse(1) { "" })
    var optionC by mutableStateOf(seed.options.getOrElse(2) { "" })
    var optionD by mutableStateOf(seed.options.getOrElse(3) { "" })
    var correctOption by mutableStateOf("ABCD".getOrElse(seed.correctIndex) { 'A' }.toString())
}

/** Small input dialog for the "From Topic" batch generator: just a topic/chapter and how many
 * questions to draft. The actual generation call, review, and save happen elsewhere — this
 * dialog only collects the two inputs. */
@Composable
private fun TopicBatchDialog(onGenerate: (topic: String, count: Int) -> Unit, onDismiss: () -> Unit) {
    var topic by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("5") }
    val count = countText.toIntOrNull()?.coerceIn(1, 10)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate Questions from a Topic") },
        text = {
            Column {
                Text(
                    "The AI will write a new set of questions from scratch — you'll review " +
                        "every one before anything is saved.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic or chapter") },
                    placeholder = { Text("e.g. Class 5 Science — Photosynthesis") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter(Char::isDigit).take(2) },
                    label = { Text("How many (1–10)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = topic.isNotBlank() && count != null,
                onClick = { onGenerate(topic.trim(), count ?: 5) }
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Full-screen review step shown after a batch of AI-drafted questions comes back — from
 * "From Topic" or from splitting a multi-question scan. Every field stays editable, any draft
 * can be dropped individually, and nothing reaches the question bank until "Save All" — same
 * "AI drafts, parent decides" contract as the single-question flow. */
@Composable
private fun QuestionBatchReviewContent(
    drafts: List<DraftQuestionState>,
    onRemove: (Int) -> Unit,
    onDiscardAll: () -> Unit,
    onSaveAll: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Review Generated Questions", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "${drafts.size} question${if (drafts.size == 1) "" else "s"} drafted — edit or " +
                "remove any before saving. Nothing is added to the question bank until you " +
                "tap Save All.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        if (drafts.isEmpty()) {
            Text("All drafts removed — nothing left to save.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(drafts.size) { index ->
                    val draft = drafts[index]
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Question ${index + 1}", style = MaterialTheme.typography.labelMedium)
                                IconButton(onClick = { onRemove(index) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove this draft")
                                }
                            }
                            OutlinedTextField(
                                value = draft.prompt,
                                onValueChange = { draft.prompt = it },
                                label = { Text("Question") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            DraftOptionRow("A", draft.optionA, { draft.optionA = it }, draft.correctOption == "A") { draft.correctOption = "A" }
                            DraftOptionRow("B", draft.optionB, { draft.optionB = it }, draft.correctOption == "B") { draft.correctOption = "B" }
                            DraftOptionRow("C", draft.optionC, { draft.optionC = it }, draft.correctOption == "C") { draft.correctOption = "C" }
                            DraftOptionRow("D", draft.optionD, { draft.optionD = it }, draft.correctOption == "D") { draft.correctOption = "D" }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDiscardAll, modifier = Modifier.weight(1f)) { Text("Discard All") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSaveAll, enabled = drafts.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Save All (${drafts.size})")
            }
        }
    }
}

@Composable
private fun DraftOptionRow(
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

    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isValid = prompt.isNotBlank() && optionA.isNotBlank() && optionB.isNotBlank() &&
        optionC.isNotBlank() && optionD.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Question" else "Edit Question") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = prompt.isNotBlank() && !aiBusy,
                        onClick = {
                            aiBusy = true
                            aiError = null
                            coroutineScope.launch {
                                when (val result = QuestionAiGenerator.generateOptions(context, prompt)) {
                                    is QuestionAiGenerator.Result.Success -> {
                                        optionA = result.options[0]
                                        optionB = result.options[1]
                                        optionC = result.options[2]
                                        optionD = result.options[3]
                                        correctOption = "ABCD"[result.correctIndex].toString()
                                    }
                                    is QuestionAiGenerator.Result.Unavailable -> {
                                        aiError = result.reason
                                    }
                                }
                                aiBusy = false
                            }
                        }
                    ) { Text(if (aiBusy) "Generating…" else "Generate options with AI") }
                    if (aiBusy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                aiError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(4.dp))

                OptionRow("A", optionA, { optionA = it }, correctOption == "A") { correctOption = "A" }
                OptionRow("B", optionB, { optionB = it }, correctOption == "B") { correctOption = "B" }
                OptionRow("C", optionC, { optionC = it }, correctOption == "C") { correctOption = "C" }
                OptionRow("D", optionD, { optionD = it }, correctOption == "D") { correctOption = "D" }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap the circle next to the correct answer. AI-drafted options are a " +
                        "starting point — always double-check them before saving.",
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
