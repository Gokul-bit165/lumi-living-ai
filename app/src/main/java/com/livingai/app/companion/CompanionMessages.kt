package com.livingai.app.companion

/**
 * Message text lives here, not inside FocusEngine — the engine only knows *that* it decided to
 * interrupt and *why* (a reason code), not the exact words the character says.
 */
object CompanionMessages {

    fun forDistraction(reason: String, goalTitle: String?): String {
        val goal = goalTitle ?: "your goal"
        return when {
            reason.startsWith("distracting_app") -> "Hey 😾 — you wanted to focus on \"$goal\" first."
            reason == "unknown_app_inactive" -> "Still there? Let's get back to \"$goal\"."
            else -> "Let's get back to \"$goal\"."
        }
    }

    fun forCelebration(goalTitle: String?): String {
        val goal = goalTitle ?: "your goal"
        return "Nice work! You focused on \"$goal\". 🎉"
    }

    fun forFocusStarted(goalTitle: String?): String {
        val goal = goalTitle ?: "your goal"
        return "Let's do this — focusing on \"$goal\"."
    }
}
