package com.livingai.app.ai.inference

import kotlinx.coroutines.flow.StateFlow

enum class ModelLoadState { NOT_DOWNLOADED, DOWNLOADING, LOADING, READY, FAILED }

data class ModelStatus(
    val state: ModelLoadState,
    val downloadProgressBytes: Long = 0L,
    val downloadTotalBytes: Long = -1L,
    val errorMessage: String? = null
)

/**
 * Real boundary: the rest of the app never imports MediaPipe/TFLite types directly, only this
 * interface. [MediaPipeLocalTextModel] is the real, production/demo implementation.
 * [MockLocalTextModel] exists ONLY for development when no model file is present and is never
 * used unless explicitly selected via [com.livingai.app.ai.routing.InferencePolicy] dev flag.
 */
interface LocalTextModel {
    val status: StateFlow<ModelStatus>
    val modelName: String
    val runtimeName: String

    /** Loads the model into memory if a downloaded file exists. No-op if already loaded. */
    suspend fun initialize()

    /** Downloads the model file from Hugging Face using a user-supplied token, then initializes it. */
    suspend fun downloadAndInitialize(hfToken: String)

    /** Runs real local inference. Returns the raw model text and how long load/inference took. */
    suspend fun generate(prompt: String): Result<GenerationResult>

    /** Best-effort cancellation of an in-flight generate() call. See implementation note on limits. */
    fun cancel()

    fun close()
}

data class GenerationResult(val text: String, val inferenceMs: Long)
