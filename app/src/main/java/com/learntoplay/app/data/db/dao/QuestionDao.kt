package com.learntoplay.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE curriculumId = :curriculumId")
    suspend fun getForCurriculum(curriculumId: String): List<com.learntoplay.app.data.db.entities.QuestionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<com.learntoplay.app.data.db.entities.QuestionEntity>)
}
