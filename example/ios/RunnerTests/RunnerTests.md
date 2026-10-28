# RunnerTests.swift

**Path:** `example/ios/RunnerTests/RunnerTests.swift` · **Layer:** tests (iOS, XCTest)

## Purpose
Unit tests of the iOS `TrackingEngine` using protocol fakes for the location provider and the store. The Kotlin core is the **real** one (the `TrackingCore` framework), so the glue and the core are tested together without GPS, permission dialogs or disk.

## Test doubles
| Type | Description |
|---|---|
| `TestClock` | One controllable time: `mono` is the engine's monotonic clock, `wall` the wall clock the fix ages are measured against. |
| `FakeProvider: LocationProviding` | Settable `permission` and `startError`; counts `starts` and `stops`; records `updateRequest` calls; `push(north:seconds:accuracy:speed:)` moves the test clock to that moment and sends a `CLLocation` (metres north of the origin) to the delegate: the fix is measured and arrives then. |
| `MemoryStore: FixStoring` | In-memory store. |

## Tests
| Test | What it checks |
|---|---|
| `testStartPauseResumeStop` | The state machine; a resume opens segment 1. |
| `testMissingBackgroundModeIsAnErrorWithCodeMessageDetails` | A missing background mode is a `PigeonError` with code `MISSING_BACKGROUND_MODE`, a message and details. |
| `testFixesAreNumberedPersistedAndDelivered` | Fixes get sequence numbers 0, 1, are delivered to `fixObserver` and persisted. |
| `testAnInvalidCoordinateIsNotForwarded` | A negative horizontal accuracy is dropped. |
| `testPermissionDeniedStartsNothing` | Without permission the state is `permissionDenied` and updates are not started. |
| `testFixesBecomeDistanceInTheSnapshotThroughTheKotlinCore` | A 3 m/s run gives about 30 m, quality good, 11 fixes. |
| `testPauseKeepsTheTotalAndResumeNeverBridges` | The total survives a pause and a far fix after resume only anchors. |
| `testRestoreRebuildsTheDistanceFromPersistedFixesAndComesBackPaused` | Replay of two segments is not joined; the session comes back paused. |
| `testAShakenPhoneWithoutGpsAddsNothingAndTheReportExplainsWhy` | A step estimate while GPS is good is ignored; the report contains the timeline. |
| `testAdaptiveSamplingChangesTheDisplacementFilterWhenStandingStill` | After a standstill the engine asks the provider for a different displacement filter. |

## Run
```
cd example/ios
xcodebuild test -workspace Runner.xcworkspace -scheme Runner \
  -destination 'platform=iOS Simulator,id=<simulator id>' CODE_SIGNING_ALLOWED=NO
```
