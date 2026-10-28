# TrackingEngineTest.kt

**Path:** `android/src/test/kotlin/com/uprace/flutter_tracking_gps/TrackingEngineTest.kt` · **Layer:** tests (Android native, JVM unit tests)

## Purpose
Tests the Android `TrackingEngine` and its glue to the shared Kotlin core without a device, using fakes. Run: `cd example/android && ./gradlew :flutter_tracking_gps:testDebugUnitTest`.

## Test doubles
| Type | Description |
|---|---|
| `FakeSource: LocationSource` | Records every location request `(interval, minDistance)`; tests push `RawLocation` values; a `failure` makes the flow throw (for example `SecurityException`). |
| `MemoryStore: FixStore` | In-memory store; `failOnAppend` simulates a full disk. |
| `loc(seconds, north, speed, accuracy)` | A `RawLocation` `north` metres north of the origin. |
| `engine(...)` | A `TrackingEngine` on an `UnconfinedTestDispatcher` scope with injected wall and monotonic clocks (`MONO` is the monotonic base, `BASE` the wall base). |

## Tests
| Test | What it checks |
|---|---|
| `fixesBecomeDistanceInTheSnapshot` | A 3 m/s run gives about 30 m, state running, quality good, 11 fixes. |
| `fixesArePersistedInOrderWithSequenceNumbers` | The writer stores fixes with sequence numbers 0, 1, 2. |
| `pauseKeepsTheTotalAndResumeOpensANewSegmentWithoutBridging` | Pause keeps the total; resume is segment 1; a far fix after resume only anchors. |
| `stopFreezesTheResult` | After stop the state is STOPPED and later fixes change nothing. |
| `aStepEstimateIsIgnoredWhileGpsIsStillGood` | An estimate submitted while GPS is good is not counted or held. |
| `restoreRebuildsTheDistanceFromPersistedFixesAndComesBackPaused` | Restore replays the fixes of two segments without joining them, comes back PAUSED, and a second restore replays nothing. |
| `aMissingPermissionBecomesAStateAndAnError` | A `SecurityException` becomes `PERMISSION_DENIED` and an error string with the stable code. |
| `aFailedWriteIsReportedButRecordingContinues` | A store failure shows as `STORE_WRITE_FAILED` while the state stays RUNNING. |
| `aWallClockJumpDoesNotAgeOrReorderFixes` | The wall clock jumps back two hours on every fix; the core runs on the monotonic clock, so the distance is unaffected. |
| `adaptiveSamplingReRequestsLocationsWhenTheUserStandsStill` | After a standstill the engine re-requests locations with a 3 s interval. |

## Notes
`android.util.Log` returns defaults in unit tests (`isReturnDefaultValues = true` in `build.gradle.kts`); the writer coroutine runs on the real `Dispatchers.IO`, so a few tests wait briefly for it.

Added: `stepsWhileGpsIsDegradedBecomeAPendingEstimateNotDistance`, `pauseUnregistersTheStepSensorAndResumeRegistersItAgain`, `aMissingPermissionOnlyDisablesStepsTheRecordingGoesOn`, `stepsOffByConfigNeverTouchTheSensor`, `walkedWhilePausedIsNotCountedAfterResume` (with a `FakeSteps` source).
