package com.learntoplay.app.data.db.entities

import androidx.room.Entity

/**
 * Single-row table (id always 0) holding the parent's PIN hash and the live time-bank balance.
 * No name/email/identity is ever stored, per the pre-MVP no-personal-data decision.
 */
@Entity(tableName = "admin_settings", primaryKeys = ["id"])
data class AdminSettingsEntity(
    val id: Int = 0,
    val pinHash: String,
    val pinSalt: String,
    val timeBankSecondsRemaining: Long = 0L,
    val quizPassThresholdPercent: Int = 50
)
