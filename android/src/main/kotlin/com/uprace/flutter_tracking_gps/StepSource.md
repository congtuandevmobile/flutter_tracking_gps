# StepSource.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/StepSource.kt` · **Layer:** Android native (sensor)

## Purpose
Where step readings come from, behind an interface so `TrackingEngine` can be tested with a fake. It only reads the sensor: baseline, deltas and metres are decided by the shared core (`StepSession`, `StrideEstimator`).

## Types
| Type | Description |
|---|---|
| `StepSample(raw, elapsedMs)` | One reading of the device's cumulative counter and its sensor time on the monotonic clock. |
| `StepSource` | `status(): NativeStepsStatus` (hardware + permission) and `counts(): Flow<StepSample>` (cold: collecting registers the sensor, cancelling unregisters it, so steps taken while no collector exists are never read). |
| `NoStepSource` | Steps off: status `OFF`, empty flow. |
| `SensorStepSource(context)` | `TYPE_STEP_COUNTER`, `SENSOR_DELAY_NORMAL`, no batching, own `HandlerThread` released in `awaitClose`. Unbounded buffer: a reading is never dropped. |

## Status rules
No counter sensor → `UNAVAILABLE`. Android 10+ without `ACTIVITY_RECOGNITION` → `PERMISSION_DENIED`. Otherwise `ACTIVE`. A failed registration closes the flow with an error (the engine maps it to `UNAVAILABLE` + `STEPS_FAILED`).

## Known limits (not measured on devices)
AOSP stamps a reading with the time of its **last** step and may report up to ~10 s late: a step just before a pause can be lost when the listener is unregistered, and the steps before a segment's first reading are absorbed by its baseline. Both under-count. The engine feeds the core the **arrival** time.

## Used by / tested by
`TrackingEngine`, `TrackingRuntime`. Tested through `TrackingEngineTest` with `FakeSteps`.
