# FixtureParityTest.kt

**Path:** `tracking-core/src/jvmTest/kotlin/tracker/FixtureParityTest.kt` · **Layer:** tests (JVM only, parity with Dart)

## Purpose
Runs every CSV file in `test/fixtures` through the Kotlin `GpsDistanceEngine` and checks the final total. The Dart test `test/fixtures_test.dart` runs the **same files** through the Dart engine, so the two implementations cannot drift apart.

## Fixture format (one event per line, `#` starts a comment)
| Line | Meaning |
|---|---|
| `fix,seq,segment,tSec,northMeters,accuracy,speed` | A fix (`speed` may be empty = unknown). |
| `tick,tSec` | One-second tick (GPS silence detection). |
| `pdr,tSec,meters` | A step estimate submitted by the app. |
| `pause,tSec` / `resume,tSec,segment` / `finish,tSec` | Lifecycle. |
| `expect,totalMeters,toleranceMeters` | The expected final total. |

## How it works
For each file the engine is started, each line is applied (the fix arrives at its own time), and the final `totalMeters` must be within the tolerance of `expect`. A failure prints the file name and the engine log.

## Fixtures
`clean_run`, `stationary_shaking`, `gps_lost_recovery`, `paused_resume`, `urban_jump`, `stationary_drift`, `cycling_tunnel`.

## Notes
JVM source set only, because it reads files (`../test/fixtures`). Run: `cd tracking-core && ./gradlew jvmTest`.
