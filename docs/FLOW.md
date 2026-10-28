# Tracking flow

How a recording moves from the sensor to the distance on screen, and what
happens in every situation. Everything numeric is a **starting value chosen by
reasoning, not measured**: tune it from real recordings (see `TrackingConfig`).

## Layers

```
GPS hardware
   │  raw locations
   ▼
Native (Kotlin / Swift)        collects fixes, numbers them, persists them,
   │                           1 Hz tick, adaptive sampling
   │  every fix
   ▼
TrackingCoreBridge             one door, primitives only
   ▼
GpsDistanceEngine (Kotlin)     the only place that decides what is distance
   │  DistanceLedger  (append-only, sum = total)
   ▼
NativeSnapshot  (Pigeon, EventChannel, ~1 Hz)
   ▼
TrackingSession (Dart)         commands + maps the snapshot for the UI
```

- **Native owns the session AND the distance.** If the Flutter engine dies,
  native keeps recording and keeps measuring (so a notification could keep
  showing the distance). After a process death `restoreSession` replays the
  persisted fixes through the same engine and the session comes back paused.
- **The engine is pure.** Time is passed in; no platform call. A recording can be
  replayed fix by fix. The Dart engine (`lib/src/engine`) is a reference twin: the
  same CSV fixtures (`test/fixtures`) run through both.

## Time
Every time the engine sees comes from one **monotonic** clock (Android
`elapsedRealtime`, iOS `CLOCK_MONOTONIC`): a wall-clock jump can neither reorder
nor age a fix. Wall time is only kept for display and storage.

## Three independent states

| Aspect | Values |
|---|---|
| Recording | `idle`, `running`, `paused`, `finished` |
| GPS quality | `unknown`, `good`, `degraded`, `unavailable`, `recovering` |
| Distance source | `gps`, `gapBridge`, `pdr` (per ledger entry) |

Running does not mean the person is moving; GPS quality does not say how far.

## What happens to one fix

```
fix arrives
 ├─ engine not running ........................ ignored (notRunning)
 ├─ other segment ............................. dropped (wrongSegment)
 ├─ seq / time not newer ...................... dropped (duplicate)
 ├─ platform says mock ........................ dropped (mock)
 ├─ other session ............................. dropped (wrongSession)
 ├─ NaN / out of range / negative ............. dropped (invalid)
 ├─ measured > 30 s ago ....................... dropped (stale)
 ├─ accuracy unknown or > 40 m ................ quality = degraded, no distance
 ├─ first good fix of the segment ............. becomes the anchor, no distance
 ├─ after real silence (> 10 s) ............... RECOVERY (below)
 └─ normal stretch from the anchor
      ├─ implied speed > ceiling (GPS error of both ends allowed for)
      │                                         jump: rejected, anchor kept
      ├─ Doppler speed < 0.4 m/s .............. stationary: no distance, anchor kept
      ├─ displacement < noise radius .......... noise: no distance, anchor kept
      └─ otherwise ............................ distance added (gps), anchor = fix
```

A GPS gap is judged from the time of the last HEALTHY fix, not from the anchor
(which stays put while standing). The anchor only moves when distance is credited, so drift cannot add up one small
step at a time.

## Recovery (first good fix after silence)

```
gap = time between the last anchor and this fix
chord = straight line anchor → fix, U = both accuracies
 ├─ gap > 2 min ............................... not bridged, fresh anchor
 ├─ chord / gap > speed ceiling ............... not bridged (teleport)
 └─ bridge = max(0, chord − U)
      pending step estimate P (if any):
        confirmed when chord − U ≥ 0.5 · P
        credit = max(bridge, confirmed ? min(P, ceiling·gap) : 0)
```

**One source per gap, never a sum.** A step estimate is **pending** until the GPS
that returns confirms it, so a phone shaken without GPS adds 0 m (the straight
line is ~0 and nothing confirms it). Cost: while GPS is silent the distance on
screen does not move; it jumps when GPS returns.

## Situations

