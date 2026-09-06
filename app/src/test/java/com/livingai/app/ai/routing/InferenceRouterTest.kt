package com.livingai.app.ai.routing

import android.graphics.Bitmap
import com.livingai.app.ai.inference.GenerationResult
import com.livingai.app.ai.inference.LocalTextModel
import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.inference.ModelStatus
import com.livingai.app.ai.inference.RemoteTextModel
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

private class FakeRemoteTextModel(
    private val configured: Boolean = true,
    private val response: Result<GenerationResult> = Result.success(GenerationResult("hi from the cloud", 250))
) : RemoteTextModel {
    override val runtimeName = "fake-cloud-provider"
    var lastPrompt: String? = null
    override suspend fun isConfigured(): Boolean = configured
    override suspend fun generate(prompt: String): Result<GenerationResult> {
        lastPrompt = prompt
        return response
    }
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

    @Test
    fun `local model unusable but remote configured and online routes to REMOTE_FALLBACK, tagged as such`() = runTest {
        val textModel = FakeTextModel(initialState = ModelLoadState.NOT_DOWNLOADED)
        val remoteModel = FakeRemoteTextModel()
        val router = InferenceRouter(
            textModel = textModel,
            visionModel = FakeVisionModel(),
            remoteModel = remoteModel,
            networkAvailable = { true },
            runtimeStatus = { RuntimeStatus.GREEN }
        )

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.REMOTE_FALLBACK, response.tier)
        assertEquals("hi from the cloud", response.text)
        assertEquals(250, response.inferenceMs)
        assertEquals(null, textModel.lastPrompt) // real local model never invoked
        assertTrue(remoteModel.lastPrompt != null)
    }

    @Test
    fun `remote fallback is skipped while offline even when configured, degrading to RULE instead`() = runTest {
        val textModel = FakeTextModel(initialState = ModelLoadState.NOT_DOWNLOADED)
        val remoteModel = FakeRemoteTextModel()
        val router = InferenceRouter(
            textModel = textModel,
            visionModel = FakeVisionModel(),
            remoteModel = remoteModel,
            networkAvailable = { false },
            runtimeStatus = { RuntimeStatus.GREEN }
        )

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.RULE, response.tier)
        assertEquals(null, remoteModel.lastPrompt)
    }

    @Test
    fun `remote fallback failure returns a friendly degraded response tagged REMOTE_FALLBACK, not a crash`() = runTest {
        val textModel = FakeTextModel(initialState = ModelLoadState.NOT_DOWNLOADED)
        val remoteModel = FakeRemoteTextModel(response = Result.failure(RuntimeException("HTTP 401")))
        val router = InferenceRouter(
            textModel = textModel,
            visionModel = FakeVisionModel(),
            remoteModel = remoteModel,
            networkAvailable = { true },
            runtimeStatus = { RuntimeStatus.GREEN }
        )

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.REMOTE_FALLBACK, response.tier)
        assertFalse(response.wasStructured)
        assertEquals(0, response.inferenceMs)
    }

    @Test
    fun `a ready local model is preferred over remote fallback even when both are available`() = runTest {
        val textModel = FakeTextModel()
        val remoteModel = FakeRemoteTextModel()
        val router = InferenceRouter(
            textModel = textModel,
            visionModel = FakeVisionModel(),
            remoteModel = remoteModel,
            networkAvailable = { true },
            runtimeStatus = { RuntimeStatus.GREEN }
        )

        val response = router.route(textRequest("Explain this"))

        assertEquals(ModelTier.LOCAL_TEXT, response.tier)
        assertEquals(null, remoteModel.lastPrompt)
        assertTrue(textModel.lastPrompt != null)
    }

    private fun textRequest(text: String) = AIRequest(
        type = AIRequestType.TEXT_QUESTION,
        userText = text,
        imageBytes = null,
        goalTitle = "Exam prep",
        focusActive = true
    )
}
