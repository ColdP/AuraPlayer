// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.lyrics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Integrates gyroscope angular velocities into bounded [-1f, 1f] spatial parallax coordinates
 * with soft auto-recentering, evoking iOS Spatial Scenes depth response around any holding pose.
 */
internal class SpatialGyroFilter(
    private val sensitivity: Float = 2.65f,
    private val recenterRatePerSec: Float = 1.15f,
    private val smoothRatePerSec: Float = 15f,
) {
    private var targetX = 0f
    private var targetY = 0f
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set

    fun ingestAngularVelocity(
        wxRadPerSec: Float,
        wyRadPerSec: Float,
        dtSec: Float,
        displayRotation: Int = Surface.ROTATION_0,
    ) {
        val dt = dtSec.coerceIn(0f, 0.08f)
        if (dt <= 0f) return
        val (yawRate, pitchRate) = remapAxes(wxRadPerSec, wyRadPerSec, displayRotation)
        targetX = (targetX + yawRate * dt * sensitivity).coerceIn(-1.45f, 1.45f)
        targetY = (targetY + pitchRate * dt * sensitivity).coerceIn(-1.45f, 1.45f)
    }

    fun step(dtSec: Float): Pair<Float, Float> {
        val dt = dtSec.coerceIn(0f, 0.05f)
        if (dt <= 0f) return x to y
        val decay = exp(-dt * recenterRatePerSec)
        targetX *= decay
        targetY *= decay
        val desiredX = tanh(targetX)
        val desiredY = tanh(targetY)
        val blend = 1f - exp(-dt * smoothRatePerSec)
        x += (desiredX - x) * blend
        y += (desiredY - y) * blend
        if (abs(x) < 0.0005f && abs(targetX) < 0.0005f) x = 0f
        if (abs(y) < 0.0005f && abs(targetY) < 0.0005f) y = 0f
        return x to y
    }

    fun reset() {
        targetX = 0f
        targetY = 0f
        x = 0f
        y = 0f
    }

    companion object {
        internal fun remapAxes(
            wx: Float,
            wy: Float,
            displayRotation: Int,
        ): Pair<Float, Float> = when (displayRotation) {
            Surface.ROTATION_90 -> -wx to wy
            Surface.ROTATION_180 -> -wy to -wx
            Surface.ROTATION_270 -> wx to -wy
            else -> wy to wx
        }
    }
}

/**
 * Observes device gyroscope motion while [enabled] is true and produces smooth 60fps
 * normalized spatial tilt in [-1f, 1f], gracefully settling back to center when disabled.
 */
@Composable
internal fun rememberSpatialGyroTilt(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val filter = remember { SpatialGyroFilter() }
    val tiltState = remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(enabled, context) {
        if (!enabled) {
            filter.reset()
            onDispose {}
        } else {
            val sensorManager = context.getSystemService(SensorManager::class.java)
            val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val fallbackRot = if (gyro == null) {
                sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                    ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            } else {
                null
            }
            var lastEventNs = 0L
            var hasPrevAngles = false
            val rotMatrix = FloatArray(9)
            val angles = FloatArray(3)
            var prevPitch = 0f
            var prevRoll = 0f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val rotation = runCatching { context.display?.rotation ?: Surface.ROTATION_0 }
                        .getOrDefault(Surface.ROTATION_0)
                    val dtSec = if (lastEventNs == 0L) 0.016f else ((event.timestamp - lastEventNs) / 1e9f).coerceIn(0.001f, 0.08f)
                    lastEventNs = event.timestamp
                    when (event.sensor.type) {
                        Sensor.TYPE_GYROSCOPE -> {
                            filter.ingestAngularVelocity(
                                wxRadPerSec = event.values[0],
                                wyRadPerSec = event.values[1],
                                dtSec = dtSec,
                                displayRotation = rotation,
                            )
                        }
                        Sensor.TYPE_GAME_ROTATION_VECTOR,
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                            SensorManager.getOrientation(rotMatrix, angles)
                            val pitch = angles[1]
                            val roll = angles[2]
                            if (hasPrevAngles) {
                                val wx = (pitch - prevPitch) / dtSec
                                val wy = (roll - prevRoll) / dtSec
                                filter.ingestAngularVelocity(
                                    wxRadPerSec = wx,
                                    wyRadPerSec = wy,
                                    dtSec = dtSec,
                                    displayRotation = rotation,
                                )
                            }
                            prevPitch = pitch
                            prevRoll = roll
                            hasPrevAngles = true
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            val registeredSensor = gyro ?: fallbackRot
            if (registeredSensor != null) {
                sensorManager?.registerListener(listener, registeredSensor, SensorManager.SENSOR_DELAY_GAME)
            }
            onDispose {
                if (registeredSensor != null) {
                    sensorManager?.unregisterListener(listener)
                }
            }
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            filter.reset()
            var current = tiltState.value
            while (abs(current.x) > 0.002f || abs(current.y) > 0.002f) {
                withFrameNanos { }
                current = Offset(current.x * 0.82f, current.y * 0.82f)
                tiltState.value = current
            }
            tiltState.value = Offset.Zero
            return@LaunchedEffect
        }
        var lastNs = withFrameNanos { it }
        while (true) {
            val nowNs = withFrameNanos { it }
            val dtSec = ((nowNs - lastNs) / 1e9f).coerceIn(0f, 0.05f)
            lastNs = nowNs
            val (sx, sy) = filter.step(dtSec)
            val current = tiltState.value
            if (abs(sx - current.x) > 0.0008f || abs(sy - current.y) > 0.0008f) {
                tiltState.value = Offset(sx, sy)
            }
        }
    }

    return tiltState
}

/**
 * Scales normalized [-1f, 1f] gyroscope tilt by user sensitivity so subtle tilts feel
 * livelier at higher settings while peak deflection remains smoothly bounded.
 */
internal fun scaleSpatialParallaxTilt(tilt: Offset, sensitivity: Float): Offset {
    if (tilt == Offset.Zero) return Offset.Zero
    val gain = sensitivity.takeIf { it.isFinite() }?.coerceIn(0.2f, 2.5f) ?: 1.0f
    if (gain == 1.0f) return tilt
    val maxReach = (0.72f + 0.34f * gain).coerceIn(0.2f, 1.55f)
    return Offset(
        x = tanh((tilt.x * gain / maxReach).toDouble()).toFloat() * maxReach,
        y = tanh((tilt.y * gain / maxReach).toDouble()).toFloat() * maxReach,
    )
}
