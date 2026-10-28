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
| **Android** | The SDK runs its own foreground service (type `location`) with a plain ongoing notification. Title and text come from `createSession(notificationTitle:, notificationText:)` (default text: "Tracking your activity. Tap to return to the app."); tapping it opens the app. | The SDK's service and notification are STILL there. The presenter may replace the notification's content (below), or show its own surface in addition. |
| **iOS** | Nothing extra. Background recording is kept alive by the `location` background mode. | The presenter drives the Live Activity. |

**The SDK always keeps the process alive itself.** A presenter only draws; it
never decides whether the recording survives. If the presenter fails to start,
throws, or its engine dies, the SDK's notification is still there and the
recording is unaffected.

### Showing ONE notification (Android)

To avoid two notifications, a presenter can update the SDK's notification instead
of posting its own: post with the same id and channel (`NOTIFICATION_ID` 7421 and
`CHANNEL_ID` `flutter_tracking_gps`, public constants of `TrackingService`).
Android replaces the content in place. That needs a small option in the
presenter's own plugin ("update this notification id, do not start a service");
the SDK cannot do it for it.

## `TrackingPresenter`

```dart
abstract class TrackingPresenter {
  Future<void> onStart(TrackingSnapshot s) async {}
  Future<void> onUpdate(TrackingSnapshot s) async {}   // ~1 Hz, coalesced
  Future<void> onPause(TrackingSnapshot s) async {}
  Future<void> onResume(TrackingSnapshot s) async {}
  Future<void> onFinish(TrackingSnapshot s) async {}
}
```

Delivery is bounded (`PresenterDispatcher`):

- Lifecycle events (start, pause, resume, finish) are delivered in order and never
  dropped (the queue is capped at 32; on overflow the oldest is discarded and
  reported).
- `onUpdate` is COALESCED: only the newest waits, so a slow presenter builds no
  backlog; a pending update is discarded once `finish` is queued.
- Every call has a time limit (`createSession(presenterTimeout:)`, default 5 s): a
  call that hangs is reported as a `TimeoutException` and skipped, and the next
  event is delivered.
- A throwing presenter, or a throwing `onPresenterError`, is contained.
- `dispose` waits for delivery but never longer than a bound, so it cannot hang on
  a presenter that never answers.
- A presenter lives in the Flutter isolate: when the engine is destroyed while
  recording it stops being called. The SDK's notification stays; a Live Activity
  cannot be updated by native code of this package and freezes until the app returns.

## Example adapter

A skeleton: replace the `MyLiveActivity` calls with the plugin the app uses.
Not compiled by this package.

```dart
class MyPresenter extends TrackingPresenter {
  final _surface = MyLiveActivity(); // the third-party plugin

  @override
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
