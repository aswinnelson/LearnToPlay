package com.learntoplay.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity
import com.learntoplay.app.data.repository.AdminRepository
import com.learntoplay.app.data.repository.QuizRepository
import com.learntoplay.app.data.repository.TimeBankRepository
import kotlinx.coroutines.flow.combine
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

class AdminViewModel(private val db: AppDatabase) : ViewModel() {
    private val adminRepo = AdminRepository(db)
    private val timeBankRepo = TimeBankRepository(db)
    private val quizRepo = QuizRepository(db)

    val gatedApps = adminRepo.observeGatedApps()
    val curricula = db.curriculumDao().observeAll()

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
    suspend fun verifyPin(pin: String) = adminRepo.verifyPin(pin)

    fun selectCurriculum(id: String) = viewModelScope.launch { adminRepo.selectCurriculum(id) }

    fun setAppGated(packageName: String, displayName: String, enabled: Boolean) =
        viewModelScope.launch { adminRepo.setAppGated(packageName, displayName, enabled) }

    suspend fun getScoreTimeRules(): List<ScoreTimeRuleEntity> = timeBankRepo.getRules()

    fun saveScoreTimeRules(rules: List<ScoreTimeRuleEntity>) =
        viewModelScope.launch { timeBankRepo.setRules(rules) }
}
