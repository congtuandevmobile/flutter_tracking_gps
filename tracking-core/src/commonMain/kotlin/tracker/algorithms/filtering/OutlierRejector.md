# OutlierRejector.kt

**Path:** `.../tracker/algorithms/filtering/OutlierRejector.kt` · **Layer:** algorithm (filtering)

## Purpose
Detects an impossible position jump: covering the distance in the elapsed time would need a speed above the ceiling.

## API
| Member | Description |
|---|---|
| `OutlierRejector.isJump(distanceMeters, seconds, maxSpeedMps): Boolean` | `true` when `distance / seconds > maxSpeedMps`, and also when any input is non-finite or negative, when no time passed (`seconds ≤ 0`) or when the ceiling is not positive: an invalid measurement is not a valid movement. |

## Notes
A pure function with no state. The engine decides how to count consecutive rejections and when to re-anchor (`reanchorAfterRejects`).

## Used by
`engine/GpsDistanceEngine.kt`.
