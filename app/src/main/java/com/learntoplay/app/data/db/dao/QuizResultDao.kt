package com.learntoplay.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.learntoplay.app.data.db.entities.QuizResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuizResultDao {
    @Insert
    suspend fun insert(result: QuizResultEntity)

    @Query("SELECT * FROM quiz_results ORDER BY takenAtEpochMillis DESC")
    fun observeHistory(): Flow<List<QuizResultEntity>>
}
