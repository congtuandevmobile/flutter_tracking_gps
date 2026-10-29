# flutter_tracking_gps

Own GPS tracking engine for Flutter. Native code collects raw fixes and step readings and keeps them on disk
(iOS: CoreLocation + CMPedometer; Android: Fused Location + `TYPE_STEP_COUNTER` inside a foreground service).
A deterministic distance engine, written once in Kotlin Multiplatform (`tracking-core`) and running natively on
both platforms, decides what counts as distance. Dart sends commands and shows snapshots.

**Status: 0.0.1-beta.2. Not tested on a physical device.** Every threshold in `TrackingConfig` is a starting
value chosen by reasoning, not measured. Unit tests prove the rules; they do not prove a sensor, the background or
the battery (see [Limits and tests](#limits-and-tests)).

Read `doc/FLOW.md` before changing the engine and `doc/NATIVE_RULES.md` before touching `android/` or `ios/`.

## Contents

1. [Principles](#principles)
2. [Install and use](#install-and-use)
3. [Architecture](#architecture)
4. [What each layer does](#what-each-layer-does)
5. [Time](#time)
6. [What happens to one fix](#what-happens-to-one-fix)
7. [Situations](#situations)
8. [Weak GPS: how many seconds until distance stops](#weak-gps-how-many-seconds-until-distance-stops)
9. [Steps, stride and shaking the phone](#steps-stride-and-shaking-the-phone)
10. [Pace, speed and cadence](#pace-speed-and-cadence)
11. [Algorithms](#algorithms)
12. [Configuration: every threshold and where to change it](#configuration-every-threshold-and-where-to-change-it)
13. [Android](#android) · [iOS](#ios)
14. [Journal, restore and finish](#journal-restore-and-finish)
15. [When the app is killed](#when-the-app-is-killed)
16. [Live activity and notification](#live-activity-and-notification)
17. [Snapshot fields](#snapshot-fields)
18. [Limits and tests](#limits-and-tests)
19. [Repository layout](#repository-layout)

## Principles

1. **GPS is the truth.** Distance is computed from raw fixes (position, Doppler speed, accuracy), not read from a
   black-box odometer. The optional Kalman filter is only a plausibility gate; it never supplies coordinates.
2. **Steps never become distance on their own.** While GPS is weak a step count makes a *pending* estimate that only
   counts as far as the GPS that returns confirms it. A phone shaken in the hand without moving adds 0 m.
3. **One source per gap, never a sum.** A GPS gap is credited once, as the larger of the straight line across it and
   the step estimate that GPS corroborates.
4. **What is unknown is reported, not hidden.** Time whose distance cannot be known is surfaced as `uncreditedGap`
   (unknown, not "0 m").
5. **The engine is a pure function of the journal.** Time is passed in and no platform call is made, so replaying the
   journal reproduces the live result exactly.

## Install and use

```yaml
dependencies:
  flutter_tracking_gps: ^0.0.1-beta
```

Releases are tagged `v<version>` from `pubspec.yaml`; see `CHANGELOG.md`.

```dart
final gps = FlutterTrackingGps();

if (await gps.permissionStatus() != PlatformPermission.granted) {
  await gps.requestPermission();
}

final session = gps.createSession(
  config: TrackingConfig.forSport(TrackingSport.running),
  notificationTitle: 'Recording',
  stepsEnabled: true,                   // steps help while GPS is weak
  presenter: MyLiveActivityPresenter(), // optional, see "Live activity and notification"
);
session.updates.listen((s) => print('${s.totalMeters} m, gps ${s.quality.name}'));

await session.start(sessionId: 'activity-123');
await session.pause();
await session.resume();                 // new segment: nothing is bridged across the pause
final result = await session.finish();  // FINAL: journal durable, steps finalized
print(result.activeTime);               // workout time, pauses excluded
print(result.uncreditedGap);            // time whose distance is unknown (reported, not hidden)
print(await session.report());          // coordinate-free timeline of the engine's decisions
final route = await session.persistedFixes(); // the raw route, oldest first
await session.clearPersisted();         // after the activity was saved
```

After the process died: `await gps.listSessions()` shows what is on the device,
`await gps.activeSessionId()` is the open-session pointer (per DEVICE: decide whether it is yours), and
`await gps.attachOrRecover(accept: ...)` or `await session.restore(sessionId: ..., recoverRunning: true)` brings it
back. Do not call `start` on a restored session and do not add the dead time yourself: native already did.

Setup:

- **iOS (minimum 14):** `Info.plist` needs `NSLocationWhenInUseUsageDescription`, `UIBackgroundModes: [location]`
  and, for steps, `NSMotionUsageDescription`. Call `FlutterTrackingGpsRuntime.bootstrapAtLaunch()` from
  `application(_:didFinishLaunchingWithOptions:)` so an open session is recovered before Flutter exists.
- **Android:** nothing to add; the plugin manifest declares the permissions and the foreground service. Start a
  recording from the foreground ("while in use" is enough).

## Architecture

```
GPS hardware / step sensor / (a watch)
   │  raw locations, step readings
   ▼
Native (Kotlin / Swift)        collects, numbers fixes, 1 Hz tick, adaptive sampling,
   │                           permissions, foreground service (Android)
   ▼
SessionRecorder (core)         WRITES EVERY INPUT TO THE JOURNAL, THEN applies it
   │                           (one writer, flush barrier, CRC per line)
   ▼
TrackingCoreBridge             one door, primitives only (unknown = negative number)
   ▼
GpsDistanceEngine (core)       the only place that decides what is distance
   │  DistanceLedger           (append-only, sum = total)
   ▼
NativeSnapshot (Pigeon, EventChannel, ~1 Hz)
   ▼
TrackingSession (Dart)         commands, snapshots for the UI, presenter hook
```

**Native owns the session and the distance.** If the Flutter engine dies, native keeps recording and measuring.
A watch is one more producer: fixes and step readings are source-agnostic events, and the KMP core compiles for the JVM
and Apple targets (designed for, not built here).

## What each layer does

### Kotlin Multiplatform (`tracking-core`)

Algorithms are written once in plain Kotlin (no platform API, no third-party library) and used by both platforms, so
there is never a second Kalman or distance rule that disagrees with the first.

| Platform | How the core is used |
|---|---|
| Android | `android/build.gradle.kts` adds `tracking-core/src/commonMain/kotlin` to the plugin's source dirs. No second Gradle project. |
| iOS | `tool/build_core.sh [debug\|release]` builds `TrackingCore.xcframework` into `ios/Frameworks/` (git-ignored); the podspec vendors it. Swift calls e.g. `GeoMath.shared.haversineMeters(...)`. |
| Tests | `cd tracking-core && ./gradlew jvmTest` (JVM, fast). |

| Folder | Content | On the recording path |
|---|---|---|
| `engine` | `GpsDistanceEngine`, `DistanceLedger`, `TrackingLog` | yes |
| `bridge` | `TrackingCoreBridge`, `BridgeSnapshot`, `BridgePlan` | yes |
| `journal` | `SessionRecorder`, `JournalCodec`, `Records`, `Wire` (CRC-32) | yes |
| `model` | `TrackingFix`, `TrackingConfig`, `TrackingSnapshot`, state enums | yes |
| `algorithms/geometry` | `GeoMath` (haversine, local plane), `DouglasPeucker` | GeoMath yes; DouglasPeucker is a utility |
| `algorithms/filtering` | `Kalman2D`, `OutlierRejector`, `LowPassFilter` | Kalman (off by default) and OutlierRejector yes; LowPassFilter is a utility |
| `algorithms/motion` | `StopDetector`, `StrideEstimator`, `StepSession` | yes |
| `algorithms/fusion` | `GapReconciler` | yes |
| `algorithms/power` | `AdaptiveSamplingPolicy` | yes, when `adaptiveSampling` is on |
| `auxiliary` | `Dbscan`, `KMeans`, `RoadGraph` (Dijkstra, A*), `HmmMapMatcher` | no: side library with tests, not wired into the engine |

Deliberately **not** in the core: trilateration and A-GPS (the GNSS chip and the OS do this), map matching (needs a road
graph the app does not have and changes the distance; do it server-side on the finished route), routing, BLE, K-Means for
stop detection (use the online stay-point detector instead), barometer fusion (a separate ascent feature).

### Kotlin (Android)

- `FusedLocationSource`: Google Fused Location, `PRIORITY_HIGH_ACCURACY`, a cold `Flow` of `RawLocation` (no Android types).
- `SensorStepSource`: `TYPE_STEP_COUNTER`, reads only; baseline, deltas and metres are the core's.
- `TrackingEngine`: coroutine orchestration: fix numbering, 1 s tick, location request changes when the adaptive plan
  changes, snapshot push. Every callback carries the generation it was registered under; late ones are dropped.
- `TrackingService`: foreground service of type `location`, `START_STICKY`, recovers the open session without Flutter.
- `TrackingRuntime`: ONE engine per process, outliving the Flutter engine and the plugin instance.
- `FileJournalStore`: journal files under `filesDir/flutter_tracking_gps/journal`, one writer.
- `ForegroundHandshake`: `startForegroundService()` only queues the start, so recording begins only after the service
  acknowledges `startForeground` (otherwise `FOREGROUND_START_FAILED`).

### Swift (iOS)

- `CoreLocationProvider` (`LocationProviding`): `desiredAccuracy = Best`, `activityType = .fitness`,
  `pausesLocationUpdatesAutomatically = false`, background updates on. CoreLocation has no interval, only `distanceFilter`.
- `CoreMotionStepProvider` (`StepProviding`): `CMPedometer` counts from a chosen date (no baseline needed) and gives the
  exact count of a closed segment at a boundary.
- `TrackingEngine`: serial queue, `DispatchSourceTimer` 1 Hz tick, every result to Flutter on the main queue, `[weak self]`.
- `FlutterTrackingGpsRuntime`: process-wide owner; `bootstrapAtLaunch()` recovers an open session before Flutter exists.
- Opt-in, off by default, not verified on a device: `useLiveUpdates` (iOS 17+ `CLLocationUpdate.liveUpdates` +
  `CLBackgroundActivitySession`), `wakeOnSignificantChange` (needs Always).

### Dart

- Sends commands over Pigeon (`pigeons/tracking_api.dart`; never edit `*.g.*`) and receives snapshots over an EventChannel.
- `TrackingSession`: `start`, `pause`, `resume`, `finish`, `restore`, `report`, `persistedFixes`, `clearPersisted`,
  `updateNotification`, `submitPdr`, `updates`, `fixes`.
- `TrackingPresenter` + `PresenterDispatcher`: the Live Activity hook.
- `lib/src/engine` is the **reference twin** of the distance rules (not the shipped engine). The same CSV fixtures in
  `test/fixtures` run through both. It has no time model, adaptive sampling, Kalman gate or journal. **Change a rule in
  BOTH engines and add it to a fixture.**

## Time

Every time the engine sees (`fix.timeMs`, `nowMs`, `atMs`) comes from ONE monotonic clock (Android `elapsedRealtime`, iOS
`CLOCK_MONOTONIC`): a wall-clock jump can neither reorder nor age a fix. Wall time is kept only for display and storage.

| Time | Field | Meaning |
|---|---|---|
| active | `activeTime` | The running legs only; pauses excluded. The headline timer. |
| moving | `movingTime` | Speed above the stationary limit, plus the time of credited GPS gaps crossed at moving speed. |
| uncredited | `uncreditedGap`, `uncreditedGaps` | The part of the active time whose DISTANCE is unknown: weak start, gap beyond `maxBridge`, gap with no steps, gap still open at finish, process-death silence. |
| bridged | `bridgedGap` | Time of credited gaps. |
| elapsed | `elapsedTime` | Start to finish, pauses included. |

They are not three parts that add up: moving and uncredited are each a part OF the active time (`moving <= active`,
`bridged + uncredited <= active`; a test pins this). Uncredited is about unknown DISTANCE, not unknown motion.

Three independent states: **recording** (`idle`, `running`, `paused`, `finishing`, `finished`), **GPS quality** (`unknown`,
`good`, `degraded`, `unavailable`, `recovering`) and **distance source** per ledger entry (`gps`, `gapBridge`, `pdr`).

## What happens to one fix

Order of the checks in `GpsDistanceEngine.onFix` (each is a `FixVerdict`):

| # | Condition | Result |
|---|---|---|
| 1 | engine not running | `NOT_RUNNING`, ignored |
| 2 | other session / other segment | `WRONG_SESSION` / `WRONG_SEGMENT`, dropped |
| 3 | NaN, out of range, negative | `INVALID`, dropped |
| 4 | `seq` or time not newer than the last fix | `DUPLICATE`, dropped |
| 5 | (live only) measured more than 2 s in the future, older than `maxFixAge` (30 s), or before the leg began | `FUTURE` / `STALE`, dropped |
| 6 | platform says mock and `rejectMock` is on | `MOCK`, dropped |
| 7 | accuracy unknown or above `maxAccuracyMeters` (40 m) | `LOW_ACCURACY`: quality `degraded`, no distance |
| 8 | no anchor yet (first good fix of the segment) | `ANCHOR`: becomes the anchor, no distance (an interval longer than `silenceAfter` without GPS before it is recorded as uncredited) |
| 9 | real silence (quality `unavailable`, or more than `silenceAfter` since the last HEALTHY fix) | `RECOVERED`: recovery (see below) |
| 10 | (Kalman on) gate rejects it, or it re-localizes | `OUTLIER` / `REANCHORED` |
| 11 | implied speed above `maxSpeedMps` (GPS error of both ends allowed for), against the anchor AND the last healthy fix | `JUMP`: rejected, anchor kept; after `reanchorAfterRejects` (3) in a row `REANCHORED` (fresh anchor, nothing credited) |
| 12 | Doppler below `stationarySpeedMps` (0.4 m/s) when its own accuracy is at most 1.5 m/s | `STATIONARY`, anchor kept |
| 13 | no usable Doppler and displacement speed below 0.4 m/s | `STATIONARY` (it only ever blocks; it never confirms movement) |
| 14 | stopped (inside `stopRadiusMeters` for `stopMinDwell`) and the fix is still inside the radius | `STATIONARY` |
| 15 | displacement below `max(minStepMeters, accuracyNoiseFactor × max(anchor accuracy, fix accuracy))` | `NOISE`, anchor kept |
| 16 | otherwise | `ACCEPTED`: `chord` is added to the ledger (source `gps`), anchor = this fix |

The anchor only moves when distance is credited, so drift cannot add up one small step at a time, and when the person
leaves the radius the whole displacement is credited at once: nothing is lost. A GPS gap is judged from the time of the last
HEALTHY fix, not from the anchor (which is old on purpose while standing).

## Situations

| Situation | What happens |
|---|---|
| Start, GPS good, run | One fix per second → `gps` distance. |
| Start, GPS good, sit still | Doppler below 0.4 or inside the noise radius → 0 m. After 30 s within 15 m, `isStopped` is true and moving time stops. |
| Start with no GPS, shake the phone | No anchor ever → pending estimate dropped → **0 m**. The unmeasured interval is uncredited. |
| Good → tunnel 500 m → good | Silence at 10 s; steps (if enabled) pending; recovery credits `max(straight line, confirmed estimate)` once. The distance on screen does not move inside the tunnel and jumps when GPS returns. |
| Good → weak → stop → shake | Pending not confirmed (line ≈ 0) → 0 m. |
| GPS flapping good/degraded within seconds | A degraded fix is judged as a normal stretch when the next good fix arrives within 10 s of the last healthy one; pending and step evidence of that short period are dropped so they cannot leak into a later gap. |
| Pause | Segment closed, anchor forgotten, pending dropped, nothing bridged. |
| Resume | New segment (`segmentId + 1`); first good fix is a fresh anchor; total kept. Movement while paused is never counted. |
| Finish | Pending dropped, state frozen, later fixes ignored. `finishing` waits for the exact step count (3 s limit), then `finished`. |
| Standing for a while, adaptive sampling on | Native asks for sparser fixes; the silence limit rises with the plan so it is not mistaken for an outage. |
| Slow walker, Doppler under 0.4 | Counts as stationary. Known limit: tune `stationarySpeedMps` from data. |
| Cycling | `TrackingConfig.forSport(cycling)` (25 m/s ceiling), no step estimates. |

**Limit pinned by a test:** without a Doppler speed, a drift faster than the stationary limit (say 0.6 m/s) cannot be told
from slow walking and IS credited.

## Weak GPS: how many seconds until distance stops

**While GPS is weak or gone, distance does not grow.** It jumps when GPS returns, and only if the conditions below allow.

```
0 – 10 s        not a gap yet. A fix worse than 40 m makes quality `degraded` and adds nothing. If a good fix returns
                inside this window it is judged as a normal stretch from the anchor.
> 10 s          silence: quality `unavailable`. Steps (if enabled and a stride is known) become pending.
10 s – 120 s    on return: bridge = max(0, chord − both accuracies), capped by speed ceiling × gap seconds,
                plus the step estimate the GPS corroborates.
> 120 s         not bridged: 0 m, the time is uncredited, a fresh anchor. Only exception: `PdrPolicy.trustedWhenCalibrated`.
```

| Threshold | Default | Field | Effect |
|---|---|---|---|
| A fix counts as weak | > 40 m (or unknown) | `maxAccuracyMeters` | `degraded`, no distance |
| No fix for this long = GPS silent | 10 s | `silenceAfter` | Opens a gap. With adaptive sampling: `max(silenceAfter, 4 × interval)` (12 s still, 20 s battery saver) |
| Longest gap still bridged | 120 s | `maxBridge` | Longer: not bridged, fresh anchor, uncredited |
| A gap with no steps credits 0 | 30 s | `recoveryStepEvidenceAfter` | Needs a working step sensor; `Duration.zero` disables |
| Speed ceiling | 10 m/s (25 cycling) | `maxSpeedMps` | A gap whose implied speed is above it is a teleport: not bridged; also caps the credit at `maxSpeed × gap seconds` |
| Estimate confirmation | 0.5 | `pdrConfirmRatio` | Estimate P is accepted up to `lowerChord / 0.5` |
| Step policy | `confirmedByGps` | `pdrPolicy` | `off`, `confirmedByGps`, `trustedWhenCalibrated` |

How one gap is credited (`GapReconciler`):

```
unc        = accuracy(previous fix) + accuracy(new fix)
lowerChord = max(0, chord − unc)
ceiling    = maxSpeed × gap seconds
if gap > maxBridge        → 0 m
if lowerChord > ceiling   → 0 m   (implied speed impossible, teleport)
credit     = min(lowerChord, ceiling)            // source gapBridge
if pending P > 0 and lowerChord > 0:
    cap        = min(ceiling, lowerChord / pdrConfirmRatio)
    confirmed  = min(P, cap)
    if confirmed > credit → credit = confirmed   // source pdr
if P > 0 and lowerChord == 0 → "pdr NOT corroborated" (0 m)
```

Because the chord is reduced by the accuracy of both ends, a short gap where the position moved only a few tens of metres
with 20 m accuracy at each end credits 0 m. That is deliberate (under-count rather than inflate), but a short tunnel can
read lower than another GPS watch.

## Steps, stride and shaking the phone

```
sensor ─► StepSession (baseline / segments / counter reset) ─► steps delta
                                                                  │
          GPS good ◄───────────────────────────────────────────────┤──► GPS not good
  StrideEstimator learns metres per step                      stride known?  yes ─► PENDING (steps × stride)
  (steps + GPS metres of the SAME window)                                     no  ─► nothing
                                                                  │
                                    the GPS that returns confirms it (GapReconciler): one source per gap
```

- Native reads only the sensor (Android `TYPE_STEP_COUNTER`, iOS `CMPedometer`); accounting is the core's. The first reading
  of a segment is only a baseline, so steps taken before it (or while paused) are never counted; a counter that goes
  backwards (reboot) keeps the accepted total and becomes the new baseline.
- Three numbers: `steps` (what the platform reported for the active segments), `ambiguousSteps` (interval could not be
  told: kept, used for nothing), `distanceEligibleSteps` (taught the stride or became a pending estimate).
- **A batch is judged by the interval it was MEASURED in**, not by when it was delivered. Wholly in good GPS: teaches the
  stride (not distance). Wholly weak and a stride is known: `steps × stride` becomes pending. Straddling a quality change:
  ambiguous. A weak batch that arrives after GPS came back is too late to attach (lost, never double counted).

`StrideEstimator` learns on good GPS only:

| Parameter | Value | Meaning |
|---|---|---|
| minimum steps / metres per sample | 20 / 20 m | enough to average out step batching and GPS noise |
| plausible stride | 0.3 – 2.5 m | a sample outside is dropped |
| EMA alpha | 0.3 | one odd window moves the estimate only a little |
| abandon | ≥ 200 steps with < 20 m | standing or shaking: the window is dropped |

The stride counts as **calibrated** after 3 samples in the session (`CALIBRATED_SAMPLES`); `initialStrideMeters` alone is
not calibration. Pass the stride learned in the user's last recording (`snapshot.strideMeters`) as `initialStrideMeters`.

| Shaking scenario | Steps | Distance |
|---|---|---|
| GPS good, standing, shaking | sensor counts steps | 0 m (Doppler / noise radius); stride window abandoned past 200 steps with < 20 m |
| GPS weak/lost, shaking on the spot, default policy | pending (if stride known) | **0 m**: the straight line on return is ≈ 0, so nothing corroborates it |
| GPS weak/lost, shaking, `trustedWhenCalibrated` | pending | **may be credited** (capped by the speed ceiling). The price of the opt-in; the default never does this |
| Shake, then walk a short way, GPS returns | large pending | accepted only up to `lowerChord / 0.5` |

Pause closes the segment and drops the learning window (stride kept). Resume: Android takes a fresh baseline, iOS counts from
the new segment's start date. At a pause iOS asks the OS for the exact count and corrects the displayed total (never
distance); **finish waits** for it (3 s limit). If it fails or times out the snapshot reports a stable code and
`stepsFinal == false` and the live count is kept (never reported as 0). No permission / no sensor: the recording goes on and
`stepsStatus` says why; ask with `requestStepsPermission()`.

**Not measured:** Android may report steps up to ~10 s late and stamps a reading with its LAST step, so steps near a pause are
under-counted; the core is fed the arrival time. Stride, thresholds and cadence behaviour are unvalidated.

## Pace, speed and cadence

**The SDK computes neither pace nor cadence.** It supplies two raw inputs in every snapshot: `speedMps` (instantaneous Doppler
speed) and `steps` (the session step total). A host derives pace, average speed and cadence from them.

### `speedMps`

- The Doppler speed of the latest healthy fix (Android `Location.speed`, iOS `CLLocation.speed`), not distance divided by time.
- Trusted only when `speedAccuracy` is at most **1.5 m/s** or the platform gives none; otherwise `null`. A platform "unknown"
  (negative) is also `null`.
- **Not smoothed, not averaged**: each fix overwrites the previous value, so a host that shows current pace should apply its own
  averaging window.
- `null` while quality is `degraded` or `unavailable`, right after a pause or resume, and before the first healthy fix of a
  segment. Treat `null` as "unknown", never as "standing still". The same value drives the `STATIONARY` decision (below
  `stationarySpeedMps`, 0.4 m/s by default), `isStopped` and adaptive sampling.

### Deriving pace and speed

```
current pace  [s/km] = 1000 / speedMps           (show nothing / "unknown" when speedMps is null or below a host-chosen floor)
current speed [km/h] = speedMps × 3.6
average speed [m/s]  = totalMeters / movingTime.inSeconds
average pace  [s/km] = movingTime.inSeconds / (totalMeters / 1000)
```

Use `movingTime` (not `activeTime` or `elapsedTime`) for averages. Distance and moving time stay consistent: a credited gap
contributes metres and moving time (when crossed at `stationarySpeedMps` or more), an uncredited gap contributes neither, so a GPS
outage cannot produce "time without metres". If a host picks a display floor for pace, remember the SDK itself calls anything under
`stationarySpeedMps` stationary (0.4 m/s by default): a floor below that shows a pace while distance does not grow. When rounding
a pace to `m:ss`, round the whole number of seconds first and then split into minutes and seconds (rounding only the seconds part
can print `5:60`).

### Cadence

The SDK exposes only the **step total** (`steps`, `ambiguousSteps`, `distanceEligibleSteps`). A host derives walking/running
cadence (steps per minute) from it. A reasonable method, as a pure function with the clock passed in:

```
keep a queue of (time, total) samples, appended when the total changes
    total went down, or time went back → clear the queue (new session or restore)
    trim samples older than (now − window), keeping ONE sample at or before the cutoff as the base
cadence(now, total):
    no samples, or (now − base.time) below a minimum span → 0   ("not enough data")
    steps = total − base.total; steps <= 0 → 0
    value = round(steps / seconds(span) × 60)                    // steps per minute
    value above a plausible ceiling (e.g. 220) → 0               // sensor noise
```

- Use a window longer than the step batches the platform delivers (Android up to ~10 s late; iOS in batches), for example 30 s with
  a 10 s minimum span. The cost is a slow response to a change of rhythm.
- Compute at read time so it decays to 0 when no steps arrive; treat `0` as "not enough data", not "the user stood still".
- Reset the window on pause, resume, restore and finish so the paused interval is not read as slow walking.
- `steps` includes `ambiguousSteps` (batches that straddle a GPS-quality change); cadence has no reason to exclude them.
- Android may stamp a reading with the time of the LAST step and report it ~10 s late, and iOS updates in batches, so cadence lags
  and can read low at the start of a window.

### Cycling: pedal cadence (rpm) is not available from the phone

The step sensor counts steps, not pedal revolutions, so a step-derived value is not a cycling cadence. Enable `stepsEnabled` only for
sports where steps mean something (running, walking). Pedal cadence needs a dedicated source: a BLE Cycling Speed and Cadence sensor
or a sports watch. The SDK has no such source today (BLE is deliberately out of scope), so a host that wants rpm must add one
as another producer of events. Counting is simply revolutions per minute: `rpm = crank revolutions in an interval ÷ minutes`, for
example 45 pedal-downs of one leg in 30 s is 45 × 2 = 90 rpm. The rpm cannot be inferred reliably from GPS speed alone, because it
also depends on the gear ratio and wheel size, which the SDK does not know.

| Figure | Source | Computed by | Smoothing |
|---|---|---|---|
| Distance | raw GPS fixes | engine (KMP core) | none (anchor + gates) |
| Current pace / speed | `speedMps` (Doppler) | host | **none from the SDK** |
| Average pace / speed | `totalMeters`, `movingTime` | host | whole-session average |
| Cadence (steps/min) | `steps` | host | host-chosen window |
| Pedal cadence (rpm) | not available | needs a BLE sensor or watch | not applicable |

## Algorithms

- **`GeoMath`**: haversine distance, validity checks, local tangent-plane projection (`toXY`/`fromXY`). Every GPS distance is
  `haversine(anchor, fix)` on raw coordinates.
- **`OutlierRejector`**: a stretch is a jump when `distance ÷ seconds > maxSpeed` (distance already reduced by the accuracy of
  both ends). A negative or non-finite input, or no elapsed time, is also a jump.
- **`StopDetector`**: online stay-point detector, O(1) per fix. Stopped once the phone stayed within `stopRadiusMeters` (15 m)
  of one point with no Doppler at or above 0.4 m/s for `stopMinDwell` (30 s), without an observation hole longer than
  `max(silenceAfter, 10 s)`. A candidate signal for moving time, not the sole authority.
- **`Kalman2D`** (off by default): constant-velocity filter per axis on a local plane, weighted by accuracy. In the engine it
  is a quality gate only. Parameters: acceleration sigma 2.0, chi-square gate 13.82, re-localize after 30 s of silence or 5
  consecutive rejections; a discontinuity is a fresh anchor, never a distance step. Limits: no accelerometer/gyro fusion, one
  motion model for every sport, sharp turns can be rejected. Not validated on devices.
- **`GapReconciler`**: see the formula above.
- **`StrideEstimator`** and **`StepSession`**: see Steps.
- **`AdaptiveSamplingPolicy`** (hysteresis, falls back to the moving plan on an unknown speed or a clock that went backwards):

  | Plan | When | Interval | Min distance | High accuracy |
  |---|---|---|---|---|
  | FAST | speed ≥ 6 m/s | 1 s | 3 m | yes |
  | MOVING | 0.4 – 6 m/s, or still for less than 20 s, or unknown speed | 1 s | 2 m | yes |
  | STILL | < 0.4 m/s for ≥ 20 s | 3 s | 3 m | yes |
  | STILL_SAVER | like STILL with `batterySaver` | 5 s | 5 m | no (**not applied natively yet**) |

  With adaptive off, native requests `updateInterval` and `minDistanceMeters` from the config. Geofencing is deliberately not
  used while recording.
- **`DistanceLedger`**: append-only, `sum = total`, entries are (segment, source, metres, time); the total only grows.
  **`TrackingLog`** is the coordinate-free decision timeline (`session.report()`).

## Configuration: every threshold and where to change it

Every threshold in `TrackingConfig` is an unmeasured starting value; say so when you change one. A restore replays with the
configuration stored in the journal header, not the one the app passes later.

### `TrackingConfig`

| Field | Default | Effect |
|---|---|---|
| `maxAccuracyMeters` | 40 | Worse (or unknown) fixes are `degraded`, no distance |
| `maxFixAge` | 30 s | A fix measured longer ago when it arrives is dropped |
| `silenceAfter` | 10 s | No fix for this long = GPS silent |
| `maxSpeedMps` | 10 | Speed ceiling; implied speed above it between good fixes is a jump |
| `minStepMeters` | 2 | Floor of the noise radius |
| `accuracyNoiseFactor` | 0.5 | Noise radius = `max(minStepMeters, factor × accuracy)` |
| `stationarySpeedMps` | 0.4 | Doppler below this = not moving |
| `maxBridge` | 2 min | Longest gap bridged by the straight line |
| `reanchorAfterRejects` | 3 | Consecutive jumps before a fresh anchor |
| `rejectMock` | true | Drop mock fixes |
| `pdrPolicy` | `confirmedByGps` | How steps may become distance |
| `pdrConfirmRatio` | 0.5 | Confirmation ratio of a step estimate |
| `smoothing` | `off` | `kalman`: quality gate, not validated |
| `stopRadiusMeters` / `stopMinDwell` | 15 m / 30 s | Stay-point detector |
| `updateInterval` / `minDistanceMeters` | 1 s / 0 | Native request (adaptive off) |
| `initialStrideMeters` | null | Starting stride; none = no estimate until GPS teaches one |
| `recoveryStepEvidenceAfter` | 30 s | Gap at least this long, sensor on, zero steps: not credited |
| `maxRecoverGap` | null | Process dead longer than this: session comes back paused at its last event |

`TrackingConfig.forSport(sport)` changes only the speed ceiling (running/walking 10, cycling 25, other 12) and `pdrPolicy`
(cycling = off).

### `createSession` flags

`adaptiveSampling` (false), `batterySaver` (false), `stepsEnabled` (false), `initialSteps` (0), `notificationTitle` /
`notificationText`, `presenter`, `presenterTimeout` (5 s).

### Hard-coded constants (no config field; changing one means editing the core AND the Dart twin plus a fixture)

Doppler trusted up to 1.5 m/s speed accuracy; 2 s future tolerance; 10 s minimum stop-observation gap; 120 s GPS-quality
timeline; 3 samples to call a stride calibrated; 1 s tick; stride parameters (20 steps / 20 m / 0.3–2.5 / 0.3 / 200); Kalman
parameters (2.0 / 13.82 / 30 s / 5); adaptive plans and the 20 s still time; 3 s wait for the exact step count at finish.

## Android

- **Permissions** (declared by the plugin manifest): `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION` (API 34+), `POST_NOTIFICATIONS` (API 33+), `ACTIVITY_RECOGNITION` (steps, Android 10+). No
  background location permission is requested.
- The SDK **always** runs its own foreground service (notification id 7421, channel `flutter_tracking_gps`, low importance),
  so survival never depends on a third party.
- After the OS kills the process the sticky `TrackingService` is recreated with a null intent and recovers the open session
  (`TrackingEngine.recoverActive`) without Flutter.
- Resume after a process death starts the foreground service BEFORE location, tick and steps start; if the OS refuses (for
  example a start from the background) the session stays paused and `resume` throws `FOREGROUND_START_FAILED`.
- Not measured: OEM battery managers (Samsung, Xiaomi), battery, background survival per device.

## iOS

- **Minimum iOS 14.** Without the `location` background mode `start` fails with `MISSING_BACKGROUND_MODE` instead of crashing;
  without `NSMotionUsageDescription` the steps report `MISSING_MOTION_USAGE_DESCRIPTION` instead of crashing on the prompt.
- `FlutterTrackingGpsRuntime.bootstrapAtLaunch()` brings back an open session when the system starts the app again, otherwise
  the app does it when opened (`attachOrRecover`).
- Apple does not promise a relaunch after the user force-quits. Relaunch after a system kill needs Always and significant-change
  monitoring and is **not implemented**.
- A recovered session whose location cannot be collected reports `collection: blocked`.

## Journal, restore and finish

One journal per session. Header: configuration, id, engine version, host `extras`. Then every input: `Pause`, `Resume`,
`Finish`, `Fix` (with arrival time), `Tick` (with calendar time), `StepCounter` / `SegmentSteps`, `StepsCorrection`,
`StepSensor`, `Pdr`, `Recovered`, `Finalized`, `SegmentSettled`. Journal format 3, engine version 2. A record change needs a
version bump and a reader for the old one.

- Written BEFORE it is applied, by ONE writer; `finish`, reading and clearing wait for the `flush` barrier. A CRC per line.
- **A pause is acknowledged only once durable** (both platforms): a process killed right after `await pause()` does not come
  back recording.
- **Restore REBUILDS what was shown; reprocess RECOMPUTES.** A journal written by other rules is replayed anyway but the
  snapshot says `engineChanged`. `SessionRecorder.reprocess(lines, settings?)` recomputes with the current rules and writes
  nothing; never use it to resume.
- **Damage:** a half-written last record is dropped and the file repaired; damage in the middle keeps only the intact part
  (`JOURNAL_CORRUPT`) AFTER copying the original to `<file>.corrupt`; an unusable journal throws. File names are a hash of the
  session id; `listSessions()` reads the real ids from the headers.
- **Durable or not is public:** `journalPending` counts events accepted but not yet durable (at most about a second behind);
  `isDurable` is true at zero. A process crash loses only what is still queued; a power loss can also lose the unsynced part.
- **A finished session restores as finished** (read only). The journal ends with `Finish` and `Finalized` (whether the step
  total was settled). A late second answer to the exact-count query never rewrites a published final result.
- `restoreState` is `complete`, `tailRepaired` or `partial` (only the part before a damaged record was restored; the rest was
  NOT applied and the original is kept).
- **Finish is a state:** `finishing` (live data closed, exact steps requested, journal flushed) then `finished`.

## When the app is killed

The time the process was dead is credited to the **clock**, never to the **distance**; the silence is `uncreditedGap`.

| Before | Comes back | Elapsed | Active clock | Distance |
|---|---|---|---|---|
| Recording | Recording, new segment | grows by the dead interval | grows by the dead interval | unchanged; the silence is `uncreditedGap` |
| Paused | Paused | grows | unchanged | unchanged |
| Finished | Finished (read only) | frozen | frozen | frozen |
| Recording, dead interval above `maxRecoverGap` | Paused at the last event | as a plain restore | as a plain restore | unchanged |

A calendar clock that went backwards counts as zero and sets `clockAnomaly`. A reboot restarts the monotonic clock, but the
`Recovered` record measures the dead interval against a PAIR of clocks describing the same instant, so a leg never goes
negative and two clocks are never joined.

Is distance added while the app is dead and the user moves?

| Case | Distance during that time |
|---|---|
| Android, swiped from recents, process alive thanks to the foreground service | **Yes**: native keeps receiving fixes and measuring |
| Android, the system killed the process and the sticky service was recreated | No for the gap between death and recovery (clock only); measured normally afterwards |
| iOS, user swiped the app away | Usually **no** (Apple does not relaunch); on reopen only the clock grows |
| iOS, the system relaunched the app | `bootstrapAtLaunch` recovers; measured normally from then on |

These rows follow the documented design and are **not verified on a device**; check with section 5 of `doc/DEVICE_TESTS.md`.
A running clock does not mean location is being collected: check `collection` before presenting a recovered session as
"measuring".

## Live activity and notification

The SDK records without any UI. To show a recording on the lock screen, in the Dynamic Island or as a notification, an app
plugs in **one object**, a `TrackingPresenter`; the SDK depends on no live-activity package. A presenter only draws: it never
decides whether the recording survives.

```dart
abstract class TrackingPresenter {
  Future<void> onStart(TrackingSnapshot s) async {}
  Future<void> onUpdate(TrackingSnapshot s) async {}   // ~1 Hz, coalesced
  Future<void> onPause(TrackingSnapshot s) async {}
  Future<void> onResume(TrackingSnapshot s) async {}
  Future<void> onFinish(TrackingSnapshot s) async {}
}
```

`PresenterDispatcher` bounds delivery: lifecycle events are delivered in order and never dropped (queue capped at 32, oldest
discarded and reported on overflow); `onUpdate` is coalesced (only the newest waits; a pending update is discarded once
`finish` is queued); every call has a time limit (`presenterTimeout`, 5 s) and a hung call is reported and skipped; a throwing
presenter or `onPresenterError` is contained; `dispose` never waits longer than a bound. A presenter lives in the Flutter
isolate and stops being called if the engine is destroyed while recording.

`session.updateNotification(title:, text:)` replaces the text of the SDK's Android foreground notification in place (no-op on
iOS and when no session records).

### Notification on Android

Without a presenter the SDK's foreground notification shows `notificationTitle` / `notificationText`. A presenter can replace its
text in place with `session.updateNotification(title:, text:)`. A host that builds its own richer notification must post it with the
SDK's notification id (7421) so it REPLACES the SDK's instead of adding a second one; the foreground service stays the SDK's, because
Android requires one notification for it and survival must not depend on a third party. If the user swipes the notification away
(Android 14+ allows it for foreground services) the recording is unaffected; the host should post it again.

### Live Activity on iOS

The SDK ships no Live Activity. The presenter drives one that the host owns in its widget extension. Let the system draw the running
timer (`Text(startDate, style: .timer)`) so it keeps counting while the app is not running, and push only the values that change. After
a restore, call the presenter's restore path so it reuses an activity that survived the kill or starts a new one.

## Snapshot fields

`state`, `quality`, `totalMeters` (only grows), `gpsMeters` / `gapBridgeMeters` / `pdrMeters` (by source),
`pendingPdrMeters` (not part of the total), `segmentId`, `fixCount`, `speedMps`, `lastFixAt`, `activeTime`, `movingTime`,
`elapsedTime`, `bridgedGap`, `uncreditedGap`, `uncreditedGaps`, `isStopped`, `steps`, `strideMeters`, `stepsStatus`,
`stepsFinal`, `ambiguousSteps`, `distanceEligibleSteps`, `journalPending`, `journalFailed`, `isDurable`, `restoreState`,
`engineChanged`, `recoveredGap`, `clockAnomaly`, `collection` (`collecting` / `notCollecting` / `blocked`), `failureState`,
`error`.

## Limits and tests

**Not done yet**

- A real-device pass (battery, background survival, OEM behaviour): none of it is measured and every threshold is a starting
  value.
- Automatic restart after the OS kills the app (iOS significant-change, Android service restart): the journal rebuilds the
  state, but something must start the app.
- Kalman smoothing is wired but off by default and not validated.
- The sampling plan's `highAccuracy` is not applied natively (Android always HIGH_ACCURACY, iOS always best), so
  `batterySaver` only changes interval and displacement.
- A step batch delivered long after its interval cannot be attached to a gap that was already credited.
- A watch producer is designed for (same events, same core) but not built.

**Tests recorded for beta.2** (from `CHANGELOG.md`; not re-run when this README was written): 197 core (JVM,
`./gradlew jvmTest`), 60 Android JVM, 48 iOS XCTest, 92 Dart. Fixtures in `test/fixtures` run through both the Kotlin core and
the Dart twin: `clean_run`, `stationary_drift`, `stationary_shaking`, `stand_then_run`, `long_stand_short_gap`,
`gps_lost_recovery`, `old_anchor_jump`, `short_weak_pending_cleared`, `urban_jump`, `cycling_tunnel`, `paused_resume`.

**Device protocol** (`doc/DEVICE_TESTS.md`, not yet run): open-sky accuracy on a known course; weak GPS on the same stretch
and shaking on the spot (distance must not appear, `uncreditedGap` must show the time); standing still 10 minutes; background
30 and 60 minutes; process death and restore, also after a reboot; finish right after a pause; a slow or never-returning
presenter; one hour of battery with and without adaptive sampling; durability under power cut.

## Repository layout

```
lib/src/domain      value types (fix, config, snapshot, ledger, states)
lib/src/engine      Dart reference twin of the distance rules (pure, tested)
lib/src/api         FlutterTrackingGps, TrackingSession, TrackingPresenter, PresenterDispatcher
lib/src/platform    TrackingPlatform + Pigeon bridge (*.g.dart generated)
pigeons/            the channel contract (edit here, then: dart run pigeon --input pigeons/tracking_api.dart)
tracking-core/      the shipped distance engine + algorithms (Kotlin Multiplatform)
android/            Kotlin: FusedLocationSource, SensorStepSource, TrackingEngine, TrackingService, ...
ios/Classes/        Swift: CoreLocationProvider, CoreMotionStepProvider, TrackingEngine, Runtime
doc/                FLOW, KMP, NATIVE_RULES, PRESENTER, JOURNAL, DEVICE_TESTS
test/               Dart tests + CSV fixtures shared with the core
example/            a small recorder screen
```

Every Swift and Kotlin file has an English `.md` next to it with the same base name (purpose, every class and function,
limits, who uses it). Native code follows `doc/NATIVE_RULES.md`: coroutines/Flow or GCD, no `!!`, weak self, results on main,
every error a `FlutterError` with code, message and details.
