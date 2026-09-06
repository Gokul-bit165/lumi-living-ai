package com.livingai.app.ai.routing

import com.livingai.app.ai.inference.ModelLoadState
import com.livingai.app.ai.model.ModelTier
import com.livingai.app.core.RuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class InferencePolicyTest {

    private val policy = InferencePolicy()

    @Test
    fun `RED runtime status always falls back to RULE regardless of model state`() {
        assertEquals(ModelTier.RULE, policy.selectTier(RuntimeStatus.RED, ModelLoadState.READY))
    }

    @Test
    fun `model not ready falls back to RULE even when performance is GREEN`() {
        assertEquals(ModelTier.RULE, policy.selectTier(RuntimeStatus.GREEN, ModelLoadState.NOT_DOWNLOADED))
        assertEquals(ModelTier.RULE, policy.selectTier(RuntimeStatus.GREEN, ModelLoadState.DOWNLOADING))
        assertEquals(ModelTier.RULE, policy.selectTier(RuntimeStatus.GREEN, ModelLoadState.LOADING))
        assertEquals(ModelTier.RULE, policy.selectTier(RuntimeStatus.GREEN, ModelLoadState.FAILED))
    }

    @Test
    fun `GREEN or YELLOW with a ready model uses the real local text tier`() {
        assertEquals(ModelTier.LOCAL_TEXT, policy.selectTier(RuntimeStatus.GREEN, ModelLoadState.READY))
        assertEquals(ModelTier.LOCAL_TEXT, policy.selectTier(RuntimeStatus.YELLOW, ModelLoadState.READY))
    }

    @Test
    fun `degraded message reflects the actual blocking reason`() {
        assertEquals(true, policy.degradedMessage(RuntimeStatus.RED, ModelLoadState.READY).contains("battery/thermal"))
        assertEquals(true, policy.degradedMessage(RuntimeStatus.GREEN, ModelLoadState.NOT_DOWNLOADED).contains("Settings"))
    }

    @Test
    fun `local model unusable but remote configured and online falls back to REMOTE_FALLBACK`() {
        assertEquals(
            ModelTier.REMOTE_FALLBACK,
            policy.selectTier(RuntimeStatus.RED, ModelLoadState.READY, remoteConfigured = true, networkAvailable = true)
        )
        assertEquals(
            ModelTier.REMOTE_FALLBACK,
            policy.selectTier(RuntimeStatus.GREEN, ModelLoadState.NOT_DOWNLOADED, remoteConfigured = true, networkAvailable = true)
        )
    }

    @Test
    fun `remote fallback is never used while offline even if configured`() {
        assertEquals(
            ModelTier.RULE,
            policy.selectTier(RuntimeStatus.RED, ModelLoadState.READY, remoteConfigured = true, networkAvailable = false)
        )
    }

    @Test
    fun `remote fallback is never used when not configured`() {
        assertEquals(
            ModelTier.RULE,
            policy.selectTier(RuntimeStatus.RED, ModelLoadState.READY, remoteConfigured = false, networkAvailable = true)
        )
    }

    @Test
    fun `a ready local model on GREEN is always preferred over remote fallback`() {
        assertEquals(
            ModelTier.LOCAL_TEXT,
            policy.selectTier(RuntimeStatus.GREEN, ModelLoadState.READY, remoteConfigured = true, networkAvailable = true)
        )
    }
}
