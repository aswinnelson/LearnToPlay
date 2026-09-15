package com.learntoplay.app.data.db.dao

import androidx.room.*
import com.learntoplay.app.data.db.entities.CurriculumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CurriculumDao {
    @Query("SELECT * FROM curricula ORDER BY grade, subject")
    fun observeAll(): Flow<List<CurriculumEntity>>

    @Query("SELECT * FROM curricula WHERE isSelectedByAdmin = 1 LIMIT 1")
    fun observeSelected(): Flow<CurriculumEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CurriculumEntity>)

    @Query("UPDATE curricula SET isSelectedByAdmin = 0")
    suspend fun clearSelection()

    @Query("UPDATE curricula SET isSelectedByAdmin = 1 WHERE id = :id")
    suspend fun select(id: String)
}
