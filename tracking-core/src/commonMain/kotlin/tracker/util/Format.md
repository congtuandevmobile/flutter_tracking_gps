# Format.kt

**Path:** `tracking-core/src/commonMain/kotlin/tracker/util/Format.kt` · **Layer:** utility (Kotlin Multiplatform)

## Purpose
Formats a `Double` with one decimal place for the log lines of `TrackingLog`. Common Kotlin has no `String.format`, so a small platform-independent helper is needed to run on both Android and iOS.

## API
| Member | Description |
|---|---|
| `internal fun Double.f1(): String` | Rounds to 0.1 and converts to a string: `12.345.f1()` → `"12.3"`. Uses `roundToLong`, no platform API. |

## Notes
- `internal`: only visible inside the `tracking-core` module, not public API.
- No grouping separators and no locale handling.

## Used by
`engine/GpsDistanceEngine.kt` (log lines).
