# TrackingEngine.swift

**Path:** `ios/Classes/TrackingEngine.swift` · **Layer:** iOS native (session engine)

## Purpose
Collects raw fixes and steps for one session at a time and feeds the shared Kotlin core through a `SessionRecorder`, which writes every input to the journal BEFORE applying it. It outlives the Flutter engine: if the engine detaches, native keeps recording and measuring.

## Threading and identity
- All state, including the core (not thread-safe), lives on `queue`; every public call hops there. Results, fixes and snapshots are delivered on the MAIN thread. The journal has ONE writer (`FileJournalSink`).
- A location measured before the current leg started is ignored (a callback queued before a pause never reaches the next segment).
- Step readings are checked against `stepGeneration`. The exact-steps answer is checked against the identity captured when the segment was closed (`sessionToken` and the segment): a late answer can never touch a newer session.

## Functions
| Function | Description |
|---|---|
| `start(config)` | Clears any old journal of the id, writes the header and starts. |
| `pause` / `resume` | Stop / start location and steps; resume opens a new segment. |
| `stop(id)` | Finishes, then answers ONLY after the exact step count was obtained (or failed or timed out within 3 s, reported with a stable code and `stepsFinal == false`) and the journal is durable. A finish from PAUSED also waits for the pause's close that may still be in flight. |
| `restore(config)` | Replays the journal with the header's settings; damaged tail dropped and the file repaired (`JOURNAL_REPAIRED`); middle damage keeps the intact part (`JOURNAL_CORRUPT`); nothing usable fails. Comes back PAUSED. |
| `readPersisted`, `clearSession`, `listSessions` | Off the engine queue; reading waits for the writer; clearing is refused while the session is open and closes the writer first. |
| `stepsPermissionStatus`, `requestStepsPermission`, `submitPdr`, `report`, `snapshot` | As before. |

## Steps
`beginSteps()` after location starts: off by config, otherwise `availability()` (a missing permission, sensor or Info.plist key only sets the status and a stable error: the recording goes on). Readings are fed with their measured time (the data's end date converted to the monotonic clock) and the arrival time. A runtime error (for example access revoked) is reported and stops the subscription.

Tested by `RunnerTests` with fake provider, steps and in-memory store.

## Updated
`stop` goes through `.finishing` (exact steps requested, journal being flushed) and only then `.stopped`; a second `stop` meanwhile returns the current snapshot. The snapshot carries `journalPending`. Restore keeps `<file>.corrupt` before repairing damage in the middle, and reports `ENGINE_CHANGED` when the journal's engine version differs. A session that is `.finishing` cannot be cleared.

A journal that ended at `Finish` restores as `.stopped` (read-only, with the finality the journal says), never `.paused`. `stop` settles the step total IN the journal (`markFinalized`) before the flush, so a restore reports the same finality; a second answer to the exact-count query (a late one after a timeout) is ignored (`settled`), so a published final result is never rewritten. The snapshot carries `restoreState`, `engineChanged` and `distanceEligibleSteps`.

## Recheck update

Each finish is a `FinishOperation` bound to the session token: a second `stop` joins it; a start/restore replaces it and the old stop fails with `SUPERSEDED`; the token is re-checked after the journal flush. Step closes are counted per session (an old answer never touches the new counts) and journaled per segment (`markSegmentSettled`); finality is `allStepsExact()`. Step callbacks use the injected clocks; a location error outside `running` is ignored; `requestPermission` waiters are released after 120 s with the current status. Snapshot carries `journalFailed`.
