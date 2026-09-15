package com.learntoplay.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.learntoplay.app.data.db.entities.GatedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GatedAppDao {
    @Query("SELECT * FROM gated_apps")
    fun observeAll(): Flow<List<GatedAppEntity>>

    @Query("SELECT packageName FROM gated_apps WHERE isEnabled = 1")
    suspend fun getEnabledPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: GatedAppEntity)

    @Query("DELETE FROM gated_apps WHERE packageName = :packageName")
    suspend fun remove(packageName: String)
}
