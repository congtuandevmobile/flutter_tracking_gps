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
| **Android** | The SDK runs its own foreground service (type `location`) with an ongoing notification: title, distance and a self-ticking chronometer. It keeps updating from native snapshots, even when the Flutter engine is gone. This is what keeps the process, and so the recording, alive. | Same, **plus** whatever the presenter shows, unless `ownsAndroidForegroundService` is true (below). |
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
  SDK's own notification keeps updating. iOS Live Activities cannot be updated by
  native code of this package, so they freeze until the app returns.

### `ownsAndroidForegroundService`

Return `true` when the presenter's plugin owns a foreground service of type
`location` (as `uprace_live_activity`'s `LiveActivityService` does). The SDK then
starts **no** foreground service or notification of its own, so the user sees one
notification, not two. The recording then depends on the presenter's service to
keep the process alive: if the presenter fails to start it, **nothing keeps the
process alive**. Leave it `false` (default) when unsure.

## Example adapter for `uprace_live_activity`

Not compiled by this package (it has no dependency on `uprace_live_activity`);
copy it into the app.

```dart
class UpraceLiveActivityPresenter extends TrackingPresenter {
  UpraceLiveActivityPresenter({this.locale = 'vi', this.activityType = 'running'});
  final String locale;
  final String activityType;
  final _live = UpraceLiveActivity();
  String? _id;
  late final int _startMs;

  @override
  bool get ownsAndroidForegroundService => true; // its LiveActivityService is type location

  LiveActivityData _data(TrackingSnapshot s, String status) => LiveActivityData()
    ..activityType = activityType
    ..startTimestamp = _startMs
    ..elapsedSeconds = s.movingTime.inSeconds
    ..distanceMeters = s.totalMeters
    ..paceSecondsPerKm = (s.speedMps ?? 0) > 0.3 ? 1000 / s.speedMps! : 0
    ..steps = s.steps
    ..status = status;

  @override
  Future<void> onStart(TrackingSnapshot s) async {
    _startMs = DateTime.now().millisecondsSinceEpoch;
    final r = await _live.startActivity(_data(s, 'active'), locale: locale);
    _id = r.activityId;
  }

  @override
  Future<void> onUpdate(TrackingSnapshot s) async {
    final id = _id;
    if (id != null) await _live.updateActivity(id, _data(s, s.state == RecordingState.paused ? 'paused' : 'active'));
  }

  @override
  Future<void> onPause(TrackingSnapshot s) => onUpdate(s);
  @override
  Future<void> onResume(TrackingSnapshot s) => onUpdate(s);

  @override
  Future<void> onFinish(TrackingSnapshot s) async {
    final id = _id;
    if (id != null) await _live.endActivity(id);
  }
}
```

Notes for that plugin (from its own README): on iOS the host app needs a Widget
Extension target with `UpraceLiveActivityWidget.swift`, `NSSupportsLiveActivities`
in `Info.plist` and the `group.vn.vng.uprace` App Group; on Android it needs the
runtime notification permission. `elapsedSeconds` above is the engine's moving
time; the plugin's chronometer fields (`startTimestamp`, `activeStartTimestamp`)
are the app's to keep consistent with its own clock.

## Why Dart-side and not native-side?

An iOS Live Activity needs a Widget Extension target in the **host app**, and the
plugin that owns it is a Flutter plugin driven from Dart. A native hook inside
this package could not reach it. The Android default is native on purpose: it is
the part that must survive the Flutter engine.
