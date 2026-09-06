package com.livingai.app.ai.routing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.livingai.app.ai.inference.LocalTextModel
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIResponse
import com.livingai.app.ai.model.CompanionEmotion
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.ai.prompts.PromptBuilder
import com.livingai.app.ai.vision.LocalVisionModel
import com.livingai.app.core.LivingAiLog
import com.livingai.app.core.RuntimeStatus
import org.json.JSONObject

/**
 * The one place that knows about both the vision and text models. Everything else in the app
 * only ever calls [route] with an [AIRequest] and gets back an [AIResponse] — never touches
 * MediaPipe/ML Kit types directly.
 *
 * Takes a [RuntimeStatus] supplier rather than [com.livingai.app.core.PerformanceMonitor]
 * directly so this class is unit-testable without a real battery/thermal monitor.
 */
class InferenceRouter(
    private val textModel: LocalTextModel,
    private val visionModel: LocalVisionModel,
    private val policy: InferencePolicy = InferencePolicy(),
    private val runtimeStatus: () -> RuntimeStatus
) {
    suspend fun route(request: AIRequest): AIResponse {
        val runtimeStatus = runtimeStatus()
        val modelState = textModel.status.value.state
        val tier = policy.selectTier(runtimeStatus, modelState)

        if (tier == ModelTier.RULE) {
            val message = policy.degradedMessage(runtimeStatus, modelState)
            LivingAiLog.event("INFERENCE_REQUEST", "tier=RULE (degraded) reason=$message")
            return AIResponse(
                text = message,
                emotion = CompanionEmotion.CONFUSED,
                tier = ModelTier.RULE,
                wasStructured = false,
                loadMs = 0,
                inferenceMs = 0
            )
        }

        val loadStart = System.currentTimeMillis()
        var extractedText: String? = null
        if (request.imageBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(request.imageBytes, 0, request.imageBytes.size)
            if (bitmap != null) {
                extractedText = runVision(bitmap)
            }
        }
        val loadMs = System.currentTimeMillis() - loadStart

        val prompt = PromptBuilder.build(request, extractedText, relevantMemory = emptyList())
        LivingAiLog.event("INFERENCE_REQUEST", "tier=LOCAL_TEXT model=${textModel.modelName} promptChars=${prompt.length}")

        val result = textModel.generate(prompt)
        return result.fold(
            onSuccess = { generation ->
                val (text, emotion, structured) = parseStructured(generation.text)
                LivingAiLog.event("INFERENCE_RESULT", "SUCCESS inferenceMs=${generation.inferenceMs} structured=$structured")
                AIResponse(text, emotion, ModelTier.LOCAL_TEXT, structured, loadMs, generation.inferenceMs)
            },
            onFailure = { error ->
                LivingAiLog.event("INFERENCE_RESULT", "FAILED ${error.message}")
                AIResponse(
                    text = "Something went wrong on my end — mind trying again?",
                    emotion = CompanionEmotion.CONFUSED,
                    tier = ModelTier.LOCAL_TEXT,
                    wasStructured = false,
                    loadMs = loadMs,
                    inferenceMs = 0
                )
            }
        )
    }

    private suspend fun runVision(bitmap: Bitmap): String? =
        visionModel.extractText(bitmap).getOrNull()?.takeIf { it.isNotBlank() }

    /** Small local models are unreliable at strict JSON — parse leniently, never crash on it. */
    private fun parseStructured(raw: String): Triple<String, CompanionEmotion, Boolean> {
        return try {
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) throw IllegalArgumentException("no json")
            val json = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            val text = json.optString("response").takeIf { it.isNotBlank() } ?: raw.trim()
            val emotion = runCatching { CompanionEmotion.valueOf(json.optString("emotion").uppercase()) }
                .getOrDefault(CompanionEmotion.EXPLAINING)
            Triple(text, emotion, true)
        } catch (e: Exception) {
            Triple(raw.trim(), CompanionEmotion.EXPLAINING, false)
        }
    }
}
