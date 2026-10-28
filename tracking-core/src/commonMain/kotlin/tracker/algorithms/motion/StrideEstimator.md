# StrideEstimator.kt

**Path:** `.../tracker/algorithms/motion/StrideEstimator.kt` · **Layer:** algorithm (motion)

## Purpose
Learns the user's **stride length (metres per step)** from stretches where GPS is good, so a step count can later stand in for distance while GPS is weak. It is the "metres from steps" half of the step feature; the engine decides when to use it (`GpsDistanceEngine.onSteps`).

## How it works
- It accumulates the **steps** and the **GPS metres of the same window**. A sample `meters / steps` is produced only when both are big enough (`minSteps`, `minMeters`), which averages out step batching (the OS delivers steps in lots, up to ~10 s late on Android) and GPS noise.
- A window with `abandonSteps` or more steps but less than `minMeters` of GPS distance (standing, phone shaken in the hand) is abandoned.
- A sample outside `[minStride, maxStride]` is discarded.
- The estimate is an exponential moving average (`alpha`), so one odd window moves it little.

## Constructor parameters (all unmeasured starting values)
| Parameter | Default | Meaning |
|---|---|---|
| `initialStrideMeters` | null | Stride to start from (e.g. the one learned in the last recording). Must lie inside the plausible range. |
| `minSteps` | 20 | Steps needed before a sample is produced. |
| `minMeters` | 20 | GPS metres needed before a sample is produced. |
| `minStride`, `maxStride` | 0.3, 2.5 | Plausible metres per step; others are discarded. |
| `alpha` | 0.3 | Weight of a new sample. |
| `abandonSteps` | 200 | Steps without enough GPS distance after which the window is dropped. |

## API
| Member | Description |
|---|---|
| `strideMeters: Double?` | Learned or initial stride; null while unknown. |
| `addSteps(n)`, `addMeters(m)` | Add to the open window; a sample is produced when both thresholds are reached. Non-positive or non-finite input is ignored. |
| `breakWindow()` | Drops the open window (GPS quality changed, pause, resume, recovery). The learned stride is kept. |
| `reset()` | New session: forgets what was learned and returns to `initialStrideMeters`. |

## Limits
Walking slower than the stationary Doppler limit counts as standing, so such steps bias the stride low (or are abandoned). One stride for every gait: running and walking differ. Not measured on devices.

## Used by / tested by
`GpsDistanceEngine`. Dart twin: `lib/src/engine/stride_estimator.dart`. Tested by `StepsTest` (`StrideEstimatorTest`) and `test/engine/steps_test.dart`.
