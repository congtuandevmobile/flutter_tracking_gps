# TrackingCoreBridge.kt

**Path:** `tracking-core/src/commonMain/kotlin/tracker/bridge/TrackingCoreBridge.kt` · **Layer:** bridge (the one door into the engine for Swift and Kotlin-Android)

## Purpose
Kotlin default arguments, nullable numbers and enums are awkward to use from Swift. This facade exposes `GpsDistanceEngine` and `AdaptiveSamplingPolicy` with **only primitives** (unknown values are negative) and **every parameter explicit**, so the Android engine and the iOS engine call the same API.

## Types
| Type | Description |
|---|---|
| `BridgeSnapshot` | Flat copy of `TrackingSnapshot`: `state` (0 idle, 1 running, 2 paused, 3 finished), `quality` (0 unknown, 1 good, 2 degraded, 3 unavailable, 4 recovering), `totalMeters, gpsMeters, gapBridgeMeters, pdrMeters, pendingPdrMeters, segmentId, fixCount, speedMps` (negative = unknown), `lastFixAtMs` (negative = none), `movingTimeMs, isStopped`. |
| `BridgePlan` | `intervalMs`, `minDistanceMeters`, `highAccuracy`, `silenceAfterMs`: what to ask the OS for. |

## `class TrackingCoreBridge(...)`
Constructor parameters mirror `TrackingConfig` (`maxAccuracyMeters, maxFixAgeMs, silenceAfterMs, maxSpeedMps, minStepMeters, accuracyNoiseFactor, stationarySpeedMps, maxBridgeMs, reanchorAfterRejects, rejectMock, pdrEnabled, pdrConfirmRatio, kalman, stopRadiusMeters, stopMinDwellMs`) plus `adaptiveSampling` and `batterySaver`.

| Function | Description |
|---|---|
| `start(sessionId, atMs, segmentId)` | Starts a session; resets the sampling policy and the silence limit. |
| `pause(atMs)`, `resume(atMs, segmentId)`, `finish(atMs)` | Lifecycle (see `GpsDistanceEngine`). |
| `submitPdr(segmentId, atMs, meters)` | A step estimate for a segment (held as pending); one of another segment is refused. |
| `onTick(nowMs): Int` | 1 Hz tick; returns the GPS quality code. |
| `onFix(sessionId, segmentId, seq, timeMs, latitude, longitude, horizontalAccuracy, speed, speedAccuracy, isMock, nowMs, replay): Int` | Feeds one fix; returns the `FixVerdict` **code**. Exactly `-1.0` means unknown for the accuracy, speed and speed accuracy; NaN, infinity or any other negative value is `INVALID`, not unknown. `timeMs` and `nowMs` must come from the same monotonic clock; `replay = true` skips the age and future checks. |
| `snapshot(): BridgeSnapshot` | Current state. |
| `report(): String` | Coordinate-free timeline of the engine's decisions. |
| `samplingPlan(nowMs): BridgePlan` | The plan for now. With adaptive sampling off it is always the fixed plan (1 s, 2 m). With it on, the plan follows the speed the engine sees, and the engine's silence limit is raised to `plan.silenceAfterMs` so sparse fixes are not mistaken for an outage. |

## Notes
- Not thread-safe: Android guards it with a lock, iOS keeps it on its serial queue.
- **Codes are written out, never taken from `ordinal`.** State: 0 idle, 1 running, 2 paused, 3 finished. Quality: 0 unknown, 1 good, 2 degraded, 3 unavailable, 4 recovering. Verdict: 0 anchor, 1 accepted, 2 recovered, 3 noise, 4 stationary, 5 jump, 6 outlier, 7 low accuracy, 8 stale, 9 duplicate, 10 wrong segment, 11 mock, 12 not running, 13 wrong session, 14 invalid, 15 future, 16 reanchored. Reordering an enum cannot change what Swift reads.

## Used by
`android/.../TrackingEngine.kt`, `ios/Classes/TrackingEngine.swift`. Tested by `BridgeTest`.
