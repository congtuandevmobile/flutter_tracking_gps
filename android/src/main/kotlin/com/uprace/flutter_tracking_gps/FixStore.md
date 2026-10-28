# FixStore.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/FixStore.kt` · **Layer:** Android native (persistence)

## Purpose
A durable, append-only record of the fixes of a session, so the distance can be rebuilt after the Flutter engine or the process died while native kept recording. The files hold coordinates: they live in app-private storage and are deleted by `clearSession`.

## `interface FixStore`
All methods block on I/O: call them from `Dispatchers.IO`.
| Method | Description |
|---|---|
| `append(fix)` | Appends one fix. Throws `IOException`. |
| `read(sessionId, afterSeq): List<NativeFix>` | Fixes with `seq > afterSeq`, oldest first. |
| `clearSession(sessionId)` | Deletes the session file. |

## `class FileFixStore(directory: File)`
- One file per session: `<safeSessionId>.jsonl`, one JSON object per line.
- The session id is sanitised (`[^A-Za-z0-9_-]` → `_`) so a caller-supplied id can never name a path.
- JSON keys: `s` sessionId, `g` segmentId, `q` seq, `t` timeMs, `e` elapsedMs, `la`/`lo` latitude/longitude, `ha` horizontal accuracy, `sp` speed, `sa` speed accuracy, `co` course, `al` altitude, `va` vertical accuracy, `pr` provider, `mk` isMock.
- `read` throws `IOException` on a corrupt line (for example one cut short by a kill mid-write), loudly instead of skipping it silently.

## Used by
`TrackingEngine.kt` (writes), `TrackingRuntime.kt` (creates the file store).
