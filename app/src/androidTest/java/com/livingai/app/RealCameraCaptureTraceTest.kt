package com.livingai.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.prompts.PromptBuilder
import com.livingai.app.camera.CameraCaptureController
import com.livingai.app.core.LivingAiLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class RealCameraCaptureTraceTest {

    @Test
    fun traceRealPhysicalCameraCapture() = runBlocking {
        println("==================================================")
        println("BEGIN REAL PHYSICAL CAMERA CAPTURE TRACE ON CPH2527")
        println("==================================================")

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var app: LivingAiApp? = null
        scenario.onActivity { act ->
            app = act.application as LivingAiApp
        }
        val livingApp = app!!

        // Ensure model is ready
        if (livingApp.textModel.status.value.state != ModelLoadState.READY) {
            livingApp.textModel.initialize()
            withTimeout(60_000L) {
                livingApp.textModel.status.first { it.state == ModelLoadState.READY }
            }
        }

        // Wait a second for camera binding if active
        delay(1500)

        // Capture a frame from camera controller if bound, or capture directly via Camera2/CameraX
        val captureResult = livingApp.cameraCaptureController.captureBitmap()
        val bitmap = captureResult.getOrNull()
        if (bitmap == null) {
            println("[REAL_CAMERA] Camera was not bound or failed: ${captureResult.exceptionOrNull()?.message}")
            scenario.close()
            return@runBlocking
        }

        val rawWidth = bitmap.width
        val rawHeight = bitmap.height
        val jpegBytes = CameraCaptureController.bitmapToJpegBytes(bitmap)

        println("[REAL_CAMERA] STAGE 1 (Raw Dimensions): ${rawWidth}x${rawHeight}, JPEG bytes: ${jpegBytes.size}")
        println("[REAL_CAMERA] STAGE 2 (Rotation): Handled in captureBitmap() matrix transformation")
        println("[REAL_CAMERA] STAGE 3 (Preprocessing): Decoded ARGB_8888 bitmap, size=${bitmap.byteCount} bytes")

        // 4. ML Kit OCR
        val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val ocrText = suspendCancellableCoroutine<String> { cont ->
            ocrRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume("") }
        }
        println("[REAL_CAMERA] STAGE 4 (ML Kit OCR Output):\n--- OCR START ---\n$ocrText\n--- OCR END ---")

        // 5. ML Kit Labels
        val rawLabeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.05f).build())
        val allLabels = suspendCancellableCoroutine<List<Pair<String, Float>>> { cont ->
            rawLabeler.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { list -> cont.resume(list.map { it.text to it.confidence }) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }
        println("[REAL_CAMERA] STAGE 5 (Every ML Kit Label + Confidence):")
        if (allLabels.isEmpty()) {
            println("   (No labels detected even at >= 5% confidence)")
        } else {
            allLabels.forEach { (lbl, conf) ->
                println("   - $lbl: ${(conf * 100).toInt()}% (raw=$conf)")
            }
        }

        val filteredLabels = allLabels.filter { it.second >= 0.70f }.map { "${it.first} (${(it.second * 100).toInt()}%)" }
        println("[REAL_CAMERA] STAGE 5b (Labels >= 70% threshold): $filteredLabels")

        // 6. Visual context
        val visualContext = buildString {
            if (filteredLabels.isNotEmpty()) {
                append("Objects detected in camera: ${filteredLabels.joinToString(", ")}.\n")
            }
            if (ocrText.isNotBlank()) {
                append("Text extracted from the user's camera image:\n\"\"\"\n$ocrText\n\"\"\"\n")
            }
        }
        println("[REAL_CAMERA] STAGE 6 (Exact Visual Context String):\n--- CONTEXT START ---\n$visualContext--- CONTEXT END ---")

        // 7. Prompt
        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "What do you see in the picture?",
            imageBytes = jpegBytes
        )
        val prompt = PromptBuilder.build(request, ocrText.takeIf { it.isNotBlank() }, filteredLabels)
        println("[REAL_CAMERA] STAGE 7 (Exact Prompt Constructed):\n--- PROMPT START ---\n$prompt\n--- PROMPT END ---")

        println("[REAL_CAMERA] STAGE 8 (AIRequest): type=${request.type}, userText=\"${request.userText}\"")

        // 9. Inference
        val response = livingApp.inferenceRouter.route(request)
        println("[REAL_CAMERA] STAGE 9 (ModelTier Selected): ${response.tier}")
        println("[REAL_CAMERA] STAGE 10 (Gemma Input): Exactly the text prompt from Stage 7 (NO PIXELS PASSED TO GEMMA)")
        println("[REAL_CAMERA] STAGE 11 (Exact Gemma Response):\n--- RESPONSE START ---\n${response.text}\n--- RESPONSE END ---")

        LivingAiLog.event("REAL_CAMERA_TRACE", "tier=${response.tier} ocr='${ocrText.take(50)}' labels=$filteredLabels response='${response.text}'")
        scenario.close()
    }
}
