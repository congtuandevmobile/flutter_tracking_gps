

Same recovery as Android: `restore` with `recover`, `recoverActive()` (pointer), `activeSessionId`; snapshot carries `elapsedTimeMs`, `recoveredGapMs`, `clockAnomaly`, `collection`; ticks journal the calendar time; a recovered recording collects again (`resumeCollectingAfterRecovery`).
