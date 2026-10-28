# StopDetector.kt

**Path:** `.../tracker/algorithms/motion/StopDetector.kt` · **Layer:** algorithm (motion)

## Purpose
**Online stay-point detection** while recording: the phone is stopped when it stays within a radius of one point **and** shows no motion (no Doppler speed at or above the threshold), continuously for long enough. It is a candidate signal for moving time, not the sole authority.

## Constructor parameters
| Parameter | Default | Meaning |
|---|---|---|
| `radiusMeters` | 15 | Radius of one "stay". |
| `minDwellMs` | 30 000 | Minimum stay to count as a stop. |
| `stationarySpeedMps` | 0.4 | A speed at or above this is motion and breaks the stay. |
| `maxObservationGapMs` | 10 000 | A hole in the observations longer than this starts a new window. |

## API
| Member | Description |
|---|---|
| `isStopped: Boolean` | Whether a stop is currently in effect. |
| `push(timeMs, latitude, longitude, speedMps?): Boolean` | Feeds one fix and returns `isStopped`. |
| `reset()` | Forgets the anchor and the stopped state. |

## How it works
It keeps an anchor (position + time). A fix **inside the radius and not moving** counts toward the stay; once the stay lasted at least `minDwellMs` the phone is stopped. A fix outside the radius, or moving at speed, starts a new anchor and ends the stop at once. Out-of-order or invalid observations are ignored. O(1) per fix. The constructor validates its parameters.

## Unlike DBSCAN
It is continuous in time: a runner who passes the same place twice ten minutes apart is **not** a stop (a spatial clustering could confuse the two).

## Used by
`GpsDistanceEngine.markGood` (moving time, `isStopped`).
