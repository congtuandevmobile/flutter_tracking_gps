# Kalman2D.kt

**Path:** `.../tracker/algorithms/filtering/Kalman2D.kt` · **Layer:** algorithm (position filtering)

## Purpose
A constant-velocity Kalman filter on a local tangent plane (one independent filter per axis). In the engine it is a **quality gate** (`Smoothing.KALMAN`, off by default): it says whether an observation is plausible, and the distance is always measured on the **raw** accepted fixes, never on the filtered coordinates (smoothing would cut corners).

## Constructor parameters
| Parameter | Default | Meaning |
|---|---|---|
| `accelerationSigma` | 2.0 | Acceleration noise of the model (m/s²). |
| `gateChiSquare` | 13.82 | Chi-square gate (2 degrees of freedom, about 99.9%) that rejects an observation too far from the prediction. |
| `maxGapMs` | 30 000 | A silence longer than this re-localizes the filter. |
| `resetAfterRejects` | 5 | After this many rejections in a row the filter re-localizes and reports a discontinuity. |

## `Output`
| Field | Meaning |
|---|---|
| `latitude`, `longitude` | The filtered position (or the prediction when the observation was rejected). |
| `accepted` | `false` when the observation was gated out. |
| `discontinuity` | `true` when the filter re-localized (after a silence longer than `maxGapMs` **or** after `resetAfterRejects` rejections): **the caller must treat it as a new anchor, never as a distance step.** |
| `status` | `INITIALIZED`, `UPDATED`, `REJECTED`, `OUT_OF_ORDER`, `GAP_RESET`, `RELOCALIZED`. |

## Functions
- `push(latitude, longitude, accuracyMeters, timeMs): Output`: predict, gate, update. It throws `IllegalArgumentException` for an invalid coordinate, a non-finite or negative accuracy or a negative time. An accuracy below 3 m is not believed (`MIN_ACCURACY`). A sample not newer than the last returns the prediction with `OUT_OF_ORDER`. The gap limit is measured from the last ACCEPTED observation.
- `reset()`: forgets all state.

## How it works (per axis)
State `[position, velocity]` with covariance `p00, p01, p11`. Predict: `p += v·dt` and add acceleration noise `q = σa²` (white-noise acceleration model). Update: Kalman gain `K = [p00, p01]/(p00 + R)` with `R = accuracy²`; the covariance uses the Joseph form, which stays symmetric and non-negative.

## Limits
No accelerometer or gyroscope fusion; one motion model for every sport; a sharp turn can be gated out; systematic GPS error cannot be removed. It needs per-sport models and real-data validation before being enabled by default.

## Depends on / tested by
`GeoMath.toXY` / `fromXY`. Tested by `AlgorithmsTest`.
