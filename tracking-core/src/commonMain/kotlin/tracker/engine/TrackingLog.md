# TrackingLog.kt

**Path:** `tracking-core/src/commonMain/kotlin/tracker/engine/TrackingLog.kt` · **Layer:** diagnostics

## Purpose
A bounded timeline of what the engine decided (anchor set, jump rejected, GPS lost or recovered, whether step estimates were confirmed...). **It contains no coordinates**, so it can be copied off a device and shared.

## API
| Member | Description |
|---|---|
| `TrackingLog(capacity = 600)` | Keeps at most `capacity` lines (must be positive); the oldest is dropped when full. |
| `size: Int` | Number of lines currently held. |
| `note(atMs, text)` | Appends a line with its time (epoch ms). |
| `clear()` | Clears and resets the dropped-lines counter. |
| `report(): String` | `TIMELINE: N entries (+M older dropped)` followed by one `time text` line per entry. |

## Rules
Never put coordinates or personal data in `text`. The engine only logs metres, durations and reasons.

## Used by
`engine/GpsDistanceEngine.kt`.
