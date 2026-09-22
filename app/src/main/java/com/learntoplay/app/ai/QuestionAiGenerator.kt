package com.learntoplay.app.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.learntoplay.app.util.ScannedTextBlock
import com.learntoplay.app.util.cropAndSaveRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drafts multiple-choice questions using a small language model running entirely on-device
 * (MediaPipe LLM Inference API, e.g. Gemma 3 1B) — no network call, no cloud service,
 * consistent with the app's local-first design. Three ways in:
 *  - [generateOptions]: given one question the parent already typed/scanned, draft its four
 *    options (the original Stage-AI feature).
 *  - [generateBatchFromTopic]: given just a topic/chapter, write a whole new set of questions
 *    from scratch.
 *  - [generateComprehensionQuestionsFromScan]: given raw OCR text from one or more photos of
 *    whatever the child is studying (a textbook page, notes, a worksheet — a parent can scan
 *    several pages of the same topic before generating), write a fresh batch of
 *    comprehension-check questions covering that material — deliberately NOT the same questions
 *    printed on the page(s) (if any), so a quiz built from it actually tests whether the child
 *    understood the concept rather than just letting them re-answer something they may have
 *    already seen the answer to.
 *
 * The model file (500MB+) is never bundled in the APK or downloaded automatically; it's pushed
 * once via `adb push` to a fixed path and loaded from there. If it isn't present yet, every
 * entry point fails with the same clear, actionable message rather than crashing.
 *
 * This is a drafting aid, not an auto-fill: every result — single question or a whole batch —
 * is always shown back to the parent for review, edit, and explicit save. The AI never writes
 * directly to the question bank; see ManageQuestionsScreen's batch review UI.
 */
object QuestionAiGenerator {

    /** Logcat tag for the raw model output — see the [Log.d] calls below. MediaPipe doesn't
     * log the model's actual reply anywhere on its own, and when [parseBatchResponse] /
     * [parseSingleResponse] can't make sense of a reply, the only way to find out *why* (wrong
     * format? cut off? the model went off-topic?) is to see the raw text — this is the one and
     * only source of truth for that while debugging generation quality. `adb logcat -s
     * QuestionAiGenerator` isolates just these lines. */
    private const val TAG = "QuestionAiGenerator"

    /** Fixed on-device path the model is expected at — matches the `adb push` destination in
     * the setup notes given to the parent when this feature is unavailable. */
    private const val MODEL_PATH = "/data/local/tmp/llm/gemma3-1b-it.task"

    private const val MODEL_MISSING_MESSAGE =
        "AI model not found on this device. Push the .task model file to $MODEL_PATH " +
            "(see the Stage C setup notes), then try again. This only works on a real phone, " +
            "not the emulator."

    /** Default number of comprehension questions to draft from a single scanned page — enough
     * to meaningfully check understanding without asking the model (and the parent reviewing
     * the result) to churn through more than a photo's worth of content usually supports. When
     * more than one page is scanned in the same session, [defaultScanQuestionCount] scales this
     * up a bit rather than asking the same fixed count regardless of how much material was
     * actually covered. */
    private const val DEFAULT_SCAN_QUESTION_COUNT = 5

    /** Matches the ekv2048 context window the pushed model file is compiled for (see the
     * `adb push` setup notes) — this is the model's own ceiling, not an arbitrary choice. The
     * MediaPipe LLM Inference session otherwise defaults to a much smaller token budget (shared
     * between the whole prompt AND the model's reply), which was silently truncating longer
     * generations — most visibly "Scan Photo," whose prompt includes a full page of OCR'd text
     * plus instructions plus several whole question blocks to write back out. */
    private const val MAX_TOKENS = 2048

    /** Safety cap on how much OCR'd text — summed across every page scanned in one session —
     * goes into the comprehension prompt. Generous for a handful of photographed pages, but
     * bounded so a multi-page scan can't eat the entire [MAX_TOKENS] budget and leave no room
     * for the model to write its questions back out. Split evenly across however many pages
     * were scanned (see [buildComprehensionPrompt]), so adding more pages trims each page's
     * share rather than blowing the total budget. */
    private const val MAX_SCAN_CHARS_TOTAL = 3000

