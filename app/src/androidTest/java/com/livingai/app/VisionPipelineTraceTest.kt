package com.livingai.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class VisionPipelineTraceTest {

    private val app: LivingAiApp
        get() = ApplicationProvider.getApplicationContext<LivingAiApp>()

    private suspend fun ensureModelReady() {
        if (app.textModel.status.value.state == ModelLoadState.READY) return
        app.textModel.initialize()
        withTimeout(60_000L) {
            app.textModel.status.first { it.state == ModelLoadState.READY }
        }
    }

    private suspend fun runRawMlKitLabels(bitmap: Bitmap, minConfidence: Float = 0.05f): List<Pair<String, Float>> =
        suspendCancellableCoroutine { cont ->
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(minConfidence)
                    .build()
            )
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    cont.resume(labels.map { it.text to it.confidence })
                }
                .addOnFailureListener {
                    cont.resume(emptyList())
                }
        }

    private suspend fun runRawMlKitOcr(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { cont.resume("") }
        }

    @Test
    fun tracePipelineLaptopAndOutdoor() = runBlocking {
        ensureModelReady()

        println("==================================================")
        println("BEGIN VISION PIPELINE COMPLETE TRACE ON CPH2527")
        println("==================================================")

        // ==========================================
        // TEST A: Laptop on Desk
        // ==========================================
        println("\n>>> EXECUTING TEST A: Laptop on desk")
        val laptopBitmap = createLaptopOnDeskBitmap()
        val laptopJpegBytes = CameraCaptureController.bitmapToJpegBytes(laptopBitmap)
        traceSingleImage(
            tag = "TEST_A_LAPTOP",
            bitmap = laptopBitmap,
            jpegBytes = laptopJpegBytes,
            question = "What do you see in the picture?",
            rotationDegrees = 0
        )

        // ==========================================
        // TEST B: Outdoor Scene
        // ==========================================
        println("\n>>> EXECUTING TEST B: Outdoor scene")
        val outdoorBitmap = createOutdoorSceneBitmap()
        val outdoorJpegBytes = CameraCaptureController.bitmapToJpegBytes(outdoorBitmap)
        traceSingleImage(
            tag = "TEST_B_OUTDOOR",
            bitmap = outdoorBitmap,
            jpegBytes = outdoorJpegBytes,
            question = "What do you see in the picture?",
            rotationDegrees = 0
        )

        // ==========================================
        // TEST C: Empty Context Probe (Zero OCR, Zero Labels)
        // ==========================================
        println("\n>>> EXECUTING TEST C: Zero visual context probe")
        val plainBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val plainJpegBytes = CameraCaptureController.bitmapToJpegBytes(plainBitmap)
        traceSingleImage(
            tag = "TEST_C_EMPTY_CONTEXT",
            bitmap = plainBitmap,
            jpegBytes = plainJpegBytes,
            question = "What do you see in the picture?",
            rotationDegrees = 0
        )

        println("==================================================")
        println("COMPLETED VISION PIPELINE COMPLETE TRACE")
        println("==================================================")
    }

    private suspend fun traceSingleImage(
        tag: String,
        bitmap: Bitmap,
        jpegBytes: ByteArray,
        question: String,
        rotationDegrees: Int
    ) {
        // 1. Raw image dimensions
        val rawWidth = bitmap.width
        val rawHeight = bitmap.height
        val byteSize = jpegBytes.size
        println("[$tag] STAGE 1 (Raw Dimensions): ${rawWidth}x${rawHeight}, JPEG bytes: $byteSize")

        // 2. Image rotation
        println("[$tag] STAGE 2 (Rotation): ${rotationDegrees}°")

        // 3. Preprocessing result
        println("[$tag] STAGE 3 (Preprocessing): ARGB_8888 bitmap decoded, config=${bitmap.config}")

        // 4. ML Kit OCR output
        val ocrText = runRawMlKitOcr(bitmap)
        println("[$tag] STAGE 4 (ML Kit OCR Output):\n--- OCR START ---\n$ocrText\n--- OCR END ---")

        // 5. Every ML Kit Image Label + confidence (low threshold 0.05f to see everything classifier detected)
        val allLabels = runRawMlKitLabels(bitmap, minConfidence = 0.05f)
        println("[$tag] STAGE 5 (Every ML Kit Label + Confidence):")
        if (allLabels.isEmpty()) {
            println("   (No labels detected even at >= 5% confidence)")
        } else {
            allLabels.forEach { (label, conf) ->
                println("   - $label: ${(conf * 100).toInt()}% (raw=$conf)")
            }
        }

        // Filtered labels as produced by MlKitLocalVisionModel (>= 70%)
        val filteredLabels = allLabels.filter { it.second >= 0.70f }.map { "${it.first} (${(it.second * 100).toInt()}%)" }
        println("[$tag] STAGE 5b (Labels passing >= 70% threshold): $filteredLabels")

        // 6. Visual context string constructed by MlKitLocalVisionModel & PromptBuilder
        val visualContext = buildString {
            if (filteredLabels.isNotEmpty()) {
                append("Objects detected in camera: ${filteredLabels.joinToString(", ")}.\n")
            }
            if (ocrText.isNotBlank()) {
                append("Text extracted from the user's camera image:\n\"\"\"\n$ocrText\n\"\"\"\n")
            }
        }
        println("[$tag] STAGE 6 (Exact Visual Context String):\n--- CONTEXT START ---\n$visualContext--- CONTEXT END ---")

        // 7. Prompt constructed by PromptBuilder
        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = question,
            imageBytes = jpegBytes,
            goalTitle = null,
            focusActive = false
        )
        val exactPrompt = PromptBuilder.build(request, ocrText.takeIf { it.isNotBlank() }, filteredLabels)
        println("[$tag] STAGE 7 (Exact Prompt Constructed):\n--- PROMPT START ---\n$exactPrompt\n--- PROMPT END ---")

        // 8. AIRequest sent to InferenceRouter
        println("[$tag] STAGE 8 (AIRequest): type=${request.type}, userText=\"${request.userText}\", imageBytes=${request.imageBytes?.size}B")

        // 9. ModelTier selected & 10/11 Route to Gemma
        val response = app.inferenceRouter.route(request)
        println("[$tag] STAGE 9 (ModelTier Selected): ${response.tier}")
        println("[$tag] STAGE 10 (Gemma Input): Exactly the text prompt from Stage 7 (NO PIXELS PASSED TO GEMMA)")
        println("[$tag] STAGE 11 (Exact Gemma Response):\n--- RESPONSE START ---\n${response.text}\n--- RESPONSE END ---")
        println("[$tag] Latency: loadMs=${response.loadMs}, inferenceMs=${response.inferenceMs}")

        LivingAiLog.event("TRACE_$tag", "tier=${response.tier} ocrChars=${ocrText.length} labels=${filteredLabels.size} text=${response.text}")
    }

    private fun createLaptopOnDeskBitmap(): Bitmap {
        val width = 800
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Desk background (warm brown table surface)
        val deskPaint = Paint().apply { color = Color.rgb(180, 130, 90) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), deskPaint)

        // Laptop base (open on desk)
        val basePaint = Paint().apply { color = Color.rgb(50, 50, 55) }
        val basePath = Path().apply {
            moveTo(150f, 400f)
            lineTo(650f, 400f)
            lineTo(720f, 540f)
            lineTo(80f, 540f)
            close()
        }
        canvas.drawPath(basePath, basePaint)

        // Trackpad
        val trackpadPaint = Paint().apply { color = Color.rgb(75, 75, 80) }
        canvas.drawRoundRect(RectF(340f, 470f, 460f, 530f), 8f, 8f, trackpadPaint)

        // Keyboard area
        val keyboardPaint = Paint().apply { color = Color.rgb(25, 25, 25) }
        canvas.drawRoundRect(RectF(160f, 410f, 640f, 465f), 4f, 4f, keyboardPaint)

        // Laptop screen (open lid)
        val lidPaint = Paint().apply { color = Color.rgb(40, 40, 45) }
        canvas.drawRoundRect(RectF(180f, 100f, 620f, 400f), 12f, 12f, lidPaint)

        // Display area
        val screenPaint = Paint().apply { color = Color.rgb(15, 20, 30) }
        canvas.drawRect(195f, 115f, 605f, 385f, screenPaint)

        // Code text on screen for OCR
        val codePaint = Paint().apply {
            color = Color.rgb(100, 220, 100)
            textSize = 20f
            isAntiAlias = true
        }
        canvas.drawText("class Assistant {", 215f, 160f, codePaint)
        canvas.drawText("  val status = \"ONLINE\"", 215f, 195f, codePaint)
        canvas.drawText("  fun processInput() {}", 215f, 230f, codePaint)
        canvas.drawText("}", 215f, 265f, codePaint)

        return bitmap
    }

    private fun createOutdoorSceneBitmap(): Bitmap {
        val width = 800
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Sky (light sky blue)
        val skyPaint = Paint().apply { color = Color.rgb(135, 206, 235) }
        canvas.drawRect(0f, 0f, width.toFloat(), 380f, skyPaint)

        // Sun
        val sunPaint = Paint().apply { color = Color.rgb(255, 220, 0) }
        canvas.drawCircle(680f, 100f, 50f, sunPaint)

        // Clouds
        val cloudPaint = Paint().apply { color = Color.WHITE }
        canvas.drawCircle(250f, 120f, 45f, cloudPaint)
        canvas.drawCircle(290f, 110f, 55f, cloudPaint)
        canvas.drawCircle(330f, 120f, 45f, cloudPaint)

        // Grass / Ground
        val grassPaint = Paint().apply { color = Color.rgb(34, 139, 34) }
        canvas.drawRect(0f, 380f, width.toFloat(), height.toFloat(), grassPaint)

        // Tree trunk
        val trunkPaint = Paint().apply { color = Color.rgb(101, 67, 33) }
        canvas.drawRect(120f, 260f, 160f, 420f, trunkPaint)

        // Tree canopy
        val treePaint = Paint().apply { color = Color.rgb(0, 100, 0) }
        canvas.drawCircle(140f, 230f, 75f, treePaint)

        // Birds in sky
        val birdPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        val birdPath1 = Path().apply {
            moveTo(420f, 120f)
            quadTo(435f, 105f, 450f, 120f)
            quadTo(465f, 105f, 480f, 120f)
        }
        canvas.drawPath(birdPath1, birdPaint)

        val birdPath2 = Path().apply {
            moveTo(500f, 150f)
            quadTo(512f, 138f, 525f, 150f)
            quadTo(537f, 138f, 550f, 150f)
        }
        canvas.drawPath(birdPath2, birdPaint)

        return bitmap
    }
}
