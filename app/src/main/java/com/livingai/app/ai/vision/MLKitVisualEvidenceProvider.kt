package com.livingai.app.ai.vision

import android.graphics.Bitmap

/**
 * Current implementation of [VisualUnderstandingProvider].
 *
 * Uses ML Kit Text Recognition v2 + Image Labeling as lightweight candidate signals.
 * Raw pixels are not directly inspected by a neural vision-language model.
 */
class MLKitVisualEvidenceProvider(
    delegate: LocalVisionModel? = null
) : VisualUnderstandingProvider {
    private val visionDelegate: LocalVisionModel by lazy { delegate ?: MlKitLocalVisionModel() }

    override val providerName: String = "ML Kit OCR + Lightweight Image Labeling"

    override suspend fun analyze(bitmap: Bitmap): VisualEvidence {
        return visionDelegate.analyze(bitmap)
    }
}
