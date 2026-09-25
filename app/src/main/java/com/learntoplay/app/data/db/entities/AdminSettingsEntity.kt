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
    val quizPassThresholdPercent: Int = 50,
    /** Whether gated apps are additionally restricted to a specific daily time window (a
     * curfew/schedule on top of the time-bank mechanic) — see AllowedWindowChecker and
     * AppLockAccessibilityService's allowed-window check. Off by default so existing installs
     * keep behaving exactly as before this was added. */
    val allowedWindowEnabled: Boolean = false,
    /** Minutes since midnight (0-1439), inclusive start of the window. Default 8:00 AM. */
    val allowedWindowStartMinute: Int = 8 * 60,
    /** Minutes since midnight (0-1439), exclusive end of the window. Default 8:00 PM. If less
     * than [allowedWindowStartMinute], the window is treated as crossing midnight (e.g. a
     * 22:00-06:00 overnight curfew) rather than an always-false empty range — see
     * AllowedWindowChecker.isWithinWindow. */
    val allowedWindowEndMinute: Int = 20 * 60
)
