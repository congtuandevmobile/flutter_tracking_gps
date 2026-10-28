# TrackingService.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/TrackingService.kt` · **Layer:** Android native (foreground service)

## Purpose
The **default** way the SDK keeps the process alive while a session records: a foreground service of type `location` with a plain ongoing notification. Title and text come from Flutter (`createSession(notificationTitle:, notificationText:)`, defaults "Recording" and "Tracking your activity. Tap to return to the app."); tapping it opens the app. It shows **no live data** and owns **no tracking logic**.

It is **not started** when the app picked `NativeForegroundMode.external` (a third-party presenter, for example a live-activity plugin, then owns a foreground service). See `doc/PRESENTER.md`.

## Members
| Member | Description |
|---|---|
| `onStartCommand` | `ACTION_STOP` removes the foreground state and stops the service. Otherwise it reads title/text from the intent extras, calls `startForeground` (type `location` on API 29+) and returns `START_NOT_STICKY`. |
| `buildNotification(title, text)` | Channel (API 26+, importance low), ongoing notification, small icon = the app icon, tap opens the app. |
| `startForegroundCompat`, `stopForegroundCompat` | API-level differences. |
| `ACTION_START`, `ACTION_STOP`, `EXTRA_TITLE`, `EXTRA_TEXT` | Intent contract. Channel `flutter_tracking_gps`, notification id 7421. |

## Why `START_NOT_STICKY`
After a process kill there is no session to continue, and a restarted service without one would only show a stale notification.

## Notes
Start from the foreground ("while in use" is enough). Started and stopped by `FlutterTrackingGpsPlugin`.

Updated: the SDK ALWAYS runs this service while a session records (no `external` mode any more), so the recording never depends on a third party. A presenter that wants a richer surface can replace the notification's content by posting with `TrackingService.NOTIFICATION_ID` and `CHANNEL_ID` (public constants). On resume after a process death the plugin starts it BEFORE recording continues.
