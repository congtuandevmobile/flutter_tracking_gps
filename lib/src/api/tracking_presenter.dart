import 'package:flutter_tracking_gps/src/domain/tracking_snapshot.dart';

/// A place to show the recording outside the app: an iOS Live Activity / Dynamic
/// Island, an Android ongoing notification, a watch, a home-screen widget.
///
/// The SDK does not depend on any of these. An app adds one by giving
/// `createSession(presenter: ...)` an adapter around the plugin it already uses;
/// the SDK calls it on every transition and about once per second while recording. Without a presenter nothing extra is
/// shown on iOS, and Android shows the SDK's own default notification.
///
/// Rules every presenter can rely on:
///  * Calls are made from the Flutter isolate, in order, and never overlap: the
///    next call waits for the previous future.
///  * A throwing or slow presenter never affects the recording: errors go to
///    `TrackingSession.onPresenterError` and the SDK carries on.
///  * The snapshot is the same object the app receives from `session.updates`.
///  * They stop being called when the Flutter engine is gone. Native keeps
///    recording and, on Android, the SDK's own notification stays; a presenter's
///    surface does not update.
abstract class TrackingPresenter {
  const TrackingPresenter();

  /// Android only. True when this presenter owns a foreground service of type
  /// `location`. The SDK then starts NO foreground service or notification of
  /// its own and relies on the presenter's to keep the process, and so the
  /// recording, alive. False
  /// (default): the SDK keeps its own service and notification, and the
  /// presenter's surface is shown in addition. Ignored on iOS.
  bool get ownsAndroidForegroundService => false;

  /// The session started (state running).
  Future<void> onStart(TrackingSnapshot snapshot) async {}

  /// About once per second while recording or paused, and on every change that
  /// is not one of the transitions below.
  Future<void> onUpdate(TrackingSnapshot snapshot) async {}

  Future<void> onPause(TrackingSnapshot snapshot) async {}

  /// Paused session resumed (a new segment).
  Future<void> onResume(TrackingSnapshot snapshot) async {}

  /// The final snapshot. Close the surface here.
  Future<void> onFinish(TrackingSnapshot snapshot) async {}
}
