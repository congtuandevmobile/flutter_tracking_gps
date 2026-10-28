# ForegroundHandshake

Pure acknowledgement between the plugin and `TrackingService`. `startForegroundService()` only queues the start, so the plugin calls `expect()` first, starts the service, and `await`s until the service calls `acknowledge()` right after `startForeground` (or `fail()` when the OS refuses). Recording begins only after the acknowledgement; on timeout or failure the plugin stops the service and reports `FOREGROUND_START_FAILED`.

Overlapping requests: every `expect()` owns a waiter; `acknowledge`/`fail` complete ALL open waiters. `isCurrent(wait)` is true only for the newest request: after a timeout or failure only the newest may stop the service, so an older request never stops a service a newer one uses.
