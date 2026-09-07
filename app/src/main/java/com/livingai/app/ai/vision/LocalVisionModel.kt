package com.livingai.app.ai.vision

import android.graphics.Bitmap

/** Real boundary: swap the implementation to change vision backend without touching callers. */
interface LocalVisionModel : VisualUnderstandingProvider {
    val modelName: String
    override val providerName: String get() = modelName

    suspend fun extractText(bitmap: Bitmap): Result<String>
    suspend fun extractLabels(bitmap: Bitmap, minConfidence: Float = 0.70f): Result<List<String>> = Result.success(emptyList())
    suspend fun extractLabelConfidences(bitmap: Bitmap): Result<Map<String, Float>> = Result.success(emptyMap())

    override suspend fun analyze(bitmap: Bitmap): VisualEvidence {
        val ocrStart = System.currentTimeMillis()
        val text = extractText(bitmap).getOrNull()
        val ocrMs = System.currentTimeMillis() - ocrStart

        val labelStart = System.currentTimeMillis()
        val labels = extractLabelConfidences(bitmap).getOrDefault(emptyMap())
        val labelMs = System.currentTimeMillis() - labelStart

        return VisualEvidencePolicy().evaluate(
            ocrText = text,
            rawLabelConfidences = labels,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            ocrLatencyMs = ocrMs,
            labelLatencyMs = labelMs
        )
    }
}

