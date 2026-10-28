# RoadGraph.kt

**Path:** `.../tracker/auxiliary/roads/RoadGraph.kt` · **Layer:** auxiliary (offline / server-side, never feeds the recorded distance)

## Purpose
A directed road graph used for map matching and routing. Matched coordinates must not replace the real ones without reconciliation and ground-truth tests, and the app has no road data, so this is for server-side or future use.

## Types
| Type | Description |
|---|---|
| `RoadNode(id, latitude, longitude)` | A junction. |
| `RoadEdge(id, from, to, lengthMeters)` | A **directed** road segment; a two-way road needs one edge each way. |
| `RoutePath(lengthMeters, nodes)` | A route: its length and the node ids in order. |
| `RoadCandidate(edge, fraction, lateralMeters, latitude, longitude)` | A GPS point projected on one segment: position along it (0..1), lateral distance, coordinates of the projection. |

## `class RoadGraph(nodes, edges)`
The constructor validates: no duplicate node or edge id; valid node coordinates; every edge references known nodes; every length is finite and positive; **no edge is shorter than the straight line between its ends** (the straight line is a valid A\* heuristic only then). A violation throws `IllegalArgumentException`.

| Function | Description |
|---|---|
| `node(id): RoadNode?` | Looks a node up. |
| `candidates(latitude, longitude, radiusMeters, limit = 5)` | **Geometric matching:** the nearest segments within the radius, closest first. |
| `route(start, goal, useAStar = true): RoutePath?` | Shortest path. `useAStar = false` is Dijkstra, `true` is A\* with the straight-line heuristic. `null` when a node is unknown or no directed path exists. |
| `travelDistance(a, b): Double?` | **Topological matching:** road distance from candidate `a` to candidate `b` along connected, correctly directed segments; `null` when no legal path exists (for example against a one-way road). |

## `MinHeap` (internal)
A binary min-heap on a `Double` priority used by `route`; common Kotlin has no `java.util.PriorityQueue`. `push(id, priority)`, `pop()`, `isEmpty()`.

## Limits
Candidate search scans every edge (no spatial index); no turn restrictions; no time or speed on edges.

## Used by
`HmmMapMatcher`. Tested by `AuxiliaryTest`.