| Situation | What happens |
|---|---|
| Start, GPS good, run | Fixes every second → `gps` distance |
| Start, GPS good, sit still | Doppler < 0.4 or inside the noise radius → 0 m |
| Start with no GPS, shake the phone | No anchor ever → pending estimate dropped → **0 m** |
| Good → tunnel 500 m → good | Silence at 10 s; steps (if supplied) pending; recovery credits `max(straight line, confirmed estimate)` once |
| Good → weak → stop → shake | Pending estimate not confirmed (line ≈ 0) → 0 m |
| Pause | Segment closed, anchor forgotten, pending estimate dropped, nothing bridged |
| Resume | New segment (`segmentId + 1`); first good fix is a fresh anchor; total kept |
| Finish | Pending dropped, state frozen, later fixes ignored |
| GPS flapping good/degraded | Degraded fixes are judged as a normal stretch (Doppler and noise gates included) |
| Flutter engine killed while recording | Native keeps recording and measuring; Dart re-attaches to the snapshot stream |
| Whole process killed | `restoreSession` replays the persisted fixes (segments never joined); the session comes back paused |
| Standing still for a while | With adaptive sampling on, native asks for sparser fixes; the silence limit rises with the plan, so it is not mistaken for an outage |
| Slow walker, Doppler under 0.4 | Counts as stationary. Known limit: tune `stationarySpeedMps` from data |
| Cycling | Use `TrackingConfig.forSport(cycling)` (25 m/s ceiling); do not submit step estimates |

## Steps (native sensor, shared core)

Opt in with `createSession(stepsEnabled: true)`. Native reads the sensor (Android
`TYPE_STEP_COUNTER`, iOS `CMPedometer`); the shared core decides everything else:

```
sensor ─► StepSession (baseline / segments / counter reset)  ─► steps delta
                                                                  │
                          GPS good ◄───────────────────────────────┤──► GPS not good
        StrideEstimator learns metres per step                      stride known?  yes ─► PENDING (steps × stride)
        (steps + GPS metres of the SAME window)                                    no  ─► nothing
                                                                  │
                                              the GPS that returns confirms it (GapReconciler): one source per gap
```

- Steps are **not** distance while GPS is good, and never become distance on
  their own: a pending estimate counts only as far as the returning GPS
  corroborates (at most `lowerBound(line) / pdrConfirmRatio`, never more than the
  speed ceiling allows). A phone shaken without GPS adds 0 m; a loop with no GPS
  is credited only as far as the straight line allows.
- The stride is learned only from windows with enough steps AND GPS metres
  (20 / 20 m), a window with 200 steps and < 20 m is abandoned, an implausible
  sample (< 0.3 or > 2.5 m) is dropped, then an EMA. With no stride (none learned
  yet, none in `TrackingConfig.initialStrideMeters`) nothing is estimated.
  Pass the stride learned in the user's last recording (`snapshot.strideMeters`)
  as `initialStrideMeters` so a weak start still counts.
- Pause: the segment is closed, the learning window dropped, the stride kept.
  Resume: Android takes a fresh baseline (steps walked while paused are never
  counted), iOS counts from the new segment's start date. At a pause iOS asks the
  OS for the exact count up to the boundary and corrects the displayed total
  (never distance).
- No permission / no sensor: the recording goes on, `snapshot.stepsStatus` says
  why. Ask with `requestStepsPermission()` (Android 10+ `ACTIVITY_RECOGNITION`,
  iOS Motion & Fitness; iOS also prompts at the first start when not asked).
- Cycling never holds a step estimate (`pdrEnabled` is off for it).
- **Not measured:** Android may report steps up to ~10 s late and stamps a reading
  with its LAST step, so steps near a pause are under-counted; the core is fed
  the arrival time. Stride, thresholds and cadence behaviour are unvalidated.

## Foreground notification and live activity

See `PRESENTER.md`.

## Step estimates from the app (optional PDR)

With `stepsEnabled` the package has its own step sensor (above). An app with its own step source may still call `session.submitPdr(meters)` with
an estimate from its own step source (for example one it already has). It is
held as pending only while GPS is not good, and it is dropped at pause, finish or
when there is no anchor to confirm it. `PdrPolicy.off` ignores it entirely.

## Native requirements

- **iOS:** `Info.plist` needs `NSLocationWhenInUseUsageDescription` and
  `UIBackgroundModes` containing `location`. Without the background mode,
  `start` fails with `MISSING_BACKGROUND_MODE` instead of crashing. Minimum
  iOS 14. Relaunch after the system kills the app needs the Always permission
  and is **not implemented**.
- **Android:** the manifest declares the foreground service of type `location`,
  `ACCESS_FINE/COARSE_LOCATION`, `FOREGROUND_SERVICE(_LOCATION)` and
  `POST_NOTIFICATIONS`. Start a recording from the foreground ("while in use" is
  enough). No background location permission is requested.

## Not done yet

- A real-device pass (battery, background survival, OEM behaviour): none of it is
  measured.
- Automatic restart after the OS kills the app (iOS significant-change, Android
  service restart): `restoreSession` rebuilds the state, but something must start
  the app.
- Kalman smoothing is wired but off by default and not validated on devices.
- Persisted pending step estimates (lost if the process dies).
- Publishing: homepage and repository URL in `pubspec.yaml`.
