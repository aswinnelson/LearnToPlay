package com.learntoplay.app.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.learntoplay.app.util.ScannedImage
import com.learntoplay.app.util.cropAndSaveRegion
import com.learntoplay.app.util.orientedImageDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How much the selection box grows/shrinks per tap of Bigger/Smaller, as a fraction of the
 * page's width/height. */
private const val ZOOM_STEP = 0.12f
private const val MIN_BOX_FRACTION = 0.15f
private const val MAX_BOX_FRACTION = 1f
private const val DEFAULT_BOX_WIDTH_FRACTION = 0.65f
private const val DEFAULT_BOX_HEIGHT_FRACTION = 0.4f

/**
 * Lets the parent override [com.learntoplay.app.ai.QuestionAiGenerator]'s automatic image
 * match/crop for one AI-drafted question — for when the word-overlap heuristic picked the wrong
 * part of the page, or found no confident match at all and fell back to showing the whole page.
 * Always works from [pageImages] — the scan session's original, full-resolution page photos
 * (`DraftQuestion.sourcePageImages`) — never from an already-cropped image, so re-cropping never
 * loses detail or context by cropping a crop.
 *
 * The selection is a fixed-aspect box the parent drags into position and resizes with
 * Bigger/Smaller buttons, rather than a freeform two-finger resize gesture — simpler to get
 * right on a phone screen without an accidental resize while the parent is just trying to move
 * the box, and "roughly this part of the page" is all this needs to capture.
 *
 * [onCropped] receives the new cropped image's file path (see [cropAndSaveRegion]);
 * [onUseFullPage] resets the question back to showing every page from the scan session (undoing
 * any crop — the pre-auto-crop behavior); [onDismiss] closes without changing anything.
 */
