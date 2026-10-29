package io.github.congtuandevmobile.flutter_tracking_gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** One accelerometer reading (m/s^2, gravity included, device axes). */
internal data class AccelSample(
    /** Sensor event time on the monotonic clock (`elapsedRealtimeNanos`), ms. */
    val elapsedMs: Long,
    val x: Double,
    val y: Double,
    val z: Double,
)

/** Where accelerometer readings come from. An interface so the engine can be tested with a fake. */
internal interface AccelerometerSource {
    /** Whether the sensor can feed the estimator right now. */
    fun status(): NativeMotionStatus

    /**
     * Cold flow: collecting registers the sensor, cancelling unregisters it, so nothing is read while no collector
     * exists (a pause). Fails with the underlying exception.
     */
    fun samples(): Flow<AccelSample>
}

/** Pedal cadence off (no accelerometer is touched). */
internal object NoAccelerometerSource : AccelerometerSource {
    override fun status(): NativeMotionStatus = NativeMotionStatus.OFF
    override fun samples(): Flow<AccelSample> = emptyFlow()
}

/**
 * `TYPE_ACCELEROMETER` at `SENSOR_DELAY_GAME` (about 50 Hz: well under the 200 Hz limit above which Android 12+ asks for
 * `HIGH_SAMPLING_RATE_SENSORS`, so no permission is needed). The raw sensor is used, not the derived linear
 * acceleration: it exists on every device and the estimator removes the constant gravity itself. Readings are delivered
 * on a private handler thread (not the main thread: ~50 callbacks a second), the callback only queues them, and when the
 * consumer falls behind the OLDEST readings are dropped (a gap costs the estimator a few bins, a growing queue costs
 * memory). Not measured on devices: the rate the OS really delivers, and the battery cost of holding the sensor.
 */
internal class SensorAccelerometerSource(context: Context) : AccelerometerSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override fun status(): NativeMotionStatus =
        if (accelerometer == null) NativeMotionStatus.UNAVAILABLE else NativeMotionStatus.ACTIVE

    override fun samples(): Flow<AccelSample> = callbackFlow {
        val sensorManager = manager
        val sensor = accelerometer
        if (sensorManager == null || sensor == null) {
            close(IllegalStateException("no accelerometer on this device"))
            return@callbackFlow
        }
        val thread = HandlerThread("flutter_tracking_gps.accelerometer").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(
                    AccelSample(
                        event.timestamp / 1_000_000L,
                        event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(),
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val registered = try {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, 0, Handler(thread.looper))
        } catch (e: SecurityException) {
            thread.quitSafely()
            close(e)
            return@callbackFlow
        }
        if (!registered) {
            thread.quitSafely()
            close(IllegalStateException("could not register the accelerometer"))
            return@callbackFlow
        }
        awaitClose {
            sensorManager.unregisterListener(listener)
            thread.quitSafely()
        }
    }.buffer(512, BufferOverflow.DROP_OLDEST)
}
