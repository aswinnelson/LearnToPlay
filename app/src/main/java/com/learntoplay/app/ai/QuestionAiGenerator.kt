package com.learntoplay.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drafts four multiple-choice options (and a best-guess correct answer) for a question's text,
 * using a small language model running entirely on-device (MediaPipe LLM Inference API, e.g.
 * Gemma 3 1B) — no network call, no cloud service, consistent with the app's local-first design.
 *
 * The model file (500MB+) is never bundled in the APK or downloaded automatically; it's pushed
 * once via `adb push` to a fixed path and loaded from there. If it isn't present yet, generation
 * fails with a clear, actionable message rather than crashing.
 *
 * This is a drafting aid, not an auto-fill: the caller always shows the result in the existing
 * question-edit form for the parent to review, correct, and explicitly save — the AI never
 * writes directly to the question bank.
 */
object QuestionAiGenerator {

    /** Fixed on-device path the model is expected at — matches the `adb push` destination in
     * the setup notes given to the parent when this feature is unavailable. */
    private const val MODEL_PATH = "/data/local/tmp/llm/gemma3-1b-it.task"

    @Volatile
    private var inference: LlmInference? = null

    sealed interface Result {
        data class Success(val options: List<String>, val correctIndex: Int) : Result
        data class Unavailable(val reason: String) : Result
    }

    suspend fun generateOptions(context: Context, questionText: String): Result =
        withContext(Dispatchers.IO) {
            if (questionText.isBlank()) {
                return@withContext Result.Unavailable("Type or scan a question first.")
            }
            if (!File(MODEL_PATH).exists()) {
                return@withContext Result.Unavailable(
                    "AI model not found on this device. Push the .task model file to " +
                        "$MODEL_PATH (see the Stage C setup notes), then try again. " +
                        "This only works on a real phone, not the emulator."
                )
            }
            try {
                val llm = inference ?: synchronized(this) {
                    inference ?: LlmInference.createFromOptions(
                        context.applicationContext,
                        LlmInferenceOptions.builder()
                            .setModelPath(MODEL_PATH)
                            .setMaxTopK(64)
                            .build()
                    ).also { inference = it }
                }

                val response = llm.generateResponse(buildPrompt(questionText))
                parseResponse(response)
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

    private fun buildPrompt(questionText: String): String = """
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

    private fun parseResponse(text: String): Result.Success? {
        val optionRegex = Regex("""^[ABCD]\)\s*(.+)$""", RegexOption.MULTILINE)
        val options = optionRegex.findAll(text).map { it.groupValues[1].trim() }.toList()
        val correctLetter = Regex("""CORRECT:\s*([ABCD])""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.uppercase()
        if (options.size != 4 || correctLetter == null) return null
        val correctIndex = "ABCD".indexOf(correctLetter)
        if (correctIndex !in 0..3) return null
        return Result.Success(options, correctIndex)
    }
}
