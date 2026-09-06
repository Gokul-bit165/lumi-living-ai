package com.livingai.app.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL_OR_WORSE, UNKNOWN }

/**
 * Real thermal signal via PowerManager.addThermalStatusListener (API 29+).
 * Below API 29 there is no public thermal API, so we report UNKNOWN rather than fake a value.
 */
class ThermalMonitor(context: Context, private val scope: CoroutineScope) {

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _level = MutableStateFlow(ThermalLevel.UNKNOWN)
    val level: StateFlow<ThermalLevel> = _level.asStateFlow()

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _level.value = ThermalLevel.UNKNOWN
            return
        }
        _level.value = powerManager.currentThermalStatus.toLevel()
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            _level.value = status.toLevel()
        }
        listener = l
        powerManager.addThermalStatusListener(l)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listener?.let { powerManager.removeThermalStatusListener(it) }
        }
        listener = null
    }

    private fun Int.toLevel(): ThermalLevel = when (this) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        else -> ThermalLevel.CRITICAL_OR_WORSE
    }
}
