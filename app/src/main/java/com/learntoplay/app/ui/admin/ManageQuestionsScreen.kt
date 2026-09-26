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
import com.learntoplay.app.FeatureFlags
import com.learntoplay.app.ai.QuestionAiGenerator
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.db.entities.QuestionType
import com.learntoplay.app.data.db.entities.toImagePathsColumn
import com.learntoplay.app.util.PendingShare
import com.learntoplay.app.util.ScannedImage
import com.learntoplay.app.util.ScannedTextBlock
import com.learntoplay.app.util.importSharedImage
import com.learntoplay.app.util.rememberPhotoScanLauncher
import kotlinx.coroutines.launch

/** Hard cap on how many pages a parent can add to one "Scan Photo" session before questions are
 * generated automatically — keeps a single AI call (and the review list before it) from growing
 * without bound. See [QuestionAiGenerator.generateComprehensionQuestionsFromScan]. */
private const val MAX_SCAN_PAGES = 5

/** Parent-facing question bank for one curriculum: add, edit, or remove multiple-choice and
 * fill-in-the-blank questions — typed by hand, started from one or more photos of whatever the
 * child is studying (Stage C OCR, now paired with on-device AI that writes fresh
 * comprehension-check questions about that content rather than just copying whatever's printed
 * on it — a parent can scan several pages of the same topic before generating, and the
 * questions cover all of them together), started from content shared in from another app (e.g.
 * a teacher's WhatsApp message — see [incomingShare]), and/or drafted from just a topic name.
 * Every AI batch mixes in at least one fill-in-the-blank question alongside the multiple-choice
 * ones (see QuestionAiGenerator), so a quiz built from this bank isn't pure multiple-choice
 * guessing — see QuizRepository.getQuizQuestions for how that's enforced per-quiz. Every AI call
 * also gets the curriculum's already-saved question prompts so it can steer away from repeating
 * them, and any draft that still looks like a repeat (of the bank, or of an earlier draft in the
 * same batch) is flagged in the review screen rather than silently dropped or silently kept. Every
 * AI path lands in a review step; nothing is saved to the question bank without the parent
 * explicitly saving it.
 *
 * [incomingShare], when non-null, is handled once on first composition (see the
 * `remember { incomingShare }` latch below) and fed into the very same scan-session pipeline a
 * camera photo uses — a shared WhatsApp message becomes a "page" exactly like a photographed
 * textbook page, right down to auto-cropping a question's image out of a shared screenshot.
 * [onIncomingShareConsumed] is called once that hand-off is done (whether it succeeded or not),
 * so the caller (AppNavGraph) can clear its own pending-share state and this screen won't try to
 * process the same share again on a later recomposition. */
