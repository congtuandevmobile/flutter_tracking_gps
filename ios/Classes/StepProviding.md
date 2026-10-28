# StepProviding.swift

**Path:** `ios/Classes/StepProviding.swift` · **Layer:** iOS native (sensor)

## Purpose
Where step counts come from, as a protocol so `TrackingEngine` can be tested with a fake. It only reads `CMPedometer`; the accounting is the shared core's. Every failure is a typed `StepError` with a stable code and message and is NEVER collapsed into "0 steps".

## `StepError`
`permissionDenied` (`STEPS_PERMISSION_DENIED`), `unavailable` (`STEPS_UNAVAILABLE`), `missingUsageDescription` (`MISSING_MOTION_USAGE_DESCRIPTION`: `NSMotionUsageDescription` is missing, asking would crash), `timeout` (`STEPS_QUERY_TIMEOUT`: not the same as no steps) and `failed(message)` (`STEPS_FAILED`).

## `StepProviding`
`availability()` (hardware, the Info.plist key and the permission), `permission`, `requestPermission` (CoreMotion has no explicit request: a tiny query shows the prompt; without the usage key it refuses instead of crashing), `startUpdates(from:handler:)` (live `StepReading(count, endDate)` or a runtime `StepError`, never swallowed), `stopUpdates()`, `query(from:to:timeout:completion:)` (the OS's exact count, or `.timeout` / an error).

## `CoreMotionStepProvider`
`CMPedometer` can count from a chosen date, so no baseline is needed, and at a boundary the OS gives the exact count of the closed segment. A generation counter drops a reading queued before `stopUpdates()`. `query` answers once (the OS answer and the timeout race, guarded by `Once`). CoreMotion errors are mapped to `StepError`.

## Limits (not measured on devices)
Starting the sensor with the permission `notDetermined` shows the system prompt. If the exact query fails or times out the engine keeps the live count and says so; distance is never derived from it.
