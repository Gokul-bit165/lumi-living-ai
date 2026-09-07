package com.livingai.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.core.LivingAiLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

import com.livingai.app.ai.inference.ModelLoadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@RunWith(AndroidJUnit4::class)
class OnDeviceAiProofTest {

    private val app: LivingAiApp
        get() = ApplicationProvider.getApplicationContext<LivingAiApp>()

    private suspend fun ensureModelReady(): Long {
        if (app.textModel.status.value.state == ModelLoadState.READY) {
            return app.textModel.status.value.loadMs
        }
        val start = System.currentTimeMillis()
        app.textModel.initialize()
        val finalStatus = withTimeout(60_000L) {
            app.textModel.status.first { it.state == ModelLoadState.READY || it.state == ModelLoadState.FAILED }
        }
        val loadMs = System.currentTimeMillis() - start
        LivingAiLog.event("PROOF_TEST", "MODEL_READY state=${finalStatus.state} loadMs=$loadMs err=${finalStatus.errorMessage}")
        assertEquals("Model must be READY", ModelLoadState.READY, finalStatus.state)
        return loadMs
    }

    @Test
    fun testOnDeviceTextInference() = runBlocking {
        LivingAiLog.event("PROOF_TEST", "START testOnDeviceTextInference")
        val loadMs = ensureModelReady()
        val request = AIRequest(
            type = AIRequestType.VOICE_QUESTION,
            userText = "What is 2 + 2?",
            goalTitle = "Math Study",
            focusActive = true
        )
        val response = app.inferenceRouter.route(request)
        LivingAiLog.event("PROOF_TEST", "RESULT tier=${response.tier} loadMs=${response.loadMs} inferenceMs=${response.inferenceMs} text=${response.text}")

        assertEquals("InferenceRouter must select LOCAL_TEXT tier", ModelTier.LOCAL_TEXT, response.tier)
        assertTrue("Inference response text should not be blank", response.text.isNotBlank())
        assertTrue("Inference latency should be positive", response.inferenceMs > 0)
    }

    @Test
    fun testOnDeviceVisionAndTextInference() = runBlocking {
        LivingAiLog.event("PROOF_TEST", "START testOnDeviceVisionAndTextInference")
        ensureModelReady()
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            isAntiAlias = true
        }
        canvas.drawText("Lumi Exam Chapter 4", 30f, 100f, paint)

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val imageBytes = stream.toByteArray()

        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "Summarize what is written in this image.",
            imageBytes = imageBytes,
            goalTitle = "Exam Review",
            focusActive = true
        )
        val response = app.inferenceRouter.route(request)
        LivingAiLog.event("PROOF_TEST", "RESULT tier=${response.tier} loadMs=${response.loadMs} inferenceMs=${response.inferenceMs} text=${response.text}")

        assertEquals("InferenceRouter must select LOCAL_TEXT tier", ModelTier.LOCAL_TEXT, response.tier)
        assertTrue("Inference response text should not be blank", response.text.isNotBlank())
        assertTrue("Vision extraction time (loadMs) should be recorded", response.loadMs >= 0)
        assertTrue("Inference latency should be positive", response.inferenceMs > 0)
    }
}
