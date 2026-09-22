package com.learntoplay.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

private const val IMAGE_PATH_SEPARATOR = "\u001F"

/** MCQ (four options, [QuestionEntity.correctOption] picks one) or FILL_IN (typed free-text
 * answer, matched leniently against [QuestionEntity.correctAnswerText]). Existing rows are all
 * MCQ by default so this addition needed no data migration beyond two new columns. */
object QuestionType {
    const val MCQ = "MCQ"
    const val FILL_IN = "FILL_IN"
}

@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val curriculumId: String,
    val prompt: String,
    // For FILL_IN questions these four stay unused ("" sentinel) rather than nullable, so the
    // v3->v4 migration is a plain ADD COLUMN instead of a destructive NOT NULL relaxation.
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val correctOption: String, // "A" | "B" | "C" | "D" — unused ("") for FILL_IN questions
    val timesAsked: Int = 0,
    val timesCorrect: Int = 0,
    val lastAskedAtEpochMillis: Long = 0L,
    val imagePaths: String? = null,
    val questionType: String = QuestionType.MCQ,
    val correctAnswerText: String? = null // set only for FILL_IN questions
)

fun List<String>.toImagePathsColumn(): String? =
    if (isEmpty()) null else joinToString(IMAGE_PATH_SEPARATOR)

fun QuestionEntity.imagePathList(): List<String> =
    imagePaths?.split(IMAGE_PATH_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

fun QuestionEntity.isFillIn(): Boolean = questionType == QuestionType.FILL_IN
