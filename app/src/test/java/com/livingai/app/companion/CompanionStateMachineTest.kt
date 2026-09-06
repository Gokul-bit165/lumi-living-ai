package com.livingai.app.companion

import com.livingai.app.context.DeviceState
import com.livingai.app.context.MovementState
import com.livingai.app.context.UserContext
import com.livingai.app.core.ThermalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
