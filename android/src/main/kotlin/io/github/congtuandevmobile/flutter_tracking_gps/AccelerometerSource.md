# AccelerometerSource.kt

**Path:** `android/src/main/kotlin/io/github/congtuandevmobile/flutter_tracking_gps/AccelerometerSource.kt` · **Layer:** Android native (sensor)

## Purpose
Where accelerometer readings come from for the pedal-cadence estimator, behind an interface so `TrackingEngine` can be tested with a fake. It only reads the sensor; the rhythm is found by the shared core (`PedalCadenceEstimator`).

## Types
| Type | Description |
|---|---|
| `AccelSample(elapsedMs, x, y, z)` | One reading (m/s², gravity included, device axes) with the sensor time on the monotonic clock (`elapsedRealtimeNanos`), ms. |
| `AccelerometerSource` | `status(): NativeMotionStatus` and `samples(): Flow<AccelSample>` (cold: collecting registers the sensor, cancelling unregisters it, so nothing is read during a pause). |
| `NoAccelerometerSource` | Pedal cadence off: status `OFF`, empty flow. |
| `SensorAccelerometerSource(context)` | `TYPE_ACCELEROMETER` at `SENSOR_DELAY_GAME` (about 50 Hz), delivered on a private `HandlerThread`, unregistered and the thread quit in `awaitClose`. |

## Choices
- Raw `TYPE_ACCELEROMETER`, not `TYPE_LINEAR_ACCELERATION`: it exists on every device, and the estimator removes the constant part itself (the leg's tilt changes gravity's projection at the pedalling frequency, which is part of the signal).
- About 50 Hz is below the 200 Hz limit above which Android 12+ requires `HIGH_SAMPLING_RATE_SENSORS`, so no permission is needed.
- The buffer drops the OLDEST readings when the consumer falls behind (bounded memory); a gap only costs the estimator a few bins.

## Status rules
No accelerometer → `UNAVAILABLE`. Otherwise `ACTIVE`. A failed registration closes the flow with an error (the engine maps it to `FAILED` + `CADENCE_FAILED`).

## Known limits (not measured on devices)
The rate the OS really delivers (it may be slower under load) and the battery cost of holding the sensor for the whole recording.

## Used by / tested by
`TrackingEngine`, `TrackingRuntime`. Tested through `TrackingEngineTest` with `FakeAccel`.
