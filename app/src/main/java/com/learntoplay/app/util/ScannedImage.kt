package com.learntoplay.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Displays one scanned page photo (see [rememberPhotoScanLauncher], `QuestionEntity.imagePaths`)
 * from its permanent on-disk path — used in QuizScreen so the child can see the actual page a
 * (possibly vaguely worded) comprehension question is about, and in ManageQuestionsScreen's
 * batch review so the parent can double-check it before saving.
 *
 * Decodes at a reduced resolution rather than full camera resolution: a modern phone's photo can
 * be 10+ megapixels, which as a raw ARGB_8888 [Bitmap] would be tens of MB — far more detail
 * than anything here displays at. Decoding happens off the main thread; nothing is shown while
 * it's in flight or if the file is missing (e.g. the user cleared app storage) rather than
 * crashing or showing a broken-image placeholder that implies something worse is wrong than
 * "this one photo is gone."
 */
@Composable
fun ScannedImage(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    maxDimensionPx: Int = 1024
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeOrientedBitmap(path, maxDimensionPx)?.first }
    }
    val decoded = bitmap
    if (decoded != null) {
        Image(
            bitmap = decoded.asImageBitmap(),
            contentDescription = "Scanned page photo",
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(modifier)
    }
}

/**
 * Crops the region [left]/[top]/[right]/[bottom] — full-resolution pixel coordinates in
 * [imagePath]'s own EXIF-corrected orientation, e.g. straight from an ML Kit OCR block's
 * bounding box (computed in that same corrected space) — out of the photo at [imagePath], pads
 * it by [paddingPx] on every side (clamped to the photo's bounds, so a region near an edge just
 * gets less padding rather than failing), and saves the crop as a new JPEG alongside the source
 * photo. Returns the new file's path, or null on any failure (missing/corrupt source, decode
 * error, disk error) — callers should fall back to showing the full page rather than fail
 * outright over a crop that didn't work out.
 */
fun cropAndSaveRegion(
    context: Context,
    imagePath: String,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    paddingPx: Int = 120
): String? = runCatching {
    // Capped well above display's 1024px so the crop keeps good detail, but still bounded —
    // this decode is short-lived (happens once right after generation; the full bitmap isn't
    // held in any UI state afterward), so a higher ceiling here is fine without reintroducing
    // the "decode a 12+ megapixel camera photo uncapped" memory problem this file otherwise
    // avoids.
    val (bitmap, sampleSize) = decodeOrientedBitmap(imagePath, maxDimensionPx = 2200) ?: return null
    val l = ((left - paddingPx) / sampleSize).coerceIn(0, bitmap.width - 1)
    val t = ((top - paddingPx) / sampleSize).coerceIn(0, bitmap.height - 1)
    val r = ((right + paddingPx) / sampleSize).coerceIn(l + 1, bitmap.width)
    val b = ((bottom + paddingPx) / sampleSize).coerceIn(t + 1, bitmap.height)
    val cropped = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
    val dir = File(context.filesDir, "question_images").apply { mkdirs() }
    val dest = File(dir, "crop_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
    FileOutputStream(dest).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 90, out) }
    dest.absolutePath
}.getOrNull()

/**
 * Decodes [path] downsampled so neither dimension exceeds [maxDimensionPx] — halving resolution
 * (via [BitmapFactory.Options.inSampleSize], which only accepts powers of two) until it would go
 * below that ceiling, rather than decoding at full size and scaling down afterward, which would
 * spend the memory this is trying to avoid in the first place — and rotated/flipped to match its
 * EXIF orientation tag.
 *
 * That EXIF correction matters for two reasons: many phones store a portrait photo as a
 * landscape raster plus a rotation tag rather than baking the rotation into the pixels, and
 * [BitmapFactory] does not apply that tag on its own — skipping this showed (and would have
 * cropped) sideways or upside-down photos on those devices. It also keeps this file's pixel
 * coordinates consistent with ML Kit's, since `InputImage.fromFilePath` (used for on-device OCR
 * in PhotoScanCapture) applies this same EXIF correction internally before recognizing text —
 * an OCR block's bounding box is already in this corrected space, so [cropAndSaveRegion] can use
 * one directly without a separate un-rotate step.
 *
 * Returns the corrected bitmap paired with the integer sample size actually used, so a caller
 * with full-resolution pixel coordinates (an OCR bounding box) can scale them into this bitmap's
 * own coordinate space by dividing by that factor. Returns null for a missing/corrupt file
 * instead of throwing.
 */
private fun decodeOrientedBitmap(path: String, maxDimensionPx: Int): Pair<Bitmap, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxDimensionPx ||
        bounds.outHeight / (sampleSize * 2) >= maxDimensionPx
    ) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val rawBitmap = BitmapFactory.decodeFile(path, options) ?: return null

    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return rawBitmap to sampleSize
    }
    val corrected = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    if (corrected !== rawBitmap) rawBitmap.recycle()
    return corrected to sampleSize
}
