package com.livingai.app.focus

import com.livingai.app.core.LivingAiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-memory focus-session state machine (start/pause/resume/end). Deliberately not persisted —
 * only the goal needs to survive a restart per V1 scope; a session interrupted by a process
 * death simply ends. The 1s tick only runs while a session is RUNNING, never in the background.
 */
class FocusSessionManager(private val scope: CoroutineScope) {

    private val _session = MutableStateFlow<FocusSession?>(null)
    val session: StateFlow<FocusSession?> = _session.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var tickJob: Job? = null

    fun start(goalId: String) {
        _session.value = FocusSession(goalId, startedAt = System.currentTimeMillis(), accumulatedMs = 0L, status = FocusSessionStatus.RUNNING)
        _elapsedMs.value = 0L
        startTicking()
        LivingAiLog.event("FOCUS_EVENT", "FOCUS_SESSION_STARTED goalId=$goalId")
    }

    fun pause() {
        val current = _session.value ?: return
        if (current.status != FocusSessionStatus.RUNNING) return
        stopTicking()
        val accumulated = current.accumulatedMs + (System.currentTimeMillis() - current.startedAt)
        _session.value = current.copy(accumulatedMs = accumulated, status = FocusSessionStatus.PAUSED)
        _elapsedMs.value = accumulated
        LivingAiLog.event("FOCUS_EVENT", "FOCUS_SESSION_PAUSED elapsedMs=$accumulated")
    }

    fun resume() {
        val current = _session.value ?: return
        if (current.status != FocusSessionStatus.PAUSED) return
        _session.value = current.copy(startedAt = System.currentTimeMillis(), status = FocusSessionStatus.RUNNING)
        startTicking()
        LivingAiLog.event("FOCUS_EVENT", "FOCUS_SESSION_RESUMED")
    }

    fun end() {
        val current = _session.value ?: return
        stopTicking()
        val accumulated = if (current.status == FocusSessionStatus.RUNNING) {
            current.accumulatedMs + (System.currentTimeMillis() - current.startedAt)
        } else {
            current.accumulatedMs
        }
        _session.value = current.copy(accumulatedMs = accumulated, status = FocusSessionStatus.ENDED)
        _elapsedMs.value = accumulated
        LivingAiLog.event("FOCUS_EVENT", "FOCUS_SESSION_ENDED elapsedMs=$accumulated")
    }

    private fun startTicking() {
        stopTicking()
        tickJob = scope.launch {
            while (true) {
                val current = _session.value
                if (current == null || current.status != FocusSessionStatus.RUNNING) break
                _elapsedMs.value = current.accumulatedMs + (System.currentTimeMillis() - current.startedAt)
                delay(1_000L)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }
}
