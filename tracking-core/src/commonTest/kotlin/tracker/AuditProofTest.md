# AuditProofTest.kt

**Path:** `tracking-core/src/commonTest/kotlin/tracker/AuditProofTest.kt` · **Layer:** tests (regressions found by an audit)

## Purpose
Each test was written to **fail on the code as it was** and pass after the fix, so the bug cannot come back.

## Tests
| Test | Bug it pins down |
|---|---|
| `standingStillThenMovingIsNotAGpsGap` | The engine decided "GPS gap" from the age of the accounting anchor, which stays put while standing. After 10 s of standing, real movement was treated as a recovery and its first metres were lost. It must be an ordinary accepted fix. |
| `aFixOfAnotherSessionIsRefused` | The engine only checked the segment, so a fix of another session with the same segment id was accepted. It must be `WRONG_SESSION`. |
| `nonFiniteValuesAreInvalidNotUnknown` | NaN accuracy or latitude must be `INVALID`, not treated as "unknown". |
| `distanceAcrossTheAntimeridianIsShort` | The tangent-plane projection did not wrap longitude, so a point 100 m across ±180° projected about 40 000 km away. |

## Run
`cd tracking-core && ./gradlew jvmTest`
