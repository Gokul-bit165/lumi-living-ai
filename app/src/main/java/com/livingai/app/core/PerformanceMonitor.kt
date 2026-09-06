package com.livingai.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RuntimeStatus { GREEN, YELLOW, RED }

/**
 * Combines real battery + thermal signals into one coarse status the rest of the app can act on
 * without re-deriving thresholds everywhere. Deliberately simple thresholds — this is a hackathon
 * safety valve, not a tuned power-management model.
 */
class PerformanceMonitor(
    private val batteryMonitor: BatteryMonitor,
    private val thermalMonitor: ThermalMonitor,
    scope: CoroutineScope
) {
    private val _runtimeStatus = MutableStateFlow(RuntimeStatus.GREEN)
    val runtimeStatus: StateFlow<RuntimeStatus> = _runtimeStatus.asStateFlow()

    init {
        scope.launch {
            batteryMonitor.snapshot.collect { recompute() }
        }
        scope.launch {
            thermalMonitor.level.collect { recompute() }
        }
    }

    private fun recompute() {
        val battery = batteryMonitor.snapshot.value
        val thermal = thermalMonitor.level.value

        val status = when {
            thermal == ThermalLevel.SEVERE || thermal == ThermalLevel.CRITICAL_OR_WORSE -> RuntimeStatus.RED
            !battery.isCharging && battery.level <= 15 -> RuntimeStatus.RED
            thermal == ThermalLevel.MODERATE -> RuntimeStatus.YELLOW
            !battery.isCharging && battery.level <= 30 -> RuntimeStatus.YELLOW
            else -> RuntimeStatus.GREEN
        }

        if (status != _runtimeStatus.value) {
            LivingAiLog.event("PERFORMANCE_STATE", "runtimeStatus -> $status (battery=${battery.level}%, thermal=$thermal)")
        }
        _runtimeStatus.value = status
    }
}
