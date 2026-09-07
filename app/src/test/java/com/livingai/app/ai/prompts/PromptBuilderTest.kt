package com.livingai.app.ai.prompts

import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.vision.VisualEvidence
import com.livingai.app.ai.vision.VisualEvidenceCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `detected objects and OCR text are both grounded with candidate disclaimers`() {
        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "Explain this diagram",
            imageBytes = ByteArray(1),
            goalTitle = "Physics Chapter 5",
            focusActive = true
        )
        val prompt = PromptBuilder.build(
            request = request,
            extractedImageText = "F = m * a",
            detectedObjects = listOf("Blackboard (94%)", "Diagram (82%)")
        )

        assertTrue(prompt.contains("Physics Chapter 5"))
        assertTrue(prompt.contains("VISIBLE TEXT:"))
        assertTrue(prompt.contains("F = m * a"))
        assertTrue(prompt.contains("VISUAL OBJECT EVIDENCE:"))
        assertTrue(prompt.contains("Blackboard (94%)"))
        assertTrue(prompt.contains("Diagram (82%)"))
        assertTrue(prompt.contains("USER: Explain this diagram"))
    }

    @Test
    fun `candidate objects without text are framed as tentative candidate signals`() {
        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "What is this?",
            imageBytes = ByteArray(1),
            goalTitle = null,
            focusActive = false
        )
        val prompt = PromptBuilder.build(
            request = request,
            visualEvidence = VisualEvidence(
                labels = listOf("Coffee cup"),
                labelConfidences = mapOf("Coffee cup" to 0.75f),
                confidenceLevel = VisualEvidenceCategory.WEAK_OBJECT
            )
        )

        assertTrue(prompt.contains("VISUAL OBJECT EVIDENCE:"))
        assertTrue(prompt.contains("Coffee cup (75%)"))
        assertTrue(prompt.contains("Answer using only provided visual evidence."))
        assertTrue(prompt.contains("Do not invent physical objects."))
        assertTrue(prompt.contains("USER: What is this?"))
    }

    @Test
    fun `camera question with zero evidence includes strict anti-hallucination rules`() {
        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "What do you see in the picture?",
            imageBytes = ByteArray(1),
            goalTitle = null,
            focusActive = false
        )
        val prompt = PromptBuilder.build(
            request = request,
            visualEvidence = VisualEvidence(confidenceLevel = VisualEvidenceCategory.NO_RELIABLE_EVIDENCE)
        )

        assertTrue(prompt.contains("STATUS: No reliable visual evidence was extracted from this image."))
        assertTrue(prompt.contains("You cannot see the image directly."))
        assertTrue(prompt.contains("Do not invent physical objects, people, locations, sky, birds, instruments, or scanners."))
        assertTrue(prompt.contains("I can't reliably tell what's in this image yet. Try moving closer or improving the lighting."))
        assertTrue(prompt.contains("USER: What do you see in the picture?"))
    }

    @Test
    fun `plain text questions omit visual context completely`() {
        val request = AIRequest(
            type = AIRequestType.TEXT_QUESTION,
            userText = "Hello Lumi",
            imageBytes = null,
            goalTitle = null,
            focusActive = false
        )
        val prompt = PromptBuilder.build(
            request = request,
            visualEvidence = null
        )

        assertFalse(prompt.contains("VISUAL EVIDENCE"))
        assertFalse(prompt.contains("Candidate visual signal"))
        assertFalse(prompt.contains("The camera produced this text evidence"))
        assertTrue(prompt.contains("USER: Hello Lumi"))
    }
}
