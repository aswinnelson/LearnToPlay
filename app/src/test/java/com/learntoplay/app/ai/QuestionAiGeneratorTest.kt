package com.learntoplay.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [QuestionAiGenerator.shuffledWithCorrectIndex] — the fix for AI-generated questions
 * reliably putting the correct answer in a predictable slot (see that function's doc comment).
 * Since the function is genuinely random, these tests check its invariants hold across many
 * trials rather than asserting one fixed output: every call must return a true permutation of
 * the input options, and the option sitting at the returned "correct" index must always be the
 * same option that was correct before shuffling — shuffling must never change *which answer*
 * is correct, only *where* it sits.
 */
class QuestionAiGeneratorTest {

    private val options = listOf("Paris", "London", "Berlin", "Madrid")

    @Test
    fun `shuffled result is always a permutation of the original options`() {
        repeat(200) {
            val (shuffled, _) = QuestionAiGenerator.shuffledWithCorrectIndex(options, correctIndex = 2)
            assertEquals(options.size, shuffled.size)
            assertEquals(options.toSet(), shuffled.toSet())
        }
    }

    @Test
    fun `the option at the new correct index is always the original correct option`() {
        val originalCorrectOption = options[2]
        repeat(200) {
            val (shuffled, newCorrectIndex) = QuestionAiGenerator.shuffledWithCorrectIndex(options, correctIndex = 2)
            assertEquals(originalCorrectOption, shuffled[newCorrectIndex])
        }
    }

    @Test
    fun `works correctly regardless of which index started as correct`() {
        for (startingIndex in options.indices) {
            val originalCorrectOption = options[startingIndex]
            repeat(50) {
                val (shuffled, newCorrectIndex) =
                    QuestionAiGenerator.shuffledWithCorrectIndex(options, startingIndex)
                assertEquals(originalCorrectOption, shuffled[newCorrectIndex])
            }
        }
    }

    @Test
    fun `the correct answer does not always land in the same slot`() {
        // Not a hard guarantee of any single run, but over many trials the correct answer
        // landing in every possible slot at least once confirms this is actually shuffling
        // and not, say, silently returning the input unchanged.
        val seenIndices = mutableSetOf<Int>()
        repeat(200) {
            val (_, newCorrectIndex) = QuestionAiGenerator.shuffledWithCorrectIndex(options, correctIndex = 0)
            seenIndices += newCorrectIndex
        }
        assertTrue(
            "Expected the correct answer to land in more than one slot across 200 shuffles, " +
                "saw only: $seenIndices",
            seenIndices.size > 1
        )
    }
}
