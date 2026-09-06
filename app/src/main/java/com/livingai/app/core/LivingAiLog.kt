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
        try {
            Log.i(TAG, "[$type] $message")
        } catch (_: RuntimeException) {
            // android.util.Log is unavailable in plain JVM unit tests (no Robolectric) —
            // fall back so test runs don't crash on logging alone.
            println("$TAG [$type] $message")
        }
    }
}
