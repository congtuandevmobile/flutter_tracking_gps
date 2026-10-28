# JournalStore.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/JournalStore.kt` · **Layer:** Android native (persistence; replaces `FixStore`)

## Purpose
Where the session journals live (`doc/JOURNAL.md`). One file per session in app-private storage, named from a SHA-256 hash of the session id (two ids never collide; the real id is in the header). The files hold coordinates and are deleted by `clear`.

## Types
| Type | Description |
|---|---|
| `DurableSink` | A core `JournalSink` plus `failure` (the first write failure) and `close()`. |
| `JournalStore` | `open(id)` (ONE shared writer per session), `read(id)` (waits for the writer), `rewrite(id, lines)` (atomic replace, drops a damaged tail), `clear(id)` (closes and drains the writer, then deletes), `list()` (real ids from the headers). |
| `FileJournalStore` | The file implementation. |
| `FileJournalSink` | The single writer: `append` only queues (never blocks), one thread writes in order and flushes each line, `flush()` is the barrier (queue marker + `fsync`, 10 s limit), `close()` drains. A write failure is logged, kept as `failure` and never stops the recording. An append after `close` is reported, never silently lost. |

Blocking I/O: call from `Dispatchers.IO`. Tested by `FileJournalStoreTest` and, through a memory implementation, `TrackingEngineTest`.

## Updated
`DurableSink.pending` counts lines queued but not on disk (public as `journalPending`). `rewrite(id, lines, keepBackup)` copies the original to `<name>.corrupt` first when the damage was in the MIDDLE of the file.
