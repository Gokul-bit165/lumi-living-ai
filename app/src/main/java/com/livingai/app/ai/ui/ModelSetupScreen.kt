package com.livingai.app.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.inference.ModelStatus
import com.livingai.app.ai.inference.RemoteAiSettings
import com.livingai.app.ai.inference.RemoteProvider

@Composable
fun ModelSetupScreen(
    status: ModelStatus,
    modelName: String,
    runtimeName: String,
    onDownload: (token: String) -> Unit,
    remoteSettings: RemoteAiSettings,
    onSaveRemoteSettings: (RemoteAiSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var token by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("< Home") }

        Text(text = "Lumi's offline brain", style = MaterialTheme.typography.headlineSmall)
        Text(text = "Model: $modelName")
        Text(text = "Runtime: $runtimeName")

        when (status.state) {
            ModelLoadState.NOT_DOWNLOADED -> {
                Text(
                    "Lumi needs her offline AI model. Download once (Google's Gemma 3 1B, " +
                        "a few hundred MB) — then she can answer camera and voice questions fully offline."
                )
                Text("Paste a Hugging Face access token with read access (create one free at huggingface.co/settings/tokens, " +
                    "after accepting the Gemma license at huggingface.co/litert-community/Gemma3-1B-IT).")
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Hugging Face token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { onDownload(token) }, enabled = token.isNotBlank()) {
                    Text("Download model")
                }
            }

            ModelLoadState.DOWNLOADING -> {
                val progress = if (status.downloadTotalBytes > 0) {
                    status.downloadProgressBytes.toFloat() / status.downloadTotalBytes.toFloat()
                } else null
                Text("Downloading…")
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${status.downloadProgressBytes / (1024 * 1024)} MB / ${status.downloadTotalBytes / (1024 * 1024)} MB")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("${status.downloadProgressBytes / (1024 * 1024)} MB downloaded")
                }
            }

            ModelLoadState.LOADING -> Text("Preparing Lumi's brain…")

            ModelLoadState.READY -> Text("Ready — Lumi can now answer fully offline.")

            ModelLoadState.FAILED -> {
                Text("Download/load failed: ${status.errorMessage ?: "unknown error"}")
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Hugging Face token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { onDownload(token) }, enabled = token.isNotBlank()) {
                    Text("Retry download")
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        RemoteFallbackSection(remoteSettings = remoteSettings, onSave = onSaveRemoteSettings)
    }
}

@Composable
private fun RemoteFallbackSection(
    remoteSettings: RemoteAiSettings,
    onSave: (RemoteAiSettings) -> Unit
) {
    var provider by remember(remoteSettings.provider) { mutableStateOf(remoteSettings.provider) }
    var apiKey by remember { mutableStateOf(remoteSettings.apiKey) }
    var modelId by remember(remoteSettings.modelId) { mutableStateOf(remoteSettings.modelId) }

    Text(text = "Cloud fallback (optional)", style = MaterialTheme.typography.titleMedium)
    Text(
        "Only used when Lumi's offline brain can't run right now (e.g. the phone is too hot, or " +
            "the model isn't downloaded yet) AND you're online. Never the primary path — every " +
            "answer from this is clearly tagged REMOTE_FALLBACK in the debug panel, never \"local\"."
    )
    Text(if (remoteSettings.isConfigured) "Status: configured (${remoteSettings.provider.displayName})" else "Status: not configured")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteProvider.entries.forEach { p ->
            FilterChip(
                selected = provider == p,
                onClick = {
                    if (provider != p) {
                        provider = p
                        modelId = ""
                    }
                },
                label = { Text(p.displayName) }
            )
        }
    }

    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = { Text("${provider.displayName} API key") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = modelId,
        onValueChange = { modelId = it },
        label = { Text("Model id (default: ${provider.defaultModel})") },
        modifier = Modifier.fillMaxWidth()
    )

    Text("Quick select model:", style = MaterialTheme.typography.labelSmall)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        provider.suggestedModels.forEach { suggested ->
            SuggestionChip(
                onClick = { modelId = suggested },
                label = { Text(suggested, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onSave(RemoteAiSettings(provider = provider, apiKey = apiKey, modelId = modelId)) },
            enabled = apiKey.isNotBlank()
        ) { Text("Save cloud fallback") }

        if (remoteSettings.isConfigured) {
            TextButton(onClick = {
                apiKey = ""
                modelId = ""
                onSave(RemoteAiSettings())
            }) { Text("Clear") }
        }
    }
}
