# TrackingEngine.swift

**Path:** `ios/Classes/TrackingEngine.swift` · **Layer:** iOS native (session engine)

## Purpose
Collects raw fixes for one session at a time, persists them, and **feeds the shared Kotlin distance engine** (`GpsDistanceEngine` through `TrackingCoreBridge`, from the `TrackingCore` framework). It **outlives the Flutter engine on purpose**: if the engine detaches, native keeps recording and keeps computing the distance.

## Top-level function
`makeCoreBridge(_ e: EngineConfig, adaptiveSampling:batterySaver:)` maps the channel's `EngineConfig` onto the core.

## Threading
- All state, including the core (which is not thread-safe), lives on a serial `queue`; every public call hops there.
- Results, fixes and snapshots are delivered on the **main thread** (`deliver`, `fixObserver`, `snapshotObserver`).
- Disk writes run on a serial `storeQueue`, one fix at a time, in order.
- Closures use `[weak self]`.

## Construction
`TrackingEngine(provider: LocationProviding, store: FixStoring, coreFactory:, monotonicMs:, wallNow:)`; the last three have defaults and are injectable for tests. It becomes the provider's delegate.

## Properties
| Property | Description |
|---|---|
| `fixObserver` | Receives each fix on the main thread (set by the plugin while attached). |
| `snapshotObserver` | Receives the engine state on the main thread, on every fix and once per second. |
| private state | `core`, `tickTimer`, `sessionId`, `state`, `segmentId`, `seq`, `fixCount`, `lastFixTimeMs`, `lastError`, `minDistanceMeters`, `adaptive`, `permissionWaiters`. |

## Functions
| Function | Description |
|---|---|
| `permissionStatus()`, `requestPermission(completion:)` | Permission (queue-sync / asks and completes when authorization changes). |
| `snapshot()` | Current `NativeSnapshot`, including the core's distance block. |
| `report()` | The core's coordinate-free timeline. |
| `start(config:completion:)` | Resets the session, creates the core, starts updates and the 1 Hz ticker. Without permission the state is `permissionDenied`; a missing background mode is a `PigeonError` with code `MISSING_BACKGROUND_MODE`. |
| `pause`, `resume`, `stop` | State machine, each also driving the core; `resume` increments `segmentId`. |
| `submitPdr(sessionId:segmentId:meters:)` | Forwards a step estimate for a segment to the core (held as pending); one of another segment is refused. |
| `restore(config:completion:)` | After a process death: reads the persisted fixes (store queue), replays them into a fresh core on the engine queue (segments never joined) and leaves the session PAUSED. If the session already runs nothing is replayed (`RESTORE_FAILED` on a store error). |
| `readPersisted`, `clearSession` | Store access on the store queue. |

## Delegate callbacks
- `didUpdate`: for each location with `horizontalAccuracy >= 0` (a negative value means CoreLocation says the coordinate is invalid) it builds a `NativeFix`, numbers it, gives it to the core, persists it, delivers it, and, when adaptive sampling is on, asks the provider to change the displacement filter if the plan changed (iOS has no interval request).
- `didFailWith`: `kCLErrorLocationUnknown` is transient and only recorded; `denied` sets `permissionDenied`; anything else is logged and kept in `lastError`.
- `locationProviderDidChangeAuthorization`: completes waiting permission requests.

## Clocks
`monotonicNowMs()` is `CLOCK_MONOTONIC`, which also counts the time the device sleeps (unlike `systemUptime`), so a fix delivered after a sleep is not squeezed into a shorter interval and a wall-clock jump cannot reorder or age a fix. The core runs on it: a fix's time is the arrival time minus the fix age. The arrival clocks are read in the delegate callback, not later on the queue. `wallNow` is only used for the age and for `updatedAtMs`.

## Ticker
`startTicker` / `stopTicker`: a 1 Hz `DispatchSourceTimer` on the engine queue calls `core.onTick` so GPS silence is detected, then publishes a snapshot.

## Fix mapping (`makeFix`)
Unknown speed, course and accuracies become `-1`; altitude 0 when the vertical accuracy is invalid; `elapsedMs` = arrival monotonic time minus the fix age; `provider` is `"gps"`; `isMock` from `sourceInformation.isSimulatedBySoftware` (iOS 15+).

## Error handling
Every failure is a `PigeonError` with code, message, details; a failed write is logged and shown in `lastError` while recording continues.

## Tested by
`RunnerTests.swift` (fake provider and store, real Kotlin core).

## Steps (added)
`init(..., steps: StepProviding = CoreMotionStepProvider())`. `beginSteps()` (after the location updates start, on start and resume): `stepsEnabled` false → `.off`; otherwise `steps.status` (`.permissionDenied` / `.unavailable` only set the status), `.active` subscribes from the segment start and feeds `core.onSegmentSteps` on `queue`, guarded by `stepGeneration` and `state == .running`. `closeStepSegment()` (pause, stop): ends the subscription, asks the OS for the exact count up to the boundary, and applies it with `core.correctClosedSegment` only if the same segment is still the closed one and the session is not running. `stepsPermissionStatus()` / `requestStepsPermission()`. Snapshot: `stepsStatus`, `distance.steps`, `distance.strideMeters`.
