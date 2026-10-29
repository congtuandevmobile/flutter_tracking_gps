

Same recovery as Android: `restore` with `recover`, `recoverActive()` (pointer), `activeSessionId`; snapshot carries `elapsedTimeMs`, `recoveredGapMs`, `clockAnomaly`, `collection`; ticks journal the calendar time; a recovered recording collects again (`resumeCollectingAfterRecovery`).


## Pedal cadence
With `StartConfig.pedalCadence` the engine also reads the accelerometer (`MotionProviding`) into a live-only `PedalCadenceEstimator` (core). It is never journaled and never touches distance. `beginCadence()` starts the sensor for each running segment (estimator reset, generation bumped so a queued reading cannot reach a later segment), `endCadence()` stops it at a pause, the stop or a new session. CoreMotion stamps readings with the uptime clock, so an offset to `CLOCK_MONOTONIC` is taken when the sensor starts. The snapshot carries `pedalCadenceRpm` (negative = unknown; always unknown unless running) and `cadenceStatus`. A missing or failing sensor sets `cadenceStatus` and `CADENCE_FAILED` and never stops the recording. The flag is kept in the journal header extras (`pedalCadence`) so a recovery turns it back on.

## External cadence
An `ExternalCadenceHub` (core, `tracker.algorithms.cadence`) holds the cadence of a sensor the HOST connected to. `pushCsc` (the `Data` is copied into a `KotlinByteArray`), `pushExternalRpm` and `setExternalConnected` hop to `queue`, only for the open session while running or paused. The receipt time is taken on the caller's thread, from `cadenceClockMs` (default `systemUptime`, the same clock as the phone estimator, injectable for tests), so a notification that waits in the queue keeps its time. `makeSnapshot()` calls `resolveCadence()`; always unknown unless running. The hub is reset on `start` and `restore`. Never journaled, no effect on distance.
