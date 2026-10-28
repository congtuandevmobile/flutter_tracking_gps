# TrackingService.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/TrackingService.kt` · **Layer:** Android native (foreground service)

## Purpose
Keeps the process in the foreground while a session records. Declared in the manifest as `foregroundServiceType="location"`. It owns **no tracking logic**: `TrackingEngine` does the work; this class only holds the ongoing notification Android requires.

## Members
| Member | Description |
|---|---|
| `onStartCommand` | `ACTION_STOP` removes the foreground state and stops the service. Otherwise it builds the notification from the intent extras and calls `startForeground` (type `location` on API 29+), then returns `START_NOT_STICKY`. |
| `buildNotification(title, text)` | Creates the notification channel (API 26+, importance low) and an ongoing notification (small icon = the app icon, tap opens the app). |
| `startForegroundCompat`, `stopForegroundCompat` | API-level differences for starting and stopping the foreground state. |
| `ACTION_START`, `ACTION_STOP` | Intent actions. |
| `EXTRA_TITLE`, `EXTRA_TEXT` | Intent extras carrying the notification text. |
| internal constants | Channel id `flutter_tracking_gps`, channel name "Activity recording", notification id 7421. |

## Why `START_NOT_STICKY`
After a process kill there is no session to continue, and a restarted service without one would only show a stale notification.

## Notes
Start it from the foreground (the "while in use" permission is enough). It is started and stopped by `FlutterTrackingGpsPlugin`.
