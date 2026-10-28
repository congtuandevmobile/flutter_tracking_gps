# TrackingEngine.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/TrackingEngine.kt` · **Layer:** Android native (session engine)

## Purpose
Collects raw fixes and steps for one session at a time and feeds the shared Kotlin core through a `SessionRecorder`, which writes every input to the journal BEFORE applying it. It outlives the UI: if the UI dies, the SDK keeps recording and measuring.

## Concurrency rules
- Commands (`start`, `pause`, `resume`, `stop`, `restore`) are serialized by a mutex and `cancelAndJoin` the jobs they stop.
- EVERY callback (location, tick, steps) carries the generation it was registered under and is dropped, under the core lock, when it is no longer current. The generation is bumped FIRST by `haltJobs`.
- Fixes are numbered inside the core lock; the monotonic clock is the engine's; the wall clock is display only.
- The journal has ONE writer; `stop`, `readPersisted` and `clearSession` wait for it first.

## Functions
| Function | Description |
|---|---|
| `start(config)` | Clears any old journal of the id, writes the header (settings + extras: notification text, intervals, steps) and starts. |
| `pause` / `resume` | Stop / start the jobs; resume opens a new segment. |
| `stop(id)` | Finishes, waits for the journal to be durable, returns a final snapshot (`stepsFinal`). |
| `restore(config)` | Replays the journal with the header's settings; a damaged tail is dropped and the file repaired (`JOURNAL_REPAIRED`); middle damage keeps the intact part (`JOURNAL_CORRUPT`); nothing usable throws. Comes back PAUSED. |
| `readPersisted(id, afterSeq)` | The persisted fixes with their original times, after the writer reached the disk. |
| `clearSession(id)` | Refused while the session is open; closes the writer first. |
| `listSessions()` | Real ids from the journal headers. |
| `sessionExtras()` | What was stored at start (the plugin uses the notification text on resume). |
| `submitPdr`, `report`, `markPermissionDenied`, `snapshot` | As before. |

## Steps
`beginSteps()` on start and resume: off by config; otherwise `StepSource.status()` (`PERMISSION_DENIED` / `UNAVAILABLE` only set the status). An active sensor is journaled (`setStepSensorActive`) and each reading is fed with the sensor's own time and the arrival time. A runtime failure sets `FAILED` + `STEPS_FAILED`. Adaptive re-requests only restart the collector and ticker.

## Snapshot
`lastAcceptedFixAtMs` is converted to calendar time here; adds `activeTimeMs`, `bridgedGapMs`, `uncreditedGapMs`, `uncreditedGaps`, `ambiguousSteps`, `stepsFinal`; a journal write failure shows as `STORE_WRITE_FAILED`.

Tested by `TrackingEngineTest` (fake location, step and journal sources).

## Updated
`stop` goes through `FINISHING` (journal being flushed) and only then `STOPPED`; nothing published meanwhile passes for final. The snapshot carries `journalPending`. Restore keeps `<file>.corrupt` before repairing damage in the middle, and reports `ENGINE_CHANGED` when the journal's engine version differs. A session that is `FINISHING` cannot be cleared.

A journal that ended at `Finish` restores as `STOPPED` (read-only, with the finality the journal says), never `PAUSED`. `stop` journals `Finalized(true)` (no exact-count query on Android). The snapshot carries the structured `restoreState` (`COMPLETE` / `TAIL_REPAIRED` / `PARTIAL`) and `engineChanged`, and `distanceEligibleSteps`.
