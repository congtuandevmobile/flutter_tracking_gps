# The session journal

One append-only text file per session. It is the single source of truth for a
restore, a route export and (later) syncing with a watch.

## Why a journal

The engine is deterministic: its result is a pure function of the inputs it was
given and of the time of each. If every input is kept, **replaying them reproduces
the live result exactly**: distance, pending estimates, learned stride, step
totals, gaps and times. Keeping only the fixes (as the first version did) lost
the step events, the accepted estimates and the configuration, so a restored
recording could differ from the live one.

## Format (version 2; version 1 journals are still read)

One record per line: `CRC32|TYPE|field|field|...`

- The CRC-32 (8 hex digits) covers everything after the first `|`. A flipped or
  half-written line fails it and is never guessed at.
- Text fields are escaped (`%`, `|`, `=`, CR, LF become `%XX`). Numbers use
  Kotlin's shortest round-trip text. A boolean is `1` or `0`.
- Times named `...AtMs` are on the engine's monotonic clock; `wallTimeMs` is the
  calendar time of a fix, kept for display and export.

| Type | Record |
|---|---|
| `H` | Header: format version, engine version, session id, start (monotonic and wall), first segment, initial steps, the 20 engine settings, and the host's extras (notification text, request interval...) |
| `P` / `R` / `F` | Pause, Resume (with the new segment), Finish |
| `X` | A raw fix and the time it ARRIVED (`nowMs`): a fix rejected live as stale is rejected in the replay too |
| `T` | A tick: EVERY tick is journaled (about one per second), because a tick can change state (GPS silence) and replay must see the same clock |
| `C` / `S` | A device step counter reading (Android) / a segment step count (iOS), each with its measured and delivered times |
| `K` | An exact step count for the segment just closed |
| `A` | Step sensor active / inactive |
| `D` | A step estimate from the app (`submitPdr`) |
| `Z` | Finalized: written once after `Finish`; whether the step total was settled (exact) or only the live count |
| `Y` | A segment settled: whether ITS step count became exact. The total is final only when every stepped segment settled exactly (one provisional segment is never upgraded by another) |

## Reading

`JournalCodec.read(lines)` returns the intact prefix and a status:

| Status | Meaning | What native does |
|---|---|---|
| `OK` | Every line intact | Restore |
| `TRUNCATED_TAIL` | Only the LAST line is damaged (a kill mid-write) | Drop it, rewrite the file, restore, report `JOURNAL_REPAIRED` |
| `CORRUPT` | A line before the last is damaged | Restore the intact prefix, rewrite the file, report `JOURNAL_CORRUPT` |
| `UNSUPPORTED_VERSION` | Newer format than this code | Refuse |
| `EMPTY` | Nothing | Refuse |

The first record must be a header. After a repair new records are appended after
an intact line, so the file stays readable.

## Writing

`SessionRecorder` (core) appends the line, THEN applies the input
(write-ahead). The host implements `JournalSink`: `append` must not block or
reorder, `flush` must return only when everything appended is on disk. Android
(`FileJournalSink`) and iOS (`FileJournalSink`) use one writer thread/queue per
file; `finish`, reads and clears call `flush` first, and `clear` closes and drains
the writer before deleting, so nothing re-creates the file.

## A watch

A watch is one more producer of the same events. A watch app embeds the same core
and writes the same records (its fixes and its own pedometer); the phone replays
the journal it receives. Merging two producers into ONE session (phone GPS plus
watch heart rate, say) needs a source id per record and a merge rule; that is not
built, but nothing in the format or the rules prevents it.

## Restore, reprocess and the engine version

The header stores the settings AND `engineVersion` (`JournalCodec.ENGINE_VERSION`, bumped
whenever a rule change can alter a result). **Restore** rebuilds what was shown, with the
header's settings; if the version differs the replay still runs but is flagged
(`ENGINE_CHANGED`). **Reprocess** (`SessionRecorder.reprocess`) recomputes with the current
rules and optionally other settings, writes nothing and may differ; it is for recomputing,
never for resuming.

## Clocks and reboots

Times inside one running leg use ONE monotonic clock, so durations are exact. The clock
restarts at a reboot, so a monotonic time from before a reboot must never be compared to one
after it. The format keeps that safe by construction: a restore always pauses (at the time of
the LAST journaled event) and `resume` opens a new segment and a new leg, and every engine
state that compares times (leg start, last fix, the GPS-quality timeline, the step interval
cursor) is reset per leg. A reboot therefore cannot join an old measurement interval to a new
one. Calendar time (`wallTimeMs`) is carried per fix for display and export.

## Durability

Three levels: queued (accepted, not written), written (in the OS, survives a process
crash) and durable (`fsync`ed, survives a power loss). `pending` (public as `journalPending`)
counts everything that is not durable yet; the writer syncs at most a second behind and at
every barrier. `flush` is the barrier; `finish` returns only after it. Damage in the
middle of the file is reported, never silently skipped, and the original is kept as
`<file>.corrupt` before the repair.

## Not built, on purpose

- **A checkpoint.** Replaying a ten-hour recording at 1 Hz (36 003 records) takes about 0.2 s
  on a laptop JVM (`ReplayPerformanceTest`); a checkpoint would add a second source of truth
  for no measured gain. Add one if a device measurement says replay is slow.
- **An explicit event sequence number and a clock-epoch id.** Line order is the order of
  application, and the per-leg reset above makes epochs unnecessary for one device. They become
  necessary the moment a second producer is merged (below).
- **A formal `reduce(state, event) -> state + effects` API.** `SessionRecorder` is that in
  practice: replay runs only the pure core, and effects (sensors, service, files, snapshots)
  belong to native and are not re-run.
- **Merging a watch.** Needs a source id per record, de-duplication and a rule that two
  devices never both contribute steps or distance for the same interval.

## Encoding and the validation boundary

Field text escapes `%`, `|`, `=`, `,`, newline and carriage return as `%XX` (so a comma or an
emoji in an extra, such as a notification text, round-trips). A pause, resume or finish the core
REFUSES (wrong state) is not journaled and never throws: the refusal is reported
(`lastFailure`). The engine is started before the header is written, so a journal never begins
with something that cannot be replayed. Replay stops at the first record that cannot be applied:
status `CORRUPT`, `damagedLine` set, nothing after it applied.

## Durability, truthfully

`flush()` returns true only when everything appended is on disk AND no write or sync ever failed.
A line whose write or sync failed stays pending for good (`journalPending`), and the failure is
sticky (`journalFailed`): a failure can never look like "everything is saved". `rewrite` replaces
the file atomically (temp file, sync, atomic swap), keeping `<name>.corrupt` when damage was in the
middle.


## Restore: repair and migrate before appending

A restore first asks the core for a plan (`SessionRecorder.plan`). The kept lines are the longest
prefix that is intact AND applicable; a record with a valid CRC that the engine cannot apply (for
example a resume that reuses a segment) makes the status `CORRUPT` (`PARTIAL` in the snapshot)
and is removed, with a `.corrupt` copy of the original, before anything is appended. A journal
with an older header version is migrated to the current version (atomic rewrite, backup kept)
before new records follow it; the header's `engineVersion` is left as recorded, so
`engineChanged` keeps saying that the old part came from another engine. A finished journal is
read-only and is not migrated.

