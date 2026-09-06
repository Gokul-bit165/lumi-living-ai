package com.livingai.app.ai.routing

import android.graphics.Bitmap
import com.livingai.app.ai.inference.GenerationResult
import com.livingai.app.ai.inference.LocalTextModel
import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.inference.ModelStatus
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.ai.vision.LocalVisionModel
import com.livingai.app.core.RuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTextModel(
    initialState: ModelLoadState = ModelLoadState.READY,
    private val response: Result<GenerationResult> = Result.success(GenerationResult("hi", 10))
) : LocalTextModel {
    private val _status = MutableStateFlow(ModelStatus(initialState))
    override val status: StateFlow<ModelStatus> = _status
    override val modelName = "fake-model"
    override val runtimeName = "fake-runtime"
    var lastPrompt: String? = null

    override suspend fun initialize() = Unit
    override suspend fun downloadAndInitialize(hfToken: String) = Unit
    override suspend fun generate(prompt: String): Result<GenerationResult> {
        lastPrompt = prompt
        return response
    }
    override fun cancel() = Unit
    override fun close() = Unit
}

private class FakeVisionModel : LocalVisionModel {
    override val modelName = "fake-vision"
    override suspend fun extractText(bitmap: Bitmap): Result<String> = Result.success("extracted text")
}

class InferenceRouterTest {

    @Test
    fun `RED runtime status skips the model entirely and returns a RULE-tier degraded response`() = runTest {
        val textModel = FakeTextModel()
        val router = InferenceRouter(textModel, FakeVisionModel()) { RuntimeStatus.RED }

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.RULE, response.tier)
        assertFalse(response.wasStructured)
        assertEquals(null, textModel.lastPrompt) // model was never actually invoked
    }

    @Test
    fun `model not ready falls back to RULE without invoking generate`() = runTest {
        val textModel = FakeTextModel(initialState = ModelLoadState.NOT_DOWNLOADED)
        val router = InferenceRouter(textModel, FakeVisionModel()) { RuntimeStatus.GREEN }

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.RULE, response.tier)
        assertEquals(null, textModel.lastPrompt)
    }

    @Test
    fun `well-formed structured JSON is parsed into text and emotion`() = runTest {
        val textModel = FakeTextModel(
            response = Result.success(GenerationResult("""{"response": "Here's the idea", "emotion": "EXPLAINING"}""", 500))
        )
        val router = InferenceRouter(textModel, FakeVisionModel()) { RuntimeStatus.GREEN }

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.LOCAL_TEXT, response.tier)
        assertTrue(response.wasStructured)
        assertEquals("Here's the idea", response.text)
        assertEquals(500, response.inferenceMs)
    }

    @Test
    fun `malformed output falls back to the raw text instead of crashing`() = runTest {
        val textModel = FakeTextModel(response = Result.success(GenerationResult("just plain text, no json", 300)))
        val router = InferenceRouter(textModel, FakeVisionModel()) { RuntimeStatus.GREEN }

        val response = router.route(textRequest("Explain this"))

        assertFalse(response.wasStructured)
        assertEquals("just plain text, no json", response.text)
    }

    @Test
    fun `generation failure returns a friendly degraded response, not a crash`() = runTest {
        val textModel = FakeTextModel(response = Result.failure(RuntimeException("native inference error")))
        val router = InferenceRouter(textModel, FakeVisionModel()) { RuntimeStatus.GREEN }

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.LOCAL_TEXT, response.tier)
        assertFalse(response.wasStructured)
        assertEquals(0, response.inferenceMs)
    }

    private fun textRequest(text: String) = AIRequest(
        type = AIRequestType.TEXT_QUESTION,
        userText = text,
        imageBytes = null,
        goalTitle = "Exam prep",
        focusActive = true
    )
}
