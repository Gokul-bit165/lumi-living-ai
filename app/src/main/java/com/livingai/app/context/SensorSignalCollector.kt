package com.livingai.app.context

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.livingai.app.core.LivingAiLog
import kotlin.math.sqrt

/**
 * Real accelerometer + gyroscope collection.
 *
 * Deliberately event-driven and cheap: registered at SENSOR_DELAY_NORMAL (~200ms), and we only
 * emit a [MovementState] change when a rolling-average magnitude crosses a threshold —
 * never a raw per-event callback to the rest of the app. This is the one signal source in V1
 * that genuinely needs continuous sensor registration, so it batches internally to stay cheap.
 */
class SensorSignalCollector(
    context: Context,
    private val onMovementChanged: (MovementState) -> Unit
) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var runningMagnitude = 0f
    private var lastState = MovementState.UNKNOWN
    private var sampleCount = 0

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (accelerometer == null) {
            LivingAiLog.event("CONTEXT_EVENT", "No accelerometer on this device — movement stays UNKNOWN")
            onMovementChanged(MovementState.UNKNOWN)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val (x, y, z) = event.values
        val magnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
        // Exponential moving average — smooths noise without buffering a window of samples.
        runningMagnitude = 0.8f * runningMagnitude + 0.2f * kotlin.math.abs(magnitude)
        sampleCount++

        // Only re-classify (and only notify on change) every ~1s worth of samples, not per event.
        if (sampleCount % 5 != 0) return

        val newState = when {
            runningMagnitude < 0.15f -> MovementState.STILL
            runningMagnitude < 1.2f -> MovementState.WALKING
            else -> MovementState.ACTIVE
        }
        if (newState != lastState) {
            lastState = newState
            LivingAiLog.event("CONTEXT_EVENT", "MOVEMENT_CHANGED -> $newState (mag=$runningMagnitude)")
            onMovementChanged(newState)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
