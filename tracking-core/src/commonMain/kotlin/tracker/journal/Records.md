# Records.kt

**Path:** `.../tracker/journal/Records.kt` · **Layer:** journal (types)

## `BridgeSettings`
Every engine setting as primitives (20 of them: the 15 of `TrackingConfig`, `initialStrideMeters`, `pdrTrustedWhenCalibrated`, `recoveryStepEvidenceAfterMs`, `adaptiveSampling`, `batterySaver`). It is stored in the journal header so a restore replays with the configuration the session RAN with. `toBridge()` builds a `TrackingCoreBridge`.

## `JournalRecord` (sealed)
`Header`, `Pause`, `Resume`, `Finish`, `Fix` (a raw fix plus its arrival time `nowMs` and calendar time `wallTimeMs`), `Tick` (only when it changed the state), `StepCounter`, `SegmentSteps`, `StepsCorrection`, `StepSensor`, `Pdr`. Times named `...AtMs` are monotonic. See `doc/JOURNAL.md`.

Used by `JournalCodec` and `SessionRecorder`. Tested by `JournalTest`.

`Header.engineVersion` is the rules version that produced the recorded result.

`Finalized(stepsFinal)`: written once after `Finish`; true when the platform's exact step count was obtained (or there is no step sensor).
