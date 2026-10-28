package com.uprace.flutter_tracking_gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** One reading of the device's cumulative step counter. */
internal data class StepSample(
    /** Steps since the device booted (`TYPE_STEP_COUNTER`). */
    val raw: Long,
    /** Sensor event time on the monotonic clock (`elapsedRealtimeNanos`), ms. */
    val elapsedMs: Long,
)

/** Where step readings come from. An interface so the engine can be tested with a fake. */
internal interface StepSource {
    /** Whether the sensor can feed the engine right now (hardware and permission). */
    fun status(): NativeStepsStatus

    /**
     * Cold flow: collecting registers the sensor, cancelling unregisters it, so
     * steps taken while no collector exists (a pause) are never read. Fails with
     * the underlying exception.
     */
    fun counts(): Flow<StepSample>
}

/** Steps off (no step sensor is touched). */
internal object NoStepSource : StepSource {
    override fun status(): NativeStepsStatus = NativeStepsStatus.OFF
    override fun counts(): Flow<StepSample> = emptyFlow()
}

/**
 * `TYPE_STEP_COUNTER`, delivered on the main looper without batching (the callback only queues the reading).
 * AOSP stamps a reading with the time of its LAST step and may report up to
 * ~10 s late, so a step taken just before a pause can be lost when the listener
 * is unregistered, and the steps before the first reading of a segment are
 * absorbed by its baseline: both under-count. Not measured on devices.
 */
internal class SensorStepSource(context: Context) : StepSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val counter: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    override fun status(): NativeStepsStatus {
        if (counter == null) return NativeStepsStatus.UNAVAILABLE
        // ACTIVITY_RECOGNITION is a runtime permission from Android 10.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return NativeStepsStatus.PERMISSION_DENIED
        }
        return NativeStepsStatus.ACTIVE
    }

    override fun counts(): Flow<StepSample> = callbackFlow {
        val sensorManager = manager
        val sensor = counter
        if (sensorManager == null || sensor == null) {
            close(IllegalStateException("no step counter on this device"))
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(StepSample(event.values[0].toLong(), event.timestamp / 1_000_000L))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        // The callback only queues the reading (the counter reports every few
        // seconds), so the main looper is enough, like the location updates; all
        // processing happens in the engine's coroutine, off the main thread.
        val registered = try {
            sensorManager.registerListener(
                listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0, Handler(Looper.getMainLooper()),
            )
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        if (!registered) {
            close(IllegalStateException("could not register the step counter"))
            return@callbackFlow
        }
        awaitClose { sensorManager.unregisterListener(listener) }
        // Never drop a reading: the counter is cumulative, but a lost reading would delay the estimate.
    }.buffer(Channel.UNLIMITED)
}
