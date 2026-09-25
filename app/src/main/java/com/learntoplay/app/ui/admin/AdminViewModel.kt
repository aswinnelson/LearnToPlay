package com.learntoplay.app.ui.admin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity
import com.learntoplay.app.data.repository.AdminRepository
import com.learntoplay.app.data.repository.PinCheckResult
import com.learntoplay.app.data.repository.QuizRepository
import com.learntoplay.app.data.repository.TimeBankRepository
import com.learntoplay.app.remote.AuthOutcome
import com.learntoplay.app.remote.AuthRepository
import com.learntoplay.app.remote.FamilySyncRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A quiz attempt paired with a human-readable label, for the history screen — the raw
 * QuizResultEntity only stores curriculumId, which isn't something a parent wants to read. */
data class QuizHistoryRow(
    val curriculumLabel: String,
    val correctCount: Int,
    val totalCount: Int,
    val scorePercent: Int,
    val minutesAwarded: Int,
    val takenAtEpochMillis: Long
)

/** The "Allowed Hours" schedule as the Admin Dashboard reads and edits it — see
 * AdminSettingsEntity.allowedWindow* and AppLockAccessibilityService. [startMinute] and
 * [endMinute] are minutes since midnight (0-1439); a window with startMinute > endMinute
 * crosses midnight (see AllowedWindowChecker). */
data class AllowedWindow(
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int
)

class AdminViewModel(private val db: AppDatabase, context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val adminRepo = AdminRepository(db, appContext)
    private val timeBankRepo = TimeBankRepository(db)
    private val quizRepo = QuizRepository(db)

    val gatedApps = adminRepo.observeGatedApps()
    val curricula = db.curriculumDao().observeAll()

    /** Live remaining time-bank balance in whole minutes, for the Admin Dashboard's
     * "Time Bank" card. Backed by the same reactive Room Flow the child's Home screen
     * reads and the tracker service ticks down, so a parent edit and an in-progress
     * countdown never clobber each other — each just reads-then-writes the current row. */
    val timeBankMinutesRemaining = timeBankRepo.observeBalanceSeconds()
        .map { ((it?.timeBankSecondsRemaining ?: 0L) / 60L).toInt() }

    /** Live Allowed Hours schedule for the Admin Dashboard's card. Defaults (disabled,
     * 8:00 AM-8:00 PM) match AdminSettingsEntity's own column defaults, used before the
     * settings row exists yet (i.e. before a PIN has ever been set). */
    val allowedWindow = adminRepo.observeAdminSettings().map { settings ->
        AllowedWindow(
            enabled = settings?.allowedWindowEnabled ?: false,
            startMinute = settings?.allowedWindowStartMinute ?: DEFAULT_ALLOWED_WINDOW_START,
            endMinute = settings?.allowedWindowEndMinute ?: DEFAULT_ALLOWED_WINDOW_END
        )
    }

    val quizHistory = combine(quizRepo.observeHistory(), curricula) { results, allCurricula ->
        val labelsById = allCurricula.associateBy({ it.id }, { "${it.subject} — ${it.chapterTitle}" })
        results.map { result ->
            QuizHistoryRow(
                curriculumLabel = labelsById[result.curriculumId] ?: "Unknown curriculum",
                correctCount = result.correctCount,
                totalCount = result.totalCount,
                scorePercent = result.scorePercent,
                minutesAwarded = result.minutesAwarded,
                takenAtEpochMillis = result.takenAtEpochMillis
            )
        }
    }

    suspend fun isPinSet() = adminRepo.isPinSet()
    suspend fun setPin(pin: String) = adminRepo.setPin(pin)
    suspend fun verifyPin(pin: String): PinCheckResult = adminRepo.verifyPin(pin)

    fun selectCurriculum(id: String) = viewModelScope.launch { adminRepo.selectCurriculum(id) }

    fun createCurriculum(board: String, grade: Int, subject: String, chapterTitle: String) =
        viewModelScope.launch { adminRepo.createCurriculum(board, grade, subject, chapterTitle) }

    fun observeQuestionsForCurriculum(curriculumId: String) =
        adminRepo.observeQuestionsForCurriculum(curriculumId)

    fun upsertQuestion(question: QuestionEntity) =
        viewModelScope.launch { adminRepo.upsertQuestion(question) }

    fun deleteQuestion(id: Long) =
        viewModelScope.launch { adminRepo.deleteQuestion(id) }

    fun setAppGated(packageName: String, displayName: String, enabled: Boolean) =
        viewModelScope.launch {
            adminRepo.setAppGated(packageName, displayName, enabled)
            syncNow()
        }

    suspend fun getScoreTimeRules(): List<ScoreTimeRuleEntity> = timeBankRepo.getRules()

    fun saveScoreTimeRules(rules: List<ScoreTimeRuleEntity>) =
        viewModelScope.launch { timeBankRepo.setRules(rules) }

    /** Directly overrides the child's remaining play time, in minutes. Applied immediately —
     * reflected on the child's Home screen right away, and on the tracker service's very
     * next one-second tick if a gated-app session is in progress. */
    fun setTimeBankMinutes(minutes: Int) = viewModelScope.launch {
        timeBankRepo.setBalanceSeconds(minutes.coerceAtLeast(0).toLong() * 60L)
        syncNow()
    }

    /** Parent edit from the Admin Dashboard's Allowed Hours card — toggling the schedule on/off,
     * or moving the start/end time. Applied immediately; AppLockAccessibilityService reads the
     * new values on the very next foreground-app event, and TimeBankTrackerService's tick loop
     * picks it up on its next tick if a gated-app session is already in progress. */
    fun setAllowedWindow(enabled: Boolean, startMinute: Int, endMinute: Int) = viewModelScope.launch {
        adminRepo.setAllowedWindow(enabled, startMinute, endMinute)
        syncNow()
    }

    /** The random pairing code a parent's separate phone types in to view this device's
     * synced data remotely. Generated once and stable thereafter — see FamilySyncRepository. */
    fun getFamilyCode(): String = FamilySyncRepository.getOrCreateFamilyCode(appContext)

    /** Pushes the current state to Firestore right away, e.g. right after generating a pairing
     * code for the first time, rather than waiting for the next automatic sync point. */
    fun syncNow() = viewModelScope.launch { FamilySyncRepository.pushSnapshot(appContext, db) }

    // --- Parent account (see AuthRepository) — separate from the family-code pairing above
    // and from the local PIN gate; purely "who is this parent," not "can they open Admin Mode
    // on this device" or "which device is synced to which." ---

    /** The signed-in parent's email, or null if not signed in with a real account (an
     * anonymous-only session reads the same as no session at all). Plain synchronous read of
     * FirebaseAuth's current state, not a Flow — the call site re-reads this itself right
     * after a sign-up/sign-in/sign-out call resolves, same pattern DeviceSettingsScreen
     * already uses for isDeviceOwner. */
    fun currentParentEmail(): String? = AuthRepository.currentParentEmail()

    suspend fun signUpParent(email: String, password: String): AuthOutcome =
        AuthRepository.signUp(email, password)

    suspend fun signInParent(email: String, password: String): AuthOutcome =
        AuthRepository.signIn(email, password)

    fun signOutParent() = AuthRepository.signOut()

    companion object {
        const val DEFAULT_ALLOWED_WINDOW_START = 8 * 60
        const val DEFAULT_ALLOWED_WINDOW_END = 20 * 60
    }
}
