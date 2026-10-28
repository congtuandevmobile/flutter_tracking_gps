# DistanceLedger.kt

**Path:** `tracking-core/src/commonMain/kotlin/tracker/engine/DistanceLedger.kt` · **Layer:** engine

## Purpose
Append-only record of every accepted stretch of distance. The total **is the sum of the entries**, so it can only grow and every metre has a source (`GPS`, `GAP_BRIDGE`, `PDR`).

## API
| Member | Description |
|---|---|
| `totalMeters: Double` | Accepted total (public getter, private setter). |
| `entries: List<LedgerEntry>` | A copy of the entries; the caller cannot modify the ledger through it. |
| `meters(source): Double` | Total for one source. |
| `add(segmentId, source, meters, atMs)` | Appends an entry. A negative or non-finite amount is a bug in the caller and throws `IllegalArgumentException`; zero is ignored; the total must stay finite. |
| `clear()` | Removes everything (new session). |

## How it works
Every valid `add` appends a `LedgerEntry`, adds to `totalMeters` and to the per-source total. There is no subtract operation, so the total never decreases while recording.

## Limits
Not thread-safe: use it from the same serial context as the engine. It does not persist itself: native stores the fixes and the engine rebuilds the ledger by replay.

## Used by
`engine/GpsDistanceEngine.kt`.
