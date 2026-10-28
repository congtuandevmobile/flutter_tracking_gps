# GpsDistanceEngine.kt

**Path:** `tracking-core/src/commonMain/kotlin/tracker/engine/GpsDistanceEngine.kt` · **Layer:** engine (the decision core)

## Purpose
The **only** place that decides what counts as distance. It is pure and deterministic: time is passed in and nothing is read from the platform, so a recording can be replayed fix by fix. The Dart twin (`lib/src/engine/gps_distance_engine.dart`) gives the same totals on the same CSV fixtures (`test/fixtures`).

## Time domain
Every time given to the engine (`fix.timeMs`, `nowMs`, `atMs`) must come from the **same clock**. The native layers use a **monotonic** one (Android `elapsedRealtime` / `elapsedRealtimeNanos`, iOS `CLOCK_MONOTONIC`), so a wall-clock jump can neither reorder nor age a fix. Wall time is only kept for display and storage.

## Principles
1. **GPS is the truth.** A step estimate stays pending until the GPS that returns confirms it; a phone shaken without GPS adds 0 m.
2. **Distance is measured on RAW accepted fixes.** The Kalman filter is only a quality gate; it never supplies coordinates (smoothing would cut corners).
3. **One source per stretch:** a gap is credited once (`GapReconciler`), never summed.
4. **Nothing is bridged across a pause or a segment boundary;** a fix of another session or segment, and a step estimate of another segment, are refused.
5. A Doppler speed below the stationary limit means no distance (only believed when its own accuracy is at most 1.5 m/s, an unmeasured value).
6. **A GPS gap is decided from the time of the last HEALTHY fix**, not from the accounting anchor, which stays put on purpose while standing. (A bug found by the audit: the anchor's age made 10 s of standing look like a gap and lost the first metres of movement.)
7. The total only grows (through `DistanceLedger`).

## Properties
| Property | Description |
|---|---|
| `config`, `log`, `ledger` | Configuration (validated), diagnostic timeline, the ledger. |
| `silenceAfterMs` | How long without a fix means GPS is silent. Starts at `config.silenceAfterMs`; an adaptive plan raises it. Must be positive. |
| `state`, `quality`, `segmentId` | Public read, engine-only write. |
| `totalMeters`, `snapshot` | Accepted total; snapshot for the UI (including `pendingPdrMeters`, `movingTimeMs`, `isStopped`). |

## Functions
| Function | What it does |
|---|---|
| `start(sessionId, atMs, segmentId = 0)` | Validates the id, clears ledger and log, enters `RUNNING`, resets all state, `StopDetector` and `Kalman2D`. |
| `pause(atMs)` / `finish(atMs)` | Close the leg: drop a pending estimate, forget the anchor. `finish` freezes the result. |
| `resume(atMs, segmentId)` | Requires a NEW (larger) segment id; the first good fix only anchors; the total is kept. |
| `submitPdr(segmentId, atMs, meters)` | A step estimate for a segment. Refused for another segment, with the policy off, when not running, for a non-positive or non-finite amount, or while GPS is good; otherwise held as pending. |
| `onTick(nowMs)` | About once per second: silent longer than `silenceAfterMs` sets `UNAVAILABLE`. |
| `onFix(fix, nowMs, replay = false)` | Processes one fix and returns a `FixResult`. **Never throws for data:** a bad fix gets a verdict. `replay` skips the age and future checks. |

## Fix pipeline
```
not RUNNING ................ NOT_RUNNING
other session .............. WRONG_SESSION
other segment .............. WRONG_SEGMENT
NaN / out of range / negative INVALID
seq or time not newer ...... DUPLICATE
measured after arrival ..... FUTURE        (not for replay)
too old, or before the leg . STALE         (not for replay)
mock ....................... MOCK
accuracy unknown or > 40 m . LOW_ACCURACY  (quality DEGRADED)
first good fix ............. ANCHOR        (adds nothing)
silent > limit since the last HEALTHY fix, or UNAVAILABLE
                             RECOVERED     (GapReconciler: the gap is credited once)
Kalman gate (optional) ..... OUTLIER, or REANCHORED if the filter re-localizes
implausible speed (GPS error of both ends allowed for)
                             JUMP          (anchor kept; after N in a row: REANCHORED, nothing credited)
trusted Doppler < 0.4 m/s .. STATIONARY
displacement < noise radius  NOISE         (anchor kept)
otherwise .................. ACCEPTED      (raw chord added, anchor = fix)
```
The anchor only moves when distance is credited (or on a recovery or re-anchor), so noise cannot accumulate one small step at a time.

## Moving time
`markHealthy` feeds each healthy fix to `StopDetector`; the time between two healthy fixes (up to `silenceAfterMs`) is added to `movingTimeMs` only when the detector says the phone is not stopped **and** the trusted Doppler speed is not below the stationary limit.

## Limits
- Not thread-safe: drive it from one serial context (Android uses a lock, iOS a serial queue).
- It does not detect steps: the app supplies estimates through `submitPdr`.
- The ledger is append-only: a segment already counted cannot be revised when a late measurement arrives. A segment-based model with revision and confidence would be needed for that.
- Fixes and estimates must arrive ordered by measurement time (a native reorder buffer is not built).

## Uses / tested by
Uses `DistanceLedger`, `TrackingLog`, `Kalman2D`, `OutlierRejector`, `GapReconciler`, `StopDetector`, `GeoMath`. Tested by `EngineTest`, `AuditProofTest` and `FixtureParityTest`.

## Steps (`onSteps`)
`onSteps(segmentId, atMs, steps)` takes a **delta**, not a running total (the bridge computes it with `StepSession`).
- GPS **good**: the steps go to the `StrideEstimator` together with the GPS metres of the same window. They are **not** distance.
- GPS **not good** and a stride known: `steps * stride` becomes a **pending** estimate, exactly like `submitPdr`; it only counts if the GPS that returns corroborates it (`GapReconciler`). No known stride (none configured, none learned yet) = nothing is estimated.
- Refused: other segment, not running, non-positive delta. The learning window is dropped at pause, resume, finish and recovery. `start` returns to `config.initialStrideMeters`.
`submitPdr` stays for an app that has its own estimate. Snapshot field `strideMeters`.

## Review fixes (74e1cd7 baseline)
`lastHealthyFix` is the latest healthy spatial observation, separate from the accounting anchor. Normal plausibility checks both observations; real gaps bridge from the recent healthy endpoint and its time. Re-anchoring after repeated jumps clears the recent spatial reference so the next plausible sample can recover. Quality loss breaks the stride learning window immediately. On a short degraded interval that returns through the normal path, pending PDR is dropped because normal GPS already owns that displacement. This conservative policy may under-count curved short weak intervals; it prevents cross-gap reuse, not full late-event reconciliation. No numeric thresholds changed.

## Time model, gaps and stationary policy
- **Time:** `activeTimeMs` (running legs, pauses excluded), `movingTimeMs` (speed, plus credited gaps crossed at moving speed) and `uncreditedGapMs` / `uncreditedGaps` (a weak start, a gap nothing was credited for, a gap open at the boundary), plus `bridgedGapMs`. Reported, never hidden.
- **Step intervals:** `onSteps(segment, fromMs, toMs, deliveredAtMs, steps)` judges a batch by the GPS quality during its MEASURED interval, using a bounded timeline of when GPS was measuring (marks): wholly good teaches the stride, wholly weak becomes pending, a straddling batch is `ambiguousSteps`. A weak batch delivered after GPS came back is not attached. The 3-argument form is an instant. `setStepSensorActive` says silence is evidence.
- **Recovery:** after a gap with a working sensor and a pedestrian policy, no steps in a gap of at least `recoveryStepEvidenceAfterMs` = 0 m; `PdrPolicy.TRUSTED_WHEN_CALIBRATED` with 3 stride samples credits the estimate as `PDR` even for a long or curved gap.
- **Stationary:** Doppler below the limit; no usable Doppler falls back to the displacement speed; a fix inside the stay radius while `StopDetector` says stopped is jitter (the anchor stays put, so leaving the radius credits the whole displacement).
