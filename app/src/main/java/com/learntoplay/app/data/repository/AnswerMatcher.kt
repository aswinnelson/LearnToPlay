package com.learntoplay.app.data.repository

/** Lenient matching for fill-in-the-blank answers: a child still has to know/type the right
 * word(s), but formatting slip-ups (capitalization, extra spaces, a stray period or comma)
 * don't count against them. Pure and dependency-free so it's plain-JUnit testable, same as
 * ScoreTimeCalculator. */
object AnswerMatcher {
    fun isCorrect(typed: String, correctAnswer: String): Boolean =
        normalize(typed) == normalize(correctAnswer) && normalize(correctAnswer).isNotEmpty()

    private fun normalize(text: String): String =
        text
            .trim()
            .lowercase()
            .replace(Regex("[\\p{Punct}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
