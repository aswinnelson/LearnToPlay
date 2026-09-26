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

    /** Atomic decrement, floored at zero, done entirely inside SQLite. The tracker service
     * calls this once a second; doing it as read-then-write in Kotlin (as it used to) meant a
     * parent's edit or a quiz reward landing between the read and the write got silently
     * overwritten by the stale value. */
    @Query(
        "UPDATE admin_settings SET timeBankSecondsRemaining = " +
            "MAX(0, timeBankSecondsRemaining - :seconds) WHERE id = 0"
    )
    suspend fun spendSeconds(seconds: Long)

    /** Atomic increment — same reasoning as [spendSeconds], for quiz rewards and remote
     * "add time" commands. */
    @Query("UPDATE admin_settings SET timeBankSecondsRemaining = timeBankSecondsRemaining + :seconds WHERE id = 0")
    suspend fun addSeconds(seconds: Long)

    /** Direct column update (not a read-modify-write via [upsert]) so a parent toggling the
     * Allowed Hours schedule can never race TimeBankTrackerService's once-a-second
     * timeBankSecondsRemaining writes and silently clobber a tick. */
    @Query(
        "UPDATE admin_settings SET allowedWindowEnabled = :enabled, " +
            "allowedWindowStartMinute = :startMinute, allowedWindowEndMinute = :endMinute WHERE id = 0"
    )
    suspend fun setAllowedWindow(enabled: Boolean, startMinute: Int, endMinute: Int)
}
