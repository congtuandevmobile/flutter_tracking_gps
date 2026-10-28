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
