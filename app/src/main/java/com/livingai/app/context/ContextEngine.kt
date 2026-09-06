package com.livingai.app.context

import android.content.Context
import com.livingai.app.core.BatteryMonitor
import com.livingai.app.core.LivingAiLog
import com.livingai.app.core.PermissionManager
import com.livingai.app.core.ThermalMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Boundary interface: everything else in the app (companion, later focus/attention) reacts to
 * [currentContext] and must not know how signals were collected.
 */
interface ContextEngine {
    val currentContext: StateFlow<UserContext>
    fun start()
    fun stop()
}

/**
 * Combines real hardware signals into one [UserContext]:
 *  - accelerometer/gyroscope: event-driven (pushed by the sensor listener on real change)
 *  - usage stats: polled on a slow interval (only if usage access is granted)
 *  - battery/thermal: pushed by the OS via their own monitors
 * No signal here invokes any AI model — this is pure rule-based aggregation.
 */
class ContextEngineImpl(
    context: Context,
    private val scope: CoroutineScope,
    private val permissionManager: PermissionManager,
    private val batteryMonitor: BatteryMonitor,
    private val thermalMonitor: ThermalMonitor,
    private val usagePollIntervalMs: Long = 8_000L
) : ContextEngine {

    private val appContext = context.applicationContext
    private val usageStatsCollector = UsageStatsCollector(appContext)
    private val sensorCollector = SensorSignalCollector(appContext) { movement ->
        updateContext { it.copy(movementState = movement) }
    }

    private val _currentContext = MutableStateFlow(UserContext.initial())
    override val currentContext: StateFlow<UserContext> = _currentContext.asStateFlow()

    private var usagePollJob: Job? = null
    private var hardwareCollectJob: Job? = null

    override fun start() {
        sensorCollector.start()
        thermalMonitor.start()

        hardwareCollectJob = scope.launch {
            batteryMonitor.snapshot.collect { battery ->
                updateContext {
                    it.copy(deviceState = it.deviceState.copy(batteryLevel = battery.level, charging = battery.isCharging))
                }
            }
        }
        scope.launch {
            thermalMonitor.level.collect { thermal ->
                updateContext { it.copy(deviceState = it.deviceState.copy(thermal = thermal)) }
            }
        }

        if (permissionManager.hasUsageAccess()) {
            usagePollJob = scope.launch {
                while (true) {
                    val app = usageStatsCollector.currentForegroundApp()
                    updateContext { it.copy(currentApp = app) }
                    delay(usagePollIntervalMs)
                }
            }
        } else {
            LivingAiLog.event("CONTEXT_EVENT", "Usage access not granted — currentApp stays null")
        }
    }

    override fun stop() {
        sensorCollector.stop()
        thermalMonitor.stop()
        usagePollJob?.cancel()
        hardwareCollectJob?.cancel()
    }

    private fun updateContext(transform: (UserContext) -> UserContext) {
        // Battery, thermal and usage updates can race on different coroutines; `update` applies
        // the transform atomically (compare-and-set + retry) instead of a plain read-then-write,
        // which was dropping concurrent updates.
        _currentContext.update { current ->
            transform(current).copy(
                timestamp = System.currentTimeMillis(),
                sourceSignals = buildList {
                    add("sensors")
                    if (permissionManager.hasUsageAccess()) add("usage_stats")
                    add("battery")
                    add("thermal")
                }
            )
        }
        LivingAiLog.event("CONTEXT_EVENT", "context=${_currentContext.value}")
    }
}