    /** Minimum number of [significantTokens] a scanned page's region (see
     * [clusterBlocksIntoRegions]) must share with a generated question before
     * [matchAndCropRegion] trusts it enough to crop that region — below this, the shared
     * word(s) are treated as coincidental rather than a real topic match. */
    private const val MIN_MATCH_TOKENS = 1

    /** Words common enough to show up in almost any question regardless of topic — excluded from
     * [significantTokens] so they don't inflate every block's match score equally and drown out
     * the words that actually identify what a question is about. Deliberately short; the goal is
     * filtering out grammatical noise, not building a real stopword list. */
    private val MATCH_STOPWORDS = setOf(
        "the", "and", "for", "are", "was", "were", "this", "that", "these", "those",
        "how", "what", "which", "does", "did", "show", "shows", "shown", "many", "much",
        "correct", "answer", "option", "options", "question", "true", "false", "not"
    )

    /** How far apart (as a fraction of each OCR block's own height/width) two blocks on the
     * same page can be and still get merged into one [ScannedRegion] by
     * [clusterBlocksIntoRegions] — see that function's doc for why merging matters at all.
     * Vertical padding is generous relative to horizontal: a pictograph table's rows (title,
     * then one row per item) are usually stacked vertically with more gap between them than a
     * label sits from its own number beside it, so the two axes need different tolerances. */
    private const val REGION_PAD_VERTICAL_FACTOR = 0.9
    private const val REGION_PAD_HORIZONTAL_FACTOR = 0.5

    /** Floor on the per-block padding [clusterBlocksIntoRegions] uses, in pixels — a tiny OCR
     * block (e.g. a single short number) would otherwise get almost no padding at all under the
     * proportional formula above, making it too easy to end up isolated from the row/label it
     * visually belongs with. */
    private const val REGION_PAD_MIN_PX = 24

    @Volatile
    private var inference: LlmInference? = null

    /** One AI-drafted question with its four options and which one is correct — always shown
     * to the parent for review before anything reaches the question bank. [imagePaths] carries
     * the picture(s) currently attached to this question — the auto-matched crop if
     * [matchAndCropRegion] found one, otherwise every scanned page (the previous, whole-page
     * behavior) — and is empty for a "From Topic" or manually-typed question. [sourcePageImages]
     * is the scan session's original, full-resolution page photo(s) this question came from,
     * regardless of what [imagePaths] currently shows; ManageQuestionsScreen's manual "Adjust
     * picture" flow re-crops from these, never from an already-cropped image, so fixing a bad
     * auto-crop never loses detail by cropping a crop. Also empty for a "From Topic" or
     * manually-typed question. */
    data class DraftQuestion(
        val prompt: String,
        val options: List<String>,
        val correctIndex: Int,
        val imagePaths: List<String> = emptyList(),
        val sourcePageImages: List<String> = emptyList()
    )

    sealed interface Result {
        data class Success(val options: List<String>, val correctIndex: Int) : Result
        data class Unavailable(val reason: String) : Result
    }

    sealed interface BatchResult {
        data class Success(val questions: List<DraftQuestion>) : BatchResult
        data class Unavailable(val reason: String) : BatchResult
    }

    suspend fun generateOptions(context: Context, questionText: String): Result =
        withContext(Dispatchers.IO) {
            if (questionText.isBlank()) {
                return@withContext Result.Unavailable("Type or scan a question first.")
            }
            val llm = loadModelOrNull(context)
                ?: return@withContext Result.Unavailable(MODEL_MISSING_MESSAGE)
            try {
                val response = llm.generateResponse(buildSingleQuestionPrompt(questionText))
                Log.d(TAG, "generateOptions raw response (${response.length} chars): $response")
                parseSingleResponse(response)
                    ?: Result.Unavailable(
                        "The AI's answer didn't come back in a format I could read. Try again, " +
                            "or just fill in the four options by hand."
                    )
            } catch (e: Exception) {
                Result.Unavailable(
                    "AI generation failed (${e.message ?: "unknown error"}). Try again, or fill " +
                        "in the options by hand."
                )
            }
        }

