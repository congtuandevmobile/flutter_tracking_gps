## v0.0.1-beta

First beta. **Not tested on a physical device yet** (battery, background
survival and OEM behaviour are unmeasured) and every threshold is an unmeasured
starting value.

### Contracts hardened after independent review

* **Journal v2:** every tick is journaled; `,` and all separators are escaped; refused inputs are
  not journaled and never throw; replay stops at the first impossible record; segment step
  finality is per segment (`Y`).
* **Steps:** a step reading is validated against its measurement time before it touches any
  counter; a weak step batch attaches only to the OPEN weak interval; moving time is not
  credited while the verdict is stationary; a weak start is reported once as uncredited.
* **Durability:** `journalFailed` (sticky) next to `journalPending`; Android and iOS writers keep
  lost lines pending and rewrite files atomically; `TrackingSession.snapshot.isDurable` is false
  after any write failure.
* **Android:** the foreground service must acknowledge `startForeground` before recording starts
  (`FOREGROUND_START_FAILED` otherwise); the journal writer is a single-thread coroutine that is
  released after stop; a fix's calendar time is fixed when it is accepted.
* **iOS:** every async boundary re-checks an operation token: a second `stop` joins the first, a
  finish overtaken by a new session fails with `SUPERSEDED`, a late location error is ignored.
* **Dart:** snapshots are compared field by field (nothing can be forgotten); a closed presenter
  dispatcher never calls the presenter; a `TrackingSession` records once; `finish` fails unless
  native answered `stopped`.
* **Restore:** the core's `plan` decides the trusted prefix (CRC and replay); an impossible
  record is `PARTIAL` and removed before appending, and an old-format header is migrated
  atomically (backup kept) before new records follow it.
* **Foreground handshake:** overlapping requests all complete; only the newest may stop the service.
* **Release:** the workflow runs on macOS, gates analyze/tests/core tests/example build, builds
  and verifies the XCFramework, and `ios/.pubignore` ships it in the package.

### Engine (shared Kotlin core, `tracking-core`)

* A deterministic distance engine decides what counts as distance from raw fixes
  (position, Doppler speed, accuracy), not from a black-box odometer: it drops
  stale, duplicate, mock, invalid and other-session/segment fixes, treats
  inaccurate fixes as quality only, anchors on the first good fix, and rejects
  jumps (against the latest healthy fix as well as the accounting anchor),
  stationary readings and noise-radius drift. The total only grows.
* A GPS gap is judged from the last healthy fix and credited **once** (the larger
  of the straight line shrunk by its uncertainty and a step estimate the returning
  GPS corroborates), never as a sum. Nothing is bridged across a pause or a
  segment; a resume opens a new segment.
* **Time:** `activeTime` excludes pauses; `movingTime` and `uncreditedGap` are parts
  OF it (not added on top). `movingTime` includes credited gaps crossed at moving
  speed, and `uncreditedGap` / `uncreditedGaps` report the time whose distance is
  unknown, because nothing was credited for it (a weak start, a gap beyond `maxBridge`, a gap with no steps, a
  gap open at finish). The distance for that time is unknown, not zero, and is
  never hidden.
* **Standing still:** Doppler below the limit, then the displacement speed when no
  Doppler is usable, then a stay-radius gate once stopped (the anchor stays put so
  real movement is credited in full when it leaves the radius).
* **Steps.** `StepSession` (baseline, segments, counter reset) and `StrideEstimator`
  (metres per step learned from good GPS). A step batch is judged by the interval
  it was MEASURED in against a timeline of GPS quality: wholly good teaches the
  stride, wholly weak becomes a pending estimate the returning GPS must confirm, a
  batch straddling a quality change is `ambiguousSteps` (kept in the step total and the
  journal, but not used for the stride or a pending estimate). A long
  gap with a working sensor that delivered no step event is not credited and is
  reported as uncredited (absence of evidence, not proof of standing still). Opt-in
  `PdrPolicy.trustedWhenCalibrated` credits a calibrated estimate as ESTIMATED
  distance even for a long or curved gap.
* `steps` / `ambiguousSteps` / `distanceEligibleSteps` are three different numbers:
  reported, unusable, and used for distance.
* **Journal.** `SessionRecorder` writes every input (header with the configuration,
  lifecycle, fixes with their arrival time, step readings, the tick that detected
  silence) to a CRC-per-line journal BEFORE applying it. The engine is a pure
  function of it, so replaying reproduces the live result exactly. The header also
  stores the engine version: a journal of other rules is replayed but flagged
  (`ENGINE_CHANGED`), and `reprocess` recomputes with the current rules without
  writing. A finished session restores as finished (with the same step finality), a running
  one is paused at its LAST journaled event, so the time the
  process was dead is never workout time and a reboot cannot make a leg negative.
  See `doc/JOURNAL.md`.
