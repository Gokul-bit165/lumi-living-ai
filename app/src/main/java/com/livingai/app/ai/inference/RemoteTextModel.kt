package com.livingai.app.ai.inference

/**
 * A hosted (OpenAI-compatible chat completions) API used ONLY as a last-resort fallback when the
 * real local model can't run right now (thermal/battery RED, or the model isn't downloaded yet).
 * This is never the primary path and must never be logged or reported as "local" — see
 * [com.livingai.app.ai.model.ModelTier.REMOTE_FALLBACK].
 */
enum class RemoteProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String>
) {
    OPENROUTER(
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "meta-llama/llama-3.1-8b-instruct:free",
        suggestedModels = listOf(
            "meta-llama/llama-3.1-8b-instruct:free",
            "google/gemini-2.0-flash-exp:free",
            "mistralai/mistral-7b-instruct:free"
        )
    ),
    GROQ(
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "openai/gpt-oss-120b",
        suggestedModels = listOf(
            "openai/gpt-oss-120b",
            "allam-2-7b"
        )
    )
}

data class RemoteAiSettings(
    val provider: RemoteProvider = RemoteProvider.OPENROUTER,
    val apiKey: String = "",
    val modelId: String = ""
) {
    val cleanApiKey: String get() = apiKey.trim()
    val isConfigured: Boolean get() = cleanApiKey.isNotBlank()
    fun effectiveModelId(): String {
        val trimmed = modelId.trim()
        if (trimmed.isBlank()) return provider.defaultModel
        if (provider == RemoteProvider.GROQ && (trimmed.startsWith("llama-3.1-") || trimmed.startsWith("llama-3.3-"))) {
            return provider.defaultModel
        }
        return trimmed
    }
}

interface RemoteTextModel {
    val runtimeName: String
    suspend fun isConfigured(): Boolean
    suspend fun generate(prompt: String): Result<GenerationResult>
}
