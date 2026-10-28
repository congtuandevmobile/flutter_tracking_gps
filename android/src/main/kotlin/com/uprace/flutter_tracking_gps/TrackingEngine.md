# TrackingEngine.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/TrackingEngine.kt` · **Layer:** Android native (session engine)

## Purpose
Collects raw fixes for one session at a time, persists them, and **feeds the shared Kotlin distance engine** (`GpsDistanceEngine` through `TrackingCoreBridge`). It **outlives the Flutter engine on purpose**: if the UI engine dies, native keeps recording and keeps computing the distance.

## Top-level function
`createBridge(engine: EngineConfig, adaptiveSampling, batterySaver): TrackingCoreBridge` maps the channel's `EngineConfig` onto the core.

## Constructor
`TrackingEngine(source: LocationSource, store: FixStore, scope: CoroutineScope, wallClockMs = System::currentTimeMillis, monotonicMs = SystemClock::elapsedRealtime, coreFactory = ::createBridge)`. Every dependency is injectable, so `TrackingEngineTest` runs it with fakes.

## Properties
| Property | Description |
|---|---|
| `fixes: SharedFlow<NativeFix>` | Every fix in order (extra buffer 512). |
| `snapshots: SharedFlow<NativeSnapshot>` | The engine state, on every fix and once per second (replay 1, drop-oldest: a snapshot is a state, the newest wins). |
| state fields | `sessionId`, `state`, `segmentId`, `fixCount`, `lastFixTimeMs`, `lastError` (volatile), plus the private `seq`, `core`, `request`, `adaptive`. |

## Functions
| Function | Description |
|---|---|
| `snapshot()` | Current state, counts, last error and the core's distance block. |
| `suspend start(config)` | Cancels any running collection, resets the session, creates the core, requests location updates, starts the 1 Hz ticker. |
| `suspend pause(id)` / `resume(id)` / `stop(id)` | State machine, each also driving the core (`pause`, `resume` with `segmentId + 1`, `finish`). |
| `submitPdr(id, segment, meters)` | Forwards a step estimate for a segment to the core (held as pending); one of another segment is refused. |
| `report()` | The core's coordinate-free timeline. |
| `suspend restore(config)` | After a process death: reads the persisted fixes, replays them into a fresh core (segments never joined) and leaves the session PAUSED. If the session already runs, nothing is replayed. |
| `suspend readPersisted(id, afterSeq)`, `suspend clearSession(id)` | Store access on `Dispatchers.IO`. |
| `markPermissionDenied()` | Sets state `PERMISSION_DENIED`. |

## How it works
- **The core runs on a monotonic clock:** a fix's time is `elapsedMs` (from `elapsedRealtimeNanos`) and "now" is `elapsedRealtime`, so a wall-clock jump can neither reorder nor age a fix. The wall clock is only used for `updatedAtMs`.
- Commands are serialized by a `Mutex`.
- One collector coroutine numbers fixes in arrival order; each fix goes to the core (under `coreLock`, because the core is not thread-safe and the ticker also uses it), then to the write channel and the `fixes` flow, then a snapshot is published.
- A 1 Hz ticker coroutine calls `core.onTick` so GPS silence is detected, and publishes a snapshot.
- Adaptive sampling: after each fix the bridge's plan is compared with the current request; when it changed, a new coroutine (under the command mutex) cancels the collector and starts it again with the new interval and displacement. The core's silence limit follows the plan.
- A writer coroutine on `Dispatchers.IO` appends fixes one at a time, in order.
- `RawLocation` maps to `NativeFix`; the engine itself uses no Android location type.

## Core calls
`withCore` is for data coming from the OS (fix, tick, estimate): if the core refuses an input (`IllegalArgumentException` / `IllegalStateException`) the failure is logged and shown in `lastError` as `CORE_REJECTED`, and the collector is never crashed. `coreStrict` is for commands (pause, resume, stop): a failure propagates to the caller and becomes a `FlutterError`.

## Error handling
Never swallowed: a location error is logged and put in `lastError` (state `PERMISSION_DENIED` for `SecurityException`, otherwise `ERROR`); a failed write is logged and shown in `lastError` while recording continues. Cancellation is rethrown.

## Rules followed
Coroutines and Flow only, no `!!`, heavy work on `Dispatchers.IO`.

## Used by / tested by
`TrackingRuntime.kt` creates it, `FlutterTrackingGpsPlugin.kt` drives it; `TrackingEngineTest.kt` tests it.

## Steps (added)
Constructor `stepSource: StepSource = NoStepSource`. `start`/`restore` pass `initialSteps` and `engine.initialStrideMeters`. `beginSteps()` runs on start and resume: with `stepsEnabled` false the status is `OFF`; otherwise `stepSource.status()` decides (`PERMISSION_DENIED`/`UNAVAILABLE` only set `stepsStatus`, the recording goes on); `ACTIVE` collects `counts()` in `stepJob` and feeds `bridge.onStepCounter(segment, now, raw)` with the segment captured at registration. `stopJobs()` (pause, stop) cancels `stepJob`, which unregisters the sensor; adaptive re-requests of the location only restart the collector and ticker, never the step sensor. Snapshot: `stepsStatus`, `distance.steps`, `distance.strideMeters`. New error code `STEPS_FAILED`.
