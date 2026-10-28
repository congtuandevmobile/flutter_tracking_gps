# AlgorithmsTest.kt

**Path:** `tracking-core/src/commonTest/kotlin/tracker/AlgorithmsTest.kt` · **Layer:** tests (algorithms)

## Purpose
Unit tests of the measurement-pipeline algorithms: low-pass, Kalman, Douglas-Peucker, stop detection and adaptive sampling.

## Tests
| Test | What it checks |
|---|---|
| `lowPassSmoothsAndRejectsBadInput` | EMA values; NaN and an out-of-range `alpha` throw. |
| `kalmanTracksAConstantVelocityBetterThanRawNoise` | On a 3 m/s track with ±4 m noise, the filtered path is shorter than the raw one and close to the true 180 m. |
| `kalmanGatesOutliersThenReLocalizes` | An outlier is gated out; after the configured number of rejections the filter reports a discontinuity. |
| `kalmanIsAGateOnlyAndNeverSuppliesTheCoordinates` | With the Kalman gate on, the distance of a clean run equals the raw distance. |
| `kalmanReportsAGapResetAsADiscontinuityAndValidatesInput` | A silence reset is a discontinuity (`GAP_RESET`); out-of-order and invalid input are handled. |
| `geoMathWrapsTheAntimeridianAndRefusesThePoles` | Projection and its inverse across ±180°; poles and NaN refused. |
| `outlierRejectorTreatsInvalidMeasurementsAsJumps` | NaN and zero seconds are jumps. |
| `gapReconcilerNeverAcceptsAHugeEstimateOnATinyChord` | A 200 m estimate on a 2 m lower chord is capped; invalid input credits 0. |
| `stopDetectorNeedsBothDwellAndNoMotion` | Inside the radius with real speed is not a stop. |
| `adaptiveSamplingTreatsAnInvalidSpeedAndABackwardsClockAsMoving` | NaN speed and a clock that went back reset the timer. |
| `invalidConfigurationIsRefused` | Config, log capacity and ledger validation; cycling turns the step estimate off. |
| `douglasPeuckerDropsCollinearPointsAndKeepsCorners` | A straight line collapses to 2 points; corners and a zero tolerance keep everything; a negative tolerance throws. |
| `stopDetectorNeedsAContinuousStayAndEndsOnLeaving` | A 30 s stay is a stop; moving away ends it. |
| `stopDetectorIgnoresAPassBySamePlaceTwice` | Returning to a place ten minutes later is not a stop. |
| `adaptiveSamplingNeverMakesSparseFixesLookLikeAnOutage` | Hysteresis, sparse plan after standing, and `silenceAfterMs ≥ 4 × interval`. |

## Run
`cd tracking-core && ./gradlew jvmTest`
