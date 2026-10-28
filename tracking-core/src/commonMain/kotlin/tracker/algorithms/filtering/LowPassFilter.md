# LowPassFilter.kt

**Path:** `.../tracker/algorithms/filtering/LowPassFilter.kt` · **Layer:** algorithm (filtering)

## Purpose
An exponential moving average (low-pass filter) for scalar signals such as speed or altitude, applied **after** outliers were removed. Do not stack it on top of `Kalman2D` output: that only adds delay.

## API
| Member | Description |
|---|---|
| `LowPassFilter(alpha)` | `alpha` must be in [0, 1], otherwise `IllegalArgumentException`. A larger `alpha` follows new values more closely. |
| `push(value): Double` | Returns the new estimate `alpha × value + (1 − alpha) × previous`. The first value is returned as is. A non-finite value (NaN, infinity) throws `IllegalArgumentException`. |
| `reset()` | Forgets the estimate (use when a new segment starts). |

## Limits
One fixed coefficient for every speed; it does not adapt to the time between samples.