@Composable
fun ManualCropDialog(
    pageImages: List<String>,
    onCropped: (String) -> Unit,
    onUseFullPage: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedIndex by remember { mutableStateOf(0) }
    val selectedPath = pageImages.getOrNull(selectedIndex)

    var imageSize by remember(selectedPath) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(selectedPath) {
        imageSize = selectedPath?.let { path -> withContext(Dispatchers.IO) { orientedImageDimensions(path) } }
    }

    // The selection box, as fractions (0f..1f) of the displayed image — a center point plus a
    // width/height, rather than four independent edges, so Bigger/Smaller can grow or shrink it
    // from its own middle instead of the parent having to drag two edges into place separately.
    // Reset to sensible defaults whenever the selected page changes, since a box positioned for
    // one page's layout has no reason to make sense on another.
    var boxWidthFrac by remember(selectedPath) { mutableStateOf(DEFAULT_BOX_WIDTH_FRACTION) }
    var boxHeightFrac by remember(selectedPath) { mutableStateOf(DEFAULT_BOX_HEIGHT_FRACTION) }
    var centerX by remember(selectedPath) { mutableStateOf(0.5f) }
    var centerY by remember(selectedPath) { mutableStateOf(0.5f) }
    var cropping by remember { mutableStateOf(false) }

    fun clampCenterToBox() {
        val halfW = boxWidthFrac / 2f
        val halfH = boxHeightFrac / 2f
        centerX = centerX.coerceIn(halfW, 1f - halfW)
        centerY = centerY.coerceIn(halfH, 1f - halfH)
    }

    AlertDialog(
        onDismissRequest = { if (!cropping) onDismiss() },
        title = { Text("Adjust picture") },
        text = {
            Column {
                Text(
                    "Drag the box over the part of the page this question is about, then use " +
                        "Bigger/Smaller to resize it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                // Only worth a page picker when there's more than one photo to choose from —
                // most scan sessions are a single page, and showing a one-item picker for that
                // case would just be visual noise.
                if (pageImages.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        pageImages.forEachIndexed { index, _ ->
                            val selected = index == selectedIndex
                            OutlinedButton(
                                onClick = { selectedIndex = index },
                                colors = if (selected) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                }
                            ) { Text("Page ${index + 1}") }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                val dims = imageSize
                val path = selectedPath
                if (path == null || dims == null) {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val (imgW, imgH) = dims
                    val aspect = imgW.toFloat() / imgH.toFloat().coerceAtLeast(1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .pointerInput(path, boxWidthFrac, boxHeightFrac) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val dxFrac = dragAmount.x / size.width
                                    val dyFrac = dragAmount.y / size.height
                                    val halfW = boxWidthFrac / 2f
                                    val halfH = boxHeightFrac / 2f
                                    centerX = (centerX + dxFrac).coerceIn(halfW, 1f - halfW)
                                    centerY = (centerY + dyFrac).coerceIn(halfH, 1f - halfH)
                                }
                            }
                    ) {
                        // matchParentSize() is a BoxScope member extension, not a top-level
                        // import — it resolves automatically inside this Box's content lambda.
                        ScannedImage(
                            path = path,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        // Spotlight-style overlay: darken the whole photo, then punch a
                        // transparent hole out of the current selection (via a cleared layer,
                        // not just an opaque rectangle drawn on top) so only the selected part
                        // reads clearly — makes it obvious at a glance what will and won't be
                        // kept, without needing a legend to explain it.
                        Canvas(Modifier.matchParentSize()) {
                            val left = (centerX - boxWidthFrac / 2f) * size.width
                            val top = (centerY - boxHeightFrac / 2f) * size.height
                            val right = (centerX + boxWidthFrac / 2f) * size.width
                            val bottom = (centerY + boxHeightFrac / 2f) * size.height

                            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                            drawRect(color = Color.Black.copy(alpha = 0.55f))
                            drawRect(
                                color = Color.Transparent,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                blendMode = BlendMode.Clear
                            )
                            drawContext.canvas.restore()

                            drawRect(
                                color = Color.White,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            boxWidthFrac = (boxWidthFrac - ZOOM_STEP).coerceIn(MIN_BOX_FRACTION, MAX_BOX_FRACTION)
                            boxHeightFrac = (boxHeightFrac - ZOOM_STEP).coerceIn(MIN_BOX_FRACTION, MAX_BOX_FRACTION)
                            clampCenterToBox()
                        }) { Text("Smaller") }
                        OutlinedButton(onClick = {
                            boxWidthFrac = (boxWidthFrac + ZOOM_STEP).coerceIn(MIN_BOX_FRACTION, MAX_BOX_FRACTION)
                            boxHeightFrac = (boxHeightFrac + ZOOM_STEP).coerceIn(MIN_BOX_FRACTION, MAX_BOX_FRACTION)
                            clampCenterToBox()
                        }) { Text("Bigger") }
                    }
                }

                if (cropping) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Cropping…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !cropping && selectedPath != null && imageSize != null,
                onClick = {
                    val path = selectedPath ?: return@TextButton
                    val (imgW, imgH) = imageSize ?: return@TextButton
                    cropping = true
                    val left = ((centerX - boxWidthFrac / 2f) * imgW).toInt()
                    val top = ((centerY - boxHeightFrac / 2f) * imgH).toInt()
                    val right = ((centerX + boxWidthFrac / 2f) * imgW).toInt()
                    val bottom = ((centerY + boxHeightFrac / 2f) * imgH).toInt()
                    // Off the main thread: cropAndSaveRegion decodes the (up to 2200px) bitmap
                    // and writes a new JPEG — real I/O that shouldn't block the dialog's UI.
                    scope.launch(Dispatchers.IO) {
                        val result = cropAndSaveRegion(context, path, left, top, right, bottom, paddingPx = 40)
                        withContext(Dispatchers.Main) {
                            cropping = false
                            if (result != null) onCropped(result) else onDismiss()
                        }
                    }
                }
            ) { Text("Use This Area") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !cropping, onClick = onUseFullPage) { Text("Use Full Page") }
                TextButton(enabled = !cropping, onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
