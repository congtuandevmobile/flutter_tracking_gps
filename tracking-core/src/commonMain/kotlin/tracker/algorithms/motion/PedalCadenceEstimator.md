# PedalCadenceEstimator.kt

**Path:** `.../tracker/algorithms/motion/PedalCadenceEstimator.kt` · **Layer:** algorithm (motion)

## Purpose
Estimates the **pedalling cadence (rpm)** from the accelerometer of a phone carried on the rider's leg (trouser pocket, thigh). The leg moves once per crank revolution, so the acceleration is periodic with the cadence. It is a live display value: it is **not journaled and has no effect on distance**.

## Constructor parameters (all starting values, not measured on a bike)
| Parameter | Default | Meaning |
|---|---|---|
| `windowMs` | 8 000 | Length of the analysed window. |
| `binMs` | 50 | Bin size (20 Hz): readings are averaged into uniform bins (a box filter). |
| `minRpm` / `maxRpm` | 30 / 130 | Range searched (0.5 to 2.17 Hz). |
| `minAmplitude` | 0.2 | Standard deviation (m/s²) of the projected signal below which there is no pedalling motion. |
| `minCorrelation` | 0.4 | Best normalized autocorrelation below which the rhythm is not trusted. |
| `subHarmonicRatio` | 0.85 | A peak at the SMALLEST lag with at least this share of the best peak wins (avoids picking twice the period). |
| `smoothing` | 0.4 | Exponential average of the accepted estimates. |
| `holdMs` | 5 000 | A value is held this long after the last good estimate. |
| `recomputeEveryMs` | 1 000 | The estimate is recomputed at most this often. |

## API
| Member | Description |
|---|---|
| `push(timeMs, x, y, z)` | One reading (m/s², any orientation). Invalid or not-newer samples are ignored. A ring of 2048 samples; the oldest is dropped. |
| `rpm(nowMs): Double` | The cadence, or a NEGATIVE number (`UNKNOWN`) when it does not know. |
| `reset()` | Forgets every sample and the estimate (pause, new segment). |
| `PedalCadenceEstimator.standard()` | An estimator with the starting values (Swift cannot use Kotlin default arguments). |

## How it works
1. Average the last window into uniform bins; interpolate a few empty bins, give up if more than a quarter are empty or the oldest sample does not reach back 75% of the window (warm-up).
2. Remove the mean of each axis (gravity, fixed orientation), find the axis of greatest variance (power iteration on the 3×3 covariance) and project on it. The phone's orientation is unknown, and a projection keeps the fundamental frequency (the vector magnitude would double it).
3. No pedalling motion → unknown.
4. Normalized autocorrelation over the lags of the rpm range; best peak must reach `minCorrelation`; the smallest-lag peak near the best one is chosen; parabolic interpolation refines it; `rpm = 60 · fs / lag`.
5. Exponential smoothing; held for `holdMs`; unknown when the feed is stale (no reading for 3 s).

## Limits
- Only meaningful with the phone ON THE LEG. A handlebar mount sees road vibration, not the legs.
- Coasting with the legs still, or pedalling out of rhythm, gives unknown (by design: no guess).
- Resolution is limited by the 20 Hz bins (refined by interpolation); tolerance in the tests is ±3 rpm on synthetic signals.
- Not validated on a real bike: every number is a starting value.

## Used by
Android `TrackingEngine` and iOS `TrackingEngine`, fed by `AccelerometerSource` / `MotionProviding`. Tested in `PedalCadenceTest`.
