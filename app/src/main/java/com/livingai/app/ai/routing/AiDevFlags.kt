package com.livingai.app.ai.routing

/**
 * Explicit, single-place developer flag. Must be flipped by hand in source — never toggled by
 * runtime state — so the mock can never silently activate in a demo build.
 */
object AiDevFlags {
    const val USE_MOCK_TEXT_MODEL = false
}
