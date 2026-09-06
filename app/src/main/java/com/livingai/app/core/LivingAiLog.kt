package com.livingai.app.core

import android.util.Log

/**
 * Structured logging: every call is tagged with an event type so logcat can be
 * filtered per subsystem (e.g. `logcat -s LivingAI:CONTEXT_EVENT`).
 * Never pass raw camera/audio bytes through this — only derived, human-readable state.
 */
object LivingAiLog {
    private const val TAG = "LivingAI"

    fun event(type: String, message: String) {
        Log.d(TAG, "[$type] $message")
    }
}
