# GapReconciler.kt

**Path:** `.../tracker/algorithms/fusion/GapReconciler.kt` · **Layer:** algorithm (fusion)

## Purpose
Decides what a GPS gap is worth, **exactly once**: the larger of the straight line across the gap (minus its own uncertainty) and the **confirmed** step estimate. It never adds the two.

## API
| Member | Description |
|---|---|
| `GapResult(creditMeters, source, note)` | Metres to credit, the source (`GAP_BRIDGE` or `PDR`) and a log note (no coordinates). |
| `GapReconciler.reconcile(chordMeters, uncertaintyMeters, gapMs, pendingPdrMeters, config)` | Pure function. |

## How it works
1. An invalid interval (non-finite or negative input, no time), or a gap longer than `maxBridgeMs` → **0 m**.
2. `lowerChord = max(0, chord − uncertainty)` (uncertainty = sum of both end accuracies). If `lowerChord` is more than `maxSpeedMps × seconds`, the displacement is physically impossible even allowing for the GPS error → **0 m**.
3. `credit = min(lowerChord, ceiling)`.
4. With a pending step estimate P and `lowerChord > 0`: the estimate is accepted up to `cap = min(ceiling, lowerChord / pdrConfirmRatio)`, i.e. `min(P, cap)`. A large estimate cannot be taken just because GPS moved a little. The credit is `max(straight line, that value)`; the source is `PDR` if the estimate wins.

A phone shaken without GPS: the chord is about 0, nothing is confirmed → 0 m.

## Limits
No allocation of steps into small stretches inside a long gap, no handling of curved routes (the chord is always shorter than the real path), no confidence calibration.

## Used by
`GpsDistanceEngine.recover`.
