# KMeans.kt

**Path:** `.../tracker/auxiliary/clustering/KMeans.kt` · **Layer:** auxiliary (offline analysis, not part of the distance)

## Purpose
Splits a set of places into `k` regions. Useful to analyse geographic areas, not to detect when a user stopped.

## API
| Member | Description |
|---|---|
| `KMeans.centers(points, k, maxIterations = 40): List<LatLon>` | Returns the `k` centers. Requires non-empty `points`, `k` in `1..points.size` and `maxIterations > 0`, otherwise `IllegalArgumentException`. |

## How it works
Points are projected onto a tangent plane around the first point. Centers start evenly spread over the input order, so the result is deterministic. Each round assigns every point to its nearest center and moves each center to the mean of its group; it stops when no center moves more than 1 cm. An empty group keeps its center.

## Limits
Sensitive to the initial centers; no spatial index; the tangent plane is only valid for small areas.
