package com.livingai.app.focus

enum class GoalPriority { LOW, MEDIUM, HIGH }

/** V1 supports a single active goal at a time — matches the product spec's "active goal" concept. */
data class Goal(
    val id: String,
    val title: String,
    val deadline: Long?,
    val priority: GoalPriority,
    val progress: Float = 0f
)

enum class FocusSessionStatus { RUNNING, PAUSED, ENDED }

data class FocusSession(
    val goalId: String,
    val startedAt: Long,
    val accumulatedMs: Long,
    val status: FocusSessionStatus
)

/** A rule-based signal that *might* warrant an interruption — not yet a decision. */
data class DistractionCandidate(val confidence: Float, val reason: String)

data class InterventionDecision(
    val shouldInterrupt: Boolean,
    val reason: String,
    val confidence: Float,
    val cooldownRemainingMs: Long
)
