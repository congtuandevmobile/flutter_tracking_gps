# Models.kt

**Path:** `tracking-core/src/commonMain/kotlin/tracker/model/Models.kt` · **Layer:** data model (pure, platform independent)

## Purpose
Every shared type of the core: recording state, GPS quality, distance source, one raw GPS fix, configuration, the result of processing a fix, and the snapshot shown to the UI.

## Enums
| Enum | Values | Meaning |
|---|---|---|
| `RecordingState` | `IDLE, RUNNING, PAUSED, FINISHED` | The recording lifecycle. Not the same as GPS quality or whether the person is moving. |
| `GpsQuality` | `UNKNOWN, GOOD, DEGRADED, UNAVAILABLE, RECOVERING` | `GOOD`: recent good fixes. `DEGRADED`: fixes arrive but are inaccurate. `UNAVAILABLE`: silent for too long. `RECOVERING`: first good fix after a gap, the gap was just reconciled. |
| `DistanceSource` | `GPS, GAP_BRIDGE, PDR` | Where a metre came from: two consecutive fixes, the straight line across a gap, or a step estimate confirmed by GPS. |
| `FixVerdict` | `ANCHOR, ACCEPTED, RECOVERED, NOISE, STATIONARY, JUMP, OUTLIER, LOW_ACCURACY, STALE, DUPLICATE, WRONG_SEGMENT, MOCK, NOT_RUNNING, WRONG_SESSION, INVALID, FUTURE, REANCHORED` | What the engine did with a fix. `WRONG_SESSION`: another session. `INVALID`: a non-finite, out-of-range or negative value. `FUTURE`: measured after the arrival clock allows. `REANCHORED`: repeated jumps, a fresh anchor, nothing credited. |
| `PdrPolicy` | `OFF, CONFIRMED_BY_GPS` | Whether step estimates are held as pending until returning GPS confirms them. |
| `Smoothing` | `OFF, KALMAN` | Whether positions go through `Kalman2D` before distance is measured. |
| `Sport` | `RUNNING, WALKING, CYCLING, OTHER` | Only used to choose the speed ceiling. |

## Data classes
### `TrackingFix` — one raw location reading
| Property | Type | Description |
|---|---|---|
| `sessionId` | `String` | Recording session; fixes of an older session are dropped by the layer above. |
| `segmentId` | `Int` | 0 for the first segment, +1 on every resume. The engine never bridges two segments. |
| `seq` | `Long` | Increasing number within a session; detects duplicates and out-of-order fixes. |
| `timeMs` | `Long` | When the position was **measured**, on the same **monotonic** clock as the `nowMs` given to the engine (not necessarily epoch time). |
| `latitude`, `longitude` | `Double` | Coordinates. |
| `horizontalAccuracy` | `Double?` | Horizontal accuracy in metres; `null` = unknown. |
| `speed`, `speedAccuracy` | `Double?` | Doppler speed (m/s) and its accuracy. |
| `course`, `altitude`, `verticalAccuracy` | `Double?` | Heading, altitude, vertical accuracy. |
| `provider` | `String` | `gps`, `fused`, `network`, ... (default `unknown`). |
| `isMock` | `Boolean` | The platform flags the fix as simulated. |

### `TrackingConfig` — configuration (every number is an **unmeasured starting value**)
| Property | Default | Meaning |
|---|---|---|
| `maxAccuracyMeters` | 40 | A fix with a worse (or unknown) accuracy is DEGRADED: it tells the quality, never distance. |
| `maxFixAgeMs` | 30 000 | A fix measured longer ago than this when it arrives is dropped (STALE). |
| `silenceAfterMs` | 10 000 | No fix for this long = GPS unavailable. |
| `maxSpeedMps` | 10 | Implied speed above this between two fixes is a jump (JUMP). |
| `minStepMeters`, `accuracyNoiseFactor` | 2 / 0.5 | Displacement below `max(minStep, factor × accuracy)` is noise and not counted. |
| `stationarySpeedMps` | 0.4 | Doppler speed below this means "not moving": no distance. |
| `maxBridgeMs` | 120 000 | A longer gap is not bridged with the straight line. |
| `reanchorAfterRejects` | 3 | After this many jump rejections in a row the engine re-anchors (no distance). |
| `rejectMock` | true | Drop simulated fixes. |
| `pdrPolicy` | `CONFIRMED_BY_GPS` | See the enum. |
| `pdrConfirmRatio` | 0.5 | A pending estimate P is confirmed when the straight line is at least 0.5 × P. |
| `smoothing` | `OFF` | Set `KALMAN` to smooth positions. |
| `stopRadiusMeters`, `stopMinDwellMs` | 15 / 30 000 | `StopDetector` parameters (moving time). |

`TrackingConfig.forSport(sport)` builds a config with a per-sport speed ceiling: cycling 25 m/s, running/walking 10, other 12; **cycling turns the step estimate off** (a bike has no steps).

The constructor validates every value (`maxAccuracyMeters` finite and positive, positive durations, `pdrConfirmRatio` in (0, 1], ...) and throws `IllegalArgumentException` otherwise.

### Results
- `FixResult(verdict, addedMeters = 0.0, source: DistanceSource? = null)`: result of `onFix`.
- `LedgerEntry(segmentId, source, meters, atMs)`: one line of the ledger.
- `TrackingSnapshot`: immutable snapshot for the UI: `sessionId, state, quality, totalMeters` (never decreases), `gpsMeters, gapBridgeMeters, pdrMeters`, `pendingPdrMeters` (**not** part of the total), `segmentId, fixCount, speedMps, lastFixAtMs, movingTimeMs, isStopped`.

## Rules
- No threshold in `TrackingConfig` has been measured on devices: tune from real recordings, not by feel.
- No coordinates ever enter a log; `TrackingFix` only lives in memory and in the native store.

## Used by
`engine/GpsDistanceEngine.kt`, `algorithms/fusion/GapReconciler.kt`, and the tests.

## Steps (added)
`TrackingConfig.initialStrideMeters: Double?` (null = none) and `TrackingSnapshot.strideMeters: Double?`.

## Added
`PdrPolicy.TRUSTED_WHEN_CALIBRATED`; `TrackingConfig.recoveryStepEvidenceAfterMs` (default 30 s); `TrackingSnapshot.activeTimeMs`, `bridgedGapMs`, `uncreditedGapMs`, `uncreditedGaps`, `ambiguousSteps`.