    /** Writes a whole new set of [count] (1–10) multiple-choice questions about [topic] from
     * scratch — e.g. "Class 5 Science — Photosynthesis" — for the parent to review and
     * selectively save in one pass instead of drafting one question at a time. */
    suspend fun generateBatchFromTopic(context: Context, topic: String, count: Int): BatchResult =
        withContext(Dispatchers.IO) {
            if (topic.isBlank()) {
                return@withContext BatchResult.Unavailable("Type a topic or chapter first.")
            }
            val llm = loadModelOrNull(context)
                ?: return@withContext BatchResult.Unavailable(MODEL_MISSING_MESSAGE)
            try {
                val askedFor = count.coerceIn(1, 10)
                val response = llm.generateResponse(buildBatchPrompt(topic, askedFor))
                Log.d(TAG, "generateBatchFromTopic raw response (${response.length} chars): $response")
                // The model sometimes writes more (or fewer) questions than asked for — cap to
                // what the parent actually requested rather than dumping every extra one on
                // them in the review screen.
                val parsed = parseBatchResponse(response)?.take(askedFor)
                if (parsed.isNullOrEmpty()) {
                    BatchResult.Unavailable(
                        "The AI's answer didn't come back in a format I could read. Try again " +
                            "with a more specific topic."
                    )
                } else {
                    BatchResult.Success(parsed)
                }
            } catch (e: Exception) {
                BatchResult.Unavailable("AI generation failed (${e.message ?: "unknown error"}). Try again.")
            }
        }

    /** Given raw OCR text from one or more photos of whatever the child is studying — a parent
     * can take several photos of the same topic (e.g. consecutive textbook pages) in one Scan
     * Photo session before generating — writes a fresh batch of [count] comprehension-check
     * questions about the concepts covered across all of them combined. Not a copy, reformat,
     * or light reword of anything already printed on any page. The point is to check whether
     * the child actually understood the material, so the AI is explicitly told to write its own
     * original questions rather than lift the pages'. Works whether the photos are a worksheet
     * with existing questions, plain textbook pages, or class notes — they don't need to already
     * contain questions. [count] defaults to [defaultScanQuestionCount], which scales up a
     * little with how many pages were scanned rather than asking for the same fixed count
     * regardless of how much material was actually covered. Returns [BatchResult.Unavailable]
     * (rather than throwing) when the model isn't installed, so the caller can fall back to the
     * original manual-entry flow instead of breaking scanning entirely. [imagePaths] (the
     * persisted photo(s) for this scan session, if any — see PhotoScanCapture) becomes every
     * draft's [DraftQuestion.sourcePageImages] unconditionally, and is also the fallback
     * [DraftQuestion.imagePaths] for a question when nothing better can be found; [blocks] (that
     * session's OCR'd text blocks with position, also from PhotoScanCapture) lets
     * [matchAndCropRegion] try to narrow that down to just the part of the page a given question
     * is actually about — see its doc for how. Either way, the parent/child ends up seeing a
     * picture of the actual scanned material alongside what might otherwise be a vaguely-worded
     * question, and the parent can always override the auto-match by hand (ManageQuestionsScreen's
     * "Adjust picture") since [DraftQuestion.sourcePageImages] is always the original, uncropped
     * photo(s) regardless of what got auto-selected. */
    suspend fun generateComprehensionQuestionsFromScan(
        context: Context,
        scannedPages: List<String>,
        imagePaths: List<String> = emptyList(),
        blocks: List<ScannedTextBlock> = emptyList(),
        count: Int = defaultScanQuestionCount(scannedPages.size)
    ): BatchResult =
        withContext(Dispatchers.IO) {
            val pages = scannedPages.map { it.trim() }.filter { it.isNotBlank() }
            if (pages.isEmpty()) {
                return@withContext BatchResult.Unavailable("Nothing was scanned.")
            }
            val llm = loadModelOrNull(context)
                ?: return@withContext BatchResult.Unavailable(MODEL_MISSING_MESSAGE)
            try {
                val askedFor = count.coerceIn(1, 10)
                val response = llm.generateResponse(buildComprehensionPrompt(pages, askedFor))
                Log.d(TAG, "generateComprehensionQuestionsFromScan raw response " +
                    "(${response.length} chars, ${pages.size} page(s)): $response")
                // The model sometimes writes more (or fewer) questions than asked for — cap to
                // what the parent actually requested rather than dumping every extra one on
                // them in the review screen. For each question, try to find the region of the
                // page(s) its wording actually matches (e.g. the specific pictograph it's
                // asking about on a page with several) and crop just that region — a scanned
                // page can cover more than one question's worth of content, and showing every
                // question the entire page is otherwise both less useful and more cluttered than
                // showing just the relevant part. Falls back to every page photo, unmatched
                // (the previous behavior), when no confident match is found for a question —
                // and every draft keeps the original page(s) as sourcePageImages regardless, so
                // a bad auto-crop is never unrecoverable.
                val parsed = parseBatchResponse(response)?.take(askedFor)?.map { draft ->
                    val croppedPath = matchAndCropRegion(context, draft, blocks)
                    draft.copy(
                        imagePaths = if (croppedPath != null) listOf(croppedPath) else imagePaths,
                        sourcePageImages = imagePaths
                    )
                }
                if (parsed.isNullOrEmpty()) {
                    BatchResult.Unavailable(
                        "Couldn't write questions from that scan. Try again with a clearer, " +
                            "well-lit photo of the page, or add a question by hand."
                    )
                } else {
                    BatchResult.Success(parsed)
                }
            } catch (e: Exception) {
                BatchResult.Unavailable("AI generation failed (${e.message ?: "unknown error"}).")
            }
        }

