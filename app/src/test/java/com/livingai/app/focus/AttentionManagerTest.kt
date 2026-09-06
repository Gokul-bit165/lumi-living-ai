package com.livingai.app.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionManagerTest {

    private val highConfidence = DistractionCandidate(confidence = 0.9f, reason = "distracting_app:com.instagram.android")
    private val lowConfidence = DistractionCandidate(confidence = 0.2f, reason = "unknown_app_inactive")

    // A. Study + distracting app -> interrupt
    @Test
    fun `high confidence candidate during active session triggers interrupt`() {
        val manager = AttentionManager(cooldownMs = 1000L, minConfidence = 0.5f)
        val decision = manager.evaluate(highConfidence, focusSessionActive = true, now = 0L)
        assertTrue(decision.shouldInterrupt)
        assertEquals("distracting_app:com.instagram.android", decision.reason)
    }

    // B. Study + normal app -> ignore (no candidate at all)
    @Test
    fun `no candidate during active session is ignored`() {
        val manager = AttentionManager()
        val decision = manager.evaluate(null, focusSessionActive = true, now = 0L)
        assertFalse(decision.shouldInterrupt)
        assertEquals("no_distraction", decision.reason)
    }

    // C. Recent intervention + distracting app -> ignore because of cooldown
    @Test
    fun `second distraction within cooldown window is ignored`() {
        val manager = AttentionManager(cooldownMs = 120_000L, minConfidence = 0.5f)
        val first = manager.evaluate(highConfidence, focusSessionActive = true, now = 0L)
        assertTrue(first.shouldInterrupt)

        val second = manager.evaluate(highConfidence, focusSessionActive = true, now = 30_000L)
        assertFalse(second.shouldInterrupt)
        assertEquals("cooldown", second.reason)
        assertTrue(second.cooldownRemainingMs > 0)
    }

    @Test
    fun `distraction after cooldown elapses triggers interrupt again`() {
        val manager = AttentionManager(cooldownMs = 120_000L, minConfidence = 0.5f)
        manager.evaluate(highConfidence, focusSessionActive = true, now = 0L)
        val afterCooldown = manager.evaluate(highConfidence, focusSessionActive = true, now = 121_000L)
        assertTrue(afterCooldown.shouldInterrupt)
    }

    // D. No active focus session -> ignore
    @Test
    fun `no active focus session is always ignored regardless of candidate`() {
        val manager = AttentionManager()
        val decision = manager.evaluate(highConfidence, focusSessionActive = false, now = 0L)
        assertFalse(decision.shouldInterrupt)
        assertEquals("no_active_session", decision.reason)
    }

    // E. Low confidence -> ignore
    @Test
    fun `low confidence candidate is ignored`() {
        val manager = AttentionManager(minConfidence = 0.5f)
        val decision = manager.evaluate(lowConfidence, focusSessionActive = true, now = 0L)
        assertFalse(decision.shouldInterrupt)
        assertEquals("low_confidence", decision.reason)
    }
}
