package com.livingai.app.companion

/** Trimmed to states V1's rules actually produce — not the full future roster. */
enum class CompanionActivity {
    SLEEPING, IDLE, WALKING, WARNING, HAPPY, DETERMINED, STUDYING, RESTING, CELEBRATING
}

data class CompanionState(
    val activity: CompanionActivity = CompanionActivity.IDLE,
    val message: String? = null,
    val expanded: Boolean = false
)
