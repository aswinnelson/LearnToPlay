package com.learntoplay.app.ai

import android.content.Context
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
 *  - [splitScannedTextIntoQuestions]: given raw OCR text from a scanned page that may contain
 *    several exercise questions, split it into distinct questions and draft options for each.
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

    /** Fixed on-device path the model is expected at — matches the `adb push` destination in
     * the setup notes given to the parent when this feature is unavailable. */
    private const val MODEL_PATH = "/data/local/tmp/llm/gemma3-1b-it.task"

    private const val MODEL_MISSING_MESSAGE =
        "AI model not found on this device. Push the .task model file to $MODEL_PATH " +
            "(see the Stage C setup notes), then try again. This only works on a real phone, " +
            "not the emulator."

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
                val response = llm.generateResponse(buildBatchPrompt(topic, count.coerceIn(1, 10)))
                val parsed = parseBatchResponse(response)
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

    /** Given raw OCR text from a scanned worksheet/textbook page that may contain several
     * exercise questions mixed in with other page text, asks the AI to pull out each distinct
     * question and draft four options for it. Returns [BatchResult.Unavailable] (rather than
     * throwing) when the model isn't installed, so the caller can fall back to the original
     * single-question-with-raw-text flow instead of breaking scanning entirely. */
    suspend fun splitScannedTextIntoQuestions(context: Context, scannedText: String): BatchResult =
        withContext(Dispatchers.IO) {
            if (scannedText.isBlank()) {
                return@withContext BatchResult.Unavailable("Nothing was scanned.")
            }
            val llm = loadModelOrNull(context)
                ?: return@withContext BatchResult.Unavailable(MODEL_MISSING_MESSAGE)
            try {
                val response = llm.generateResponse(buildSplitPrompt(scannedText))
                val parsed = parseBatchResponse(response)
                if (parsed.isNullOrEmpty()) {
                    BatchResult.Unavailable(
                        "Couldn't find distinct questions in that scan. Try again with a " +
                            "clearer photo, or add the question by hand."
                    )
                } else {
                    BatchResult.Success(parsed)
                }
            } catch (e: Exception) {
                BatchResult.Unavailable("AI splitting failed (${e.message ?: "unknown error"}).")
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

    private fun buildSplitPrompt(scannedText: String): String = """
        Below is text recognized from a photo of a textbook or worksheet page. It may contain
        one or more exercise questions mixed in with other page text (headings, page numbers,
        instructions) — ignore anything that isn't a question. For each distinct question you
        find, write exactly four short multiple-choice answer options labeled A to D with
        exactly one correct answer, even if the original page didn't list options — invent
        reasonable ones in that case. Reply with ONLY this format, one block per question,
        nothing else, separated by a line containing only ---:

        Q: <question text>
        A) <option>
        B) <option>
        C) <option>
        D) <option>
        CORRECT: <letter>
        ---

        Scanned text:
        ${scannedText.trim()}
    """.trimIndent()

    private fun parseSingleResponse(text: String): Result.Success? {
        val optionRegex = Regex("""^[ABCD]\)\s*(.+)$""", RegexOption.MULTILINE)
        val options = optionRegex.findAll(text).map { it.groupValues[1].trim() }.toList()
        val correctLetter = Regex("""CORRECT:\s*([ABCD])""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.uppercase()
        if (options.size != 4 || correctLetter == null) return null
        val correctIndex = "ABCD".indexOf(correctLetter)
        if (correctIndex !in 0..3) return null
        return Result.Success(options, correctIndex)
    }

    /** Parses one or more "Q: ... A) ... B) ... C) ... D) ... CORRECT: X" blocks out of a
     * response, in any order and regardless of what separates them — deliberately not
     * dependent on the "---" separator actually showing up verbatim, since small on-device
     * models don't always follow formatting instructions to the letter. */
    private fun parseBatchResponse(text: String): List<DraftQuestion>? {
        val blockRegex = Regex(
            """Q:\s*(.+?)\s*\n\s*A\)\s*(.+?)\s*\n\s*B\)\s*(.+?)\s*\n\s*C\)\s*(.+?)\s*\n\s*D\)\s*(.+?)\s*\n\s*CORRECT:\s*([ABCD])""",
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
                DraftQuestion(g[1].trim(), listOf(g[2].trim(), g[3].trim(), g[4].trim(), g[5].trim()), correctIndex)
            }
        }
    }
}
