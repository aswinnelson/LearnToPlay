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
import com.learntoplay.app.data.db.entities.toImagePathsColumn
import com.learntoplay.app.util.ScannedImage
import com.learntoplay.app.util.ScannedTextBlock
import com.learntoplay.app.util.rememberPhotoScanLauncher
import kotlinx.coroutines.launch

/** Hard cap on how many pages a parent can add to one "Scan Photo" session before questions are
 * generated automatically — keeps a single AI call (and the review list before it) from growing
 * without bound. See [QuestionAiGenerator.generateComprehensionQuestionsFromScan]. */
private const val MAX_SCAN_PAGES = 5

/** Parent-facing question bank for one curriculum: add, edit, or remove multiple-choice
 * questions — typed by hand, started from one or more photos of whatever the child is studying
 * (Stage C OCR, now paired with on-device AI that writes fresh comprehension-check questions
 * about that content rather than just copying whatever's printed on it — a parent can scan
 * several pages of the same topic before generating, and the questions cover all of them
 * together), and/or drafted from just a topic name. Every AI path lands in a review step;
 * nothing is saved to the question bank without the parent explicitly saving it. */
@Composable
fun ManageQuestionsScreen(viewModel: AdminViewModel, curriculumId: String, onBack: () -> Unit) {
    val questions by viewModel.observeQuestionsForCurriculum(curriculumId).collectAsState(initial = emptyList())
    var editingQuestion by remember { mutableStateOf<QuestionEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var scannedText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanBusy by remember { mutableStateOf(false) }
    var scanBusyLabel by remember { mutableStateOf("") }

    // Pages accumulated in the current "Scan Photo" session, in the order they were taken —
    // cleared once questions are generated (or the session is canceled). Empty means no scan is
    // in progress. scannedPageImages runs in parallel with scannedPages (same index = same
    // photo) but can be shorter if persisting a photo failed for one page — see
    // PhotoScanCapture.persistScanImageOrNull — so every generated question can still show
    // whichever page photos were actually saved rather than losing the whole batch over one
    // failed copy.
    val scannedPages = remember { mutableStateListOf<String>() }
    val scannedPageImages = remember { mutableStateListOf<String>() }
    // OCR'd text blocks with their on-page position, across every photo in the current session
    // — used to guess which part of the page(s) a given generated question is actually about,
    // so it can be shown a crop of just that region instead of the whole page. See
    // QuestionAiGenerator.matchAndCropRegion.
    val scannedBlocks = remember { mutableStateListOf<ScannedTextBlock>() }
    var showPageChoiceDialog by remember { mutableStateOf(false) }

    var showTopicDialog by remember { mutableStateOf(false) }
    // True for the whole "From Topic" AI call, not just while the dialog is open — the dialog
    // itself closes right away, so this is what tells the parent something is still happening.
    // On-device generation on a real phone (no cloud call) can take anywhere from ~10 seconds to
    // a couple of minutes depending on the phone and how many questions were asked for, and with
    // no visible feedback this previously looked identical to "did nothing" — see the status
    // banner below.
    var topicBusy by remember { mutableStateOf(false) }
    var topicBusyLabel by remember { mutableStateOf("") }

    val draftReview = remember { mutableStateListOf<DraftQuestionState>() }
    var showBatchReview by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun openBatchReview(drafts: List<QuestionAiGenerator.DraftQuestion>) {
        draftReview.clear()
        draftReview.addAll(drafts.map { DraftQuestionState(it) })
        showBatchReview = true
    }

    // Ends the current scan session: takes whatever pages have been accumulated so far, clears
    // them, and runs one AI call covering all of them together. Called either when the parent
    // taps "Generate Questions Now" in the page-choice dialog, or automatically once
    // [MAX_SCAN_PAGES] is reached.
    fun generateFromScannedPages() {
        showPageChoiceDialog = false
        val pages = scannedPages.toList()
        val images = scannedPageImages.toList()
        val blocks = scannedBlocks.toList()
        scannedPages.clear()
        scannedPageImages.clear()
        scannedBlocks.clear()
        if (pages.isEmpty()) return
        scanBusyLabel = if (pages.size == 1) "your photo" else "your ${pages.size} photos"
        scanBusy = true
        coroutineScope.launch {
            when (val result = QuestionAiGenerator.generateComprehensionQuestionsFromScan(context, pages, images, blocks)) {
                is QuestionAiGenerator.BatchResult.Success -> openBatchReview(result.questions)
                is QuestionAiGenerator.BatchResult.Unavailable -> {
                    errorMessage = result.reason
                    // Fall back to the manual single-question dialog, pre-filled with every
                    // scanned page's raw text (separated so it's still clear where one page
                    // ends and the next begins) for the parent to trim down by hand. The manual
                    // dialog doesn't support attaching an image yet, so the photo(s) themselves
                    // aren't carried over here — only their OCR'd text.
                    scannedText = pages.joinToString("\n\n---\n\n")
                    showAddDialog = true
                }
            }
            scanBusy = false
        }
    }

    // Taking a photo adds it to the current scan session; after each one, the parent chooses
    // whether to add another page (same topic, next page of the book) or generate questions
    // now from everything scanned so far — see the page-choice dialog below. Reaching
    // MAX_SCAN_PAGES skips the choice and generates immediately. Each generated batch is NEW
    // comprehension questions about the content, not the same questions repeated, so a quiz
    // built from it actually checks whether the child understood the material (see
    // QuestionAiGenerator.generateComprehensionQuestionsFromScan). Only falls back to the old
    // manual single-question dialog with raw OCR text when there's no AI model on this device at
    // all (e.g. the emulator) or the model couldn't produce a readable result.
    val launchScan = rememberPhotoScanLauncher(
        onTextRecognized = { text, imagePath, blocks ->
            scannedPages.add(text)
            imagePath?.let { scannedPageImages.add(it) }
            scannedBlocks.addAll(blocks)
            if (scannedPages.size >= MAX_SCAN_PAGES) {
                errorMessage = "Reached the $MAX_SCAN_PAGES-page limit — generating questions from all $MAX_SCAN_PAGES pages now."
                generateFromScannedPages()
            } else {
                showPageChoiceDialog = true
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
                                lastAskedAtEpochMillis = 0L,
                                imagePaths = d.imagePaths.toImagePathsColumn()
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
                if (scanBusy || topicBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { launchScan() }, enabled = !scanBusy && !topicBusy) { Text("Scan Photo") }
                TextButton(onClick = { showTopicDialog = true }, enabled = !scanBusy && !topicBusy) { Text("From Topic") }
                IconButton(onClick = { scannedText = null; showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add question")
                }
            }
        }

        // Prominent status banner while an AI call is in flight — the small spinner above is
        // easy to miss, and on a real phone this can run for a while (CPU-only inference, no
        // cloud call). Without this, the previous behavior was: dialog closes, nothing visibly
        // happens for up to a couple of minutes, and it looks exactly like a silent failure —
        // that's what "not working, questions not generating" turned out to be.
        if (topicBusy || scanBusy) {
            Spacer(Modifier.height(8.dp))
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (topicBusy) {
                            "Drafting $topicBusyLabel… this can take a minute or two on this " +
                                "phone. Keep the app open and on screen until it finishes."
                        } else {
                            "Writing new comprehension questions from $scanBusyLabel… this can " +
                                "take a minute or two on this phone. Keep the app open until " +
                                "it finishes."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
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
                "No questions yet. Tap + to type one, Scan Photo to take a picture of a " +
                    "textbook page (you can scan several pages of the same topic before " +
                    "generating) and have the AI write new comprehension questions about it, " +
                    "or From Topic to draft a whole batch from just a topic name — a handful " +
                    "is enough for a quiz to run.",
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
                topicBusy = true
                topicBusyLabel = "$count question${if (count == 1) "" else "s"} on \"$topic\""
                errorMessage = null
                coroutineScope.launch {
                    when (val result = QuestionAiGenerator.generateBatchFromTopic(context, topic, count)) {
                        is QuestionAiGenerator.BatchResult.Success -> openBatchReview(result.questions)
                        is QuestionAiGenerator.BatchResult.Unavailable -> errorMessage = result.reason
                    }
                    topicBusy = false
                }
            },
            onDismiss = { showTopicDialog = false }
        )
    }
    if (showPageChoiceDialog) {
        ScanPageChoiceDialog(
            pageCount = scannedPages.size,
            maxPages = MAX_SCAN_PAGES,
            onAddAnotherPage = { showPageChoiceDialog = false; launchScan() },
            onGenerateNow = { generateFromScannedPages() },
            onCancel = {
                scannedPages.clear()
                scannedPageImages.clear()
                scannedBlocks.clear()
                showPageChoiceDialog = false
            }
        )
    }
}

/** Shown right after each photo is scanned into the current session: the parent decides whether
 * to keep going (same topic, next page) or stop here and let the AI write questions covering
 * everything scanned so far. Dismissing (tap outside / back) cancels the whole session rather
 * than silently keeping partial pages around. */
@Composable
private fun ScanPageChoiceDialog(
    pageCount: Int,
    maxPages: Int,
    onAddAnotherPage: () -> Unit,
    onGenerateNow: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (pageCount == 1) "Page scanned" else "$pageCount pages scanned") },
        text = {
            Text(
                if (pageCount == 1) {
                    "Add another page of the same topic before generating questions, or " +
                        "generate now from just this one page (up to $maxPages pages total)."
                } else {
                    "Add another page of the same topic, or generate questions now covering " +
                        "all $pageCount pages scanned so far (up to $maxPages pages total)."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onAddAnotherPage) { Text("Add Another Page") }
        },
        dismissButton = {
            TextButton(onClick = onGenerateNow) { Text("Generate Questions Now") }
        }
    )
}

