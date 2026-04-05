package com.example.photonid.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Device motion stability controller using accelerometer + gyroscope.
 *
 * Detects whether the user is holding the phone steady enough for
 * accurate corneal glint capture. Provides:
 * - Real-time stability score (0–100%)
 * - Tilt angles (pitch, roll) for UI guidance arrows
 * - "Stable" flag when movement is below threshold for sustained period
 *
 * Usage in eKYC: ensures the camera is steady during the liveness
 * challenge so that glint extraction is not corrupted by motion blur.
 */
class MotionStabilityController(context: Context) : SensorEventListener {

    companion object {
        // Thresholds
        private const val GYRO_STABLE_THRESHOLD = 0.08f    // rad/s — below this = stable
        private const val ACCEL_STABLE_THRESHOLD = 0.5f    // m/s² deviation from gravity
        private const val STABILITY_REQUIRED_MS = 500L     // must be stable for 500ms
        private const val IDEAL_PITCH_MIN = -15f           // degrees — phone slightly tilted
        private const val IDEAL_PITCH_MAX = 15f
        private const val IDEAL_ROLL_MIN = -10f
        private const val IDEAL_ROLL_MAX = 10f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // ── Public state ───────────────────────────────────────────
    var stabilityScore: Float = 0f; private set       // 0–100%
    var isStable: Boolean = false; private set
    var pitch: Float = 0f; private set                 // degrees
    var roll: Float = 0f; private set                  // degrees
    var gyroMagnitude: Float = 0f; private set         // rad/s
    var accelDeviation: Float = 0f; private set        // m/s² from gravity

    // Guidance messages
    val guidanceMessage: String
        get() = when {
            !isStable && gyroMagnitude > GYRO_STABLE_THRESHOLD * 3 -> "Hold your phone steady"
            pitch < IDEAL_PITCH_MIN -> "Tilt phone up ↑"
            pitch > IDEAL_PITCH_MAX -> "Tilt phone down ↓"
            roll < IDEAL_ROLL_MIN -> "Tilt phone right →"
            roll > IDEAL_ROLL_MAX -> "Tilt phone left ←"
            !isStable -> "Almost there... hold still"
            else -> "✓ Steady"
        }

    val isIdealAngle: Boolean
        get() = pitch in IDEAL_PITCH_MIN..IDEAL_PITCH_MAX &&
                roll in IDEAL_ROLL_MIN..IDEAL_ROLL_MAX

    // ── Internal ───────────────────────────────────────────────
    private var lastStableTimestamp = 0L
    private val gravity = FloatArray(3)
    private var hasGravity = false

    // Smoothing buffers (simple moving average)
    private val gyroBuffer = ArrayDeque<Float>(20)
    private val accelBuffer = ArrayDeque<Float>(20)
    private val bufferSize = 15

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event.values)
            Sensor.TYPE_GYROSCOPE -> processGyroscope(event.values)
        }
        updateStability()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processAccelerometer(values: FloatArray) {
        // Low-pass filter to isolate gravity
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2]
        hasGravity = true

        // Linear acceleration (motion without gravity)
        val linX = values[0] - gravity[0]
        val linY = values[1] - gravity[1]
        val linZ = values[2] - gravity[2]
        accelDeviation = sqrt(linX * linX + linY * linY + linZ * linZ)

        // Smooth
        accelBuffer.addLast(accelDeviation)
        if (accelBuffer.size > bufferSize) accelBuffer.removeFirst()

        // Calculate tilt angles from gravity vector
        val gMag = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gMag > 0.1f) {
            pitch = Math.toDegrees(
                Math.asin((gravity[1] / gMag).toDouble().coerceIn(-1.0, 1.0))
            ).toFloat()
            roll = Math.toDegrees(
                Math.asin((gravity[0] / gMag).toDouble().coerceIn(-1.0, 1.0))
            ).toFloat()
        }
    }

    private fun processGyroscope(values: FloatArray) {
        gyroMagnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])

        gyroBuffer.addLast(gyroMagnitude)
        if (gyroBuffer.size > bufferSize) gyroBuffer.removeFirst()
    }

    private fun updateStability() {
        val smoothGyro = if (gyroBuffer.isNotEmpty()) gyroBuffer.average().toFloat() else gyroMagnitude
        val smoothAccel = if (accelBuffer.isNotEmpty()) accelBuffer.average().toFloat() else accelDeviation

        val gyroStability = ((1f - (smoothGyro / (GYRO_STABLE_THRESHOLD * 5f)).coerceIn(0f, 1f)) * 100f)
        val accelStability = ((1f - (smoothAccel / (ACCEL_STABLE_THRESHOLD * 5f)).coerceIn(0f, 1f)) * 100f)
        val angleStability = if (isIdealAngle) 100f else 60f

        stabilityScore = (gyroStability * 0.4f + accelStability * 0.4f + angleStability * 0.2f)
            .coerceIn(0f, 100f)

        val instantStable = smoothGyro < GYRO_STABLE_THRESHOLD &&
                smoothAccel < ACCEL_STABLE_THRESHOLD

        val now = System.currentTimeMillis()
        if (instantStable) {
            if (lastStableTimestamp == 0L) lastStableTimestamp = now
            isStable = (now - lastStableTimestamp) >= STABILITY_REQUIRED_MS
        } else {
            lastStableTimestamp = 0L
            isStable = false
        }
    }
}
