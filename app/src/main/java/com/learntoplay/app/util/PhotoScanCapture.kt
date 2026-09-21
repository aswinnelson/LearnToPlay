package com.learntoplay.app.util

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Photo-to-text for the "scan a textbook page" question-entry flow (Stage C).
 *
 * Captures a photo via the device's own camera app (no in-app camera preview to build or
 * maintain, no CAMERA permission to request), then runs Google ML Kit's text recognizer
 * entirely on-device — the photo and the recognized text never leave the phone, matching the
 * app's local-first, no-cloud-account design.
 *
 * Deliberately does NOT try to parse the recognized text into a prompt + four options — OCR
 * text from a real textbook photo is messy (line breaks mid-sentence, stray page furniture,
 * misread characters), and guessing a split with no human check would produce bad questions
 * silently. Instead the raw recognized text is handed back for the parent to review, trim down
 * to just the question, and correct in the existing question-edit dialog — same place they'd
 * type a question by hand.
 *
 * On success, the captured photo is also copied to permanent app-private storage (see
 * [persistScanImageOrNull]) and its path handed back alongside the text, so a generated
 * question can show the actual photographed page next to a possibly-vague AI question — see
 * QuestionEntity.imagePaths.
 *
 * @return a function that starts the capture-and-recognize flow when called (wire to a button).
 */
@Composable
fun rememberPhotoScanLauncher(
    onTextRecognized: (text: String, imagePath: String?) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { capturedOk ->
        val uri = pendingUri
        val file = pendingFile
        if (!capturedOk || uri == null || file == null) {
            onError("Photo was canceled — nothing was scanned.")
            return@rememberLauncherForActivityResult
        }
        runCatching { InputImage.fromFilePath(context, uri) }
            .onSuccess { image ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text.trim()
                        if (text.isBlank()) {
                            onError("Couldn't find readable text in that photo — try a closer, well-lit shot.")
                        } else {
                            // A photo-save hiccup here (rare — disk full, etc.) shouldn't block
                            // generating the question itself; onTextRecognized just gets a null
                            // path and the question ends up with no attached image, same as
                            // before this feature existed.
                            onTextRecognized(text, persistScanImageOrNull(context, file))
                        }
                    }
                    .addOnFailureListener {
                        onError("Text recognition failed on that photo — try again.")
                    }
            }
            .onFailure {
                onError("Couldn't open that photo — try again.")
            }
    }

    return {
        val file = createScanImageFile(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingUri = uri
        pendingFile = file
        cameraLauncher.launch(uri)
    }
}

/** A fresh scratch file (under the app's cache dir) for the camera app to write the next photo
 * into. Cache is fine for this temporary copy — the camera app just needs somewhere to write —
 * but is NOT where a photo that ends up attached to a saved question should live long-term; see
 * [persistScanImageOrNull]. */
private fun createScanImageFile(context: Context): File {
    val dir = File(context.cacheDir, "scanned_questions").apply { mkdirs() }
    return File(dir, "scan_${System.currentTimeMillis()}.jpg")
}

/** Copies a just-captured scan photo out of the cache directory into permanent app-private
 * storage (`filesDir/question_images/`). The cache directory it starts in can be cleared by
 * Android at any time — low storage, the user tapping "Clear cache", etc. — which would
 * silently break any saved question still pointing at it; files under `filesDir` persist for as
 * long as the app is installed. Returns null (rather than throwing) on any failure, so the
 * caller can fall back to generating the question without an attached image instead of failing
 * the whole scan over a copy error.
 *
 * Note: nothing currently deletes these once copied, even if the parent later discards the
 * drafted question(s) that referenced them (Discard All / removing an individual draft in
 * ManageQuestionsScreen) — a handful of orphaned JPEGs from declined scans is an acceptable
 * trade for now against the complexity of tracking "was this ever saved," but worth revisiting
 * if scanning becomes heavy, frequent usage. */
private fun persistScanImageOrNull(context: Context, tempFile: File): String? = runCatching {
    val dir = File(context.filesDir, "question_images").apply { mkdirs() }
    val dest = File(dir, tempFile.name)
    tempFile.copyTo(dest, overwrite = true)
    dest.absolutePath
}.getOrNull()
