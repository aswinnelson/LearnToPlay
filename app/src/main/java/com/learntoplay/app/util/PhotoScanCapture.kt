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
 * @return a function that starts the capture-and-recognize flow when called (wire to a button).
 */
@Composable
fun rememberPhotoScanLauncher(
    onTextRecognized: (String) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { capturedOk ->
        val uri = pendingUri
        if (!capturedOk || uri == null) {
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
                            onTextRecognized(text)
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
        val uri = createScanImageUri(context)
        pendingUri = uri
        cameraLauncher.launch(uri)
    }
}

/** A fresh content:// Uri (via FileProvider) for the camera app to write the next photo into,
 * under a scratch cache folder — nothing here is meant to persist past the OCR pass. */
private fun createScanImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "scanned_questions").apply { mkdirs() }
    val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
