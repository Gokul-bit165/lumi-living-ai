package com.livingai.app.camera.ui

import android.graphics.Bitmap
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.livingai.app.camera.CameraCaptureController
import kotlinx.coroutines.launch

/**
 * Camera + voice/text question UI. No live analysis loop — a frame is only captured when the
 * user taps "Capture", matching the "process only on explicit submit" rule.
 */
@Composable
fun CameraHelpScreen(
    cameraController: CameraCaptureController,
    isBusy: Boolean,
    resultText: String?,
    question: String,
    onQuestionChange: (String) -> Unit,
    onListen: () -> Unit,
    onSubmit: (imageBytes: ByteArray?, question: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("< Home") }
        Text(text = "Show Lumi", style = MaterialTheme.typography.headlineSmall)
        Text(text = "Camera understanding (Local Pixel Perception + Text Recognition)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (capturedBitmap == null) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    scope.launch {
                        runCatching { cameraController.bind(lifecycleOwner, preview) }
                    }
                    previewView
                }
            )
            Button(onClick = {
                scope.launch {
                    cameraController.captureBitmap().onSuccess { bitmap -> capturedBitmap = bitmap }
                }
            }) { Text("Capture") }
        } else {
            Image(
                bitmap = capturedBitmap!!.asImageBitmap(),
                contentDescription = "Captured photo",
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )
            TextButton(onClick = { capturedBitmap = null }) { Text("Retake") }
        }

        OutlinedTextField(
            value = question,
            onValueChange = onQuestionChange,
            label = { Text("What do you want to ask?") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = onListen) { Text("🎤 Speak instead") }

        Button(
            enabled = !isBusy && (question.isNotBlank() || capturedBitmap != null),
            onClick = {
                val bytes = capturedBitmap?.let { CameraCaptureController.bitmapToJpegBytes(it) }
                onSubmit(bytes, question.ifBlank { "Explain this." })
            }
        ) { Text(if (isBusy) "Lumi is thinking…" else "Ask Lumi") }

        if (isBusy) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Lumi is perceiving pixels & thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        resultText?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
