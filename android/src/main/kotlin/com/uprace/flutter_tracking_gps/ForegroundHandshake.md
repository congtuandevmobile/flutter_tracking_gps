# ForegroundHandshake

Pure acknowledgement between the plugin and `TrackingService`. `startForegroundService()` only queues the start, so the plugin calls `expect()` first, starts the service, and `await`s until the service calls `acknowledge()` right after `startForeground` (or `fail()` when the OS refuses). Recording begins only after the acknowledgement; on timeout or failure the plugin stops the service and reports `FOREGROUND_START_FAILED`.
