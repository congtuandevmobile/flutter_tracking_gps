# TrackingService.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/TrackingService.kt` · **Layer:** Android native (foreground service)

## Purpose
The **default** way the SDK keeps the process alive while a session records: a foreground service of type `location` with an ongoing notification showing the **distance** and a **self-ticking chronometer**. It owns no tracking logic: it reads `TrackingEngine.snapshots` (so it keeps updating when the Flutter engine is gone) and draws them.

It is **not started** when the app picked `NativeForegroundMode.external` (a third-party presenter, for example a live-activity plugin, then owns a foreground service). See `docs/PRESENTER.md`.

## Members
| Member | Description |
|---|---|
| `onStartCommand` | `ACTION_STOP` stops observing, removes the foreground state and stops the service. Otherwise it reads title/text, calls `startForeground` (type `location` on API 29+) with a first notification, starts observing the snapshots, and returns `START_NOT_STICKY`. |
| `observeSnapshots()` | Collects the engine's snapshots in a main-thread scope; a failure is logged and never affects the recording. |
| `show(snapshot)` | Redraws only when the text or the running flag changed. Active time excludes pauses (kept per state change); the chronometer base is `now - active time`, so no update is needed every second. Paused / finished / permission-missing states show a status word. |
| `build(text, base, running)` | Channel (API 26+, importance low), ongoing notification, small icon = the app icon, tap opens the app, chronometer on while running. |
| `startForegroundCompat`, `stopForegroundCompat` | API-level differences. |
| `ACTION_START`, `ACTION_STOP`, `EXTRA_TITLE`, `EXTRA_TEXT` | Intent contract. Channel `flutter_tracking_gps`, notification id 7421. |

## Why `START_NOT_STICKY`
After a process kill there is no session to continue, and a restarted service without one would only show a stale notification.

## Notes
Start from the foreground ("while in use" is enough). Started and stopped by `FlutterTrackingGpsPlugin`. Not unit-tested (needs a device); compiled and built with the example.

Snapshots are ignored until the first `RUNNING` one: the replayed snapshot may belong to the previous session, because the service starts just before the engine.
