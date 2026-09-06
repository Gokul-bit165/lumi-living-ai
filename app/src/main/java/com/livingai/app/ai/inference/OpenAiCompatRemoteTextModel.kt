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
            val url = URL(settings.provider.baseUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LivingAI-Android/1.0")
                setRequestProperty("Authorization", "Bearer $apiKey")
                if (settings.provider == RemoteProvider.OPENROUTER) {
                    setRequestProperty("HTTP-Referer", "https://github.com/livingai/livingai")
                    setRequestProperty("X-Title", "Living AI")
                }
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }

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

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

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
                return@withContext Result.failure(RuntimeException("HTTP $responseCode: $errMsg"))
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
}
