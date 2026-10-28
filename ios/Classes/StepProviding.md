# StepProviding.swift

**Path:** `ios/Classes/StepProviding.swift` · **Layer:** iOS native (sensor)

## Purpose
Where step counts come from, as a protocol (like `LocationProviding`) so `TrackingEngine` can be tested with a fake. It only reads `CMPedometer`; the accounting is the shared core's (`StepSession`).

## `StepProviding`
`status` (hardware + Motion & Fitness permission), `permission`, `requestPermission` (CoreMotion has no explicit request: the system prompt appears on the first query, so a tiny query is run), `startUpdates(from:handler:)` (live count since the segment start), `stopUpdates()`, `query(from:to:completion:)` (the OS's exact count for a range, nil on failure or after a 5 s timeout).

## `CoreMotionStepProvider`
- `CMPedometer` can count from a chosen date, so no baseline is needed; at a boundary the OS gives the **exact** count of the closed segment.
- A generation counter drops a reading queued before `stopUpdates()`, so it cannot reach a later segment.
- `query` answers once (the OS answer and the timeout race, guarded by `Once`).

## Limits (not measured on devices)
Starting the sensor with the permission `notDetermined` shows the system prompt. If the exact query fails or times out, the segment keeps its last live count (an under-count by the steps since that reading). Distance is never derived from the exact count.

## Used by / tested by
`TrackingEngine`. Tested through `RunnerTests` with `FakeSteps`.
