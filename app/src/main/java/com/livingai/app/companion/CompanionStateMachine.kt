package com.livingai.app.companion

import com.livingai.app.context.MovementState
import com.livingai.app.context.UserContext
import com.livingai.app.core.LivingAiLog
import com.livingai.app.core.ThermalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val INACTIVITY_TO_SLEEP_MS = 3 * 60 * 1000L
private const val LOW_BATTERY_THRESHOLD = 15

/**
 * Maps [UserContext] to a [CompanionState]. Rule-based on purpose — the character's mood must
 * never look random, it must be explainable from the context that produced it.
 */
class CompanionStateMachine(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(CompanionState())
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private var lastNonStillAt = System.currentTimeMillis()
    private var lastContext: UserContext? = null

    init {
        // Re-evaluate periodically so SLEEPING can trigger from time passing alone, not only
        // when a new sensor/usage/battery event happens to arrive.
        scope.launch {
            while (true) {
                delay(30_000L)
                lastContext?.let { evaluate(it) }
            }
        }
    }

    fun onContextChanged(context: UserContext) {
        lastContext = context
        evaluate(context)
    }

    private fun evaluate(context: UserContext) {
        val now = System.currentTimeMillis()
        if (context.movementState != MovementState.STILL) {
            lastNonStillAt = now
        }

        val next = when {
            context.deviceState.thermal == ThermalLevel.SEVERE ||
                context.deviceState.thermal == ThermalLevel.CRITICAL_OR_WORSE ->
                CompanionActivity.WARNING to "Phone's running hot — I'll take it easy for a bit."

            context.deviceState.batteryLevel <= LOW_BATTERY_THRESHOLD && !context.deviceState.charging ->
                CompanionActivity.WARNING to "Battery's low (${context.deviceState.batteryLevel}%)."

            context.movementState == MovementState.WALKING || context.movementState == MovementState.ACTIVE ->
                CompanionActivity.WALKING to null

            now - lastNonStillAt > INACTIVITY_TO_SLEEP_MS ->
                CompanionActivity.SLEEPING to null

            else -> CompanionActivity.IDLE to null
        }

        val updated = _state.value.copy(activity = next.first, message = next.second ?: _state.value.message.takeIf { _state.value.expanded })
        if (updated.activity != _state.value.activity) {
            LivingAiLog.event("COMPANION_STATE", "activity -> ${updated.activity}")
        }
        _state.value = updated
    }

    fun onTap() {
        _state.value = _state.value.copy(expanded = !_state.value.expanded)
    }
}
