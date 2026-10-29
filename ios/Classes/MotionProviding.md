# MotionProviding.swift

**Path:** `ios/Classes/MotionProviding.swift` · **Layer:** iOS native (sensor)

## Purpose
Where accelerometer readings come from for the pedal-cadence estimator, as a protocol so `TrackingEngine` can be tested with a fake. It only reads the sensor; the rhythm is found by the shared core (`PedalCadenceEstimator`). Every failure is a typed `MotionError` with a stable code (`CADENCE_FAILED`) and is never swallowed.

## Types
| Type | Description |
|---|---|
| `AccelReading(timestamp, x, y, z)` | One reading in m/s² (gravity included, device axes). `timestamp` is `CMLogItem.timestamp`: seconds since boot (the `systemUptime` clock), converted by the engine to its monotonic clock with an offset taken when the segment starts. |
| `MotionError` | `unavailable` (no accelerometer) or `failed(message)`. |
| `MotionProviding` | `isAvailable`, `startUpdates(handler:)` (about 50 readings a second, a runtime failure arrives as `.failure`) and `stopUpdates()`. |
| `CoreMotionAccelerometerProvider` | `CMMotionManager` accelerometer at 50 Hz on a private serial queue; converts g to m/s². |

## Limits (not measured on devices)
The rate the OS really delivers and the battery cost of holding the sensor for the whole recording. Whether reading the raw accelerometer needs a usage-description key is not verified here; the step sensor already requires `NSMotionUsageDescription`.

## Used by / tested by
`TrackingEngine` (pedal cadence). Faked in the iOS tests.
