package com.livingai.app.ai.vision

import android.graphics.Bitmap

/** Real boundary: swap the implementation to change vision backend without touching callers. */
interface LocalVisionModel {
    val modelName: String
    suspend fun extractText(bitmap: Bitmap): Result<String>
}
