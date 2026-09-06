package com.livingai.app.companion

import com.livingai.app.ai.model.AIResponse
import com.livingai.app.ai.model.CompanionEmotion
import com.livingai.app.context.MovementState
import com.livingai.app.context.UserContext
import com.livingai.app.core.LivingAiLog
import com.livingai.app.core.ThermalLevel
import com.livingai.app.focus.FocusSession
import com.livingai.app.focus.FocusSessionStatus
import com.livingai.app.focus.InterventionDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val INACTIVITY_TO_SLEEP_MS = 3 * 60 * 1000L
private const val LOW_BATTERY_THRESHOLD = 15
private const val TRANSIENT_MOOD_MS = 2_500L
private const val CELEBRATION_MS = 4_000L

/**
 * Maps [UserContext] plus focus-session/intervention events to a [CompanionState]. Rule-based
 * on purpose — the character's mood must never look random, it must be explainable from what
 * produced it. This remains the single boundary other systems (FocusEngine) react through;
 * they never touch [CompanionState] directly.
 */
class CompanionStateMachine(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(CompanionState())
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private var lastNonStillAt = System.currentTimeMillis()
    private var lastContext: UserContext? = null
    private var lastFocusSession: FocusSession? = null

    /** Non-null while a transient/manual mood (warning, celebration, acknowledgement) overrides
     * the normal context-driven mapping. Warnings clear via [dismissIntervention]; the rest
     * clear themselves after a short timer. */
    private var pinnedActivity: CompanionActivity? = null

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

    fun onFocusSessionChanged(session: FocusSession?) {
        lastFocusSession = session
        lastContext?.let { evaluate(it) }
    }

    fun onFocusStarted() {
        pinTransient(CompanionActivity.DETERMINED, null)
    }

    fun onFocusCompleted(goalTitle: String?) {
        pinTransient(CompanionActivity.CELEBRATING, CompanionMessages.forCelebration(goalTitle), durationMs = CELEBRATION_MS, autoExpand = true)
        LivingAiLog.event("COMPANION_STATE", "FOCUS_SESSION completed -> CELEBRATING")
    }

    fun onGoalSet(goalTitle: String) {
        pinTransient(CompanionActivity.HAPPY, "Got it — \"$goalTitle\". I'll remember that.", autoExpand = true)
    }

    fun onInterventionDecision(decision: InterventionDecision, goalTitle: String?) {
        if (!decision.shouldInterrupt) return
        pinnedActivity = CompanionActivity.WARNING
        _state.value = _state.value.copy(
            activity = CompanionActivity.WARNING,
            message = CompanionMessages.forDistraction(decision.reason, goalTitle),
            expanded = true
        )
        LivingAiLog.event("COMPANION_STATE", "activity -> WARNING (${decision.reason})")
    }

    /** User tapped "Back to focus" on an intervention bubble. */
    fun dismissIntervention() {
        if (pinnedActivity != CompanionActivity.WARNING) return
        pinnedActivity = null
        _state.value = _state.value.copy(expanded = false, message = null)
        lastContext?.let { evaluate(it) }
    }

    fun onTap() {
        _state.value = _state.value.copy(expanded = !_state.value.expanded)
    }

    // --- Camera + Voice + AI lifecycle reactions (Phase 9) ---

    fun onCameraOpened() = pinIndefinite(CompanionActivity.CAMERA_HELP)

    fun onCameraClosed() {
        if (pinnedActivity == CompanionActivity.CAMERA_HELP) {
            pinnedActivity = null
            lastContext?.let { evaluate(it) }
        }
    }

    fun onListening() = pinIndefinite(CompanionActivity.LISTENING)

    fun onThinking() = pinIndefinite(CompanionActivity.THINKING)

    /** User cancelled mid-listen or mid-inference; drop back to whatever context/focus dictates. */
    fun onAiCancelled() {
        if (pinnedActivity == CompanionActivity.LISTENING || pinnedActivity == CompanionActivity.THINKING) {
            pinnedActivity = null
            lastContext?.let { evaluate(it) }
        }
    }

    fun onAiResult(response: AIResponse) {
        val activity = when (response.emotion) {
            CompanionEmotion.THINKING -> CompanionActivity.THINKING
            CompanionEmotion.EXPLAINING -> CompanionActivity.EXPLAINING
            CompanionEmotion.HAPPY -> CompanionActivity.HAPPY
            CompanionEmotion.CONFUSED -> CompanionActivity.CONFUSED
            CompanionEmotion.WARNING -> CompanionActivity.WARNING
        }
        pinTransient(activity, response.text, durationMs = 12_000L, autoExpand = true)
    }

    private fun pinIndefinite(activity: CompanionActivity, message: String? = null) {
        pinnedActivity = activity
        _state.value = _state.value.copy(activity = activity, message = message, expanded = message != null)
    }

    private fun pinTransient(activity: CompanionActivity, message: String?, durationMs: Long = TRANSIENT_MOOD_MS, autoExpand: Boolean = false) {
        pinnedActivity = activity
        _state.value = _state.value.copy(activity = activity, message = message ?: _state.value.message, expanded = autoExpand || _state.value.expanded)
        scope.launch {
            delay(durationMs)
            if (pinnedActivity == activity) {
                pinnedActivity = null
                lastContext?.let { evaluate(it) } ?: run { _state.value = _state.value.copy(message = null, expanded = false) }
            }
        }
    }

    private fun evaluate(context: UserContext) {
        if (pinnedActivity != null) return // a warning/celebration/acknowledgement is showing; don't overwrite it

        val now = System.currentTimeMillis()
        if (context.movementState != MovementState.STILL) {
            lastNonStillAt = now
        }

        val session = lastFocusSession
        val next = when {
            context.deviceState.thermal == ThermalLevel.SEVERE ||
                context.deviceState.thermal == ThermalLevel.CRITICAL_OR_WORSE ->
                CompanionActivity.WARNING to "Phone's running hot — I'll take it easy for a bit."

            context.deviceState.batteryLevel <= LOW_BATTERY_THRESHOLD && !context.deviceState.charging ->
                CompanionActivity.WARNING to "Battery's low (${context.deviceState.batteryLevel}%)."

            session?.status == FocusSessionStatus.RUNNING -> CompanionActivity.STUDYING to null
            session?.status == FocusSessionStatus.PAUSED -> CompanionActivity.RESTING to null

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
}
