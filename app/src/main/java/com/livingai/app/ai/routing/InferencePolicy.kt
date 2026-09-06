package com.livingai.app.ai.routing

import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.core.RuntimeStatus

/**
 * Pure decision function: given device performance state and model readiness, should we run
 * the local LLM at all right now? Kept separate from [InferenceRouter] so it's trivially
 * unit-testable without a real model or device.
 */
class InferencePolicy {

    fun selectTier(runtimeStatus: RuntimeStatus, modelState: ModelLoadState): ModelTier {
        if (runtimeStatus == RuntimeStatus.RED) return ModelTier.RULE
        if (modelState != ModelLoadState.READY) return ModelTier.RULE
        return ModelTier.LOCAL_TEXT
    }

    fun degradedMessage(runtimeStatus: RuntimeStatus, modelState: ModelLoadState): String = when {
        runtimeStatus == RuntimeStatus.RED -> "My brain needs to rest for a bit — your phone's battery/thermal state is too low right now."
        modelState == ModelLoadState.NOT_DOWNLOADED -> "I haven't downloaded my offline brain yet — head to Settings to grab it."
        modelState == ModelLoadState.DOWNLOADING -> "Still downloading my offline brain — one sec."
        modelState == ModelLoadState.LOADING -> "Preparing Lumi's brain…"
        modelState == ModelLoadState.FAILED -> "My offline brain failed to load. Check Settings."
        else -> "I can't think right now."
    }
}
