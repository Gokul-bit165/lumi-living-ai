package com.livingai.app.ai.prompts

import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `detected objects and OCR text are both included in prompt when present`() {
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
        assertTrue(prompt.contains("Objects detected in camera: Blackboard (94%), Diagram (82%)"))
        assertTrue(prompt.contains("F = m * a"))
        assertTrue(prompt.contains("USER: Explain this diagram"))
    }

    @Test
    fun `detected objects without text are included in prompt`() {
        val request = AIRequest(
            type = AIRequestType.CAMERA_QUESTION,
            userText = "What is this?",
            imageBytes = ByteArray(1),
            goalTitle = null,
            focusActive = false
        )
        val prompt = PromptBuilder.build(
            request = request,
            extractedImageText = null,
            detectedObjects = listOf("Coffee cup (89%)")
        )

        assertTrue(prompt.contains("Objects detected in camera: Coffee cup (89%)"))
        assertTrue(!prompt.contains("Text extracted from the user's camera image"))
        assertTrue(prompt.contains("USER: What is this?"))
    }

    @Test
    fun `empty vision inputs omit image sections cleanly`() {
        val request = AIRequest(
            type = AIRequestType.TEXT_QUESTION,
            userText = "Hello",
            imageBytes = null,
            goalTitle = null,
            focusActive = false
        )
        val prompt = PromptBuilder.build(
            request = request,
            extractedImageText = null,
            detectedObjects = emptyList()
        )

        assertTrue(!prompt.contains("Objects detected in camera"))
        assertTrue(!prompt.contains("Text extracted from the user's camera image"))
        assertTrue(prompt.contains("USER: Hello"))
    }
}
