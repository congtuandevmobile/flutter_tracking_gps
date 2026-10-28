# HmmMapMatcher.kt

**Path:** `.../tracker/auxiliary/roads/HmmMapMatcher.kt` · **Layer:** auxiliary (offline map matching, never feeds the recorded distance)

## Purpose
Offline Viterbi map matching (reduced Newson and Krumm approach): chooses, for each GPS sample, the road segment of the most likely path.

## Constructor
`HmmMapMatcher(graph: RoadGraph, sigmaMeters = 12.0, betaMeters = 40.0)`

| Parameter | Meaning |
|---|---|
| `graph` | The directed road graph. |
| `sigmaMeters` | GPS noise: emission score is `−0.5 (lateral / sigma)²`. |
| `betaMeters` | Transition tolerance: the penalty is `|road distance − straight distance| / beta`. |

`sigmaMeters` and `betaMeters` must be finite and positive, and `match` needs a positive `radiusMeters`, otherwise `IllegalArgumentException`.

## API
- `match(samples: List<LatLon>, radiusMeters = 50.0): List<RoadCandidate?>`: one entry per sample, the matched road position or `null`.

## How it works
For each run of samples that have candidates within the radius, it scores each candidate by emission plus the best previous score minus the transition penalty, using `RoadGraph.travelDistance` (so a transition must be a legal directed path). Back-pointers recover the best sequence. A sample with no road in range, or with no legal transition, splits the trace into separate runs; unmatched samples are `null`.

## Limits (reduced version)
No timestamps or gap handling, no heading, no posterior confidence, no spatial index, no turn restrictions, no transition cache. Split the trace at every Pause before calling it. For production prefer a routing engine (OSRM Match, Valhalla).
