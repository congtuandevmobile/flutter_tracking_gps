# JournalStore.swift

**Path:** `ios/Classes/JournalStore.swift` · **Layer:** iOS native (persistence; replaces `FixStore`)

Same contract as the Android `JournalStore.kt` (see `doc/JOURNAL.md`): `JournalStoring` (`open`, `read`, `rewrite`, `clear`, `list`), `DurableSink` (a core `JournalSink` plus `failure` and `close()`), `FileJournalStore` (one file per session named from a SHA-256 hash of the id; `read` waits for the writer; `rewrite` atomic; `clear` closes and drains the writer before deleting) and `FileJournalSink` (ONE serial queue writes in order; `append` only queues; `flush()` is the barrier with `synchronize()`; `close()` drains; a write failure is logged and kept as `failure`, never stopping the recording).

Blocking I/O: call off the main thread. Tested through a memory implementation in `RunnerTests`.

## Updated
`DurableSink.pending` counts lines queued but not on disk (public as `journalPending`). `rewrite(_:lines:keepBackup:)` copies the original to `<name>.corrupt` first when the damage was in the MIDDLE of the file.

`pending` now counts lines that are not DURABLE: queued, or written but not synchronized. The writer syncs a second after a write, and at every barrier.

## Recheck update

Same contract as Android: `flush()` returns Bool (true only when durable and nothing failed), lost lines stay pending, `failure` is sticky, `rewrite` swaps atomically (`replaceItemAt`, temp file and directory synced), `release(id)` closes an idle writer.
