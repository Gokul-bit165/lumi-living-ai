package com.livingai.app.ai.vision

import android.graphics.Bitmap

/**
 * Clean abstraction for visual evidence extraction.
 *
 * Current implementation uses ML Kit Text Recognition v2 + Image Labeling.
 * In future phases, this provider can be swapped with a real local multimodal
 * vision model without altering Camera UI, InferenceRouter, Memory, Companion, Focus, or Voice.
 */
interface VisualUnderstandingProvider {
    val providerName: String
    suspend fun analyze(bitmap: Bitmap): VisualEvidence
}
