# Tracking flow

How a recording moves from the sensor to the distance on screen, and what
happens in every situation. Everything numeric is a **starting value chosen by
reasoning, not measured**: tune it from real recordings (see `TrackingConfig`).

## Layers

```
GPS hardware / step sensor / (a watch)
   │  raw locations, step readings
   ▼
Native (Kotlin / Swift)        collects, numbers fixes, schedules the 1 Hz tick,
   │                           adaptive sampling, permissions, foreground service
   ▼
SessionRecorder (Kotlin core)  WRITES EVERY INPUT TO THE JOURNAL, THEN applies it
   │                           (one writer, flush barrier, CRC per line; see JOURNAL.md)
   ▼
TrackingCoreBridge             one door, primitives only
   ▼
GpsDistanceEngine (Kotlin)     the only place that decides what is distance
   │  DistanceLedger  (append-only, sum = total)
   ▼
NativeSnapshot  (Pigeon, EventChannel, ~1 Hz)
   ▼
TrackingSession (Dart)         commands, snapshots for the UI, presenter hook
```

- **Native owns the session AND the distance.** If the Flutter engine dies,
  native keeps recording and keeps measuring.
- **The engine is a pure function of the journal.** Time is passed in; no
  platform call. Replaying the journal reproduces the live result exactly
  (distance, pending estimates, stride, steps, gaps, times), so a restore after a
  process death loses nothing that was recorded. The Dart engine
  (`lib/src/engine`) is a reference twin of the DISTANCE rules: the same CSV
  fixtures (`test/fixtures`) run through both. It has no time model, adaptive
  sampling, Kalman gate or journal.
- **A watch is one more producer.** Fixes and step readings are source-agnostic
  events with a measurement time. A watch app embeds the same core (the KMP core
  compiles for the JVM and Apple targets) and writes the same journal records; the
  phone can replay a watch's journal. Nothing in the rules assumes a phone.

## Time

Every time the engine sees comes from one **monotonic** clock (Android
`elapsedRealtime`, iOS `CLOCK_MONOTONIC`): a wall-clock jump can neither reorder
nor age a fix. Wall time is only kept for display and storage; the public
`lastFixAt` is converted to calendar time at the native boundary.

Three durations are reported. They are NOT three parts that add up: **moving and uncredited are each a part OF the active time**, never added on top of it (`moving <= active`, `bridged + uncredited <= active`; a test pins this).

| Time | Meaning |
|---|---|
| **active** (`activeTime`) | The running legs only; pauses excluded. The headline timer. |
| **moving** (`movingTime`) | Time moving: speed above the stationary limit, plus the time of credited GPS gaps that were crossed at moving speed. |
| **uncredited** (`uncreditedGap`, `uncreditedGaps`) | The part of the active time whose DISTANCE is unknown: GPS gaps and unmeasured intervals NOTHING was credited for: a weak start, a gap beyond `maxBridge`, a gap with no steps, a gap still open at finish. |

The uncredited time is **reported, never hidden**: for that time the distance is
unknown, not zero. (It is about unknown DISTANCE, not unknown motion: GPS lost does
not mean motion unknown, since steps may still show it. A separate
"unknown motion" duration is not defined here.) The three names are this SDK's
own; they are not claimed to match another app's definitions. An app that wants to be honest shows it ("GPS lost for 2 min
10 s").

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

## Standing still, and gaps that are too long

- **Doppler below 0.4 m/s** (when its own accuracy is decent) = no distance.
- **No usable Doppler:** the speed is taken from the displacement itself; below
  the stationary limit = no distance.
- **Stopped (stayed within the stay radius for 30 s) and a fix still inside it:**
  jitter, no distance. The anchor stays put, so when the person leaves the radius
  the whole displacement is credited at once: nothing is lost.
- **A gap with a working step sensor and no steps at all** (and a gap of at least
  30 s, since Android may deliver steps ~10 s late) is credited 0 m: the person
  stood still. Cycling (no steps) is never judged this way.
- **A gap beyond `maxBridge`, or a loop:** the conservative policies credit what
  GPS can corroborate (possibly 0 m) and REPORT the time as uncredited.
