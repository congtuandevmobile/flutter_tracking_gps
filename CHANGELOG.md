## 0.1.0

* **Steps in the engine (B + C).** Native sensors (Android `TYPE_STEP_COUNTER`, iOS
  `CMPedometer`, ported from `uprace_steps_counter`) feed the shared core. The core
  owns the accounting (`StepSession`) and the metres (`StrideEstimator`): the stride
  is learned from good GPS and, while GPS is weak, steps become a **pending**
  estimate the returning GPS must confirm (same `GapReconciler`, one source per
  gap). Opt in with `createSession(stepsEnabled: true)`; `initialStrideMeters`,
  `initialSteps`, `snapshot.steps`, `strideMeters`, `stepsStatus`,
  `requestStepsPermission()`. Dart reference twin and tests added.
* **`TrackingPresenter`** hook for a third-party live activity / notification
  (`createSession(presenter: ...)`, `docs/PRESENTER.md`). Android now has a default
  foreground notification (distance + chronometer, updated by native); a presenter
  that owns a foreground service can replace it (`ownsAndroidForegroundService`).
* Pigeon: `StartConfig.stepsEnabled/initialSteps/foregroundMode`,
  `EngineConfig.initialStrideMeters`, `NativeDistance.steps/strideMeters`,
  `NativeSnapshot.stepsStatus`, `stepsPermissionStatus`, `requestStepsPermission`.
  `TrackingCoreBridge` takes `initialStrideMeters` and has `onStepCounter`,
  `onSegmentSteps`, `correctClosedSegment`. Rebuild the iOS framework with
  `tool/build_core.sh`.
* iOS: `NSMotionUsageDescription` needed for steps; the pod links CoreMotion.
  Android: manifest adds `ACTIVITY_RECOGNITION`.
* Tests: 83 core, 15 Android engine, 15 iOS engine, 55 Dart. Not tested on a device.

## 0.0.3

Audit fixes (each pinned by a test that failed before):

* **A GPS gap is decided from the last healthy fix, not from the accounting
  anchor.** After 10 s of standing the next movement was treated as a recovery
  and its first metres were lost.
* The engine refuses a fix of another **session** (`WRONG_SESSION`), and invalid
  numbers get `INVALID` instead of being read as "unknown".
* A step estimate carries its **segment** (`submitPdr(segmentId, ...)`, also in
  the Pigeon API); a late estimate of an old segment cannot leak into the next.
* The core runs on a **monotonic clock** (Android `elapsedRealtime`, iOS
  `CLOCK_MONOTONIC`); fixes carry `elapsedMs`. A wall-clock jump no longer ages
  or reorders fixes.
* The Kalman filter is a **quality gate only**: the distance is measured on raw
  accepted fixes, and a silence reset is reported as a discontinuity.
* `GeoMath` wraps longitude at +-180 and refuses the poles; all algorithms
  validate their input; `GapReconciler` caps an estimate by the straight line.
* The bridge uses written-out stable codes (no `enum.ordinal`); `-1.0` is
  unknown, NaN is invalid; `speedAccuracy` is passed through (Doppler is only
  trusted with an accuracy of at most 1.5 m/s, an unmeasured value).
* `StopDetector` needs both a stay inside the radius and no motion; moving time
  no longer counts fixes the Doppler calls still.
* Native: a failure of the core on OS data is reported as `CORE_REJECTED`
  instead of crashing the collector.
* Tests: 59 core, 10 Android engine, 10 iOS engine, 37 Dart.

## 0.0.2

* The distance engine now runs **natively** through the shared Kotlin core
  (`TrackingCoreBridge`): Android and iOS native engines feed it every fix and a
  1 Hz tick and publish snapshots (distance, GPS quality, moving time, stopped)
  over a new `streamSnapshots` channel. Distance keeps being measured when the
  Flutter engine is gone.
* New host API: `submitPdr`, `restoreSession` (replays persisted fixes, session
  comes back paused), `getReport`. `StartConfig` carries an `EngineConfig`,
  `adaptiveSampling` and `batterySaver`.
* Adaptive sampling (Android: interval and displacement; iOS: displacement
  filter); the silence limit follows the plan.
* Dart: `TrackingSession` is now thin (commands + snapshot mapping);
  `session.report()` is async; the Dart engine stays as a reference twin.
* `tracking-core`: Kalman2D, LowPassFilter, OutlierRejector, DouglasPeucker,
  StopDetector, AdaptiveSamplingPolicy, GapReconciler, plus offline auxiliary
  algorithms (Dbscan, KMeans, RoadGraph with Dijkstra and A*, HmmMapMatcher).
* Tests: 40 core, 9 Android engine, 10 iOS engine, 36 Dart.

## 0.0.1

* First version: native fix collection with persistence (iOS CoreLocation,
  Android Fused location in a foreground service), Pigeon contract, and a
  deterministic Dart distance engine (anchor, Doppler and noise gates, jump
  rejection, gap bridging, pending step estimates confirmed by returning GPS,
  segments never bridged, append-only ledger).
* Not measured on devices; every threshold is a starting value.
