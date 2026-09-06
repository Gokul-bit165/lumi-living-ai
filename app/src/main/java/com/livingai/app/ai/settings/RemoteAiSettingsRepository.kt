package com.livingai.app.ai.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.livingai.app.ai.inference.RemoteAiSettings
import com.livingai.app.ai.inference.RemoteProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the user's own cloud-fallback API key (OpenRouter or Groq) using
 * EncryptedSharedPreferences (Android Keystore-backed) rather than plain DataStore, since unlike
 * the one-shot Hugging Face token, this key is meant to persist on the device.
 */
class RemoteAiSettingsRepository(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "living_ai_remote_ai_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RemoteAiSettings> = _settings.asStateFlow()

    fun current(): RemoteAiSettings = _settings.value

    fun save(settings: RemoteAiSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL_ID, settings.modelId)
            .apply()
        _settings.value = settings
    }

    fun clear() {
        prefs.edit().clear().apply()
        _settings.value = RemoteAiSettings()
    }

    private fun load(): RemoteAiSettings {
        val providerName = prefs.getString(KEY_PROVIDER, null)
        val provider = providerName?.let { runCatching { RemoteProvider.valueOf(it) }.getOrNull() } ?: RemoteProvider.OPENROUTER
        return RemoteAiSettings(
            provider = provider,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            modelId = prefs.getString(KEY_MODEL_ID, "") ?: ""
        )
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL_ID = "model_id"
    }
}
