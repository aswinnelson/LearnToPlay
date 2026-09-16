package com.learntoplay.app.data.repository

import androidx.room.withTransaction
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.db.entities.QuizResultEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Result of a completed quiz attempt: the score, and the minutes it actually earned. */
data class QuizSubmissionResult(val scorePercent: Int, val minutesAwarded: Int)

class QuizRepository(private val db: AppDatabase) {

    fun observeSelectedCurriculum() = db.curriculumDao().observeSelected()

    suspend fun getQuizQuestions(curriculumId: String): List<QuestionEntity> =
        db.questionDao().getForCurriculum(curriculumId).shuffled().take(5)

    /**
     * Records the quiz attempt and credits the time bank in one atomic transaction. This used
     * to be two separate suspend calls (submitQuiz() then TimeBankRepository.awardForScore()) —
     * if the app was killed between them, a QuizResultEntity could end up permanently recorded
     * with minutesAwarded=0 even when the score qualified for a reward, since nothing rolled
     * back the first write. db.withTransaction wraps the read of the score-time rules, the
     * result insert, and the time-bank credit as a single unit: either all three happen or
     * none do.
     *
     * Note: this assumes an admin_settings row already exists (created when the parent first
     * sets a PIN in AdminRepository.setPin). In practice that's guaranteed — a curriculum has
     * to be selected in Admin Mode before a quiz can run at all (see QuizViewModel.start()),
     * and selecting a curriculum requires having gone through the PIN gate first.
     */
    suspend fun submitQuizAndAwardTime(
        curriculumId: String,
        correctCount: Int,
        totalCount: Int
    ): QuizSubmissionResult = db.withTransaction {
        val scorePercent = if (totalCount == 0) 0 else (correctCount * 100) / totalCount

        val rules = db.scoreTimeRuleDao().observeAll().first()
        val minutesAwarded = rules.firstOrNull { scorePercent in it.minScorePercent..it.maxScorePercent }
            ?.minutesAwarded ?: 0

        db.quizResultDao().insert(
            QuizResultEntity(
                curriculumId = curriculumId,
                correctCount = correctCount,
                totalCount = totalCount,
                scorePercent = scorePercent,
                minutesAwarded = minutesAwarded,
                takenAtEpochMillis = System.currentTimeMillis()
            )
        )

        if (minutesAwarded > 0) {
            val currentSeconds = db.adminSettingsDao().getOnce()?.timeBankSecondsRemaining ?: 0L
            db.adminSettingsDao().setTimeBankSeconds(currentSeconds + minutesAwarded * 60L)
        }

        QuizSubmissionResult(scorePercent, minutesAwarded)
    }

    fun observeHistory(): Flow<List<QuizResultEntity>> = db.quizResultDao().observeHistory()

    /** The apps the child can now open — shown as "Play now" buttons right after a quiz that
     * earned time, instead of leaving the child stuck in Learn to Play with no obvious next step. */
    suspend fun getEnabledGatedApps() = db.gatedAppDao().getEnabledApps()

    suspend fun getTimeBankMinutesRemaining(): Int =
        ((db.adminSettingsDao().getOnce()?.timeBankSecondsRemaining ?: 0L) / 60L).toInt()
}