    /** One logical figure or section on a scanned page — a cluster of nearby OCR text blocks
     * (a title, its row labels, its numbers, ...) merged into a single region, with the union
     * of their bounding boxes and their combined text. See [clusterBlocksIntoRegions]. */
    private data class ScannedRegion(
        val imagePath: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val text: String
    )

    /** Groups a page's OCR text blocks into logical regions before [matchAndCropRegion] scores
     * them, rather than matching single blocks directly. ML Kit splits a page into many small
     * blocks — a pictograph table's title, its row labels, and its counts typically all land in
     * separate blocks even though they're one visual figure a question is about — so matching
     * single blocks either missed most of a figure's words (weak, unreliable scores) or cropped
     * just one line of it (the title, with no numbers). This merges blocks whose padded
     * bounding boxes overlap into one region via a union-find over every pair of blocks on the
     * same page — cheap here since a page realistically has a few dozen OCR blocks, never
     * thousands. Each block's padding is proportional to its own size (see
     * [REGION_PAD_VERTICAL_FACTOR]/[REGION_PAD_HORIZONTAL_FACTOR]) rather than a fixed pixel
     * amount, since photos of different pages can be scanned at very different resolutions.
     * Only blocks from the same page (same [ScannedTextBlock.imagePath]) are ever merged. */
    private fun clusterBlocksIntoRegions(blocks: List<ScannedTextBlock>): List<ScannedRegion> {
        val regions = mutableListOf<ScannedRegion>()
        for ((imagePath, pageBlocks) in blocks.groupBy { it.imagePath }) {
            val parent = IntArray(pageBlocks.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                parent[x] = r
                return r
            }
            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }
            fun padded(b: ScannedTextBlock): IntArray {
                val h = (b.bottom - b.top).coerceAtLeast(1)
                val w = (b.right - b.left).coerceAtLeast(1)
                val padY = (h * REGION_PAD_VERTICAL_FACTOR).toInt().coerceAtLeast(REGION_PAD_MIN_PX)
                val padX = (w * REGION_PAD_HORIZONTAL_FACTOR).toInt().coerceAtLeast(REGION_PAD_MIN_PX)
                return intArrayOf(b.left - padX, b.top - padY, b.right + padX, b.bottom + padY)
            }

            for (i in pageBlocks.indices) {
                val a = padded(pageBlocks[i])
                for (j in i + 1 until pageBlocks.size) {
                    val c = padded(pageBlocks[j])
                    val overlaps = a[0] < c[2] && c[0] < a[2] && a[1] < c[3] && c[1] < a[3]
                    if (overlaps) union(i, j)
                }
            }

            pageBlocks.indices.groupBy { find(it) }.values.forEach { memberIndices ->
                val members = memberIndices.map { pageBlocks[it] }
                regions += ScannedRegion(
                    imagePath = imagePath,
                    left = members.minOf { it.left },
                    top = members.minOf { it.top },
                    right = members.maxOf { it.right },
                    bottom = members.maxOf { it.bottom },
                    text = members.joinToString(" ") { it.text }
                )
            }
        }
        return regions
    }

    /** Word-overlap heuristic for guessing which part of a scanned page (if any) a generated
     * [draft] question is actually about — e.g. picking out just the "storybooks" pictograph on
     * a page with several, rather than always falling back to the whole page. Matches against
     * whole [ScannedRegion]s (clusters of nearby OCR blocks — see [clusterBlocksIntoRegions]),
     * not individual blocks, so the matched area actually covers a whole figure rather than one
     * line of it. Scores every region by how many [significantTokens] it shares with the
     * question's prompt + options, keeps the best-scoring one, and requires at least
     * [MIN_MATCH_TOKENS] shared words before trusting the guess enough to crop — one incidental
     * shared word isn't strong enough signal, and cropping the wrong part of the page would be
     * worse than just showing the whole thing. On a confident match, crops that region (plus
     * padding, via [cropAndSaveRegion]) and returns the new file's path; returns null (not a
     * hard failure) whenever there's nothing to match against, no confident match, or the crop
     * itself fails, so the caller can fall back to the full page image(s) instead — and the
     * parent can always override this by hand afterward (see [DraftQuestion.sourcePageImages]). */
    private fun matchAndCropRegion(
        context: Context,
        draft: DraftQuestion,
        blocks: List<ScannedTextBlock>
    ): String? {
        if (blocks.isEmpty()) return null
        val questionTokens = significantTokens(draft.prompt + " " + draft.options.joinToString(" "))
        if (questionTokens.isEmpty()) return null

        val regions = clusterBlocksIntoRegions(blocks)
        var bestRegion: ScannedRegion? = null
        var bestScore = 0
        for (region in regions) {
            val score = questionTokens.intersect(significantTokens(region.text)).size
            if (score > bestScore) {
                bestScore = score
                bestRegion = region
            }
        }
        val region = bestRegion ?: return null
        if (bestScore < MIN_MATCH_TOKENS) return null

        return cropAndSaveRegion(context, region.imagePath, region.left, region.top, region.right, region.bottom)
    }

    /** Lowercased alphanumeric words longer than 2 characters, minus [MATCH_STOPWORDS] — the
     * vocabulary [matchAndCropRegion] scores regions against. Short/common words (articles,
     * question words like "how"/"what", generic terms like "show"/"many") appear in nearly every
     * question regardless of topic and would otherwise match everything on the page equally,
     * defeating the point of scoring at all. */
    private fun significantTokens(text: String): Set<String> =
        Regex("""[A-Za-z0-9]+""").findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 2 && it !in MATCH_STOPWORDS }
            .toSet()

    /** One page still asks for [DEFAULT_SCAN_QUESTION_COUNT]; each additional page in the same
     * session adds a couple more, up to the same 10-question ceiling every batch path respects
     * — more scanned material can reasonably support more distinct questions, but the review
     * screen and the model's own reply length shouldn't grow unbounded. */
    private fun defaultScanQuestionCount(pageCount: Int): Int =
        (DEFAULT_SCAN_QUESTION_COUNT + (pageCount - 1).coerceAtLeast(0) * 2).coerceIn(1, 10)

    private fun loadModelOrNull(context: Context): LlmInference? {
        if (!File(MODEL_PATH).exists()) return null
        return inference ?: synchronized(this) {
            inference ?: LlmInference.createFromOptions(
                context.applicationContext,
                LlmInferenceOptions.builder()
                    .setModelPath(MODEL_PATH)
                    .setMaxTopK(64)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
            ).also { inference = it }
        }
    }

    private fun buildSingleQuestionPrompt(questionText: String): String = """
        You are helping a parent build a multiple-choice quiz question for their child.
        Given the question below, write exactly four short answer options labeled A to D,
        with exactly one correct answer. Reply with ONLY this format, nothing else:

        A) <option>
        B) <option>
        C) <option>
        D) <option>
        CORRECT: <letter>

        Question: ${questionText.trim()}
    """.trimIndent()

    private fun buildBatchPrompt(topic: String, count: Int): String = """
        You are helping a parent build a multiple-choice quiz for their child on this topic:
        "${topic.trim()}". Write exactly $count different questions about it, appropriate for a
        school-age child. Ask concrete, specific questions with a single clear factual answer —
        avoid vague or abstract questions about the topic in general (e.g. "Why is this topic
        important?" or "What is this topic about?"). For each question, give exactly four short
        answer options labeled A to D with exactly one correct answer. Reply with ONLY this
        format, one block per question, nothing else, separated by a line containing only ---:

        Q: <question text>
        A) <option>
        B) <option>
        C) <option>
        D) <option>
        CORRECT: <letter>
        ---
    """.trimIndent()

    /** Builds the comprehension-question prompt from one or more scanned pages. A single page
     * is inlined as-is (matches the original single-photo prompt exactly). Multiple pages are
     * each labeled ("Page 1:", "Page 2:", ...) and concatenated, with [MAX_SCAN_CHARS_TOTAL]
     * split evenly across however many pages there are so a multi-page scan can't let any one
     * page (or the total) blow the model's token budget. Explicitly steers away from abstract
     * "meta" questions about the material itself (its purpose, layout, or how it's presented) —
     * on-device generation kept defaulting to those ("What is the goal of the activity?", "How
     * is the information presented?") instead of asking about the actual facts/numbers/names in
     * the content, which is both harder for a child to answer meaningfully and rarely has a
     * single unambiguous correct option among the four choices. */
    private fun buildComprehensionPrompt(scannedPages: List<String>, count: Int): String {
        val perPageBudget = (MAX_SCAN_CHARS_TOTAL / scannedPages.size).coerceAtLeast(300)
        val multiPage = scannedPages.size > 1
        val pagesBlock = scannedPages.mapIndexed { index, page ->
            val trimmed = page.let {
                if (it.length > perPageBudget) it.take(perPageBudget) + "…" else it
            }
            if (multiPage) "Page ${index + 1}:\n$trimmed" else trimmed
        }.joinToString("\n\n")
        val sourceDescription = if (multiPage) "photos of ${scannedPages.size} pages" else "a photo of a page"
        val materialReference = if (multiPage) "this text across all the pages" else "this text"
        return """
        Below is text recognized from $sourceDescription a child is studying — this could be a
        textbook page, class notes, or a worksheet. Ignore anything that isn't part of the
        actual lesson content (headings, page numbers, printed instructions like "Answer the
        following"). Based on the concepts, facts, and ideas covered in $materialReference, write
        exactly $count NEW multiple-choice questions that check whether the child understood the
        material. Do NOT simply copy, reformat, or lightly reword any questions that may already
        be printed on the page(s) — write original questions of your own, at a similar
        difficulty, that a student who truly understood the material would be able to answer.
        Ask concrete questions about specific facts, numbers, names, quantities, steps, or
        relationships that actually appear in the material — never ask abstract or "meta"
        questions about the text or activity itself, such as "What is the goal of the activity?",
        "How is the information presented?", or "What is this passage about?". A well-written
        question should only be answerable by someone who read the specific content, not
        guessable from the question's own wording.
        For each question, give exactly four short answer options labeled A to D with exactly
        one correct answer. Reply with ONLY this format, one block per question, nothing else,
        separated by a line containing only ---:

        Q: <question text>
        A) <option>
        B) <option>
        C) <option>
        D) <option>
        CORRECT: <letter>
        ---

        Scanned text:
        $pagesBlock
        """.trimIndent()
    }

    private fun parseSingleResponse(text: String): Result.Success? {
        val optionRegex = Regex("""^[ABCD]\)\s*(.+)$""", RegexOption.MULTILINE)
        val options = optionRegex.findAll(text).map { cleanField(it.groupValues[1]) }.toList()
        val correctLetter = Regex("""CORRECT:\s*([ABCD])""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.uppercase()
        if (options.size != 4 || correctLetter == null) return null
        val correctIndex = "ABCD".indexOf(correctLetter)
        if (correctIndex !in 0..3) return null
        val (shuffledOptions, shuffledCorrectIndex) = shuffledWithCorrectIndex(options, correctIndex)
        return Result.Success(shuffledOptions, shuffledCorrectIndex)
    }

    /** Parses one or more "Q: ... A) ... B) ... C) ... D) ... CORRECT: X" blocks out of a
     * response, in any order and regardless of what separates them. Deliberately tolerant of
     * how the fields are laid out — one per line (the format asked for in the prompt) OR all
     * run together on a single line separated by spaces/commas (what the on-device model
     * actually tends to do — confirmed via the raw-response logging above: it reliably produces
     * the right content, just not always the requested line breaks). The earlier version of
     * this regex required a literal newline between fields and was silently rejecting every
     * otherwise-valid block a single-line reply produced — that was the real bug behind
     * generation "not working," not the model. Also not dependent on the "---" separator
     * actually showing up verbatim, since small on-device models don't always follow formatting
     * instructions to the letter. A block whose CORRECT isn't exactly one of A/B/C/D (the model
     * occasionally echoes the answer's value instead of its letter) simply doesn't match and is
     * skipped, rather than failing the whole batch. Deliberately NOT using DOT_MATCHES_ALL: each
     * captured field is expected to be plain text with no embedded newline, and keeping "."
     * confined to a single line is what stops a block with a malformed CORRECT value (see
     * above) from backtracking across the "---" separator and bleeding into the next block —
     * the field separators still cross line breaks fine since \s already matches newlines.
     *
     * Each parsed block's options are shuffled via [shuffledWithCorrectIndex] before becoming a
     * [DraftQuestion] — see that function's doc for why. */
    private fun parseBatchResponse(text: String): List<DraftQuestion>? {
        val blockRegex = Regex(
            """Q:\s*(.+?)\s*,?\s*A\)\s*(.+?)\s*,?\s*B\)\s*(.+?)\s*,?\s*C\)\s*(.+?)\s*,?\s*D\)\s*(.+?)\s*,?\s*CORRECT:\s*([ABCD])\b""",
            RegexOption.IGNORE_CASE
        )
        val matches = blockRegex.findAll(text).toList()
        if (matches.isEmpty()) return null
        return matches.mapNotNull { m ->
            val g = m.groupValues
            val correctIndex = "ABCD".indexOf(g[6].uppercase())
            if (correctIndex !in 0..3 || g[1].isBlank()) {
                null
            } else {
                val options = listOf(cleanField(g[2]), cleanField(g[3]), cleanField(g[4]), cleanField(g[5]))
                val (shuffledOptions, shuffledCorrectIndex) = shuffledWithCorrectIndex(options, correctIndex)
                DraftQuestion(cleanField(g[1]), shuffledOptions, shuffledCorrectIndex)
            }
        }
    }

    /** Randomizes the order of a question's four options, returning the new order and where
     * the correct answer ended up. Needed because the on-device model reliably writes its
     * *correct* option in a predictable slot far more often than chance would — most commonly
     * option A — since that's simply the order small local models tend to reason in (state the
     * right answer first, then invent three wrong ones). Left unshuffled, a child (or a parent
     * skimming quickly) could learn to just always pick the first option and score well without
     * knowing the material, which defeats the entire point of the quiz gate. Applied uniformly
     * to every parsed question — [parseSingleResponse] and [parseBatchResponse] — right after
     * parsing, so every downstream consumer (review screen, saved [QuestionEntity]) only ever
     * sees already-shuffled options and never needs to think about this itself.
     *
     * `internal` rather than `private` purely so ScoreTimeCalculator-style direct unit tests
     * (see QuestionAiGeneratorTest) can exercise the shuffle's invariants — that it's a true
     * permutation of the input and that the value at the returned index is still the original
     * correct option — without needing to drive a whole model response through regex parsing
     * just to reach this logic. */
    internal fun shuffledWithCorrectIndex(options: List<String>, correctIndex: Int): Pair<List<String>, Int> {
        val order = options.indices.shuffled()
        val shuffled = order.map { options[it] }
        val newCorrectIndex = order.indexOf(correctIndex)
        return shuffled to newCorrectIndex
    }

    /** The on-device model occasionally writes a literal two-character "\n" (backslash then n)
     * inside a field instead of an actual line break (confirmed by seeing it verbatim in
     * captured field text ("1 and 2\n"). [String.trim] only strips real whitespace, not that
     * literal escape sequence, so it was passing straight through into the review screen. This
     * strips it (and its escaped-tab cousin, just in case) wherever it shows up in a field, not
     * just at the end, since the model doesn't always put it only at the edges. */
    private fun cleanField(raw: String): String =
        raw.replace("\\n", " ").replace("\\t", " ").replace(Regex("""\s+"""), " ").trim()
}
