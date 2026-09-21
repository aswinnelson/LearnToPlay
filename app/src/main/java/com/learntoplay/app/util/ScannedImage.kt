package com.learntoplay.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(path, maxDimensionPx) }
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

/** Decodes [path] downsampled so neither dimension exceeds [maxDimensionPx] — halving
 * resolution (via [BitmapFactory.Options.inSampleSize], which only accepts powers of two) until
 * it would go below that ceiling, rather than decoding at full size and scaling down afterward,
 * which would spend the memory this is trying to avoid in the first place. Returns null for a
 * missing/corrupt file instead of throwing. */
private fun decodeSampledBitmap(path: String, maxDimensionPx: Int): Bitmap? {
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
    return BitmapFactory.decodeFile(path, options)
}
