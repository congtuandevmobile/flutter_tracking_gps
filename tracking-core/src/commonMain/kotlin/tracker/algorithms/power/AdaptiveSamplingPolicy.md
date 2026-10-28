# AdaptiveSamplingPolicy.kt

**Path:** `.../tracker/algorithms/power/AdaptiveSamplingPolicy.kt` · **Layer:** algorithm (battery)

## Purpose
Chooses the GPS sampling plan from the speed, with hysteresis so it does not flap. Standing still for a while samples less often to save battery; moving samples more often. Geofencing is deliberately not used while recording, because a workout needs continuous fixes.

## `SamplingPlan`
| Field | Meaning |
|---|---|
| `intervalMs` | Requested time between updates. |
| `minDistanceMeters` | Requested minimum displacement between updates. |
| `highAccuracy` | Whether to use high accuracy. |
| `silenceAfterMs(floorMs = 10_000)` | How long without a fix means "GPS is silent": at least 4 × `intervalMs`, never below `floorMs`. **A sparse plan must not be mistaken for an outage.** |

## `AdaptiveSamplingPolicy(batterySaver = false, stillAfterMs = 20_000)`
- `plan(speedMps?, nowMs): SamplingPlan`: speed ≥ 6 m/s → 1 s / 3 m; ≥ 0.4 → 1 s / 2 m; slower but not yet `stillAfterMs` → still 1 s / 2 m; still for long enough → 3 s / 3 m (or 5 s / 5 m without high accuracy when `batterySaver`). An unknown speed returns the moving plan.
- An invalid speed (NaN, infinite, negative), an unknown speed or a clock that went backwards resets the standstill timer and returns the moving plan (never a sparse one).
- `reset()`: forgets when the standstill began.

## Notes
The values are starting points to benchmark on battery. The OS treats a request as a hint and may deliver faster or slower. It is used by `TrackingCoreBridge.samplingPlan`: Android re-requests location updates with the new interval and displacement; iOS (which has no interval request) only changes `distanceFilter`. The engine's silence limit follows the plan.
