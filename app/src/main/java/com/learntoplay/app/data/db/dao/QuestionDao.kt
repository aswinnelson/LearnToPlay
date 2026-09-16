package com.learntoplay.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.learntoplay.app.data.db.entities.QuestionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE curriculumId = :curriculumId")
    suspend fun getForCurriculum(curriculumId: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE curriculumId = :curriculumId ORDER BY id")
    fun observeForCurriculum(curriculumId: String): Flow<List<QuestionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QuestionEntity>)

    /** Parent-authored add/edit from ManageQuestionsScreen. */
    @Upsert
    suspend fun upsert(question: QuestionEntity)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun delete(id: Long)

    /** Updates a question's selection-weighting stats after it's answered — see
     * QuizRepository.getQuizQuestions() for how these feed into picking future quizzes. */
    @Query(
        """
        UPDATE questions
        SET timesAsked = timesAsked + 1,
            timesCorrect = timesCorrect + :correctIncrement,
            lastAskedAtEpochMillis = :atEpochMillis
        WHERE id = :questionId
        """
    )
    suspend fun recordAttempt(questionId: Long, correctIncrement: Int, atEpochMillis: Long)
}
