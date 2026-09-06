package com.livingai.app.focus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusSessionManagerTest {

    private fun newManager() = FocusSessionManager(CoroutineScope(SupervisorJob()))

    @Test
    fun `no session before start`() {
        assertNull(newManager().session.value)
    }

    @Test
    fun `start creates a running session for the given goal`() {
        val manager = newManager()
        manager.start("goal-1")
        val session = manager.session.value
        assertEquals("goal-1", session?.goalId)
        assertEquals(FocusSessionStatus.RUNNING, session?.status)
    }

    @Test
    fun `pause moves session to PAUSED and preserves goal`() {
        val manager = newManager()
        manager.start("goal-1")
        manager.pause()
        val session = manager.session.value
        assertEquals(FocusSessionStatus.PAUSED, session?.status)
        assertEquals("goal-1", session?.goalId)
    }

    @Test
    fun `resume moves a paused session back to RUNNING`() {
        val manager = newManager()
        manager.start("goal-1")
        manager.pause()
        manager.resume()
        assertEquals(FocusSessionStatus.RUNNING, manager.session.value?.status)
    }

    @Test
    fun `end moves session to ENDED and keeps the summary instead of clearing it`() {
        val manager = newManager()
        manager.start("goal-1")
        manager.end()
        val session = manager.session.value
        assertEquals(FocusSessionStatus.ENDED, session?.status)
        assertTrue(session?.accumulatedMs!! >= 0)
    }

    @Test
    fun `pause is a no-op when there is no running session`() {
        val manager = newManager()
        manager.pause()
        assertNull(manager.session.value)
    }
}
