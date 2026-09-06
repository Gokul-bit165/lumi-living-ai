package com.livingai.app.ai.inference

import com.livingai.app.ai.settings.RemoteAiSettingsRepository
import com.livingai.app.core.LivingAiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real cloud fallback: talks to whichever OpenAI-compatible chat-completions endpoint the user
 * configured (OpenRouter or Groq) using their own API key, entered in the app's own Settings UI
 * and never seen by anyone else. Only used when [com.livingai.app.ai.routing.InferencePolicy]
 * decides the real local model can't run right now — this is a fallback, not the primary path,
 * and every response through this class is tagged [com.livingai.app.ai.model.ModelTier.REMOTE_FALLBACK],
 * never "local".
 */
class OpenAiCompatRemoteTextModel(
    private val settingsRepository: RemoteAiSettingsRepository
) : RemoteTextModel {

    override val runtimeName: String
        get() = "${settingsRepository.current().provider.displayName} (cloud fallback API)"

    override suspend fun isConfigured(): Boolean = settingsRepository.current().isConfigured

    override suspend fun generate(prompt: String): Result<GenerationResult> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        val apiKey = settings.cleanApiKey
        if (!settings.isConfigured) {
            return@withContext Result.failure(IllegalStateException("No cloud fallback API key configured"))
        }

        val start = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            val keyPrefix = apiKey.take(6) + "..."
            LivingAiLog.event("REMOTE_FALLBACK", "Starting request provider=${settings.provider.displayName} key=$keyPrefix len=${apiKey.length}")

            val targetModel = settings.effectiveModelId()
            LivingAiLog.event("REMOTE_FALLBACK", "POST url=${settings.provider.baseUrl} model=$targetModel")

            val body = JSONObject().apply {
                put("model", targetModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val jsonBytes = body.toString().toByteArray(Charsets.UTF_8)
            val url = URL(settings.provider.baseUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LivingAI-Android/1.0")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Connection", "close")
                if (settings.provider == RemoteProvider.OPENROUTER) {
                    setRequestProperty("HTTP-Referer", "https://github.com/livingai/livingai")
                    setRequestProperty("X-Title", "Living AI")
                }
                doOutput = true
                setFixedLengthStreamingMode(jsonBytes.size)
                connectTimeout = 15_000
                readTimeout = 20_000
            }

            connection.outputStream.use { it.write(jsonBytes) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }.orEmpty()

            if (responseCode !in 200..299) {
                val errMsg = try {
                    val errObj = JSONObject(raw).optJSONObject("error")
                    errObj?.optString("message") ?: raw.take(200)
                } catch (_: Exception) {
                    raw.take(200)
                }
                LivingAiLog.event("REMOTE_FALLBACK", "HTTP $responseCode: $errMsg | raw=$raw")

                val extraInfo = if (responseCode == 404) {
                    val available = fetchAvailableModels(apiKey, settings.provider)
                    if (available.isNotEmpty()) {
                        " | Available models on your key: ${available.take(5).joinToString(", ")}"
                    } else ""
                } else ""

                return@withContext Result.failure(RuntimeException("HTTP $responseCode: $errMsg$extraInfo"))
            }

            val json = JSONObject(raw)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                LivingAiLog.event("REMOTE_FALLBACK", "Empty choices: $raw")
                return@withContext Result.failure(RuntimeException("Empty response choices from ${settings.provider.displayName}"))
            }

            val content = choices
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val inferenceMs = System.currentTimeMillis() - start
            Result.success(GenerationResult(text = content, inferenceMs = inferenceMs))
        } catch (e: Exception) {
            LivingAiLog.event("REMOTE_FALLBACK", "FAILED ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchAvailableModels(apiKey: String, provider: RemoteProvider): List<String> {
        val modelsUrl = if (provider == RemoteProvider.GROQ) {
            "https://api.groq.com/openai/v1/models"
        } else {
            "https://openrouter.ai/api/v1/models"
        }
        return try {
            val conn = (URL(modelsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("User-Agent", "LivingAI-Android/1.0")
                setRequestProperty("Connection", "close")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }.orEmpty()
            conn.disconnect()
            LivingAiLog.event("REMOTE_FALLBACK", "GET $modelsUrl -> HTTP $code: ${text.take(300)}")
            if (code in 200..299) {
                val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.optString("id") }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            LivingAiLog.event("REMOTE_FALLBACK", "Failed to fetch models: ${e.message}")
            emptyList()
        }
    }
}
