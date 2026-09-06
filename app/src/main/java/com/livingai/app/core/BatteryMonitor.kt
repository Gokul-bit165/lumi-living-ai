package com.livingai.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

data class BatterySnapshot(val level: Int, val isCharging: Boolean)

/**
 * Real battery signal via the sticky ACTION_BATTERY_CHANGED broadcast — no polling loop,
 * the OS wakes us only when the state actually changes.
 */
class BatteryMonitor(context: Context, scope: kotlinx.coroutines.CoroutineScope) {

    private val appContext = context.applicationContext

    private val updates: Flow<BatterySnapshot> = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                trySend(intent.toSnapshot())
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = appContext.registerReceiver(receiver, filter)
        sticky?.let { trySend(it.toSnapshot()) }
        awaitClose { appContext.unregisterReceiver(receiver) }
    }

    val snapshot: StateFlow<BatterySnapshot> = updates.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = BatterySnapshot(level = 100, isCharging = false)
    )

    private fun Intent.toSnapshot(): BatterySnapshot {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatterySnapshot(pct, charging)
    }
}
