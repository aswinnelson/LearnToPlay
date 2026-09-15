package com.learntoplay.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A single multiple-choice comprehension question tied to a curriculum unit. */
@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val curriculumId: String,
    val prompt: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val correctOption: String // "A" | "B" | "C" | "D"
)
