package com.learntoplay.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScoreTimeRuleDao {
    @Query("SELECT * FROM score_time_rules ORDER BY minScorePercent")
    fun observeAll(): Flow<List<ScoreTimeRuleEntity>>

    @Query("DELETE FROM score_time_rules")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<ScoreTimeRuleEntity>)
}
