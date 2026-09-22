package com.learntoplay.app.data.repository

import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain-JVM unit tests for [ScoreTimeCalculator] — the score -> percent -> minutes math that
 * QuizRepository.submitQuizAndAwardTime and TimeBankRepository both rely on, and the part
 * flagged in the MVP status review as having zero test coverage despite being the whole reason
 * a parent would trust this app to award/deduct a child's screen time correctly.
 *
 * Deliberately has no Android/Room/Robolectric dependency at all — [ScoreTimeCalculator] is a
 * pure function object, so these tests run on any JDK in well under a second, with nothing that
 * can flake from a simulated-environment mismatch.
 */
class ScoreTimeCalculatorTest {

    /** Mirrors the example reward curve in ScoreTimeRuleEntity's own doc comment:
     * 50-69% -> 15 min, 70-89% -> 30 min, 90-100% -> 45 min. Anything below 50% earns nothing. */
    private val rules = listOf(
        ScoreTimeRuleEntity(minScorePercent = 50, maxScorePercent = 69, minutesAwarded = 15),
        ScoreTimeRuleEntity(minScorePercent = 70, maxScorePercent = 89, minutesAwarded = 30),
        ScoreTimeRuleEntity(minScorePercent = 90, maxScorePercent = 100, minutesAwarded = 45)
    )

    @Test
    fun `scorePercent computes a whole-number percent`() {
        assertEquals(80, ScoreTimeCalculator.scorePercent(correctCount = 4, totalCount = 5))
    }

    @Test
    fun `scorePercent rounds down rather than up`() {
        // 1 of 3 is 33.33...% — must not round up to 34.
        assertEquals(33, ScoreTimeCalculator.scorePercent(correctCount = 1, totalCount = 3))
    }

    @Test
    fun `scorePercent is zero for a quiz with no questions instead of dividing by zero`() {
        assertEquals(0, ScoreTimeCalculator.scorePercent(correctCount = 0, totalCount = 0))
    }

    @Test
    fun `ruleForScore returns the band whose range contains the score`() {
        val rule = ScoreTimeCalculator.ruleForScore(75, rules)

        assertNotNull(rule)
        assertEquals(70, rule!!.minScorePercent)
        assertEquals(30, rule.minutesAwarded)
    }

    @Test
    fun `ruleForScore treats both ends of a band as inclusive`() {
        assertEquals(15, ScoreTimeCalculator.ruleForScore(50, rules)?.minutesAwarded)
        assertEquals(15, ScoreTimeCalculator.ruleForScore(69, rules)?.minutesAwarded)
        assertEquals(30, ScoreTimeCalculator.ruleForScore(70, rules)?.minutesAwarded)
        assertEquals(45, ScoreTimeCalculator.ruleForScore(100, rules)?.minutesAwarded)
    }

    @Test
    fun `ruleForScore returns null when no configured band covers the score`() {
        assertNull(ScoreTimeCalculator.ruleForScore(10, rules))
        assertNull(ScoreTimeCalculator.ruleForScore(49, rules))
    }

    @Test
    fun `minutesForScore returns zero when no band matches instead of throwing`() {
        assertEquals(0, ScoreTimeCalculator.minutesForScore(20, rules))
    }

    @Test
    fun `minutesForScore returns zero for an empty rule set`() {
        assertEquals(0, ScoreTimeCalculator.minutesForScore(80, emptyList()))
    }

    @Test
    fun `clampNonNegative leaves a non-negative value unchanged`() {
        assertEquals(120L, ScoreTimeCalculator.clampNonNegative(120L))
        assertEquals(0L, ScoreTimeCalculator.clampNonNegative(0L))
    }

    @Test
    fun `clampNonNegative floors a negative value at zero`() {
        assertEquals(0L, ScoreTimeCalculator.clampNonNegative(-50L))
    }
}
