package com.learntoplay.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A preset curriculum unit, e.g. "CBSE Grade 5 - Science - Chapter 3: Food". Pre-MVP: bundled, no photo/OCR. */
@Entity(tableName = "curricula")
data class CurriculumEntity(
    @PrimaryKey val id: String,
    val board: String,
    val grade: Int,
    val subject: String,
    val chapterTitle: String,
    val isSelectedByAdmin: Boolean = false
)
