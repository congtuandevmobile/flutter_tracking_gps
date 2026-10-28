# TestFixes.kt

**Path:** `tracking-core/src/commonTest/kotlin/tracker/TestFixes.kt` · **Layer:** test helpers

## Purpose
Shared helpers that build `TrackingFix` values for the common tests, so each test reads as a story of positions and times instead of constructor noise.

## Members
| Member | Description |
|---|---|
| `BASE_MS` | A fixed epoch time (1 700 000 000 000 ms) all tests count from, so they are deterministic. |
| `ms(seconds)` | `BASE_MS` plus the given seconds, as epoch ms. |
| `fixAt(seq, seconds, north, segment = 0, accuracy = 5.0, speed = null, mock = false)` | A fix `north` metres north of the origin (latitude 10°, longitude 106°; 111 195 m per degree), `seconds` after `BASE_MS`. |

## Notes
All members are `internal` to the test source set. Metres are converted to latitude with a fixed factor, which is exact enough for tests.