@Composable
fun ManageQuestionsScreen(
    viewModel: AdminViewModel,
    curriculumId: String,
    incomingShare: PendingShare? = null,
    onIncomingShareConsumed: () -> Unit = {},
    onBack: () -> Unit
) {
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
    // failed copy. A shared WhatsApp message/screenshot (see [incomingShare] below) is treated
    // as a one-page session in exactly this same state, not a separate mechanism.
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
    // taps "Generate Questions Now" in the page-choice dialog, automatically once
    // [MAX_SCAN_PAGES] is reached, or immediately after a shared message/screenshot is handled
    // (a share is always treated as "generate now," never added to a multi-page session the
    // parent has to explicitly close out).
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
            val existingPrompts = questions.map { it.prompt }
            when (val result = QuestionAiGenerator.generateComprehensionQuestionsFromScan(
                context, pages, images, blocks, existingPrompts = existingPrompts
            )) {
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

    // Handles a share-sheet hand-off (a parent sharing a teacher's WhatsApp message/screenshot
    // into LearnToPlay — see PendingShare) exactly once. `handledShare` latches the value this
    // screen was first composed with via `remember { }` (no key — computed once, ignoring any
    // later change to the `incomingShare` parameter itself), and the effect below is keyed on
    // Unit for the same reason: onIncomingShareConsumed() flows back up through AppNavGraph and
    // clears its pendingShare state, which would otherwise change this composable's
    // `incomingShare` parameter to null mid-flight — if the effect were keyed on that parameter
    // directly, that change would cancel an in-progress OCR/generation coroutine right out from
    // under itself. Calling onIncomingShareConsumed() only at the very end (after handing off
    // to generateFromScannedPages(), not before) avoids that race entirely.
    val handledShare = remember { incomingShare }
    LaunchedEffect(Unit) {
        val share = handledShare
        if (share != null) {
            when (share) {
                is PendingShare.Text -> {
                    scannedPages.add(share.text)
                    generateFromScannedPages()
                }
                is PendingShare.Image -> {
                    scanBusy = true
                    scanBusyLabel = "the shared photo"
                    val imported = importSharedImage(context, share.uri)
                    if (imported == null) {
                        errorMessage = "Couldn't read that shared photo — try sharing it again, " +
                            "or use Scan Photo instead."
                        scanBusy = false
                    } else {
                        scannedPages.add(imported.text)
                        scannedPageImages.add(imported.imagePath)
                        scannedBlocks.addAll(imported.blocks)
                        generateFromScannedPages()
                    }
                }
            }
            onIncomingShareConsumed()
        }
    }

    if (showBatchReview) {
        QuestionBatchReviewContent(
            drafts = draftReview,
            onRemove = { index -> draftReview.removeAt(index) },
            onDiscardAll = { draftReview.clear(); showBatchReview = false },
            onSaveAll = {
                draftReview.forEach { d ->
                    val isFillIn = d.questionType == QuestionType.FILL_IN
                    val isValid = if (isFillIn) {
                        d.prompt.isNotBlank() && d.correctAnswerText.isNotBlank()
                    } else {
                        d.prompt.isNotBlank() && d.optionA.isNotBlank() && d.optionB.isNotBlank() &&
                            d.optionC.isNotBlank() && d.optionD.isNotBlank()
                    }
                    if (isValid) {
                        viewModel.upsertQuestion(
                            QuestionEntity(
                                id = 0,
                                curriculumId = curriculumId,
                                prompt = d.prompt.trim(),
                                optionA = if (isFillIn) "" else d.optionA.trim(),
                                optionB = if (isFillIn) "" else d.optionB.trim(),
                                optionC = if (isFillIn) "" else d.optionC.trim(),
                                optionD = if (isFillIn) "" else d.optionD.trim(),
                                correctOption = if (isFillIn) "" else d.correctOption,
                                timesAsked = 0,
                                timesCorrect = 0,
                                lastAskedAtEpochMillis = 0L,
                                imagePaths = d.imagePaths.toImagePathsColumn(),
                                questionType = d.questionType,
                                correctAnswerText = if (isFillIn) d.correctAnswerText.trim() else null
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
                // Hidden in the MVP build — see FeatureFlags.AI_QUESTION_TOOLS. Manual entry via
                // the "+" button below is the only way to add questions there.
                if (FeatureFlags.AI_QUESTION_TOOLS) {
                    TextButton(onClick = { launchScan() }, enabled = !scanBusy && !topicBusy) { Text("Scan Photo") }
                    TextButton(onClick = { showTopicDialog = true }, enabled = !scanBusy && !topicBusy) { Text("From Topic") }
                }
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
                if (FeatureFlags.AI_QUESTION_TOOLS)
                    "No questions yet. Tap + to type one, Scan Photo to take a picture of a " +
                        "textbook page (you can scan several pages of the same topic before " +
                        "generating) and have the AI write new comprehension questions about it, " +
                        "or From Topic to draft a whole batch from just a topic name — a handful " +
                        "is enough for a quiz to run."
                else
                    "No questions yet. Tap + to type one. Add at least 5 (including one " +
                        "fill-in-the-blank) so each quiz has a full set — more is better, so " +
                        "quizzes don't repeat.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(questions, key = { it.id }) { q ->
                    ListItem(
                        headlineContent = { Text(q.prompt, maxLines = 2) },
                        supportingContent = {
                            val stats = if (q.timesAsked > 0) "Asked ${q.timesAsked}× • ${q.timesCorrect}/${q.timesAsked} correct"
                                else "Not asked yet"
                            Text(if (q.questionType == QuestionType.FILL_IN) "Fill in the blank • $stats" else stats)
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
                val existingPrompts = questions.map { it.prompt }
                coroutineScope.launch {
                    when (val result = QuestionAiGenerator.generateBatchFromTopic(
                        context, topic, count, existingPrompts = existingPrompts
                    )) {
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
 * only make sense for an already-saved question (id, ask stats). [questionType] is fixed at
 * whatever the AI drafted it as (MCQ vs fill-in-the-blank) — not editable in review, matching
 * how other AI-decided shape (e.g. sourcePageImages) works here. [isLikelyDuplicate] is also
 * fixed at whatever QuestionAiGenerator/QuestionSimilarity flagged it as — it's a hint for the
 * parent while reviewing, not something they toggle. [imagePaths] is mutable (not a plain `val`)
 * so the "Adjust picture" flow (see [ManualCropDialog]) can replace it in place —
 * [sourcePageImages] stays fixed at whatever the scan session originally produced, so a manual
 * re-crop always starts from the original full-resolution photo(s), never from an already
 * auto-cropped image. */
private class DraftQuestionState(seed: QuestionAiGenerator.DraftQuestion) {
    val sourcePageImages: List<String> = seed.sourcePageImages
    val questionType: String = seed.questionType
    val isLikelyDuplicate: Boolean = seed.isLikelyDuplicate
    var imagePaths by mutableStateOf(seed.imagePaths)
    var prompt by mutableStateOf(seed.prompt)
    var optionA by mutableStateOf(seed.options.getOrElse(0) { "" })
    var optionB by mutableStateOf(seed.options.getOrElse(1) { "" })
    var optionC by mutableStateOf(seed.options.getOrElse(2) { "" })
    var optionD by mutableStateOf(seed.options.getOrElse(3) { "" })
    var correctOption by mutableStateOf("ABCD".getOrElse(seed.correctIndex) { 'A' }.toString())
    var correctAnswerText by mutableStateOf(seed.correctAnswerText ?: "")
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
                    "The AI will write a new set of questions from scratch — including at " +
                        "least one fill-in-the-blank question so it's not all multiple choice " +
                        "— and you'll review every one before anything is saved. On a real " +
                        "phone this can take a minute or two once you tap Generate; keep the " +
                        "app open until it's done.",
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
 * "From Topic", from scanning one or more photos, or from a shared WhatsApp message/screenshot.
 * Every field stays editable, any draft can be dropped individually, and nothing reaches the
 * question bank until "Save All" — same "AI drafts, parent decides" contract as the
 * single-question flow. A scan- or share-derived draft also gets an "Adjust picture" button
 * (see [ManualCropDialog]) for overriding the automatic image match/crop by hand. Each draft
 * renders either the four-option MCQ editor or a single correct-answer field, depending on
 * [DraftQuestionState.questionType], and shows a small warning when
 * [DraftQuestionState.isLikelyDuplicate] flagged it as close to an existing question — the
 * parent can still keep it (it's a hint, not a block); see QuestionAiGenerator/QuestionSimilarity. */
@Composable
private fun QuestionBatchReviewContent(
    drafts: List<DraftQuestionState>,
    onRemove: (Int) -> Unit,
    onDiscardAll: () -> Unit,
    onSaveAll: () -> Unit
) {
    // Index into `drafts` currently being adjusted in the crop dialog, or null when it's
    // closed. A single shared dialog instance (rather than one per row) keeps only one crop
    // in flight at a time, which is the only sane way to use it anyway.
    var cropDialogIndex by remember { mutableStateOf<Int?>(null) }

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
                                Text(
                                    "Question ${index + 1}" +
                                        if (draft.questionType == QuestionType.FILL_IN) " • Fill in the blank" else "",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                IconButton(onClick = { onRemove(index) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove this draft")
                                }
                            }
                            if (draft.isLikelyDuplicate) {
                                Text(
                                    "This looks similar to a question already in this curriculum's bank " +
                                        "— check it isn't a repeat before saving.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            // The scanned/shared page photo(s) this question was drafted from,
                            // if any — lets the parent see exactly what a vaguely-worded AI
                            // question is actually referring to before deciding whether to save
                            // it.
                            draft.imagePaths.forEach { path ->
                                ScannedImage(
                                    path = path,
                                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(bottom = 6.dp)
                                )
                            }
                            // Only a scan- or share-derived draft has a source page to re-crop
                            // from — a "From Topic" or manually-typed draft has nothing to
                            // adjust.
                            if (draft.sourcePageImages.isNotEmpty()) {
                                TextButton(
                                    onClick = { cropDialogIndex = index },
                                    modifier = Modifier.align(Alignment.Start)
                                ) { Text("Adjust picture") }
                            }
                            OutlinedTextField(
                                value = draft.prompt,
                                onValueChange = { draft.prompt = it },
                                label = { Text("Question") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            if (draft.questionType == QuestionType.FILL_IN) {
                                OutlinedTextField(
                                    value = draft.correctAnswerText,
                                    onValueChange = { draft.correctAnswerText = it },
                                    label = { Text("Correct answer") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                DraftOptionRow("A", draft.optionA, { draft.optionA = it }, draft.correctOption == "A") { draft.correctOption = "A" }
                                DraftOptionRow("B", draft.optionB, { draft.optionB = it }, draft.correctOption == "B") { draft.correctOption = "B" }
                                DraftOptionRow("C", draft.optionC, { draft.optionC = it }, draft.correctOption == "C") { draft.correctOption = "C" }
                                DraftOptionRow("D", draft.optionD, { draft.optionD = it }, draft.correctOption == "D") { draft.correctOption = "D" }
                            }
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

    cropDialogIndex?.let { index ->
        val draft = drafts.getOrNull(index)
        if (draft == null) {
            // The draft it pointed at was removed (onRemove) while the dialog was open —
            // just close it rather than crashing on a stale index.
            cropDialogIndex = null
        } else {
            ManualCropDialog(
                pageImages = draft.sourcePageImages,
                onCropped = { path -> draft.imagePaths = listOf(path); cropDialogIndex = null },
                onUseFullPage = { draft.imagePaths = draft.sourcePageImages; cropDialogIndex = null },
                onDismiss = { cropDialogIndex = null }
            )
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
    var questionType by remember { mutableStateOf(existing?.questionType ?: QuestionType.MCQ) }
    var prompt by remember { mutableStateOf(existing?.prompt ?: scannedText ?: "") }
    var optionA by remember { mutableStateOf(existing?.optionA ?: "") }
    var optionB by remember { mutableStateOf(existing?.optionB ?: "") }
    var optionC by remember { mutableStateOf(existing?.optionC ?: "") }
    var optionD by remember { mutableStateOf(existing?.optionD ?: "") }
    var correctOption by remember { mutableStateOf(existing?.correctOption?.takeIf { it.isNotBlank() } ?: "A") }
    var correctAnswerText by remember { mutableStateOf(existing?.correctAnswerText ?: "") }

    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isValid = if (questionType == QuestionType.FILL_IN) {
        prompt.isNotBlank() && correctAnswerText.isNotBlank()
    } else {
        prompt.isNotBlank() && optionA.isNotBlank() && optionB.isNotBlank() &&
            optionC.isNotBlank() && optionD.isNotBlank()
    }

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

                // Question type: multiple choice (four options, tap one) or fill-in-the-blank
                // (child types the answer). At least one FILL_IN question per quiz is guaranteed
                // by QuizRepository.getQuizQuestions whenever the curriculum has any, so this is
                // where a parent adds that guaranteed-no-luck question.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = questionType == QuestionType.MCQ,
                        onClick = { questionType = QuestionType.MCQ },
                        label = { Text("Multiple choice") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = questionType == QuestionType.FILL_IN,
                        onClick = { questionType = QuestionType.FILL_IN },
                        label = { Text("Fill in the blank") }
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    prompt, { prompt = it }, label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                if (questionType == QuestionType.FILL_IN) {
                    OutlinedTextField(
                        value = correctAnswerText,
                        onValueChange = { correctAnswerText = it },
                        label = { Text("Correct answer") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Checked leniently — capitalization, spacing, and punctuation don't " +
                            "have to match exactly, but the child still has to type the right " +
                            "word(s)." + if (FeatureFlags.AI_QUESTION_TOOLS)
                                " Use Scan Photo or From Topic if you'd like the AI to draft one " +
                                    "of these for you instead." else "",
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    // Hidden in the MVP build — see FeatureFlags.AI_QUESTION_TOOLS.
                    if (FeatureFlags.AI_QUESTION_TOOLS) {
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
                    }
                    Spacer(Modifier.height(4.dp))

                    OptionRow("A", optionA, { optionA = it }, correctOption == "A") { correctOption = "A" }
                    OptionRow("B", optionB, { optionB = it }, correctOption == "B") { correctOption = "B" }
                    OptionRow("C", optionC, { optionC = it }, correctOption == "C") { correctOption = "C" }
                    OptionRow("D", optionD, { optionD = it }, correctOption == "D") { correctOption = "D" }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap the circle next to the correct answer. The order doesn't matter " +
                            "— options are shuffled each time the quiz shows this question." +
                            if (FeatureFlags.AI_QUESTION_TOOLS)
                                " AI-drafted options are a starting point — always double-check " +
                                    "them before saving." else "",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    val isFillIn = questionType == QuestionType.FILL_IN
                    onSave(
                        QuestionEntity(
                            id = existing?.id ?: 0,
                            curriculumId = curriculumId,
                            prompt = prompt.trim(),
                            // FILL_IN rows leave these four unused ("" sentinel) — see
                            // QuestionEntity's doc comment for why they stay non-null.
                            optionA = if (isFillIn) "" else optionA.trim(),
                            optionB = if (isFillIn) "" else optionB.trim(),
                            optionC = if (isFillIn) "" else optionC.trim(),
                            optionD = if (isFillIn) "" else optionD.trim(),
                            correctOption = if (isFillIn) "" else correctOption,
                            timesAsked = existing?.timesAsked ?: 0,
                            timesCorrect = existing?.timesCorrect ?: 0,
                            lastAskedAtEpochMillis = existing?.lastAskedAtEpochMillis ?: 0L,
                            // Manual add/edit doesn't attach or remove images in this dialog —
                            // preserve whatever an existing question already had; a brand new
                            // manually-typed question simply has none.
                            imagePaths = existing?.imagePaths,
                            questionType = questionType,
                            correctAnswerText = if (isFillIn) correctAnswerText.trim() else null
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
