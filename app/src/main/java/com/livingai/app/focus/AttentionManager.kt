package com.livingai.app.focus

import com.livingai.app.core.LivingAiLog

/**
 * Decides INTERRUPT vs IGNORE. "Do nothing" is a first-class, expected outcome here — most
 * evaluations should end in IGNORE, or the companion would be constantly nagging the user.
 *
 * `now` is a parameter (not read internally) so cooldown behavior is deterministic and testable
 * without real delays.
 */
class AttentionManager(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE
) {
    // Nullable: 0L would otherwise be indistinguishable from "just intervened at time zero" and
    // put a fresh AttentionManager into a false cooldown on its very first evaluation.
    private var lastInterventionAt: Long? = null

    fun evaluate(
        candidate: DistractionCandidate?,
        focusSessionActive: Boolean,
        now: Long = System.currentTimeMillis()
    ): InterventionDecision {
        val sinceLast = lastInterventionAt?.let { now - it }
        val decision = when {
            !focusSessionActive ->
                ignore("no_active_session", candidate?.confidence ?: 0f)

            candidate == null ->
                ignore("no_distraction", 0f)

            candidate.confidence < minConfidence ->
                ignore("low_confidence", candidate.confidence)

            sinceLast != null && sinceLast < cooldownMs ->
                ignore("cooldown", candidate.confidence, cooldownRemainingMs = cooldownMs - sinceLast)

            else -> {
                lastInterventionAt = now
                InterventionDecision(
                    shouldInterrupt = true,
                    reason = candidate.reason,
                    confidence = candidate.confidence,
                    cooldownRemainingMs = 0L
                )
            }
        }
        LivingAiLog.event("INTERVENTION_DECISION", "$decision")
        return decision
    }

    /** How much cooldown is left right now, for the debug screen — doesn't consume it. */
    fun cooldownRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val last = lastInterventionAt ?: return 0L
        return (cooldownMs - (now - last)).coerceAtLeast(0L)
    }

    private fun ignore(reason: String, confidence: Float, cooldownRemainingMs: Long = 0L) =
        InterventionDecision(shouldInterrupt = false, reason = reason, confidence = confidence, cooldownRemainingMs = cooldownRemainingMs)

    companion object {
        const val DEFAULT_COOLDOWN_MS = 120_000L // 2 minutes; lower this for demo mode.
        const val DEFAULT_MIN_CONFIDENCE = 0.5f
    }
}
