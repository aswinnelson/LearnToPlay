package com.learntoplay.app.data.repository

import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity
import kotlinx.coroutines.flow.first

/**
 * Owns the score -> minutes mapping and the live time-bank balance.
 * This is the core "privilege" mechanic the parent configures in Admin Mode.
 */
class TimeBankRepository(private val db: AppDatabase) {

    fun observeBalanceSeconds() = db.adminSettingsDao().observe()

    suspend fun getRules(): List<ScoreTimeRuleEntity> = db.scoreTimeRuleDao().observeAll().first()

    suspend fun setRules(rules: List<ScoreTimeRuleEntity>) {
        // Admin-only screen validates non-overlapping ranges before calling this.
        db.scoreTimeRuleDao().clearAll()
        db.scoreTimeRuleDao().insertAll(rules)
    }

    /** Looks up the matching rule for a score. Used by ScoreTimeMappingScreen for editing —
     * actually crediting the time bank now happens atomically inside
     * QuizRepository.submitQuizAndAwardTime(), not here, so a quiz result and its reward can
     * never go out of sync. */
    suspend fun ruleForScore(scorePercent: Int): ScoreTimeRuleEntity? =
        getRules().firstOrNull { scorePercent in it.minScorePercent..it.maxScorePercent }

    suspend fun spendSeconds(seconds: Long) {
        val current = db.adminSettingsDao().getOnce()?.timeBankSecondsRemaining ?: 0L
        db.adminSettingsDao().setTimeBankSeconds(maxOf(0L, current - seconds))
    }

    suspend fun getBalanceSeconds(): Long =
        db.adminSettingsDao().getOnce()?.timeBankSecondsRemaining ?: 0L

    /** Parent override from the Admin Dashboard's Time Bank card. Writes straight to the
     * same row the tracker service ticks down and the child's Home screen observes, so an
     * edit mid-session takes effect on the very next tick without racing it. */
    suspend fun setBalanceSeconds(seconds: Long) {
        db.adminSettingsDao().setTimeBankSeconds(maxOf(0L, seconds))
    }
}
