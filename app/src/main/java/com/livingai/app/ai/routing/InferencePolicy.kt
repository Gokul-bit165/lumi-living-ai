package com.livingai.app.ai.routing

import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.core.RuntimeStatus

/**
 * Pure decision function: given device performance state, model readiness, and whether a cloud
 * fallback is configured/reachable, which tier should actually run this request? Kept separate
 * from [InferenceRouter] so it's trivially unit-testable without a real model or device.
 *
 * The real local model is always preferred. REMOTE_FALLBACK is only ever chosen when the local
 * model genuinely cannot run right now (thermal/battery RED, or the model isn't ready) AND the
 * user has configured their own cloud API key AND the device currently has network — never as a
 * silent replacement for local inference.
 */
class InferencePolicy {

    fun selectTier(
        runtimeStatus: RuntimeStatus,
        modelState: ModelLoadState,
        remoteConfigured: Boolean = false,
        networkAvailable: Boolean = false
    ): ModelTier {
        val localUsable = runtimeStatus != RuntimeStatus.RED && modelState == ModelLoadState.READY
        if (localUsable) return ModelTier.LOCAL_TEXT
        if (remoteConfigured && networkAvailable) return ModelTier.REMOTE_FALLBACK
        return ModelTier.RULE
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
