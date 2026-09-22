package com.learntoplay.app.data.repository

import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity

/**
 * Pure, dependency-free score/time-bank math shared by [QuizRepository] and [TimeBankRepository]
 * — no Context, no database, no coroutines. Pulled out specifically so this arithmetic (which
 * decides how much play time a child's quiz result is worth, and makes sure a time-bank balance
 * can never go negative) has direct, fast, environment-proof unit test coverage — see
 * ScoreTimeCalculatorTest — rather than only being exercised indirectly through a running app.
 */
object ScoreTimeCalculator {

    /** [correctCount] out of [totalCount] as a whole-number percent, rounded down. A quiz with
     * no questions ([totalCount] == 0) is defined as 0% rather than dividing by zero. */
    fun scorePercent(correctCount: Int, totalCount: Int): Int =
        if (totalCount == 0) 0 else (correctCount * 100) / totalCount

    /** The admin-configured reward band whose range contains [scorePercent], or null if no
     * band covers it. minScorePercent/maxScorePercent are both inclusive. */
    fun ruleForScore(scorePercent: Int, rules: List<ScoreTimeRuleEntity>): ScoreTimeRuleEntity? =
        rules.firstOrNull { scorePercent in it.minScorePercent..it.maxScorePercent }

    /** The minutes earned for [scorePercent] under [rules] — 0 if no band matches. */
    fun minutesForScore(scorePercent: Int, rules: List<ScoreTimeRuleEntity>): Int =
        ruleForScore(scorePercent, rules)?.minutesAwarded ?: 0

    /** Never lets a time-bank balance go negative, whether from spending more than remains or
     * from a parent's manual override in the Admin Dashboard. */
    fun clampNonNegative(seconds: Long): Long = maxOf(0L, seconds)
}
