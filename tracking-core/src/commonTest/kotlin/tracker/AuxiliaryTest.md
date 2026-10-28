# AuxiliaryTest.kt

**Path:** `tracking-core/src/commonTest/kotlin/tracker/AuxiliaryTest.kt` · **Layer:** tests (auxiliary algorithms)

## Purpose
Tests of the offline algorithms: DBSCAN, K-Means, the road graph (Dijkstra, A\*, one-way roads), geometric and topological matching and the HMM matcher.

## Test graph
`graph()` builds nodes a, b, c, d going north (about 111 m apart) plus a long detour a → x → d. Edge lengths are 1.001 × the straight line so the A\* heuristic stays valid. All roads are one-way.

## Tests
| Test | What it checks |
|---|---|
| `dbscanFindsTwoClustersAndIgnoresNoise` | Two dense groups become two clusters; a lone point is noise; invalid parameters throw. |
| `kmeansSeparatesTwoRegions` | Two far groups give two centers near each group. |
| `dijkstraAndAStarAgreeAndOneWayRoadsAreRespected` | Both find a → b → c → d with the same length; there is no route back; an unknown node returns `null`. |
| `anEdgeShorterThanTheStraightLineIsRejected` | The constructor refuses an edge that would break the A\* heuristic. |
| `geometricCandidatesAreClosestFirstAndTopologyUsesDirection` | Candidates are sorted by lateral distance; `travelDistance` is `null` against a one-way road and defined along it. |
| `hmmMatchesNoisySamplesToTheRoadTheyFollow` | Samples along the road match its edges; a far away sample stays unmatched. |

## Run
`cd tracking-core && ./gradlew jvmTest`
