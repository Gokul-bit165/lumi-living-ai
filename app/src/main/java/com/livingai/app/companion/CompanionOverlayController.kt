package com.livingai.app.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Boundary between "where the companion is drawn" and everything else. V1 implements it as an
 * in-app overlay (a Compose box drawn above the host screen's content) because a true
 * system-wide SYSTEM_ALERT_WINDOW overlay needs its own permission flow and foreground service —
 * out of scope for V1's demo. Swapping in a real system overlay later means only replacing
 * this implementation.
 */
interface CompanionOverlayController {
    val visible: StateFlow<Boolean>
    fun show()
    fun hide()
}

class InAppCompanionOverlayController : CompanionOverlayController {
    private val _visible = MutableStateFlow(true)
    override val visible: StateFlow<Boolean> = _visible.asStateFlow()

    override fun show() { _visible.value = true }
    override fun hide() { _visible.value = false }
}
