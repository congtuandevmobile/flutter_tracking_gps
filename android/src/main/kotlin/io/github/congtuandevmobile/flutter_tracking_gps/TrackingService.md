

`START_STICKY`: after the system kills and recreates the service (null intent or `ACTION_RECOVER`) it calls `TrackingEngine.recoverActive()` and stops itself when there is nothing to continue.
