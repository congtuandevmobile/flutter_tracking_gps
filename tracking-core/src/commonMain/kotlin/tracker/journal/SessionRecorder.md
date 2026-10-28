# SessionRecorder.kt

**Path:** `.../tracker/journal/SessionRecorder.kt` · **Layer:** journal (engine + persistence in one place)

## Purpose
The engine and its journal together, so Android, iOS and a watch record and restore identically. Every input is written to the journal BEFORE it is applied (write-ahead); the engine is a pure function of those inputs, so **replaying the journal reproduces the live result exactly**.

## `JournalSink` (implemented by the host)
`append(line)` must not block or reorder; `flush()` returns only when everything appended is on disk (the barrier before a read, a clear or a final result).

## `SessionRecorder`
| Member | Description |
|---|---|
| `start(sink, settings, sessionId, atMs, wallAtMs, segmentId, initialSteps, extras)` (companion) | Writes the header and starts the engine. |
| `restore(sink, lines, nowMs): RestoreResult` (companion) | Replays every record with the header's settings. A session that was running comes back PAUSED (a `Pause` at `nowMs` is appended). `recorder` is null when nothing usable was found. |
| `fixes(lines)` (companion) | The raw fixes of a journal, oldest first (route export). |
| `pause`, `resume`, `finish`, `onFix`, `onTick`, `onStepCounter`, `onSegmentSteps`, `correctClosedSegment`, `setStepSensorActive`, `submitPdr` | Journal, then apply. `onTick` is journaled only when it flips the quality to unavailable (ticks are otherwise not inputs). |
| `snapshot()`, `report()`, `samplingPlan(nowMs)`, `lastPlan`, `flush()` | Reads and the barrier. |

Fixes carry their ARRIVAL time, and are replayed with the age and future checks ON, so a fix rejected live is rejected in the replay too. Adaptive sampling is recomputed after every fix in both paths, so the silence limit replays identically.

`RestoreResult` has `status` and `damagedLine` (see `JournalCodec`), `sessionId`, `segmentId`, `nextSeq`, `fixCount`, `lastFixWallMs` and the host `extras`.

Not thread-safe: one serial context. Tested by `JournalTest` (`JournalReplayTest`).

## Updated
`restore(sink, lines)` no longer takes "now": a session that was running is paused at the time of the LAST journaled event. `RestoreResult` adds `engineVersion` and `engineChanged`. New `reprocess(lines, settings?)` recomputes with the current rules (optionally other settings) and writes nothing.

## Finality (updated)
`markFinalized(stepsFinal)` journals a `Finalized` record after `finish` (and any `correctClosedSegment`). `RestoreResult` adds `finished` and `stepsFinal`: a journal that ends at `Finish` restores as a finished, read-only session with the same finality; one cut between `Finish` and `Finalized` has provisional steps.
