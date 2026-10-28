# FixStore.swift

**Path:** `ios/Classes/FixStore.swift` · **Layer:** iOS native (persistence)

## Purpose
A durable, append-only record of the fixes of a session, so the distance can be rebuilt after the Flutter engine or the process died. The files hold coordinates: they live in the app's private storage and are removed by `clearSession`. Blocking I/O: never call it from the main thread.

## `protocol FixStoring`
A protocol so the engine can be tested with an in-memory store.
| Method | Description |
|---|---|
| `append(_ fix: NativeFix) throws` | Appends one fix. |
| `read(sessionId:afterSeq:) throws -> [NativeFix]` | Fixes with `seq > afterSeq`, oldest first. |
| `clearSession(_ sessionId:) throws` | Deletes the session file. |

## `FixStoreError`
`corruptLine(file:)` and `cannotCreateDirectory(path:)`.

## `final class FileFixStore: FixStoring`
- `init(directory: URL, fileManager: FileManager = .default)`.
- One file per session: `<safeSessionId>.jsonl`, one JSON object per line. The session id is sanitised (anything other than letters, digits, `-`, `_` becomes `_`) so a caller-supplied id can never name a path.
- JSON keys: `s` sessionId, `g` segmentId, `q` seq, `t` timeMs, `e` elapsedMs, `la`/`lo` latitude/longitude, `ha` horizontal accuracy, `sp` speed, `sa` speed accuracy, `co` course, `al` altitude, `va` vertical accuracy, `pr` provider, `mk` isMock. The keys match the Android store.
- `append` creates the directory when needed, writes the first line atomically, and later lines through a `FileHandle` (`seekToEnd`, `write`); on a write failure the handle is closed with `closeAfterFailure`, which logs a failing close instead of hiding it, and the original error is rethrown.
- `read` throws `corruptLine` on a malformed line (for example one cut short by a kill mid-write).

## Notes
Requires iOS 13.4+ APIs (`seekToEnd`, `write(contentsOf:)`); the pod targets iOS 14.

## Used by
`TrackingEngine.swift`.
