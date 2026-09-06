package com.livingai.app.context

import com.livingai.app.core.ThermalLevel

enum class MovementState { STILL, WALKING, ACTIVE, UNKNOWN }
enum class FocusState { FOCUSED, AT_RISK, DISTRACTED, BREAK, INACTIVE, UNKNOWN }

data class DeviceState(
    val batteryLevel: Int,
    val charging: Boolean,
    val thermal: ThermalLevel
)

/**
 * The single aggregated snapshot everything downstream (companion, focus engine) reacts to.
 * Trimmed to the fields V1 actually produces from real signals — no speculative fields.
 */
data class UserContext(
    val timestamp: Long,
    val movementState: MovementState,
    val currentApp: String?,
    val focusState: FocusState,
    val deviceState: DeviceState,
    val sourceSignals: List<String>
) {
    companion object {
        fun initial() = UserContext(
            timestamp = System.currentTimeMillis(),
            movementState = MovementState.UNKNOWN,
            currentApp = null,
            focusState = FocusState.UNKNOWN,
            deviceState = DeviceState(batteryLevel = 100, charging = false, thermal = ThermalLevel.UNKNOWN),
            sourceSignals = emptyList()
        )
    }
}
