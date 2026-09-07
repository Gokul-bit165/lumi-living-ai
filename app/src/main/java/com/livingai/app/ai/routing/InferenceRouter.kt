package com.livingai.app.ai.routing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.livingai.app.ai.inference.LocalTextModel
import com.livingai.app.ai.inference.RemoteTextModel
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIResponse
import com.livingai.app.ai.model.CompanionEmotion
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.ai.prompts.PromptBuilder
import com.livingai.app.ai.vision.LocalVisionModel
import com.livingai.app.core.LivingAiLog
import com.livingai.app.core.RuntimeStatus
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The one place that knows about the vision model, the real local text model, and the cloud
 * fallback model. Everything else in the app only ever calls [route] with an [AIRequest] and
 * gets back an [AIResponse] — never touches MediaPipe/ML Kit/HTTP types directly.
 *
 * Takes [RuntimeStatus] and network-availability suppliers rather than concrete monitors so this
 * class is unit-testable without a real device.
 */
class InferenceRouter(
    private val textModel: LocalTextModel,
    private val visionModel: LocalVisionModel,
    private val remoteModel: RemoteTextModel? = null,
    private val policy: InferencePolicy = InferencePolicy(),
    private val networkAvailable: () -> Boolean = { false },
    private val imageAnalyzer: (suspend (ByteArray) -> com.livingai.app.ai.vision.VisualEvidence)? = null,
    private val runtimeStatus: () -> RuntimeStatus
) {
    private val _lastResponse = kotlinx.coroutines.flow.MutableStateFlow<AIResponse?>(null)
    val lastResponse: kotlinx.coroutines.flow.StateFlow<AIResponse?> = _lastResponse.asStateFlow()

    suspend fun route(request: AIRequest): AIResponse {
        val runtimeStatus = runtimeStatus()
        val modelState = textModel.status.value.state
        val remoteConfigured = remoteModel?.isConfigured() ?: false
        val tier = policy.selectTier(runtimeStatus, modelState, remoteConfigured, networkAvailable())

        if (tier == ModelTier.RULE) {
            val message = policy.degradedMessage(runtimeStatus, modelState)
            LivingAiLog.event("INFERENCE_REQUEST", "tier=RULE (degraded) reason=$message")
            val degraded = AIResponse(
                text = message,
                emotion = CompanionEmotion.CONFUSED,
                tier = ModelTier.RULE,
                wasStructured = false,
                loadMs = 0,
                inferenceMs = 0
            )
            _lastResponse.value = degraded
            return degraded
        }

        val loadStart = System.currentTimeMillis()
        var visualEvidence: com.livingai.app.ai.vision.VisualEvidence? = null
        if (request.imageBytes != null) {
            visualEvidence = if (imageAnalyzer != null) {
                imageAnalyzer.invoke(request.imageBytes)
            } else {
                val bitmap = runCatching {
                    BitmapFactory.decodeByteArray(request.imageBytes, 0, request.imageBytes.size)
                }.getOrNull()
                if (bitmap != null) {
                    (visionModel as? com.livingai.app.ai.vision.VisualUnderstandingProvider)?.analyze(bitmap)
                        ?: runLegacyVision(bitmap)
                } else {
                    com.livingai.app.ai.vision.VisualEvidence(confidenceLevel = com.livingai.app.ai.vision.VisualEvidenceCategory.NO_RELIABLE_EVIDENCE)
                }
            }
            LivingAiLog.event(
                "INFERENCE_VISION",
                "category=${visualEvidence.confidenceLevel} ocrMs=${visualEvidence.ocrLatencyMs} labelMs=${visualEvidence.labelLatencyMs} textChars=${visualEvidence.ocrText?.length ?: 0} labels=${visualEvidence.labels}"
            )
        }
        val loadMs = System.currentTimeMillis() - loadStart

        // Deterministic fast path for narrow identity queries with high-confidence detections (bypassing 7-9s Gemma)
        if (visualEvidence != null && isDirectObjectIdentityQuery(request.userText)) {
            val strongDetections = visualEvidence.detections.filter { it.confidence >= 0.75f }
            if (strongDetections.isNotEmpty() && strongDetections.size <= 2) {
                val labels = strongDetections.map { it.label.replaceFirstChar { c -> c.uppercase() } }.distinct()
                val answerText = if (labels.size == 1) {
                    val label = labels.first()
                    val article = if (label.first().lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "An" else "A"
                    "$article $label."
                } else {
                    "${labels.joinToString(" and ")}."
                }
                LivingAiLog.event("INFERENCE_FAST_PATH", "Direct identity answer: $answerText (bypassing Gemma)")
                val fastResponse = AIResponse(
                    text = answerText,
                    emotion = CompanionEmotion.EXPLAINING,
                    tier = ModelTier.LOCAL_TEXT,
                    wasStructured = false,
                    loadMs = loadMs,
                    inferenceMs = 0L,
                    visualEvidence = visualEvidence
                )
                _lastResponse.value = fastResponse
                return fastResponse
            }
        }

        val prompt = PromptBuilder.build(request, visualEvidence, relevantMemory = emptyList())

        val result = if (tier == ModelTier.REMOTE_FALLBACK) {
            routeRemote(prompt, loadMs, runtimeStatus, modelState, visualEvidence)
        } else {
            routeLocal(prompt, loadMs, visualEvidence)
        }
        _lastResponse.value = result
        return result
    }

    private fun isDirectObjectIdentityQuery(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase().trim().removeSuffix("?").removeSuffix(".")
        return lower in setOf(
            "what object is this",
            "what is this",
            "what's this",
            "what is this object",
            "what is that",
            "what object is that",
            "identify this object",
            "identify this"
        )
    }

    private suspend fun routeLocal(prompt: String, loadMs: Long, visualEvidence: com.livingai.app.ai.vision.VisualEvidence? = null): AIResponse {
        LivingAiLog.event("INFERENCE_REQUEST", "tier=LOCAL_TEXT model=${textModel.modelName} promptChars=${prompt.length}")
        val result = textModel.generate(prompt)
        return result.fold(
            onSuccess = { generation ->
                val (text, emotion, structured) = parseStructured(generation.text)
                LivingAiLog.event("INFERENCE_RESULT", "tier=LOCAL_TEXT SUCCESS inferenceMs=${generation.inferenceMs} structured=$structured")
                AIResponse(text, emotion, ModelTier.LOCAL_TEXT, structured, loadMs, generation.inferenceMs, visualEvidence)
            },
            onFailure = { error ->
                LivingAiLog.event("INFERENCE_RESULT", "tier=LOCAL_TEXT FAILED ${error.message}")
                AIResponse(
                    text = "Something went wrong on my end — mind trying again?",
                    emotion = CompanionEmotion.CONFUSED,
                    tier = ModelTier.LOCAL_TEXT,
                    wasStructured = false,
                    loadMs = loadMs,
                    inferenceMs = 0,
                    visualEvidence = visualEvidence
                )
            }
        )
    }

    /**
     * Only reached when [InferencePolicy] has already determined the real local model can't run
     * right now. Every log line and every [AIResponse.tier] here says REMOTE_FALLBACK, never
     * LOCAL_TEXT — this is a cloud API call using the user's own key, not on-device inference.
     */
    private suspend fun routeRemote(
        prompt: String,
        loadMs: Long,
        runtimeStatus: RuntimeStatus,
        modelState: com.livingai.app.ai.inference.ModelLoadState,
        visualEvidence: com.livingai.app.ai.vision.VisualEvidence? = null
    ): AIResponse {
        val remote = remoteModel ?: return AIResponse(
            text = policy.degradedMessage(runtimeStatus, modelState),
            emotion = CompanionEmotion.CONFUSED,
            tier = ModelTier.RULE,
            wasStructured = false,
            loadMs = 0,
            inferenceMs = 0,
            visualEvidence = visualEvidence
        )
        LivingAiLog.event("INFERENCE_REQUEST", "tier=REMOTE_FALLBACK runtime=${remote.runtimeName} promptChars=${prompt.length}")
        val result = remote.generate(prompt)
        return result.fold(
            onSuccess = { generation ->
                val (text, emotion, structured) = parseStructured(generation.text)
                LivingAiLog.event("INFERENCE_RESULT", "tier=REMOTE_FALLBACK SUCCESS inferenceMs=${generation.inferenceMs} structured=$structured")
                AIResponse(text, emotion, ModelTier.REMOTE_FALLBACK, structured, loadMs, generation.inferenceMs, visualEvidence)
            },
            onFailure = { error ->
                LivingAiLog.event("INFERENCE_RESULT", "tier=REMOTE_FALLBACK FAILED ${error.javaClass.simpleName}: ${error.message}")
                val userFacingText = when {
                    error.message?.contains("401") == true ->
                        "Cloud fallback authentication failed (401) — please verify your ${remote.runtimeName} key in AI brain settings."
                    error.message?.contains("429") == true ->
                        "Cloud fallback rate limit reached (429) — please try again in a few seconds."
                    error.message?.contains("404") == true -> {
                        val detail = error.message?.removePrefix("HTTP 404:")?.trim()?.takeIf { it.isNotBlank() }
                        if (detail != null) {
                            "Cloud fallback model not found (404: $detail) — please check model ID in AI brain settings."
                        } else {
                            "Cloud fallback model not found (404) — please verify the model ID in AI brain settings."
                        }
                    }
                    !error.message.isNullOrBlank() ->
                        "Cloud fallback error (${error.message}) — please try again."
                    else ->
                        "I couldn't reach my cloud fallback — mind trying again in a moment?"
                }
                AIResponse(
                    text = userFacingText,
                    emotion = CompanionEmotion.CONFUSED,
                    tier = ModelTier.REMOTE_FALLBACK,
                    wasStructured = false,
                    loadMs = loadMs,
                    inferenceMs = 0,
                    visualEvidence = visualEvidence
                )
            }
        )
    }

    private suspend fun runLegacyVision(bitmap: Bitmap): com.livingai.app.ai.vision.VisualEvidence {
        val ocrStart = System.currentTimeMillis()
        val text = visionModel.extractText(bitmap).getOrNull()?.takeIf { it.isNotBlank() }
        val ocrMs = System.currentTimeMillis() - ocrStart

        val labelStart = System.currentTimeMillis()
        val labels = visionModel.extractLabels(bitmap).getOrDefault(emptyList())
        val labelMs = System.currentTimeMillis() - labelStart

        val labelMap = labels.associate { str ->
            val name = str.substringBefore(" (").trim()
            val conf = runCatching {
                str.substringAfter(" (").substringBefore("%)").toFloat() / 100f
            }.getOrDefault(0.70f)
            name to conf
        }

        return com.livingai.app.ai.vision.VisualEvidencePolicy().evaluate(
            ocrText = text,
            rawLabelConfidences = labelMap,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            ocrLatencyMs = ocrMs,
            labelLatencyMs = labelMs
        )
    }

    /** Small local models (and some remote ones) are unreliable at strict JSON — parse leniently, never crash on it. */
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
