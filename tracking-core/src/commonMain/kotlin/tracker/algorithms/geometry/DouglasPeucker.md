# DouglasPeucker.kt

**Path:** `.../tracker/algorithms/geometry/DouglasPeucker.kt` · **Layer:** algorithm (geometry)

## Purpose
Simplifies a polyline while keeping its shape, for **display** and to **shrink what is uploaded**. The simplified line must **never** be used to measure distance: it is shorter than the real route by design.

## API
| Member | Description |
|---|---|
| `LatLon(latitude, longitude)` | A coordinate pair. |
| `DouglasPeucker.simplify(points, toleranceMeters)` | Returns the kept points (the first and last are always kept). A negative `toleranceMeters` throws `IllegalArgumentException`. Fewer than 3 points returns a copy. |

## How it works
Iterative with an explicit stack (no recursion, so a long route cannot overflow the stack): for a range [start, end], find the point farthest from the chord (distance from `GeoMath.project`); if it is farther than `toleranceMeters`, keep it and split the range in two; otherwise drop the points in between.

## Rules (system-wide)
- Always keep the raw GPS so a recording can be replayed.
- Measure distance from raw/filtered points, never from the simplified line.

## Depends on
`GeoMath.project`.
