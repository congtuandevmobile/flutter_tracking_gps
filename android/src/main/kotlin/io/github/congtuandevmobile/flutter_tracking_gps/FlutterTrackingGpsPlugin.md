# FlutterTrackingGpsPlugin.kt

**Path:** `android/src/main/kotlin/io/github/congtuandevmobile/flutter_tracking_gps/FlutterTrackingGpsPlugin.kt` · **Layer:** Android native (Flutter entry point)

## Purpose
The plugin Flutter registers. It is thin on purpose: it validates, forwards to `TrackingEngine`, and turns every failure into a `FlutterError`. Results are delivered on the main thread; heavy work runs on `Dispatchers.IO` inside the engine.

## Implements
`FlutterPlugin`, `ActivityAware`, `TrackingHostApi` (the Pigeon contract) and `PluginRegistry.RequestPermissionsResultListener`.

## Lifecycle
| Callback | What it does |
|---|---|
| `onAttachedToEngine` | Keeps the application context, creates a main-thread `CoroutineScope`, registers the Pigeon API and the fix stream handler. |
| `onDetachedFromEngine` | Unregisters the API, cancels the fix and snapshot jobs and the scope, clears the pending permission callback and the context. **The recording keeps going** (see `TrackingRuntime`). |
| `onAttachedToActivity`, `onReattachedToActivityForConfigChanges` | Remember the activity binding and register for permission results. |
| `onDetachedFromActivity*` | Unregister the listener and drop the activity (no leak). |

## API (`TrackingHostApi`)
| Method | Description |
|---|---|
| `permissionStatus()` | `GRANTED`, `DENIED` or `SERVICES_DISABLED` (location switched off). |
| `requestPermission(callback)` | Asks for fine/coarse location (and `POST_NOTIFICATIONS` on API 33+). Fails with `NO_ACTIVITY` without an activity and `PERMISSION_REQUEST_PENDING` if one is already open. |
| `start(config, callback)` | Checks the permission (otherwise returns a `PERMISSION_DENIED` snapshot), starts the foreground service, then the engine. |
| `pause`, `resume`, `stop` | Forward to the engine; `stop` also stops the service. |
| `getSnapshot()` | Current engine snapshot (idle snapshot when detached). |
| `submitPdr(sessionId, segmentId, meters)` | Forwards a step estimate for a segment to the engine (held as pending); one of another segment is refused. |
| `restoreSession(config, callback)` | After a process death: the engine replays the persisted fixes and the session comes back paused (`RESTORE_FAILED` on error). |
| `getReport()` | The core's coordinate-free timeline. |
| `readPersistedFixes(sessionId, afterSeq, callback)` | Reads stored fixes. |
| `clearSession(sessionId, callback)` | Deletes stored fixes. |

## Helpers
- `command(callback, code, message, block)`: runs a suspend block on the plugin scope, delivers the result on main, rethrows `CancellationException`, and converts any other exception with `toFlutterError(code, message)`.
- `FixesHandler` and `SnapshotsHandler` (inner classes): while Dart listens, collect `engine.fixes` / `engine.snapshots` and call `sink.success(...)`; a failure becomes `sink.error(code, message, details)`; `onCancel` cancels the job.

## Rules followed
Coroutines and Flow, no `!!`, resources released on detach, every error a `FlutterError` with code, message and details.

## Steps and foreground mode (added)
`stepsPermissionStatus()` / `requestStepsPermission()`: `ACTIVITY_RECOGNITION` (a runtime permission from Android 10; granted before). Request code 7422, separate pending callback from the location one. `start` starts `TrackingService` only for `NativeForegroundMode.BUILT_IN`; with `EXTERNAL` the app's presenter must hold a foreground service of type `location`.

## Updated
`start` always starts `TrackingService`. `resume` first starts it again from the notification text stored in the journal; if the OS refuses, recording does NOT continue: the session stays paused and the call fails with `FOREGROUND_START_FAILED`. New `listSessions()`. `NativeForegroundMode` no longer exists.

New `updateNotification(title, text)` forwards to `TrackingService.update`.

## Recheck update

`start` and `resume` wait for the foreground acknowledgement (`ForegroundHandshake`, 5 s) before recording; on failure or timeout the service is stopped and `FOREGROUND_START_FAILED` is returned. `resume` validates session and state BEFORE any side effect. An existing `FlutterError` keeps its own code.
