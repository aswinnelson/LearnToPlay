package com.learntoplay.app.util

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * What MainActivity handed off from an incoming `ACTION_SEND` intent — e.g. a parent
 * long-pressing a teacher's message in a school WhatsApp group and sharing it into LearnToPlay,
 * either as plain text or a screenshot. Carried from MainActivity down through AppNavGraph to
 * ManageQuestionsScreen's `incomingShare` parameter, which feeds it into the same
 * comprehension-question pipeline as a camera scan — a shared homework/syllabus message becomes
 * a "page" the AI drafts questions from, exactly like a photographed textbook page.
 */
sealed interface PendingShare {
    data class Text(val text: String) : PendingShare
    data class Image(val uri: Uri) : PendingShare
}

/** Result of importing a shared photo (see [importSharedImage]): its permanently-saved path,
 * the OCR'd text, and the OCR'd text blocks with position — everything
 * `QuestionAiGenerator.generateComprehensionQuestionsFromScan` and its region-matching auto-crop
 * need, matching exactly what a camera scan produces via [rememberPhotoScanLauncher]. */
data class SharedImageImport(val imagePath: String, val text: String, val blocks: List<ScannedTextBlock>)

/**
 * Turns a shared image (a screenshot of a WhatsApp message, most likely — see [PendingShare])
 * into the same (permanent file path, OCR text, OCR blocks) shape a camera scan produces, so
 * ManageQuestionsScreen can feed it into its existing scan-session pipeline without needing to
 * know or care whether a photo came from the camera or from another app's share sheet.
 *
 * Deliberately a separate, self-contained function rather than a refactor of
 * [rememberPhotoScanLauncher]'s own OCR step: that one is a working, tested camera-scan code
 * path — callback-based and tied to a Compose activity-result launcher. This one is
 * suspend-based (fits naturally into ManageQuestionsScreen's own `coroutineScope.launch`) and
 * runs on an arbitrary `content://` Uri handed in by another app, not a file this app just
 * captured itself. Keeping them separate avoids risking the camera flow while adding this one.
 *
 * The shared Uri's read permission is only guaranteed for the lifetime of the intent that
 * delivered it (Android's per-Uri grant), so this copies the image into the app's own permanent
 * storage — the same `question_images/` folder a camera scan's photo ends up in — before doing
 * anything else with it, rather than risking OCR (or a later crop) running against a Uri that
 * might not be readable anymore by the time it's needed.
 *
 * Returns null if the Uri can't be opened, copied, or produces no recognizable text (mirrors
 * PhotoScanCapture's "couldn't find readable text" case) — the caller should show the same kind
 * of message it already shows for a failed camera scan rather than treating this as a crash.
 */
suspend fun importSharedImage(context: Context, sourceUri: Uri): SharedImageImport? {
    val savedPath = runCatching {
        val dir = File(context.filesDir, "question_images").apply { mkdirs() }
        val dest = File(dir, "shared_${System.currentTimeMillis()}.jpg")
        val input = context.contentResolver.openInputStream(sourceUri) ?: return null
        input.use { stream -> FileOutputStream(dest).use { output -> stream.copyTo(output) } }
        dest.absolutePath
    }.getOrNull() ?: return null

    val image = runCatching {
        InputImage.fromFilePath(context, Uri.fromFile(File(savedPath)))
    }.getOrNull() ?: return null

    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val result = suspendCancellableCoroutine<Text?> { cont ->
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    } ?: return null

    val text = result.text.trim()
    if (text.isBlank()) return null

    val blocks = result.textBlocks.mapNotNull { block ->
        val box = block.boundingBox ?: return@mapNotNull null
        val blockText = block.text.trim()
        if (blockText.isBlank()) return@mapNotNull null
        ScannedTextBlock(savedPath, blockText, box.left, box.top, box.right, box.bottom)
    }
    return SharedImageImport(savedPath, text, blocks)
}
