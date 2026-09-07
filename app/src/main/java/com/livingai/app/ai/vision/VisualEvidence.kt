package com.livingai.app.ai.vision

import android.graphics.RectF

enum class VisualEvidenceCategory {
    STRONG_TEXT,
    STRONG_OBJECT,
    WEAK_OBJECT,
    NO_RELIABLE_EVIDENCE
}

data class VisualBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun centerY(): Float = (top + bottom) / 2f
    fun centerX(): Float = (left + right) / 2f
}

/**
 * Structured detection from an on-device pixel perception model.
 *
 * @property label Canonical object label (e.g. "laptop", "keyboard", "book", "cell phone", "person").
 * @property confidence Calibrated detector confidence score (0.0 to 1.0).
 * @property boundingBox Normalized or pixel spatial coordinates ([left, top, right, bottom]), if available.
 */
data class VisualDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: VisualBoundingBox? = null
)

/**
 * Structured visual evidence extracted from an image.
 *
 * Integrates:
 * 1. Pixel-level object detections from on-device local vision model.
 * 2. Deterministic visual scene description generated from verified detections.
 * 3. Exact OCR text from ML Kit Latin Text Recognition v2.
 * 4. Lightweight candidate labels for ambient context.
 */
data class VisualEvidence(
    val ocrText: String? = null,
    val detections: List<VisualDetection> = emptyList(),
    val labels: List<String> = emptyList(),
    val labelConfidences: Map<String, Float> = emptyMap(),
    val visualDescription: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val hasTextEvidence: Boolean = !ocrText.isNullOrBlank(),
    val hasObjectEvidence: Boolean = detections.isNotEmpty() || labels.isNotEmpty() || !visualDescription.isNullOrBlank(),
    val confidenceLevel: VisualEvidenceCategory = VisualEvidenceCategory.NO_RELIABLE_EVIDENCE,
    val ocrLatencyMs: Long = 0,
    val labelLatencyMs: Long = 0,
    val visionModelLatencyMs: Long = 0
)
