package com.livingai.app.focus

import com.livingai.app.context.MovementState
import com.livingai.app.context.UserContext

/**
 * Deterministic, rule-based distraction candidate detection. Never invokes an AI model —
 * this is the cheap first pass that decides whether AttentionManager even needs to think about
 * intervening.
 */
class DistractionDetector(
    private val distractingApps: Set<String> = DEFAULT_DISTRACTING_APPS
) {
    fun evaluate(context: UserContext, focusSessionActive: Boolean): DistractionCandidate? {
        if (!focusSessionActive) return null

        val app = context.currentApp
        return when {
            app != null && app in distractingApps ->
                DistractionCandidate(confidence = 0.9f, reason = "distracting_app:$app")

            // No usage-access signal available, but the device has been still for a while during
            // an active session — worth a low-confidence nudge, not a confident interruption.
            app == null && context.movementState == MovementState.STILL ->
                DistractionCandidate(confidence = 0.3f, reason = "unknown_app_inactive")

            else -> null
        }
    }

    companion object {
        val DEFAULT_DISTRACTING_APPS = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.google.android.youtube",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.x.android"
        )
    }
}
