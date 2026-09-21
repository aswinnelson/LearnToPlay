package com.learntoplay.app.util

/**
 * One OCR'd block of text and where it sits on the source photo — pixel coordinates in that
 * photo's own EXIF-corrected orientation (the same space [decodeOrientedBitmap] produces and
 * [cropAndSaveRegion] expects), on the image at [imagePath].
 *
 * ML Kit's text recognizer already groups recognized text into blocks with bounding boxes; the
 * original Scan Photo flow only ever kept the flattened full-page text and threw this structure
 * away. It's used to guess which part of a scanned page a given AI-generated question is
 * actually about, so the parent/child can be shown just that region instead of the whole page —
 * see QuestionAiGenerator's per-question image matching, which reads [text] to score each block
 * against a question's wording, and [left]/[top]/[right]/[bottom] to crop the winner.
 */
data class ScannedTextBlock(
    val imagePath: String,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
