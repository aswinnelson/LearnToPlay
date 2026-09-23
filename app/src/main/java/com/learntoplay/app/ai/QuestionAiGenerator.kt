package com.learntoplay.app.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.learntoplay.app.data.db.entities.QuestionType
import com.learntoplay.app.util.ScannedTextBlock
import com.learntoplay.app.util.cropAndSaveRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drafts multiple-choice AND fill-in-the-blank questions using a small language model running
 * entirely on-device (MediaPipe LLM Inference API, e.g. Gemma 3 1B) — no network call, no cloud
 * service, consistent with the app's local-first design. Three ways in:
 *  - [generateOptions]: given one question the parent already typed/scanned, draft its four
 *    MCQ options (the original Stage-AI feature — MCQ only, no fill-in path here).
 *  - [generateBatchFromTopic]: given just a topic/chapter, write a whole new set of questions
 *    from scratch — a mix of MCQ plus exactly one fill-in-the-blank question, so every
 *    AI-drafted batch contributes at least one question a child can't pass by guessing (see
 *    QuizRepository.getQuizQuestions, which guarantees a quiz includes one when the bank has any).
 *  - [generateComprehensionQuestionsFromScan]: given raw OCR text from one or more photos of
 *    whatever the child is studying (a textbook page, notes, a worksheet — a parent can scan
 *    several pages of the same topic before generating), write a fresh batch of
 *    comprehension-check questions covering that material — deliberately NOT the same questions
 *    printed on the page(s) (if any), so a quiz built from it actually tests whether the child
 *    understood the concept rather than just letting them re-answer something they may have
 *    already seen the answer to. Also mixes in one fill-in-the-blank question.
 *
 * Both batch entry points also take the curriculum's already-saved question prompts
 * ([generateBatchFromTopic]'s and [generateComprehensionQuestionsFromScan]'s `existingPrompts`)
 * and use them two ways: as a "don't repeat these" note in the prompt itself, and to flag each
 * returned [DraftQuestion.isLikelyDuplicate] against both the existing bank and earlier drafts
 * in the same batch (see [QuestionSimilarity]) — easy to hit in practice when the same page gets
 * scanned twice, or "From Topic" is run again on a subject already covered. A flagged draft is
 * never dropped automatically; it's just marked for the parent to notice in review.
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

    /** Cap on how many of the curriculum's already-saved question prompts get listed in the
     * "don't repeat these" prompt note (see [buildAvoidDuplicatesNote]) — a bank can grow past
     * what's worth spending prompt budget on, and the most recently added ones are the most
     * likely to overlap with whatever the parent is generating right now anyway. */
    private const val MAX_EXISTING_PROMPTS_LISTED = 20

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

    /** One AI-drafted question, either MCQ ([options]/[correctIndex] populated, [questionType]
     * is [QuestionType.MCQ]) or fill-in-the-blank ([correctAnswerText] populated instead,
     * [questionType] is [QuestionType.FILL_IN]) — always shown to the parent for review before
     * anything reaches the question bank. [imagePaths] carries the picture(s) currently
     * attached to this question — the auto-matched crop if [matchAndCropRegion] found one,
     * otherwise every scanned page (the previous, whole-page behavior) — and is empty for a
     * "From Topic" or manually-typed question. [sourcePageImages] is the scan session's
     * original, full-resolution page photo(s) this question came from, regardless of what
     * [imagePaths] currently shows; ManageQuestionsScreen's manual "Adjust picture" flow
     * re-crops from these, never from an already-cropped image, so fixing a bad auto-crop never
     * loses detail by cropping a crop. Also empty for a "From Topic" or manually-typed
     * question. [isLikelyDuplicate] is true when [QuestionSimilarity] thinks this question is
     * close enough to one already in the curriculum's bank (or an earlier draft in this same
     * batch) to be worth a second look — never used to drop a draft automatically, only to flag
     * it for the parent. */
    data class DraftQuestion(
        val prompt: String,
        val options: List<String> = emptyList(),
        val correctIndex: Int = -1,
        val imagePaths: List<String> = emptyList(),
        val sourcePageImages: List<String> = emptyList(),
        val questionType: String = QuestionType.MCQ,
        val correctAnswerText: String? = null,
        val isLikelyDuplicate: Boolean = false
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

    /** Writes a whole new set of [count] (1–10) questions about [topic] from scratch — e.g.
     * "Class 5 Science — Photosynthesis" — for the parent to review and selectively save in one
     * pass instead of drafting one question at a time. [count]-1 are multiple-choice and
     * exactly 1 is fill-in-the-blank (all fill-in when [count] is 1), so every batch contributes
     * at least one question a child can't pass by lucky guessing. [existingPrompts] — the
     * curriculum's already-saved question texts — is used both to steer the model away from
     * repeating them and to flag any draft that ends up looking like one anyway (or a repeat of
     * an earlier draft in this same batch); see [DraftQuestion.isLikelyDuplicate]. */
    suspend fun generateBatchFromTopic(
        context: Context,
        topic: String,
        count: Int,
        existingPrompts: List<String> = emptyList()
    ): BatchResult =
        withContext(Dispatchers.IO) {
            if (topic.isBlank()) {
                return@withContext BatchResult.Unavailable("Type a topic or chapter first.")
            }
            val llm = loadModelOrNull(context)
                ?: return@withContext BatchResult.Unavailable(MODEL_MISSING_MESSAGE)
            try {
                val askedFor = count.coerceIn(1, 10)
                val response = llm.generateResponse(buildBatchPrompt(topic, askedFor, existingPrompts))
                Log.d(TAG, "generateBatchFromTopic raw response (${response.length} chars): $response")
                // The model sometimes writes more (or fewer) questions than asked for — cap
                // each kind separately so the guaranteed fill-in question never gets crowded out
                // by an MCQ overrun before take() gets to it.
                val mcqTarget = (askedFor - 1).coerceAtLeast(0)
                val mcq = (parseBatchResponse(response) ?: emptyList()).take(mcqTarget)
                val fillIn = parseFillInBlocks(response).take(1)
                val parsed = flagDuplicates(mcq + fillIn, existingPrompts)
                if (parsed.isEmpty()) {
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
     * contain questions. [count]-1 are multiple-choice and exactly 1 is fill-in-the-blank (all
     * fill-in when [count] is 1), same guaranteed-no-luck mix as [generateBatchFromTopic].
     * [count] defaults to [defaultScanQuestionCount], which scales up a little with how many
     * pages were scanned rather than asking for the same fixed count regardless of how much
     * material was actually covered. [existingPrompts] works exactly as in
     * [generateBatchFromTopic] — steers the model away from repeating the curriculum's existing
     * questions and flags any draft that looks like a repeat anyway. Returns
     * [BatchResult.Unavailable] (rather than throwing) when the model isn't installed, so the
     * caller can fall back to the original manual-entry flow instead of breaking scanning
     * entirely. [imagePaths] (the persisted photo(s) for this scan session, if any — see
     * PhotoScanCapture) becomes every draft's [DraftQuestion.sourcePageImages] unconditionally,
     * and is also the fallback [DraftQuestion.imagePaths] for a question when nothing better can
     * be found; [blocks] (that session's OCR'd text blocks with position, also from
     * PhotoScanCapture) lets [matchAndCropRegion] try to narrow that down to just the part of
     * the page a given question is actually about — see its doc for how. Either way, the
     * parent/child ends up seeing a picture of the actual scanned material alongside what might
     * otherwise be a vaguely-worded question, and the parent can always override the auto-match
     * by hand (ManageQuestionsScreen's "Adjust picture") since [DraftQuestion.sourcePageImages]
     * is always the original, uncropped photo(s) regardless of what got auto-selected. */
    suspend fun generateComprehensionQuestionsFromScan(
        context: Context,
        scannedPages: List<String>,
        imagePaths: List<String> = emptyList(),
        blocks: List<ScannedTextBlock> = emptyList(),
        count: Int = defaultScanQuestionCount(scannedPages.size),
        existingPrompts: List<String> = emptyList()
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
                val response = llm.generateResponse(buildComprehensionPrompt(pages, askedFor, existingPrompts))
                Log.d(TAG, "generateComprehensionQuestionsFromScan raw response " +
                    "(${response.length} chars, ${pages.size} page(s)): $response")
                // The model sometimes writes more (or fewer) questions than asked for — cap
                // each kind separately (see generateBatchFromTopic) so the guaranteed fill-in
                // question survives. For each question, try to find the region of the page(s)
                // its wording actually matches (e.g. the specific pictograph it's asking about
                // on a page with several) and crop just that region — a scanned page can cover
                // more than one question's worth of content, and showing every question the
                // entire page is otherwise both less useful and more cluttered than showing just
                // the relevant part. Falls back to every page photo, unmatched (the previous
                // behavior), when no confident match is found for a question — and every draft
                // keeps the original page(s) as sourcePageImages regardless, so a bad auto-crop
                // is never unrecoverable.
                val mcqTarget = (askedFor - 1).coerceAtLeast(0)
                val mcq = (parseBatchResponse(response) ?: emptyList()).take(mcqTarget)
                val fillIn = parseFillInBlocks(response).take(1)
                val combined = flagDuplicates(mcq + fillIn, existingPrompts)
                val parsed = if (combined.isEmpty()) null else combined.map { draft ->
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

    /** Marks each draft's [DraftQuestion.isLikelyDuplicate] against [existingPrompts] — the
     * curriculum's already-saved questions — AND against every draft earlier in [drafts] itself,
     * so a batch where the model accidentally wrote two versions of the same question flags the
     * second one too. Order matters: drafts are checked against everything seen so far (existing
     * bank first, then earlier drafts in this same call), never against drafts that come after
     * them, so which one of a pair gets flagged is deterministic. Never removes anything — see
     * [QuestionSimilarity]'s doc for why this only flags, never drops. */
    private fun flagDuplicates(drafts: List<DraftQuestion>, existingPrompts: List<String>): List<DraftQuestion> {
        if (drafts.isEmpty()) return drafts
        val seen = existingPrompts.toMutableList()
        return drafts.map { draft ->
            val isDuplicate = QuestionSimilarity.isLikelyDuplicateOfAny(draft.prompt, seen)
            seen += draft.prompt
            draft.copy(isLikelyDuplicate = isDuplicate)
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
     * question's prompt + options/answer, keeps the best-scoring one, and requires at least
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
        val answerText = draft.options.joinToString(" ").ifBlank { draft.correctAnswerText ?: "" }
        val questionTokens = significantTokens(draft.prompt + " " + answerText)
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

    /** A "don't repeat these" note listing the curriculum's most recently saved question
     * prompts (capped at [MAX_EXISTING_PROMPTS_LISTED]), appended into both batch prompts so
     * the model has a chance to avoid an obvious repeat before generation even happens — the
     * post-parse [flagDuplicates] check is the backstop for whatever this doesn't catch. Empty
     * string (nothing appended) when there's nothing to avoid yet, e.g. a brand new curriculum. */
    private fun buildAvoidDuplicatesNote(existingPrompts: List<String>): String {
        if (existingPrompts.isEmpty()) return ""
        val listed = existingPrompts.takeLast(MAX_EXISTING_PROMPTS_LISTED).joinToString("\n") { "- ${it.trim()}" }
        return "\n\nThis curriculum already has these questions saved — do NOT repeat or " +
            "closely reword any of them; write genuinely different questions:\n$listed\n"
    }

    /** Asks for exactly 1 fill-in-the-blank block FIRST, then [count]-1 multiple-choice blocks
     * (all fill-in when [count] is 1) — see [generateBatchFromTopic]'s doc for why the mix
     * matters. The fill-in question is asked for first deliberately: this on-device model has a
     * limited reply budget ([MAX_TOKENS], shared with the whole prompt), and on a long batch it
     * sometimes runs out mid-reply — confirmed via the raw-response logging above, where the
     * fill-in block (previously asked for last) got cut off after "FILL_IN: <question>" with no
     * "ANSWER:" line at all. Since the fill-in question is the one guaranteed slot every batch
     * relies on (see QuizRepository.getQuizQuestions), it needs to be the block most likely to
     * finish, not the one most likely to get truncated — losing a multiple-choice question or
     * two off the end of a long reply is a far smaller loss than losing the only fill-in
     * question and forcing the whole batch to fail. Two distinct, clearly-labeled block formats
     * (rather than one format with a type flag) keeps parsing reliable on a small on-device
     * model that doesn't always follow formatting instructions to the letter — see
     * [parseBatchResponse]/[parseFillInBlocks]'s docs. [existingPrompts] adds the "don't repeat
     * these" note from [buildAvoidDuplicatesNote]. */
    private fun buildBatchPrompt(topic: String, count: Int, existingPrompts: List<String> = emptyList()): String {
        val mcqCount = (count - 1).coerceAtLeast(0)
        val sb = StringBuilder()
        sb.append("You are helping a parent build a quiz for their child on this topic: \"")
            .append(topic.trim()).append("\".")
        sb.append(buildAvoidDuplicatesNote(existingPrompts))
        sb.append("\n\n")
        sb.append(
            "First, write exactly 1 fill-in-the-blank question about it — a question with no " +
                "answer choices, where the child has to type the answer themselves rather than " +
                "pick from options, so there is no chance of guessing correctly by luck. Keep " +
                "the correct answer short (a single word or a short phrase). Use exactly this " +
                "format:\n\nFILL_IN: <question text>\nANSWER: <short correct answer>\n\n"
        )
        if (mcqCount > 0) {
            sb.append("Then write exactly ").append(mcqCount)
                .append(
                    " different multiple-choice questions about it, appropriate for a " +
                        "school-age child. Ask concrete, specific questions with a single " +
                        "clear factual answer — avoid vague or abstract questions about the " +
                        "topic in general (e.g. \"Why is this topic important?\" or \"What is " +
                        "this topic about?\"). For each question, give exactly four short " +
                        "answer options labeled A to D with exactly one correct answer. Use " +
                        "exactly this format, one block per question, separated by a line " +
                        "containing only ---:\n\n" +
                        "Q: <question text>\nA) <option>\nB) <option>\nC) <option>\n" +
                        "D) <option>\nCORRECT: <letter>\n---\n\n"
                )
        }
        sb.append("Reply with ONLY the requested question block(s) above, nothing else.")
        return sb.toString()
    }

    /** Builds the comprehension-question prompt from one or more scanned pages. A single page
     * is inlined as-is (matches the original single-photo prompt exactly). Multiple pages are
     * each labeled ("Page 1:", "Page 2:", ...) and concatenated, with [MAX_SCAN_CHARS_TOTAL]
     * split evenly across however many pages there are so a multi-page scan can't let any one
     * page (or the total) blow the model's token budget. Explicitly steers away from abstract
     * "meta" questions about the material itself (its purpose, layout, or how it's presented) —
     * on-device generation kept defaulting to those ("What is the goal of the activity?", "How
     * is the information presented?") instead of asking about the actual facts/numbers/names in
     * the content, which is both harder for a child to answer meaningfully and rarely has a
     * single unambiguous correct option among the four choices. Asks for the fill-in-the-blank
     * block FIRST, then [count]-1 MCQ blocks — same reordering, and for the same
     * truncation-avoidance reason, as [buildBatchPrompt] (see its doc) — and the same
     * [existingPrompts] "don't repeat these" note via [buildAvoidDuplicatesNote]. */
    private fun buildComprehensionPrompt(
        scannedPages: List<String>,
        count: Int,
        existingPrompts: List<String> = emptyList()
    ): String {
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
        val mcqCount = (count - 1).coerceAtLeast(0)

        val sb = StringBuilder()
        sb.append(
            "Below is text recognized from $sourceDescription a child is studying — this " +
                "could be a textbook page, class notes, or a worksheet. Ignore anything that " +
                "isn't part of the actual lesson content (headings, page numbers, printed " +
                "instructions like \"Answer the following\"). Based on the concepts, facts, " +
                "and ideas covered in $materialReference:"
        )
        sb.append(buildAvoidDuplicatesNote(existingPrompts))
        sb.append("\n\n")
        sb.append(
            "First, write exactly 1 NEW fill-in-the-blank question about the material — a " +
                "question with no answer choices, about a specific fact/number/name/quantity " +
                "actually in the text, where the child has to type the answer themselves " +
                "rather than pick from options, so there is no chance of guessing correctly by " +
                "luck. Keep the correct answer short (a single word or a short phrase). Use " +
                "exactly this format:\n\nFILL_IN: <question text>\nANSWER: <short correct " +
                "answer>\n\n"
        )
        if (mcqCount > 0) {
            sb.append(
                "Then write exactly $mcqCount NEW multiple-choice questions that check " +
                    "whether the child understood the material. Do NOT simply copy, reformat, " +
                    "or lightly reword any questions that may already be printed on the " +
                    "page(s) — write original questions of your own, at a similar difficulty, " +
                    "that a student who truly understood the material would be able to answer. " +
                    "Ask concrete questions about specific facts, numbers, names, quantities, " +
                    "steps, or relationships that actually appear in the material — never ask " +
                    "abstract or \"meta\" questions about the text or activity itself, such as " +
                    "\"What is the goal of the activity?\", \"How is the information " +
                    "presented?\", or \"What is this passage about?\". A well-written question " +
                    "should only be answerable by someone who read the specific content, not " +
                    "guessable from the question's own wording. For each question, give " +
                    "exactly four short answer options labeled A to D with exactly one correct " +
                    "answer. Use exactly this format, one block per question, separated by a " +
                    "line containing only ---:\n\n" +
                    "Q: <question text>\nA) <option>\nB) <option>\nC) <option>\nD) <option>\n" +
                    "CORRECT: <letter>\n---\n\n"
            )
        }
        sb.append(
            "Reply with ONLY the requested question block(s) above, nothing else.\n\n" +
                "Scanned text:\n$pagesBlock"
        )
        return sb.toString()
    }

    private fun parseSingleResponse(text: String): Result.Success? {
        // [ABCD] is followed by either ")" or "." in practice — the prompt asks for "A)" but
        // the on-device model sometimes writes "A." instead (confirmed via the raw-response
        // logging above), and the old ")"-only pattern silently rejected an otherwise
        // perfectly good reply whenever that happened.
        val optionRegex = Regex("""^[ABCD][).]\s*(.+)$""", RegexOption.MULTILINE)
        val options = optionRegex.findAll(text).map { cleanField(it.groupValues[1]) }.toList()
        val correctLetter = Regex("""CORRECT:\s*([ABCD])""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.uppercase()
        if (options.size != 4 || correctLetter == null) return null
        val correctIndex = "ABCD".indexOf(correctLetter)
        if (correctIndex !in 0..3) return null
        return Result.Success(options, correctIndex)
    }

    /** Parses one or more "Q: ... A) ... B) ... C) ... D) ... CORRECT: X" blocks out of a
     * response, in any order and regardless of what separates them. Deliberately tolerant of
     * how the fields are laid out — one per line (the format asked for in the prompt) OR all
     * run together on a single line separated by spaces/commas (what the on-device model
     * actually tends to do — confirmed via the raw-response logging above: it reliably produces
     * the right content, just not always the requested line breaks), AND of "A." in place of
     * "A)" (also confirmed via the raw-response logging — the model doesn't always use the
     * exact punctuation the prompt asks for either). The earlier version of this regex required
     * a literal newline between fields (and only ")", not ".", after each letter) and was
     * silently rejecting otherwise-valid blocks — that was the real bug behind generation "not
     * working," not the model. Also not dependent on the "---" separator actually showing up
     * verbatim, since small on-device models don't always follow formatting instructions to the
     * letter. A block whose CORRECT isn't exactly one of A/B/C/D (the model occasionally echoes
     * the answer's value instead of its letter) simply doesn't match and is skipped, rather than
     * failing the whole batch. Deliberately NOT using DOT_MATCHES_ALL: each captured field is
     * expected to be plain text with no embedded newline, and keeping "." confined to a single
     * line is what stops a block with a malformed CORRECT value (see above) from backtracking
     * across the "---" separator and bleeding into the next block — the field separators still
     * cross line breaks fine since \s already matches newlines. */
    private fun parseBatchResponse(text: String): List<DraftQuestion>? {
        val blockRegex = Regex(
            """Q:\s*(.+?)\s*,?\s*A[).]\s*(.+?)\s*,?\s*B[).]\s*(.+?)\s*,?\s*C[).]\s*(.+?)\s*,?\s*D[).]\s*(.+?)\s*,?\s*CORRECT:\s*([ABCD])\b""",
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
                DraftQuestion(
                    prompt = cleanField(g[1]),
                    options = listOf(cleanField(g[2]), cleanField(g[3]), cleanField(g[4]), cleanField(g[5])),
                    correctIndex = correctIndex,
                    questionType = QuestionType.MCQ
                )
            }
        }
    }

    /** Parses one or more "FILL_IN: ... ANSWER: ..." blocks out of a response — the
     * fill-in-the-blank counterpart to [parseBatchResponse]. Same tolerant approach: fields
     * aren't required to be on their own line, and the block is bounded by a lookahead for the
     * next block's start ("---", "FILL_IN:", "Q:") or end of text rather than a literal
     * separator, since the small on-device model doesn't always emit "---" between blocks.
     * DOT_MATCHES_ALL is needed here (unlike parseBatchResponse) because the non-greedy "."
     * between FILL_IN: and ANSWER: has to be able to cross the line break the prompt's own
     * format asks for; the lookahead terminators are what keep it from over-matching into the
     * next block instead. A block with a blank question or answer is skipped rather than
     * failing the whole batch. This block is now asked for FIRST in both batch prompts (see
     * [buildBatchPrompt]/[buildComprehensionPrompt]) specifically so it's less likely to be the
     * one that's missing its ANSWER: line because the model ran out of reply budget partway
     * through — if a reply does get cut off, this still only matches a genuinely complete
     * "FILL_IN: ... ANSWER: ..." pair, so a truncated block (no ANSWER: at all) correctly
     * produces nothing rather than a bogus fill-in question with a garbage answer. */
    private fun parseFillInBlocks(text: String): List<DraftQuestion> {
        val blockRegex = Regex(
            """FILL_IN:\s*(.+?)\s*,?\s*ANSWER:\s*(.+?)\s*(?=---|FILL_IN:|Q:\s|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return blockRegex.findAll(text).mapNotNull { m ->
            val prompt = cleanField(m.groupValues[1])
            val answer = cleanField(m.groupValues[2])
            if (prompt.isBlank() || answer.isBlank()) {
                null
            } else {
                DraftQuestion(
                    prompt = prompt,
                    questionType = QuestionType.FILL_IN,
                    correctAnswerText = answer
                )
            }
        }.toList()
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
