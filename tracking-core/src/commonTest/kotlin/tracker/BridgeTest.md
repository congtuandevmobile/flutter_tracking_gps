# BridgeTest.kt

**Path:** `tracking-core/src/commonTest/kotlin/tracker/BridgeTest.kt` · **Layer:** tests (bridge)

## Purpose
Checks that `TrackingCoreBridge`, the door Swift and Kotlin-Android use, behaves like the engine behind it, using only primitives.

## Tests
| Test | What it checks |
|---|---|
| `aRunThroughTheBridgeMatchesTheEngine` | A 3 m/s run gives about 30 m, state running, quality good, 11 fixes. |
| `shakingWithoutGpsAddsNothing` | Silence is quality 3; a pending estimate shows in `pendingPdrMeters`; GPS returning at the same place leaves the total at 0. |
| `unknownSpeedIsNegative` | Unknown speed is reported as a negative number; `lastFixAtMs` is set after a fix. |
| `pauseAndResumeKeepTheTotalAndOpenANewSegment` | The total survives a pause; the first fix of the new segment only anchors. |
| `replayAcceptsOldFixes` | `replay = true` accepts fixes that arrive long after they were measured. |
| `adaptivePlanRaisesTheSilenceLimitWhenSamplingIsSparse` | After a standstill the plan is 3 s and `silenceAfterMs ≥ 12 s`; with adaptive sampling off the plan never changes. |

| `exactlyMinusOneIsUnknownButNaNIsInvalid` | `-1.0` is a LOW_ACCURACY fix (code 7), NaN is INVALID (code 14). |
| `theStableCodesDoNotDependOnEnumOrder` | The documented verdict codes for anchor, accepted, duplicate, wrong segment and wrong session. |
| `anEstimateOfAnotherSegmentIsRefusedThroughTheBridge` | `submitPdr` with another segment id is refused. |

## Run
`cd tracking-core && ./gradlew jvmTest`
