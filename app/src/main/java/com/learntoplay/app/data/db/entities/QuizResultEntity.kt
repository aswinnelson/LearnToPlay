package com.learntoplay.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A completed quiz attempt, kept for the parent-oversight history screen. */
@Entity(tableName = "quiz_results")
data class QuizResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val curriculumId: String,
    val correctCount: Int,
    val totalCount: Int,
    val scorePercent: Int,
    val minutesAwarded: Int,
    val takenAtEpochMillis: Long
)
