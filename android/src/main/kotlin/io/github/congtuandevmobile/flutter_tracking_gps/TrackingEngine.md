

`restore` can continue a recording (`recoverRunning`): `recoverActive()` finds the open session through the store's pointer and recovers it WITHOUT Flutter. Snapshots carry `elapsedTimeMs`, `recoveredGapMs`, `clockAnomaly` and `collection` (`BLOCKED` when location cannot be collected). Ticks journal the calendar time.


## Pedal cadence
With `StartConfig.pedalCadence` the engine also collects the accelerometer (`AccelerometerSource`) into a live-only `PedalCadenceEstimator` (core). It is never journaled and never touches distance. `beginCadence()` registers the sensor for each running segment (reset on every segment), `haltJobs()` retires it with the generation gate, the snapshot carries `pedalCadenceRpm` (negative = unknown; always unknown unless running) and `cadenceStatus`. A missing or failing sensor sets `cadenceStatus` and `CADENCE_FAILED` and never stops the recording. The flag is kept in the journal header extras (`pedalCadence`) so a recovery turns it back on.

## External cadence
An `ExternalCadenceHub` (core, `tracker.algorithms.cadence`) holds the cadence of a sensor the HOST connected to. `pushCsc`, `pushExternalRpm` and `setExternalConnected` forward the host's calls under `coreLock`, only for the open session while RUNNING or PAUSED (anything else is dropped; garbage is caught and logged, never touching the recording). The receipt time is `monotonicMs()` at arrival. `snapshot()` calls `resolveCadence()`: the phone estimate (only when `pedalCadence` and the sensor is ACTIVE) goes through the hub, which returns the chosen rpm and `cadenceSource` (external healthy > phone > none); always unknown unless RUNNING. The hub is reset on `start` and `restore`. Never journaled, no effect on distance.
