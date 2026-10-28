# Showing the recording outside the app (live activity / notification)

The SDK records without any UI. To show the recording on the lock screen, in the
Dynamic Island or as a notification, an app plugs in **one object**: a
`TrackingPresenter`. The SDK never depends on a live-activity package; the app
writes a small adapter around the one it already uses.

```dart
final session = gps.createSession(
  config: TrackingConfig.forSport(TrackingSport.running),
  presenter: MyLiveActivityPresenter(),   // optional
);
```

## What happens with and without a presenter

| | No presenter (default) | With a presenter |
|---|---|---|
| **Android** | The SDK runs its own foreground service (type `location`) with a plain ongoing notification. Title and text come from `createSession(notificationTitle:, notificationText:)` (default text: "Tracking your activity. Tap to return to the app."); tapping it opens the app. No live data. This is what keeps the process, and so the recording, alive, even when the Flutter engine is gone. | Same, **plus** whatever the presenter shows, unless `ownsAndroidForegroundService` is true (below). |
| **iOS** | Nothing extra. Background recording is kept alive by the `location` background mode. | The presenter drives the Live Activity. |

## `TrackingPresenter`

```dart
abstract class TrackingPresenter {
  bool get ownsAndroidForegroundService => false;
  Future<void> onStart(TrackingSnapshot s) async {}
  Future<void> onUpdate(TrackingSnapshot s) async {}   // ~1 Hz, throttled
  Future<void> onPause(TrackingSnapshot s) async {}
  Future<void> onResume(TrackingSnapshot s) async {}
  Future<void> onFinish(TrackingSnapshot s) async {}
}
```

- Calls are made in order and never overlap (the next waits for the previous future).
- A throwing or slow presenter never affects the recording: errors go to
  `createSession(onPresenterError: ...)` (default: a `dart:developer` log).
- A presenter lives in the Flutter isolate. If the engine is destroyed while
  recording, it stops being called; native keeps recording and, on Android, the
  SDK's own notification stays. iOS Live Activities cannot be updated by
  native code of this package, so they freeze until the app returns.

### `ownsAndroidForegroundService`

Return `true` when the presenter's plugin owns a foreground service of type
`location`. The SDK then
starts **no** foreground service or notification of its own, so the user sees one
notification, not two. The recording then depends on the presenter's service to
keep the process alive: if the presenter fails to start it, **nothing keeps the
process alive**. Leave it `false` (default) when unsure.

## Example adapter

A skeleton: replace the `MyLiveActivity` calls with the plugin the app uses.
Not compiled by this package.

```dart
class MyPresenter extends TrackingPresenter {
  final _surface = MyLiveActivity(); // the third-party plugin

  @override
  bool get ownsAndroidForegroundService => false; // true only if that plugin holds a location foreground service

  @override
  Future<void> onStart(TrackingSnapshot s) => _surface.start(distance: s.totalMeters);

  @override
  Future<void> onUpdate(TrackingSnapshot s) => _surface.update(
        distance: s.totalMeters,
        paused: s.state == RecordingState.paused,
      );

  @override
  Future<void> onFinish(TrackingSnapshot s) => _surface.end();
}
```

An iOS Live Activity needs a Widget Extension target in the host app; that setup
belongs to the plugin that provides it.

## Why Dart-side and not native-side?

An iOS Live Activity needs a Widget Extension target in the **host app**, and the
plugin that owns it is a Flutter plugin driven from Dart. A native hook inside
this package could not reach it. The Android default is native on purpose: it is
the part that must survive the Flutter engine.
