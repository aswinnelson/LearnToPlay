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

    /** Direct column update (not a read-modify-write via [upsert]) so a parent toggling the
     * Allowed Hours schedule can never race TimeBankTrackerService's once-a-second
     * timeBankSecondsRemaining writes and silently clobber a tick. */
    @Query(
        "UPDATE admin_settings SET allowedWindowEnabled = :enabled, " +
            "allowedWindowStartMinute = :startMinute, allowedWindowEndMinute = :endMinute WHERE id = 0"
    )
    suspend fun setAllowedWindow(enabled: Boolean, startMinute: Int, endMinute: Int)
}
