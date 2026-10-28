

`restoreSession` with `recoverRunning` starts the foreground service first, then continues the session; `activeSessionId()` exposes the pointer. If this process already holds the session (the service recovered it) it only attaches.