- **`PdrPolicy.trustedWhenCalibrated` (opt-in):** once the stride was calibrated on
  good GPS (3 windows), a gap is credited from the steps as ESTIMATED distance
  (source `PDR`, kept separate from GPS in the API), bounded by the speed ceiling,
  even when it is long or curved. The price: steps without movement (a phone
  shaken in the hand) can then be credited. The default never does that.

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
- **A batch is judged by the interval it was MEASURED in**, not by when it was
  delivered. The engine keeps a short timeline of when GPS was measuring; a batch
  wholly in good GPS teaches the stride, wholly in a weak interval becomes pending,
  and a batch straddling a quality change is `ambiguousSteps`: it STAYS in the step
  total and in the journal (so it can be debugged or reprocessed), but is not used
  to learn the stride nor to make a pending estimate, and is never split evenly and
  called exact. A weak batch that arrives after GPS came
  back is too late to attach.
- Pause: the segment is closed, the learning window dropped, the stride kept.
  Resume: Android takes a fresh baseline (steps walked while paused are never
  counted), iOS counts from the new segment's start date. At a pause iOS asks the
  OS for the exact count up to the boundary and corrects the displayed total
  (never distance). **Finish waits** for that exact count (3 s limit): the answer
  carries the identity (session, segment) it was asked for, so a late one can
  never touch a newer session; if it fails or times out the snapshot says so with
  a stable code and `stepsFinal == false`, and the live count is kept (never
  reported as 0).
- No permission / no sensor: the recording goes on, `snapshot.stepsStatus` says
  why. Ask with `requestStepsPermission()` (Android 10+ `ACTIVITY_RECOGNITION`,
  iOS Motion & Fitness; iOS also prompts at the first start when not asked).
- Cycling never holds a step estimate (`pdrEnabled` is off for it).
- **Not measured:** Android may report steps up to ~10 s late and stamps a reading
  with its LAST step, so steps near a pause are under-counted; the core is fed
  the arrival time. Stride, thresholds and cadence behaviour are unvalidated.

## Restore, reprocess, persistence and finish

- **One journal per session** (`JOURNAL.md`): a header with the configuration, then
  every input (lifecycle, fixes with their arrival time, step readings, the tick
  that detected silence). Written BEFORE it is applied, by ONE writer; `finish`,
  reading and clearing first wait for it to reach the disk.
- **Restore REBUILDS what was shown; reprocess RECOMPUTES.** Restore replays the
  journal with the settings stored in its header. The header also stores the
  **engine version** (the rules); a journal written by other rules is replayed anyway
  but the snapshot says `ENGINE_CHANGED` because the result may differ from what was
  shown. `SessionRecorder.reprocess(lines, settings?)` recomputes with the current
  rules (and optionally other settings) and writes nothing; use it to recompute,
  never to resume.
- **Restore** replays the journal with the configuration stored in ITS header
  (not the one the app passes later) and comes back PAUSED **at the time of the last journaled event**, not at "now":
  the time the process was dead is never workout time, and a reboot (the monotonic
  clock restarts) cannot make a leg negative or join two clocks. `resume` continues in
  a new segment. A half-written last record is dropped and the file repaired; damage
  in the middle keeps only the intact part and says so (`JOURNAL_CORRUPT`), AFTER
  copying the original to `<file>.corrupt` (the repair drops everything after the
  damage, so it never destroys the only copy); an
  unusable journal throws. File names come from a hash of the session id, so two
  ids never collide; `listSessions()` reads the real ids from the headers.
- **Android resume after a process death** starts the foreground service BEFORE
  location, tick and steps start; if the OS refuses (for example a start from the
  background) the session stays paused and `resume` throws
  `FOREGROUND_START_FAILED`.
- **Durable or not is public.** `journalPending` counts events accepted but not yet on
  disk (`isDurable` is true at zero); a crash while it is above zero can lose that many.
- **Finish is a state, not an instant.** `finishing` (live data closed, exact steps
  requested, journal flushed) then `finished`. Only `finished` is final; a snapshot
  published while `finishing` says `stepsFinal == false`.
- **Callbacks** carry the generation they were registered under and are dropped
  when it is no longer current; commands wait for the jobs they stop. A late
  callback can never touch a newer session, segment or state.

## Foreground notification and live activity

The SDK ALWAYS runs its own foreground service on Android, so the recording never
depends on a third party. See `PRESENTER.md`.

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
  measured, and every threshold is a starting value.
- Automatic restart after the OS kills the app (iOS significant-change, Android
  service restart): the journal rebuilds the state, but something must start the app.
- Kalman smoothing is wired but off by default and not validated on devices.
- Sampling accuracy: the sampling plan's `highAccuracy` is not applied natively yet
  (Android always HIGH_ACCURACY, iOS always best), so `batterySaver` only changes the
  interval and displacement.
- A step batch delivered long after its interval cannot be attached to a gap that
  was already credited (it is lost, not double counted).
- A watch producer is designed for (same events, same core) but not built here.
