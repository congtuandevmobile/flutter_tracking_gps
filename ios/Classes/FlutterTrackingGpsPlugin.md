# FlutterTrackingGpsPlugin.swift

**Path:** `ios/Classes/FlutterTrackingGpsPlugin.swift` · **Layer:** iOS native (Flutter entry point)

## Purpose
The plugin Flutter registers. It is thin on purpose: it forwards to `TrackingEngine`, which delivers every result on the main thread and converts every failure into a `PigeonError` (code, message, details).

## Members
| Member | Description |
|---|---|
| `static let engine` | Process-wide `TrackingEngine`, created lazily on the main thread (CoreLocation needs a run loop) with `CoreLocationProvider` and `FileJournalStore` in Application Support (`flutter_tracking_gps/journal`). It lives beyond the Flutter engine so a recording does not stop when the engine detaches. |
| `register(with:)` | Creates the instance, registers the Pigeon API and the fix and snapshot stream handlers, and sets `engine.fixObserver` and `engine.snapshotObserver` (weak references to the plugin). |
| `detachFromEngine(for:)` | Unregisters the API, clears both observers, the event sinks and the messenger. The recording keeps going. |
| `TrackingHostApi` methods | `permissionStatus`, `requestPermission`, `start`, `pause`, `resume`, `stop`, `getSnapshot`, `submitPdr` (with the segment), `restoreSession`, `getReport`, `readPersistedFixes`, `clearSession`: each forwards to the engine. |
| `FixesHandler`, `SnapshotsHandler` (private classes) | Hold the event sink while Dart listens, with a **weak** reference to the plugin so it cannot keep it alive; `onListen` attaches the sink, `onCancel` detaches it. |

## Rules followed
Results on the main thread, `[weak self]` / weak references in closures and callbacks, no retain cycles, errors as `PigeonError`.

## Related
Contract: `pigeons/tracking_api.dart` (generated in `TrackingApi.g.swift`).

Added: `stepsPermissionStatus()` and `requestStepsPermission(completion:)` forwarded to the engine. `StartConfig.foregroundMode` is ignored on iOS.

New `listSessions(completion:)`. The journal directory is `flutter_tracking_gps/journal` under Application Support.

`updateNotification` is a no-op on iOS.
