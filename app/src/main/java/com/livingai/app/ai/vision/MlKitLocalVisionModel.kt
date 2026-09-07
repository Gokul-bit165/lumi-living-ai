package com.livingai.app.ai.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * REAL on-device Multimodal Vision via Google ML Kit:
 * 1. Text Recognition v2 (Latin): genuinely local OCR for study notes, book pages, math, and code.
 * 2. Image Labeling (Default): genuinely local classifier identifying 400+ physical object categories.
 *
 * Runs 100% offline with zero remote calls. Labels are filtered by >= 70% confidence threshold to
 * avoid feeding noisy or speculative guesses to the local LLM.
 */
class MlKitLocalVisionModel(
    private val policy: VisualEvidencePolicy = VisualEvidencePolicy()
) : LocalVisionModel {
    override val modelName = "ML Kit Text Recognition v2 + Image Labeling"

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.50f)
            .build()
    )

    override suspend fun extractText(bitmap: Bitmap): Result<String> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { result -> cont.resume(Result.success(result.text)) }
            .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
    }

    override suspend fun extractLabelConfidences(bitmap: Bitmap): Result<Map<String, Float>> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        imageLabeler.process(image)
            .addOnSuccessListener { labels ->
                val map = labels.associate { it.text to it.confidence }
                cont.resume(Result.success(map))
            }
            .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
    }

    override suspend fun extractLabels(bitmap: Bitmap, minConfidence: Float): Result<List<String>> =
        extractLabelConfidences(bitmap).map { map ->
            map.filter { it.value >= minConfidence }
                .map { "${it.key} (${(it.value * 100).toInt()}%)" }
        }

    override suspend fun analyze(bitmap: Bitmap): VisualEvidence {
        val ocrStart = System.currentTimeMillis()
        val textResult = extractText(bitmap)
        val ocrMs = System.currentTimeMillis() - ocrStart

        val labelStart = System.currentTimeMillis()
        val labelResult = extractLabelConfidences(bitmap)
        val labelMs = System.currentTimeMillis() - labelStart

        val text = textResult.getOrNull()
        val labelMap = labelResult.getOrDefault(emptyMap())

        return policy.evaluate(
            ocrText = text,
            rawLabelConfidences = labelMap,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            ocrLatencyMs = ocrMs,
            labelLatencyMs = labelMs
        )
    }
}
