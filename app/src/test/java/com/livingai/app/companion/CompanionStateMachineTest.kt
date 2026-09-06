package com.livingai.app.companion

import com.livingai.app.context.DeviceState
import com.livingai.app.context.MovementState
import com.livingai.app.context.UserContext
import com.livingai.app.core.ThermalLevel
import com.livingai.app.focus.FocusSession
import com.livingai.app.focus.FocusSessionStatus
import com.livingai.app.focus.InterventionDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionStateMachineTest {

    private fun newMachine() = CompanionStateMachine(CoroutineScope(SupervisorJob()))

    private fun baseContext() = UserContext(
        timestamp = System.currentTimeMillis(),
        movementState = MovementState.STILL,
        currentApp = null,
        focusState = com.livingai.app.context.FocusState.UNKNOWN,
        deviceState = DeviceState(batteryLevel = 80, charging = false, thermal = ThermalLevel.NONE),
        sourceSignals = emptyList()
    )

    @Test
    fun `default still context maps to IDLE`() {
        val machine = newMachine()
        machine.onContextChanged(baseContext())
        assertEquals(CompanionActivity.IDLE, machine.state.value.activity)
    }

    @Test
    fun `walking movement maps to WALKING`() {
        val machine = newMachine()
        machine.onContextChanged(baseContext().copy(movementState = MovementState.WALKING))
        assertEquals(CompanionActivity.WALKING, machine.state.value.activity)
    }

    @Test
    fun `severe thermal state overrides everything to WARNING`() {
        val machine = newMachine()
        machine.onContextChanged(
            baseContext().copy(
                movementState = MovementState.WALKING,
                deviceState = baseContext().deviceState.copy(thermal = ThermalLevel.SEVERE)
            )
        )
        assertEquals(CompanionActivity.WARNING, machine.state.value.activity)
    }

    @Test
    fun `low battery not charging maps to WARNING`() {
        val machine = newMachine()
        machine.onContextChanged(
            baseContext().copy(deviceState = baseContext().deviceState.copy(batteryLevel = 10, charging = false))
        )
        assertEquals(CompanionActivity.WARNING, machine.state.value.activity)
    }

    @Test
    fun `low battery while charging does not warn`() {
        val machine = newMachine()
        machine.onContextChanged(
            baseContext().copy(deviceState = baseContext().deviceState.copy(batteryLevel = 10, charging = true))
        )
        assertEquals(CompanionActivity.IDLE, machine.state.value.activity)
    }

    @Test
    fun `tap toggles expanded state`() {
        val machine = newMachine()
        assertFalse(machine.state.value.expanded)
        machine.onTap()
        assertTrue(machine.state.value.expanded)
        machine.onTap()
        assertFalse(machine.state.value.expanded)
    }

    @Test
    fun `running focus session maps to STUDYING`() {
        val machine = newMachine()
        machine.onFocusSessionChanged(FocusSession("goal-1", System.currentTimeMillis(), 0L, FocusSessionStatus.RUNNING))
        machine.onContextChanged(baseContext())
        assertEquals(CompanionActivity.STUDYING, machine.state.value.activity)
    }

    @Test
    fun `paused focus session maps to RESTING`() {
        val machine = newMachine()
        machine.onFocusSessionChanged(FocusSession("goal-1", System.currentTimeMillis(), 0L, FocusSessionStatus.PAUSED))
        machine.onContextChanged(baseContext())
        assertEquals(CompanionActivity.RESTING, machine.state.value.activity)
    }

    @Test
    fun `interrupt decision maps to WARNING with a distraction message and expands the bubble`() {
        val machine = newMachine()
        val decision = InterventionDecision(shouldInterrupt = true, reason = "distracting_app:com.instagram.android", confidence = 0.9f, cooldownRemainingMs = 0L)
        machine.onInterventionDecision(decision, goalTitle = "Exam prep")
        val state = machine.state.value
        assertEquals(CompanionActivity.WARNING, state.activity)
        assertTrue(state.expanded)
        assertTrue(state.message!!.contains("Exam prep"))
    }

    @Test
    fun `ignore decision does not change companion state`() {
        val machine = newMachine()
        machine.onContextChanged(baseContext())
        val before = machine.state.value
        val decision = InterventionDecision(shouldInterrupt = false, reason = "cooldown", confidence = 0.9f, cooldownRemainingMs = 5000L)
        machine.onInterventionDecision(decision, goalTitle = "Exam prep")
        assertEquals(before, machine.state.value)
    }

    @Test
    fun `dismissing an intervention clears the message and lets context drive state again`() {
        val machine = newMachine()
        machine.onContextChanged(baseContext())
        val decision = InterventionDecision(shouldInterrupt = true, reason = "distracting_app:x", confidence = 0.9f, cooldownRemainingMs = 0L)
        machine.onInterventionDecision(decision, goalTitle = "Exam prep")

        machine.dismissIntervention()

        val state = machine.state.value
        assertEquals(CompanionActivity.IDLE, state.activity)
        assertFalse(state.expanded)
        assertNull(state.message)
    }
}
