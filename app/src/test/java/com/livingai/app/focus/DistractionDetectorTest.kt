package com.livingai.app.focus

import com.livingai.app.context.DeviceState
import com.livingai.app.context.FocusState
import com.livingai.app.context.MovementState
import com.livingai.app.context.UserContext
import com.livingai.app.core.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistractionDetectorTest {

    private val detector = DistractionDetector()

    private fun context(app: String?, movement: MovementState = MovementState.STILL) = UserContext(
        timestamp = System.currentTimeMillis(),
        movementState = movement,
        currentApp = app,
        focusState = FocusState.UNKNOWN,
        deviceState = DeviceState(80, false, ThermalLevel.NONE),
        sourceSignals = emptyList()
    )

    @Test
    fun `distracting app during active focus session yields high-confidence candidate`() {
        val candidate = detector.evaluate(context("com.instagram.android"), focusSessionActive = true)
        assertEquals(0.9f, candidate?.confidence)
    }

    @Test
    fun `normal app during active focus session yields no candidate`() {
        val candidate = detector.evaluate(context("com.livingai.app"), focusSessionActive = true)
        assertNull(candidate)
    }

    @Test
    fun `no focus session active yields no candidate even with distracting app`() {
        val candidate = detector.evaluate(context("com.instagram.android"), focusSessionActive = false)
        assertNull(candidate)
    }

    @Test
    fun `unknown app while still yields low confidence candidate`() {
        val candidate = detector.evaluate(context(null, MovementState.STILL), focusSessionActive = true)
        assertEquals(0.3f, candidate?.confidence)
    }
}
