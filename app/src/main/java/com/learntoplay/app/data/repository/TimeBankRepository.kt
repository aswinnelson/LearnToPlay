package com.learntoplay.app.data.repository

import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity
import com.learntoplay.app.util.AllowedWindowChecker
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
     * never go out of sync. See ScoreTimeCalculator (also used by QuizRepository) for the
     * actual band-matching logic and its unit tests. */
    suspend fun ruleForScore(scorePercent: Int): ScoreTimeRuleEntity? =
        ScoreTimeCalculator.ruleForScore(scorePercent, getRules())

    /** Atomic, floored at zero — see AdminSettingsDao.spendSeconds for why this is done in SQL. */
    suspend fun spendSeconds(seconds: Long) {
        db.adminSettingsDao().spendSeconds(seconds)
    }

    /** Atomic increment — see AdminSettingsDao.addSeconds. */
    suspend fun addSeconds(seconds: Long) {
        if (seconds > 0) db.adminSettingsDao().addSeconds(seconds)
    }

    suspend fun getBalanceSeconds(): Long =
        db.adminSettingsDao().getOnce()?.timeBankSecondsRemaining ?: 0L

    /** Parent override from the Admin Dashboard's Time Bank card. Writes straight to the
     * same row the tracker service ticks down and the child's Home screen observes, so an
     * edit mid-session takes effect on the very next tick without racing it. */
    suspend fun setBalanceSeconds(seconds: Long) {
        db.adminSettingsDao().setTimeBankSeconds(ScoreTimeCalculator.clampNonNegative(seconds))
    }

    /** True if a parent has turned on the "Allowed Hours" schedule AND the current time falls
     * outside it — the one curfew check both TimeBankTrackerService (stop ticking) and
     * AppLockAccessibilityService (show the lock) consult, so a banked balance can never be
     * spent, and the gate can never be bypassed, outside the window. False (never outside)
     * when no schedule is configured, which is the case for every existing install until a
     * parent opts in. */
    suspend fun isOutsideAllowedWindow(): Boolean {
        val settings = db.adminSettingsDao().getOnce() ?: return false
        if (!settings.allowedWindowEnabled) return false
        return !AllowedWindowChecker.isWithinWindow(
            AllowedWindowChecker.currentMinuteOfDay(),
            settings.allowedWindowStartMinute,
            settings.allowedWindowEndMinute
        )
    }
}
