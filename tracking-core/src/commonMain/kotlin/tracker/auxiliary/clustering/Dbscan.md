# Dbscan.kt

**Path:** `.../tracker/auxiliary/clustering/Dbscan.kt` · **Layer:** auxiliary (offline analysis, not part of the distance)

## Purpose
Groups points that are close in **space**. For offline analysis of a recorded trace. It cannot tell a continuous rest from a runner who passes the same place twice; use `StopDetector` for stops while recording.

## API
| Member | Description |
|---|---|
| `Dbscan.cluster(points, epsilonMeters, minPoints): List<List<Int>>` | Returns the indices of the points of each cluster, sorted; noise points belong to no cluster. `epsilonMeters` and `minPoints` must be positive, otherwise `IllegalArgumentException`. |

## How it works
Classic DBSCAN: a point with at least `minPoints` neighbours within `epsilonMeters` (haversine) starts a cluster; its neighbours are expanded breadth-first through a queue; every point joins at most one cluster.

## Limits
O(n²): fine for one recorded trace, not for a city. No spatial index, no time awareness.
