# EngineTest.kt

**Path:** `tracking-core/src/commonTest/kotlin/tracker/EngineTest.kt` · **Layer:** tests (engine rules)

## Purpose
Unit tests of `GpsDistanceEngine`, one per rule. They are the Kotlin port of the Dart engine tests, so both engines are held to the same behaviour.

## Tests
| Test | Rule it protects |
|---|---|
| `firstFixAnchorsLaterFixesAdd` | First good fix anchors; later fixes add the distance between them, attributed to GPS. |
| `dopplerBelowTheLimitMeansNoDistance` | A Doppler speed under the limit adds nothing (STATIONARY). |
| `driftInsideTheNoiseRadiusCannotAccumulate` | Small drift is not counted and the anchor does not move. |
| `anImpossibleJumpIsRejectedAndTheAnchorKept` | A jump is rejected; the next fix is measured from the old anchor. |
| `repeatedJumpsReAnchorWithoutDistance` | After N jumps the engine re-anchors without adding distance. |
| `duplicateStaleMockAndWrongSegmentAreDropped` | The four admission filters. |
| `aPoorFixTellsTheQualityNeverTheDistance` | A low-accuracy fix sets DEGRADED and adds nothing. |
| `silenceMarksGpsUnavailable` | `onTick` detects GPS silence. |
| `gapIsCreditedOnceAsStraightLineMinusUncertainty` | Recovery credits the chord minus both accuracies, once. |
| `implausibleOrTooLongGapsAreNotBridged` | Teleport speeds and very long gaps add nothing. |
| `shakenPhoneWithoutGpsAddsNothing` | A pending step estimate is not distance and is dropped when GPS returns at the same place. |
| `realTunnelRunConfirmsTheEstimateAndIsNotDoubleCounted` | A confirmed estimate is credited once, as `PDR`. |
| `estimateIgnoredWhileGpsIsGoodOrPolicyOff` | Estimates are ignored when GPS is good or the policy is off. |
| `nothingIsBridgedAcrossAPause` | The first fix after a resume only anchors. |
| `finishFreezesTheResult` | Fixes after finish return NOT_RUNNING and change nothing. |
| `theTotalNeverDecreases` | Monotonic total on an erratic trace. |
| `movingTimeStopsWhileStanding` | `StopDetector` stops the moving-time clock. |

| `aLateEstimateOfAnotherSegmentIsRefused` | A step estimate tagged with another segment never becomes pending (also not an old segment's after a resume). |
| `invalidNumbersGetAVerdictAndNeverThrow` | NaN accuracy, a negative speed and an out-of-range latitude each get `INVALID`. |
| `aFixMeasuredInTheFutureIsRefused` | A fix measured after the arrival clock gets `FUTURE`. |
| `aWorseSpeedAccuracyMeansDopplerIsNotBelieved` | A Doppler speed with a poor accuracy does not mark the phone stationary. |
| `movingTimeDoesNotCountFixesTheDopplerCallsStill` | Standing with Doppler 0 adds no moving time. |

## Run
`cd tracking-core && ./gradlew jvmTest`