/** One AI-drafted question's editable fields, backed by Compose state so the review list below
 * can be edited in place before saving — mirrors [QuestionEntity]'s fields minus the ones that
 * only make sense for an already-saved question (id, ask stats). */
private class DraftQuestionState(seed: QuestionAiGenerator.DraftQuestion) {
    val imagePaths: List<String> = seed.imagePaths
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
                        "every one before anything is saved. On a real phone this can take a " +
                        "minute or two once you tap Generate; keep the app open until it's done.",
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
 * "From Topic" or from scanning one or more photos. Every field stays editable, any draft can be
 * dropped individually, and nothing reaches the question bank until "Save All" — same "AI
 * drafts, parent decides" contract as the single-question flow. */
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
                            // The scanned page photo(s) this question was drafted from, if any —
                            // lets the parent see exactly what a vaguely-worded AI question is
                            // actually referring to before deciding whether to save it.
                            draft.imagePaths.forEach { path ->
                                ScannedImage(
                                    path = path,
                                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(bottom = 6.dp)
                                )
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

// Radio button + editable option text: deliberately NOT singleLine (a longer AI-drafted option
// used to overflow the field and get cut off with no way to read the rest — see the "answers
// cannot be read" bug this fixed), so long answers wrap to multiple lines instead of being
// truncated. Top-aligned rather than centered so the radio button lines up with the first line
// of a wrapped, multi-line answer rather than floating in the middle of it.
@Composable
private fun DraftOptionRow(
    letter: String,
    value: String,
    onValueChange: (String) -> Unit,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Option $letter") },
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
                            lastAskedAtEpochMillis = existing?.lastAskedAtEpochMillis ?: 0L,
                            // Manual add/edit doesn't attach or remove images in this dialog —
                            // preserve whatever an existing question already had; a brand new
                            // manually-typed question simply has none.
                            imagePaths = existing?.imagePaths
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// See DraftOptionRow above — same fix, same reason: long manually-reviewed or AI-generated
// option text should wrap rather than being cut off at the edge of the field.
@Composable
private fun OptionRow(
    letter: String,
    value: String,
    onValueChange: (String) -> Unit,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Option $letter") },
            modifier = Modifier.weight(1f)
        )
    }
}
