package com.learntoplay.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.learntoplay.app.data.db.entities.AdminSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AdminSettingsDao {
    @Query("SELECT * FROM admin_settings WHERE id = 0")
    fun observe(): Flow<AdminSettingsEntity?>

    @Query("SELECT * FROM admin_settings WHERE id = 0")
    suspend fun getOnce(): AdminSettingsEntity?

    @Upsert
    suspend fun upsert(settings: AdminSettingsEntity)

    @Query("UPDATE admin_settings SET timeBankSecondsRemaining = :seconds WHERE id = 0")
    suspend fun setTimeBankSeconds(seconds: Long)
}
