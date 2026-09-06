package com.livingai.app.ai.inference

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DEVELOPMENT-ONLY STUB. Does not run any model, on-device or otherwise — it returns a
 * canned string after a fake delay so UI can be built before the real model is wired up.
 *
 * This must never be reachable from the production/demo path. [com.livingai.app.ai.routing.InferencePolicy]
 * only selects this when [com.livingai.app.ai.routing.AiDevFlags.USE_MOCK_TEXT_MODEL] is
 * explicitly true, and every response is prefixed so it can never be mistaken for a real
 * inference result on screen or in logs.
 */
class MockLocalTextModel : LocalTextModel {
    override val modelName = "MOCK (no model — dev stub)"
    override val runtimeName = "none"

    private val _status = MutableStateFlow(ModelStatus(ModelLoadState.READY))
    override val status: StateFlow<ModelStatus> = _status.asStateFlow()

    override suspend fun initialize() = Unit
    override suspend fun downloadAndInitialize(hfToken: String) = Unit

    override suspend fun generate(prompt: String): Result<GenerationResult> {
        delay(400)
        return Result.success(
            GenerationResult(
                text = "[DEV MOCK — NOT REAL AI INFERENCE] I can't actually reason about this yet.",
                inferenceMs = 400
            )
        )
    }

    override fun cancel() = Unit
    override fun close() = Unit
}
