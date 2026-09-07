package com.livingai.app.ai.model

/** RULE never reaches the LLM at all — it's what the Focus Guardian always uses. */
enum class ModelTier { RULE, LOCAL_VISION, LOCAL_TEXT, REMOTE_FALLBACK }

enum class AIRequestType { CAMERA_QUESTION, VOICE_QUESTION, TEXT_QUESTION }

data class AIRequest(
    val type: AIRequestType,
    val userText: String? = null,
    val imageBytes: ByteArray? = null,
    val goalTitle: String? = null,
    val focusActive: Boolean = false
)

enum class CompanionEmotion { THINKING, EXPLAINING, HAPPY, CONFUSED, WARNING }

/** Structured shape we *ask* the local model for. Never trust it blindly — see [AIResponse.wasStructured]. */
data class AIResponse(
    val text: String,
    val emotion: CompanionEmotion,
    val tier: ModelTier,
    val wasStructured: Boolean,
    val loadMs: Long,
    val inferenceMs: Long,
    val visualEvidence: com.livingai.app.ai.vision.VisualEvidence? = null
)

sealed class AIError {
    data class ModelNotReady(val reason: String) : AIError()
    data class InferenceFailed(val reason: String) : AIError()
    object DegradedPerformance : AIError()
    object Cancelled : AIError()
}
