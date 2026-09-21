package com.learntoplay.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Multiple image paths join into [QuestionEntity.imagePaths] with this separator — an ASCII
 * "unit separator" control character that will never appear in a real file path, so no
 * escaping/quoting is needed and a plain TEXT column works without a Room TypeConverter. */
private const val IMAGE_PATH_SEPARATOR = "\u001F"

/** A single multiple-choice comprehension question tied to a curriculum unit.
 *
 * timesAsked/timesCorrect/lastAskedAtEpochMillis feed QuizRepository's selection weighting —
 * favor questions the child gets wrong more, and hasn't seen recently — instead of picking
 * purely at random. */
@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val curriculumId: String,
    val prompt: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val correctOption: String, // "A" | "B" | "C" | "D"
    val timesAsked: Int = 0,
    val timesCorrect: Int = 0,
    val lastAskedAtEpochMillis: Long = 0L,
    /** Absolute on-device paths (app-private permanent storage, not cache — see
     * PhotoScanCapture.persistScanImageOrNull) of the scanned page photo(s) this question was
     * drafted from, if any — null/empty for a manually-typed or "From Topic" question. Shown
     * alongside the question in QuizScreen and in ManageQuestionsScreen's review step, so the
     * child (and the parent reviewing it) can see the actual table/diagram/figure a vaguely-
     * worded question is referring to instead of having to guess. Stored as one TEXT column
     * (paths joined by [IMAGE_PATH_SEPARATOR]) rather than a separate join table — this is a
     * single-user local app with at most a handful of images per question. */
    val imagePaths: String? = null
)

/** Joins a list of image paths into [QuestionEntity.imagePaths]'s stored form — null for an
 * empty list rather than an empty string, so "no images" round-trips cleanly. */
fun List<String>.toImagePathsColumn(): String? =
    if (isEmpty()) null else joinToString(IMAGE_PATH_SEPARATOR)

/** The inverse of [toImagePathsColumn] — always safe to call, even on a pre-migration row where
 * [QuestionEntity.imagePaths] is null. */
fun QuestionEntity.imagePathList(): List<String> =
    imagePaths?.split(IMAGE_PATH_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