* Runs on a monotonic clock (Android `elapsedRealtime`, iOS `CLOCK_MONOTONIC`).
* Algorithms: Kalman2D (quality gate only), LowPassFilter, OutlierRejector,
  DouglasPeucker, StopDetector, AdaptiveSamplingPolicy, GapReconciler, plus offline
  Dbscan, KMeans, RoadGraph and HmmMapMatcher. The Dart engine is a reference twin
  of the distance rules; 11 shared CSV fixtures run on both.

### Native

* **Android:** Fused location in a foreground service (type `location`) that the SDK
  ALWAYS runs, with a plain notification whose title/text come from Flutter and
  whose tap returns to the app (a presenter can replace its content by posting with
  the same notification id). `TYPE_STEP_COUNTER` (needs `ACTIVITY_RECOGNITION`;
  recording works without it). Every callback carries a generation and a late one
  is dropped; commands wait for the jobs they stop. `resume` after a process death
  starts the service BEFORE recording continues and fails with
  `FOREGROUND_START_FAILED` if the OS refuses.
* **iOS:** CoreLocation with the `location` background mode; `CMPedometer` for steps
  (needs `NSMotionUsageDescription`; a missing key, a denied permission, a runtime
  error and a query timeout are reported with stable codes, never as "0 steps").
  `finish` waits for the exact step count, identity-checked by (session, segment).
* **Both:** one journal per session, ONE writer with a flush barrier and `fsync`
  (`journalPending` counts what is not durable), collision-safe
  file names (a hash of the session id), atomic repair of a half-written last
  record (damage in the MIDDLE keeps the original as `<file>.corrupt` first); restore replays the journal with the configuration stored in its header
  and comes back paused; `listSessions`; clearing waits for the writer and is
  refused while the session is open. Errors carry a stable code, a message and
  details; none is swallowed.

### Flutter API

* `FlutterTrackingGps`: location and steps permissions, `listSessions()`,
  `createSession(...)`.
* `TrackingSession`: `start`, `pause`, `resume` (new segment), `finish` (final:
  journal durable, steps finalized, see `stepsFinal`), `restore`, `persistedFixes`,
  `fixes`, `clearPersisted`, `report`, `updates`.
* `TrackingSnapshot`: totals by source, `activeTime`, `movingTime`, `bridgedGap`,
  `uncreditedGap`, `ambiguousSteps`, `steps`, `strideMeters`, `stepsStatus`,
  `stepsFinal`, `failureState` / `error` / `hasNativeFailure` (a native failure no
  longer hides behind the last commanded state). `lastFixAt` is calendar time.
* `createSession(stepsEnabled: true)` opts into steps; `initialStrideMeters` and
  `initialSteps` seed a returning user. `TrackingConfig.forSport(cycling)` turns
  pedestrian estimates off.
* `TrackingPresenter` hook for a third-party live activity or notification
  (`createSession(presenter: ...)`, `doc/PRESENTER.md`). Delivery is bounded:
  lifecycle events in order, `onUpdate` coalesced, a time limit per call,
  contained errors, a `dispose` that cannot hang, and a hung call is NEVER
  overlapped by another (a Dart future cannot be cancelled, so later events wait
  until it ends).
* `RestoreState` (`complete` / `tailRepaired` / `partial`) and `engineChanged` say what
  a restore found; `partial` means everything after a damaged record was NOT applied.
* `session.updateNotification(...)` (Android): a presenter changes the text of the
  SDK's own notification through the SDK instead of posting with its id.
* `RecordingState.finishing` (finalizing, not final) before `finished`;
  `journalPending` / `isDurable` say whether what is shown is on disk.

### Setup

* iOS (minimum 14): `NSLocationWhenInUseUsageDescription`,
  `UIBackgroundModes: [location]`, `NSMotionUsageDescription` for steps. Build the
  shared core framework with `tool/build_core.sh`.
* Android: nothing to add; the plugin manifest declares the permissions
  (including `ACTIVITY_RECOGNITION`) and the service.

### Known limits

See `doc/FLOW.md` ("Not done yet"): no device pass, automatic restart after the OS
kills the app, sampling accuracy not applied natively, a late step batch cannot
join a gap already credited, a watch producer is designed for but not built.

### Tests

177 shared-core (incl. exact journal replay, finished-session restore, reboot, engine
version, reprocess, drift limits, step accounting, the encoding/tick/finality contracts; a
ten-hour replay measured at ~0.2 s), 52 Android (31 engine + 2 restore plan + 3 audit probes, 9 file store,
7 foreground handshake), 41 iOS engine and 87 Dart tests pass; 11 CSV fixtures
agree on both engines. No device test: see `doc/DEVICE_TESTS.md`.
