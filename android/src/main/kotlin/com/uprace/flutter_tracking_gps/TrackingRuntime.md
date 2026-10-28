# TrackingRuntime.kt

**Path:** `android/src/main/kotlin/com/uprace/flutter_tracking_gps/TrackingRuntime.kt` · **Layer:** Android native (process-wide holder)

## Purpose
Holds the single `TrackingEngine` of the process. The engine deliberately outlives the Flutter engine and the plugin instance: a recording must not stop because the UI engine was detached.

## Members
| Member | Description |
|---|---|
| `TrackingRuntime.engine(context): TrackingEngine` | Lazily creates (thread-safe) and returns the engine, using only the application context. |
| `createEngine` | Builds the engine with `FusedLocationSource`, `FileJournalStore(filesDir/flutter_tracking_gps/journal)` and a process-wide `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. |

## Memory
The scope is process-wide by design. Nothing here holds an Activity, a plugin or a Flutter engine, so there is no leak.

## Used by
`FlutterTrackingGpsPlugin.kt`.

Also passes `SensorStepSource(appContext)` to the engine.

The journal directory is `flutter_tracking_gps/journal` under `filesDir`.
