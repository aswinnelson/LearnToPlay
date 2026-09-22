package com.learntoplay.app.data.repository

import androidx.room.withTransaction
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.db.entities.QuizResultEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/** Result of a completed quiz attempt: the score, and the minutes it actually earned. */
data class QuizSubmissionResult(val scorePercent: Int, val minutesAwarded: Int)

class QuizRepository(private val db: AppDatabase) {

    fun observeSelectedCurriculum() = db.curriculumDao().observeSelected()

    /**
     * Picks [count] questions for a quiz, weighted toward ones the child gets wrong more often
     * and hasn't been asked in a while — instead of the old pure `.shuffled().take(count)`.
     * A never-asked question is treated as maximally "weak" and "stale" so new content
     * (including parent-added ones from ManageQuestionsScreen) surfaces quickly rather than
     * getting buried under an established pool. Selection is still weighted-*random*, not a
     * strict worst-first ranking, so the same handful of hardest questions don't dominate
     * every single quiz.
     */
    suspend fun getQuizQuestions(curriculumId: String, count: Int = 5): List<QuestionEntity> {
        val all = db.questionDao().getForCurriculum(curriculumId)
        if (all.size <= count) return all.shuffled()

        val now = System.currentTimeMillis()
        val pool = all.toMutableList()
        val picked = mutableListOf<QuestionEntity>()

        repeat(count) {
            if (pool.isEmpty()) return@repeat
            val weights = pool.map { weightOf(it, now) }
            val total = weights.sum()
            var roll = Random.nextDouble() * total
            var chosenIndex = weights.lastIndex
            for (i in weights.indices) {
                roll -= weights[i]
                if (roll <= 0) { chosenIndex = i; break }
            }
            picked += pool.removeAt(chosenIndex)
        }
        return picked
    }

    private fun weightOf(q: QuestionEntity, nowMillis: Long): Double {
        val missRate = if (q.timesAsked == 0) 1.0 else 1.0 - (q.timesCorrect.toDouble() / q.timesAsked)
        val daysSinceAsked = if (q.lastAskedAtEpochMillis == 0L) 999.0
        else (nowMillis - q.lastAskedAtEpochMillis) / 86_400_000.0
        // A week or more since last asked counts as fully "stale"; under that, staleness
        // scales down toward a floor so a just-asked question can still occasionally reappear.
        val staleness = (daysSinceAsked / 7.0).coerceIn(0.2, 3.0)
        return (missRate + 0.05) * staleness
    }

    /** Called once per answered question (see QuizViewModel.answer()) so a question's
     * weighting updates immediately, rather than only at quiz completion. */
    suspend fun recordAnswer(questionId: Long, wasCorrect: Boolean) {
        db.questionDao().recordAttempt(
            questionId = questionId,
            correctIncrement = if (wasCorrect) 1 else 0,
            atEpochMillis = System.currentTimeMillis()
        )
    }

    /**
     * Records the quiz attempt and credits the time bank in one atomic transaction. This used
     * to be two separate suspend calls (submitQuiz() then TimeBankRepository.awardForScore()) —
     * if the app was killed between them, a QuizResultEntity could end up permanently recorded
     * with minutesAwarded=0 even when the score qualified for a reward, since nothing rolled
     * back the first write. db.withTransaction wraps the read of the score-time rules, the
     * result insert, and the time-bank credit as a single unit: either all three happen or
     * none do.
     *
     * The score -> percent -> minutes math itself lives in ScoreTimeCalculator (shared with
     * TimeBankRepository.ruleForScore), which is unit-tested directly — see
     * ScoreTimeCalculatorTest — since that's the actual decision a parent is trusting this app
     * to get right every time, independent of whatever this method does with the database.
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
        val scorePercent = ScoreTimeCalculator.scorePercent(correctCount, totalCount)

        val rules = db.scoreTimeRuleDao().observeAll().first()
        val minutesAwarded = ScoreTimeCalculator.minutesForScore(scorePercent, rules)

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
