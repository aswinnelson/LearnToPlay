package com.learntoplay.app.data.repository

import android.content.Context
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.AdminSettingsEntity
import com.learntoplay.app.data.db.entities.CurriculumEntity
import com.learntoplay.app.data.db.entities.GatedAppEntity
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.util.PinHasher
import java.util.UUID

/** Result of a PIN check — plain Boolean used to hide whether the caller is locked out. */
sealed interface PinCheckResult {
    data object Correct : PinCheckResult
    data class Incorrect(val attemptsRemaining: Int) : PinCheckResult
    data class LockedOut(val secondsRemaining: Long) : PinCheckResult
}

class AdminRepository(private val db: AppDatabase, context: Context) {

    // SharedPreferences rather than a new Room column/migration for this — it's local,
    // per-device attempt-counter state, not app data that needs to be queried or synced.
    private val lockoutPrefs = context.applicationContext
        .getSharedPreferences("admin_pin_lockout", Context.MODE_PRIVATE)

    suspend fun isPinSet(): Boolean = db.adminSettingsDao().getOnce() != null

    suspend fun setPin(rawPin: String) {
        val (hash, salt) = PinHasher.hash(rawPin)
        val existing = db.adminSettingsDao().getOnce()
        db.adminSettingsDao().upsert(
            (existing ?: AdminSettingsEntity(pinHash = hash, pinSalt = salt))
                .copy(pinHash = hash, pinSalt = salt)
        )
        clearLockout()
    }

    /** Seconds remaining before another PIN attempt is allowed, or 0 if none is in effect. */
    fun lockoutSecondsRemaining(): Long {
        val until = lockoutPrefs.getLong(KEY_LOCKED_UNTIL, 0L)
        return ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    suspend fun verifyPin(rawPin: String): PinCheckResult {
        val remaining = lockoutSecondsRemaining()
        if (remaining > 0) return PinCheckResult.LockedOut(remaining)

        val settings = db.adminSettingsDao().getOnce()
            ?: return PinCheckResult.Incorrect(MAX_ATTEMPTS_BEFORE_LOCKOUT)

        if (PinHasher.verify(rawPin, settings.pinHash, settings.pinSalt)) {
            clearLockout()
            return PinCheckResult.Correct
        }

        val attempts = lockoutPrefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        lockoutPrefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply()

        if (attempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            // Each lockout past the first doubles the wait (capped), so patient repeated
            // guessing gets slower rather than resetting to a fresh five tries every time.
            val tier = attempts - MAX_ATTEMPTS_BEFORE_LOCKOUT
            val lockoutSeconds = (BASE_LOCKOUT_SECONDS shl tier.coerceAtMost(6))
                .coerceAtMost(MAX_LOCKOUT_SECONDS)
            lockoutPrefs.edit()
                .putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + lockoutSeconds * 1000)
                .apply()
            return PinCheckResult.LockedOut(lockoutSeconds)
        }
        return PinCheckResult.Incorrect(MAX_ATTEMPTS_BEFORE_LOCKOUT - attempts)
    }

    private fun clearLockout() {
        lockoutPrefs.edit().clear().apply()
    }

    fun observeGatedApps() = db.gatedAppDao().observeAll()

    suspend fun setAppGated(packageName: String, displayName: String, enabled: Boolean) {
        db.gatedAppDao().upsert(GatedAppEntity(packageName, displayName, enabled))
    }

    /** Full admin_settings row as a Flow — used by AdminViewModel.allowedWindow to drive the
     * Allowed Hours card. (The time-bank balance has its own narrower observeBalanceSeconds()
     * on TimeBankRepository; this one is for the settings fields that aren't the balance.) */
    fun observeAdminSettings() = db.adminSettingsDao().observe()

    /** Parent edit from the Admin Dashboard's Allowed Hours card. A direct column update (see
     * AdminSettingsDao.setAllowedWindow) rather than a read-modify-write, so it can never race
     * TimeBankTrackerService's once-a-second balance writes. */
    suspend fun setAllowedWindow(enabled: Boolean, startMinute: Int, endMinute: Int) {
        db.adminSettingsDao().setAllowedWindow(enabled, startMinute, endMinute)
    }

    suspend fun selectCurriculum(curriculumId: String) {
        db.curriculumDao().clearSelection()
        db.curriculumDao().select(curriculumId)
    }

    /** Creates a brand-new, parent-authored curriculum (ManageQuestionsScreen's questions then
     * attach to it) — distinct from the bundled preset set seeded at first launch. */
    suspend fun createCurriculum(board: String, grade: Int, subject: String, chapterTitle: String): String {
        val id = UUID.randomUUID().toString()
        db.curriculumDao().upsert(
            CurriculumEntity(
                id = id,
                board = board,
                grade = grade,
                subject = subject,
                chapterTitle = chapterTitle
            )
        )
        return id
    }

    fun observeQuestionsForCurriculum(curriculumId: String) =
        db.questionDao().observeForCurriculum(curriculumId)

    suspend fun upsertQuestion(question: QuestionEntity) {
        db.questionDao().upsert(question)
    }

    suspend fun deleteQuestion(id: Long) {
        db.questionDao().delete(id)
    }

    companion object {
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKED_UNTIL = "locked_until"
        private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
        private const val BASE_LOCKOUT_SECONDS = 30L
        private const val MAX_LOCKOUT_SECONDS = 900L // 15 min cap
    }
}
