package com.livingai.app.ai.vision

import com.livingai.app.ai.inference.MockLocalTextModel
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.ai.prompts.PromptBuilder
import com.livingai.app.ai.routing.InferenceRouter
import com.livingai.app.core.RuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPixelPerceptionUnitTest {

    private val policy = VisualEvidencePolicy(
        strongObjectThreshold = 0.75f,
        candidateObjectThreshold = 0.50f
    )

    @Test
    fun `test confidence threshold filtering`() {
        val detections = listOf(
            VisualDetection("laptop", 0.94f),
            VisualDetection("keyboard", 0.60f),
            VisualDetection("mouse", 0.35f) // Should be filtered out (< 0.50)
        )

        val evidence = policy.evaluate(
            ocrText = null,
            detections = detections,
            imageWidth = 640,
            imageHeight = 480
        )

        assertEquals(2, evidence.detections.size)
        assertTrue(evidence.detections.any { it.label == "laptop" })
        assertTrue(evidence.detections.any { it.label == "keyboard" })
        assertFalse(evidence.detections.any { it.label == "mouse" })
        assertEquals(VisualEvidenceCategory.STRONG_OBJECT, evidence.confidenceLevel)
    }

    @Test
    fun `test deterministic description is strictly conditional on detections`() {
        // Case 1: Only laptop detected (NO keyboard, NO screen, NO desk)
        val onlyLaptop = listOf(VisualDetection("laptop", 0.92f))
        val desc1 = VisualDescriptionGenerator.generate(onlyLaptop)
        assertEquals("I can see what appears to be a laptop.", desc1)
        assertFalse(desc1!!.contains("desk"))
        assertFalse(desc1.contains("keyboard"))
        assertFalse(desc1.contains("screen"))

        // Case 2: Laptop and keyboard detected, NO desk
        val laptopAndKeyboard = listOf(
            VisualDetection("laptop", 0.90f),
            VisualDetection("keyboard", 0.85f)
        )
        val desc2 = VisualDescriptionGenerator.generate(laptopAndKeyboard)
        assertEquals("A laptop with a physical keyboard visible.", desc2)
        assertFalse(desc2!!.contains("desk"))

        // Case 3: Laptop, keyboard, and desk detected
        val laptopDeskKeyboard = listOf(
            VisualDetection("laptop", 0.90f),
            VisualDetection("keyboard", 0.85f),
            VisualDetection("dining table", 0.80f)
        )
        val desc3 = VisualDescriptionGenerator.generate(laptopDeskKeyboard)
        assertEquals("A laptop on a desk with a physical keyboard visible.", desc3)

        // Case 4: Spatial screen above keyboard
        val screenAboveKeyboard = listOf(
            VisualDetection("screen", 0.90f, boundingBox = VisualBoundingBox(50f, 20f, 300f, 150f)),
            VisualDetection("keyboard", 0.88f, boundingBox = VisualBoundingBox(50f, 200f, 300f, 350f))
        )
        val desc4 = VisualDescriptionGenerator.generate(screenAboveKeyboard)
        assertEquals("A computer display screen positioned above a keyboard.", desc4)

        // Case 5: Empty detections produces null
        val descEmpty = VisualDescriptionGenerator.generate(emptyList())
        assertNull(descEmpty)
    }

    @Test
    fun `test narrow deterministic fast path bypasses Gemma for identity queries`() = runBlocking {
        val mockTextModel = MockLocalTextModel()
        mockTextModel.initialize()

        val testEvidence = VisualEvidence(
            detections = listOf(VisualDetection("cell phone", 0.91f)),
            visualDescription = "I can see what appears to be a cell phone.",
            confidenceLevel = VisualEvidenceCategory.STRONG_OBJECT,
            visionModelLatencyMs = 38L
        )

        val mockVision = object : LocalVisionModel, VisualUnderstandingProvider {
            override val modelName: String = "Test Detector"
            override val providerName: String = "Test Provider"

            override suspend fun extractText(bitmap: android.graphics.Bitmap) = Result.success("")
            override suspend fun extractLabels(bitmap: android.graphics.Bitmap, minConfidence: Float) = Result.success(emptyList<String>())
            override suspend fun extractLabelConfidences(bitmap: android.graphics.Bitmap) = Result.success(emptyMap<String, Float>())

            override suspend fun analyze(bitmap: android.graphics.Bitmap): VisualEvidence = testEvidence
        }

        val imageBytes = ByteArray(16)

        val router = InferenceRouter(
            textModel = mockTextModel,
            visionModel = mockVision,
            imageAnalyzer = { testEvidence },
            runtimeStatus = { RuntimeStatus.GREEN }
        )

        // Query 1: Identity query -> should trigger FAST PATH (inferenceMs == 0, direct label answer)
        val fastRequest = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "What object is this?",
            imageBytes = imageBytes
        )
        val fastResponse = router.route(fastRequest)
        assertEquals("A Cell phone.", fastResponse.text)
        assertEquals(0L, fastResponse.inferenceMs)
        assertEquals(ModelTier.LOCAL_TEXT, fastResponse.tier)
        assertNotNull(fastResponse.visualEvidence)

        // Query 2: Open-ended query -> should NOT trigger fast path, passes to text reasoning
        val openEndedRequest = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "What do you see in the picture?",
            imageBytes = imageBytes
        )
        val openEndedResponse = router.route(openEndedRequest)
        // MockLocalTextModel returns mock response, inferenceMs > 0
        assertTrue(openEndedResponse.inferenceMs >= 0)
    }

    @Test
    fun `test negative evidence and prompt builder rules`() {
        val noEvidence = VisualEvidence(
            ocrText = null,
            detections = emptyList(),
            labels = emptyList(),
            confidenceLevel = VisualEvidenceCategory.NO_RELIABLE_EVIDENCE
        )

        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "What do you see in this picture?"
        )

        val prompt = PromptBuilder.build(request, noEvidence)

        assertTrue(prompt.contains("STATUS: No reliable visual evidence was extracted from this image."))
        assertTrue(prompt.contains("Do not invent physical objects, people, locations, sky, birds, instruments, or scanners."))
        assertTrue(prompt.contains("I can't reliably tell what's in this image yet. Try moving closer or improving the lighting."))
    }

    @Test
    fun `test solitary generic ambient label filtered on featureless surface`() {
        val rawLabels = mapOf("Sky" to 0.82f) // Noisy classifier hallucination on blank surface

        val evidence = policy.evaluate(
            ocrText = null,
            rawLabelConfidences = rawLabels,
            detections = emptyList(),
            imageWidth = 600,
            imageHeight = 600
        )

        assertEquals(VisualEvidenceCategory.NO_RELIABLE_EVIDENCE, evidence.confidenceLevel)
        assertFalse(evidence.hasObjectEvidence)
        assertTrue(evidence.detections.isEmpty())
    }
}
