package com.learntoplay.app.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
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
 *  - [generateComprehensionQuestionsFromScan]: given raw OCR text from a photo of whatever the
 *    child is studying (a textbook page, notes, a worksheet), write a fresh batch of
 *    comprehension-check questions about that material — deliberately NOT the same questions
 *    printed on the page (if any), so a quiz built from it actually tests whether the child
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

    /** Default number of comprehension questions to draft from one scanned page — enough to
     * meaningfully check understanding without asking the model (and the parent reviewing the
     * result) to churn through more than a photo's worth of content usually supports. */
    private const val DEFAULT_SCAN_QUESTION_COUNT = 5

    /** Matches the ekv2048 context window the pushed model file is compiled for (see the
     * `adb push` setup notes) — this is the model's own ceiling, not an arbitrary choice. The
     * MediaPipe LLM Inference session otherwise defaults to a much smaller token budget (shared
     * between the whole prompt AND the model's reply), which was silently truncating longer
     * generations — most visibly "Scan Photo," whose prompt includes a full page of OCR'd text
     * plus instructions plus several whole question blocks to write back out. */
    private const val MAX_TOKENS = 2048

    /** Safety cap on how much OCR'd page text goes into the comprehension prompt — a generous
     * amount for a single photographed page, but bounded so an unusually text-dense scan can't
     * eat the entire [MAX_TOKENS] budget and leave no room for the model to write its questions
     * back out. */
    private const val MAX_SCAN_CHARS = 2500

    @Volatile
    private var inference: LlmInference? = null

    /** One AI-drafted question with its four options and which one is correct — always shown
     * to the parent for review before anything reaches the question bank. */
    data class DraftQuestion(val prompt: String, val options: List<String>, val correctIndex: Int)

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

    /** Given raw OCR text from a photo of whatever the child is studying, writes a fresh batch
     * of [count] (1–10) comprehension-check questions about the concepts in that text — not a
     * copy, reformat, or light reword of anything already printed on the page. The point is to
     * check whether the child actually understood the material, so the AI is explicitly told to
     * write its own original questions rather than lift the page's. Works whether the photo is
     * a worksheet with existing questions, a plain textbook page, or class notes — it doesn't
     * need the page to already contain questions. Returns [BatchResult.Unavailable] (rather
     * than throwing) when the model isn't installed, so the caller can fall back to the
     * original single-question-with-raw-text flow instead of breaking scanning entirely. */
    suspend fun generateComprehensionQuestionsFromScan(
        context: Context,
        scannedText: String,
        count: Int = DEFAULT_SCAN_QUESTION_COUNT
    ): BatchResult =
        withContext(Dispatchers.IO) {
            if (scannedText.isBlank()) {
                return@withContext BatchResult.Unavailable("Nothing was scanned.")
            }
            val llm = loadModelOrNull(context)
                ?: return@withContext BatchResult.Unavailable(MODEL_MISSING_MESSAGE)
            try {
                val askedFor = count.coerceIn(1, 10)
                val response = llm.generateResponse(buildComprehensionPrompt(scannedText, askedFor))
                Log.d(TAG, "generateComprehensionQuestionsFromScan raw response " +
                    "(${response.length} chars): $response")
                // The model sometimes writes more (or fewer) questions than asked for — cap to
                // what the parent actually requested rather than dumping every extra one on
                // them in the review screen.
                val parsed = parseBatchResponse(response)?.take(askedFor)
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
        school-age child. For each question, give exactly four short answer options labeled A
        to D with exactly one correct answer. Reply with ONLY this format, one block per
        question, nothing else, separated by a line containing only ---:

        Q: <question text>
        A) <option>
        B) <option>
        C) <option>
        D) <option>
        CORRECT: <letter>
        ---
    """.trimIndent()

    private fun buildComprehensionPrompt(scannedText: String, count: Int): String {
        val trimmedScan = scannedText.trim().let {
            if (it.length > MAX_SCAN_CHARS) it.take(MAX_SCAN_CHARS) + "…" else it
        }
        return """
        Below is text recognized from a photo of a page a child is studying — this could be a
        textbook page, class notes, or a worksheet. Ignore anything that isn't part of the
        actual lesson content (headings, page numbers, printed instructions like "Answer the
        following"). Based on the concepts, facts, and ideas covered in this text, write exactly
        $count NEW multiple-choice questions that check whether the child understood the
        material. Do NOT simply copy, reformat, or lightly reword any questions that may already
        be printed on the page — write original questions of your own, at a similar difficulty,
        that a student who truly understood the material would be able to answer. For each
        question, give exactly four short answer options labeled A to D with exactly one
        correct answer. Reply with ONLY this format, one block per question, nothing else,
        separated by a line containing only ---:

        Q: <question text>
        A) <option>
        B) <option>
        C) <option>
        D) <option>
        CORRECT: <letter>
        ---

        Scanned text:
        $trimmedScan
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
        return Result.Success(options, correctIndex)
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
     * the field separators still cross line breaks fine since \s already matches newlines. */
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
                DraftQuestion(
                    cleanField(g[1]),
                    listOf(cleanField(g[2]), cleanField(g[3]), cleanField(g[4]), cleanField(g[5])),
                    correctIndex
                )
            }
        }
    }

    /** The on-device model occasionally writes a literal two-character "\n" (backslash then n)
     * inside a field instead of an actual line break — confirmed by seeing it verbatim in
     * captured field text ("1 and 2\n"). [String.trim] only strips real whitespace, not that
     * literal escape sequence, so it was passing straight through into the review screen. This
     * strips it (and its escaped-tab cousin, just in case) wherever it shows up in a field, not
     * just at the end, since the model doesn't always put it only at the edges. */
    private fun cleanField(raw: String): String =
        raw.replace("\\n", " ").replace("\\t", " ").replace(Regex("""\s+"""), " ").trim()
}
