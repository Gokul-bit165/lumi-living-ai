package com.livingai.app.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun ModelSetupScreen(
    status: ModelStatus,
    modelName: String,
    runtimeName: String,
    onDownload: (token: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var token by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
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
    }
}
