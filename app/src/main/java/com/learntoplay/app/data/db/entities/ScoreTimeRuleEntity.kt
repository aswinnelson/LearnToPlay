package com.learntoplay.app.data.db.entities

import androidx.room.Entity

/**
 * Admin-configured reward curve, e.g. 50-69% -> 15 min, 70-89% -> 30 min, 90-100% -> 45 min.
 * minScorePercent is inclusive; ranges must not overlap (validated in AdminRepository).
 */
@Entity(tableName = "score_time_rules", primaryKeys = ["minScorePercent"])
data class ScoreTimeRuleEntity(
    val minScorePercent: Int,
    val maxScorePercent: Int,
    val minutesAwarded: Int
)
