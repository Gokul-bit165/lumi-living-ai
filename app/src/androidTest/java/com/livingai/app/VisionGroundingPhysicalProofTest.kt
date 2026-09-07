package com.livingai.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.ai.prompts.PromptBuilder
import com.livingai.app.ai.vision.VisualEvidence
import com.livingai.app.ai.vision.VisualEvidenceCategory
import com.livingai.app.ai.vision.VisualEvidencePolicy
import com.livingai.app.camera.CameraCaptureController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionGroundingPhysicalProofTest {

    data class SceneResult(
        val sceneName: String,
        val ocrText: String?,
        val labels: List<String>,
        val confidences: Map<String, Float>,
        val evidenceCategory: VisualEvidenceCategory,
        val ocrMs: Long,
        val labelMs: Long,
        val gemmaMs: Long,
        val totalMs: Long,
        val prompt: String,
        val answer: String
    )

    @Test
    fun verifyVisionGroundingOnDeviceCPH2527() = runBlocking {
        println("=================================================================")
        println("STARTING ON-DEVICE VISION GROUNDING PHYSICAL PROOF ON CPH2527")
        println("=================================================================")

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var app: LivingAiApp? = null
        scenario.onActivity { act -> app = act.application as LivingAiApp }
        val livingApp = app!!

        // Ensure real local Gemma-3-1B-IT is READY
        if (livingApp.textModel.status.value.state != ModelLoadState.READY) {
            println("[TEST] Initializing local Gemma-3-1B-IT model...")
            livingApp.textModel.initialize()
            withTimeout(90_000L) {
                livingApp.textModel.status.first { it.state == ModelLoadState.READY }
            }
        }
        assertEquals(ModelLoadState.READY, livingApp.textModel.status.value.state)
        println("[TEST] Local Gemma-3-1B-IT is READY on CPH2527")

        val results = mutableListOf<SceneResult>()

        // Test Scenes:
        // A. Laptop (keyboard pattern, angled screen with code lines)
        val laptopBitmap = createLaptopBitmap()
        results.add(runSceneTest(livingApp, "A. Laptop", laptopBitmap, "What do you see in the picture?"))

        // B. Book (page of text)
        val bookBitmap = createBookBitmap()
        results.add(runSceneTest(livingApp, "B. Book", bookBitmap, "What do you see in the picture?"))

        // C. Phone (handheld device on desk)
        val phoneBitmap = createPhoneBitmap()
        results.add(runSceneTest(livingApp, "C. Phone", phoneBitmap, "What do you see in the picture?"))

        // D. Person (portrait outline)
        val personBitmap = createPersonBitmap()
        results.add(runSceneTest(livingApp, "D. Person", personBitmap, "What do you see in the picture?"))

        // E. Indoor Room (office desk environment)
        val roomBitmap = createIndoorRoomBitmap()
        results.add(runSceneTest(livingApp, "E. Indoor Room", roomBitmap, "What do you see in the picture?"))

        // F. Outdoor Scene (sky, horizon, sun)
        val outdoorBitmap = createOutdoorSceneBitmap()
        results.add(runSceneTest(livingApp, "F. Outdoor Scene", outdoorBitmap, "What do you see in the picture?"))

        // G. Intentionally Blank Surface (neutral featureless gray)
        val blankBitmap = createBlankSurfaceBitmap()
        results.add(runSceneTest(livingApp, "G. Intentionally Blank Surface", blankBitmap, "What do you see in the picture?"))

        // PRINT COMPREHENSIVE SUMMARY FOR DOCUMENTATION
        println("=================================================================")
        println("ON-DEVICE PHYSICAL PROOF TEST RESULTS (CPH2527)")
        println("=================================================================")

        for (res in results) {
            println("\n--- SCENE: ${res.sceneName} ---")
            println("OCR Text: ${if (res.ocrText.isNullOrBlank()) "<none>" else "\"${res.ocrText.replace("\n", " ")}\""}")
            println("ML Kit Labels: ${res.labels.ifEmpty { listOf("<none>") }}")
            println("Confidences: ${res.confidences}")
            println("VisualEvidence Category: ${res.evidenceCategory}")
            println("Latencies: OCR=${res.ocrMs}ms, Label=${res.labelMs}ms, Gemma=${res.gemmaMs}ms, Total=${res.totalMs}ms")
            println("Gemma Answer: \"${res.answer}\"")
        }

        // VERIFY ACCEPTANCE CRITERIA
        val laptopResult = results.first { it.sceneName.startsWith("A. Laptop") }
        val blankResult = results.first { it.sceneName.startsWith("G. Intentionally Blank") }

        // 1. Laptop must NOT claim birds, sky, or musical instrument
        val lowerLaptop = laptopResult.answer.lowercase()
        assertFalse("Laptop response must not hallucinate birds: ${laptopResult.answer}", lowerLaptop.contains("bird"))
        assertFalse("Laptop response must not hallucinate sky: ${laptopResult.answer}", lowerLaptop.contains("sky"))
        assertFalse("Laptop response must not hallucinate musical instrument: ${laptopResult.answer}", lowerLaptop.contains("musical instrument") || lowerLaptop.contains("acoustics"))

        // 2. Blank surface must NOT hallucinate objects
        val lowerBlank = blankResult.answer.lowercase()
        assertFalse("Blank surface must not hallucinate sky: ${blankResult.answer}", lowerBlank.contains("sky"))
        assertFalse("Blank surface must not hallucinate birds: ${blankResult.answer}", lowerBlank.contains("bird"))
        assertTrue("Blank surface should indicate inability to reliably identify or suggest retake: ${blankResult.answer}",
            lowerBlank.contains("reliably") || lowerBlank.contains("couldn't") || lowerBlank.contains("can't") ||
            lowerBlank.contains("lighting") || lowerBlank.contains("identify") || lowerBlank.contains("clear") || lowerBlank.contains("tell")
        )

        scenario.close()
        println("=================================================================")
        println("ALL ON-DEVICE PHYSICAL PROOF ASSERTIONS PASSED SUCCESSFULLY!")
        println("=================================================================")
    }

    private suspend fun runSceneTest(
        app: LivingAiApp,
        sceneName: String,
        bitmap: Bitmap,
        question: String
    ): SceneResult {
        val totalStart = System.currentTimeMillis()
        val jpegBytes = CameraCaptureController.bitmapToJpegBytes(bitmap)

        // 1. Visual understanding analysis
        val evidence = app.visionModel.analyze(bitmap)

        // 2. Build prompt
        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = question,
            imageBytes = jpegBytes
        )
        val prompt = PromptBuilder.build(request, evidence)

        // 3. Run Gemma inference
        val gemmaStart = System.currentTimeMillis()
        val response = app.inferenceRouter.route(request)
        val gemmaMs = System.currentTimeMillis() - gemmaStart
        val totalMs = System.currentTimeMillis() - totalStart

        assertEquals(ModelTier.LOCAL_TEXT, response.tier)

        return SceneResult(
            sceneName = sceneName,
            ocrText = evidence.ocrText,
            labels = evidence.labels,
            confidences = evidence.labelConfidences,
            evidenceCategory = evidence.confidenceLevel,
            ocrMs = evidence.ocrLatencyMs,
            labelMs = evidence.labelLatencyMs,
            gemmaMs = gemmaMs,
            totalMs = totalMs,
            prompt = prompt,
            answer = response.text
        )
    }

    // --- Bitmaps for Scenes ---

    private fun createLaptopBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(230, 230, 235)) // desk

        val paint = Paint().apply { isAntiAlias = true }

        // Laptop body
        paint.color = Color.rgb(60, 65, 75)
        canvas.drawRect(100f, 100f, 700f, 450f, paint)

        // Screen bezel & display
        paint.color = Color.BLACK
        canvas.drawRect(130f, 120f, 670f, 400f, paint)

        // Code text on screen
        paint.color = Color.rgb(80, 250, 120)
        paint.textSize = 28f
        canvas.drawText("class Assistant {", 160f, 180f, paint)
        canvas.drawText("    fun execute() {", 160f, 220f, paint)
        canvas.drawText("        val router = Router()", 160f, 260f, paint)
        canvas.drawText("    }", 160f, 300f, paint)
        canvas.drawText("}", 160f, 340f, paint)

        // Keyboard deck
        paint.color = Color.rgb(180, 185, 195)
        canvas.drawRect(80f, 450f, 720f, 570f, paint)

        // Keys grid
        paint.color = Color.rgb(40, 40, 45)
        for (row in 0..3) {
            for (col in 0..11) {
                canvas.drawRect(
                    110f + col * 48f,
                    460f + row * 24f,
                    150f + col * 48f,
                    480f + row * 24f,
                    paint
                )
            }
        }
        return bitmap
    }

    private fun createBookBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(210, 190, 160)) // table

        val paint = Paint().apply { isAntiAlias = true }
        // Book page
        paint.color = Color.rgb(250, 250, 245)
        canvas.drawRect(150f, 80f, 650f, 520f, paint)

        // Printed text
        paint.color = Color.BLACK
        paint.textSize = 30f
        canvas.drawText("Chapter 4: Operating Systems", 180f, 150f, paint)
        paint.textSize = 22f
        canvas.drawText("Processes and memory management form the core", 180f, 210f, paint)
        canvas.drawText("of multitasking computer architectures. Virtual memory", 180f, 250f, paint)
        canvas.drawText("provides hardware abstraction to executing threads.", 180f, 290f, paint)
        return bitmap
    }

    private fun createPhoneBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(220, 220, 220)) // table

        val paint = Paint().apply { isAntiAlias = true }
        // Phone body
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawRoundRect(200f, 100f, 400f, 500f, 30f, 30f, paint)

        // Screen
        paint.color = Color.rgb(70, 130, 180)
        canvas.drawRoundRect(210f, 120f, 390f, 480f, 20f, 20f, paint)
        return bitmap
    }

    private fun createPersonBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(240, 240, 240))

        val paint = Paint().apply { isAntiAlias = true }
        paint.color = Color.rgb(60, 60, 80)

        // Head
        canvas.drawCircle(300f, 200f, 80f, paint)

        // Shoulders/Torso
        canvas.drawOval(150f, 300f, 450f, 600f, paint)
        return bitmap
    }

    private fun createIndoorRoomBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }

        // Wall
        paint.color = Color.rgb(235, 230, 220)
        canvas.drawRect(0f, 0f, 800f, 400f, paint)

        // Floor
        paint.color = Color.rgb(180, 140, 100)
        canvas.drawRect(0f, 400f, 800f, 600f, paint)

        // Desk
        paint.color = Color.rgb(120, 80, 50)
        canvas.drawRect(200f, 350f, 600f, 500f, paint)
        return bitmap
    }

    private fun createOutdoorSceneBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }

        // Sky
        paint.color = Color.rgb(135, 206, 235)
        canvas.drawRect(0f, 0f, 800f, 350f, paint)

        // Sun
        paint.color = Color.rgb(255, 220, 50)
        canvas.drawCircle(650f, 100f, 50f, paint)

        // Grass/Ground
        paint.color = Color.rgb(34, 139, 34)
        canvas.drawRect(0f, 350f, 800f, 600f, paint)
        return bitmap
    }

    private fun createBlankSurfaceBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Uniform featureless gray
        canvas.drawColor(Color.rgb(180, 180, 180))
        return bitmap
    }
}
