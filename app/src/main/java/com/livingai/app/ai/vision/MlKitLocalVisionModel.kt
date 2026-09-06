package com.livingai.app.ai.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * REAL on-device OCR via ML Kit Text Recognition. Genuinely local: the recognizer model ships
 * with (or is silently fetched once by) Google Play services and runs fully offline afterward —
 * no account, license click, or manual download step, unlike the LLM in [com.livingai.app.ai.inference.MediaPipeLocalTextModel].
 */
class MlKitLocalVisionModel : LocalVisionModel {
    override val modelName = "ML Kit Text Recognition v2 (Latin)"

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun extractText(bitmap: Bitmap): Result<String> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result -> cont.resume(Result.success(result.text)) }
            .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
    }
}
