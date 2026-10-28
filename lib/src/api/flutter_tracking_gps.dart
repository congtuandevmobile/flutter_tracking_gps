import 'package:flutter_tracking_gps/src/api/tracking_presenter.dart';
import 'package:flutter_tracking_gps/src/api/tracking_session.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_config.dart';
import 'package:flutter_tracking_gps/src/platform/pigeon_tracking_platform.dart';
import 'package:flutter_tracking_gps/src/platform/tracking_platform.dart';

/// Entry point. Use ONE instance: the native session is global.
class FlutterTrackingGps {
  FlutterTrackingGps({TrackingPlatform? platform})
    : _platform = platform ?? PigeonTrackingPlatform();

  final TrackingPlatform _platform;

  Future<PlatformPermission> permissionStatus() => _platform.permissionStatus();

  /// Asks while the app is in the foreground ("when in use" is enough to record
  /// a workout; no background permission is needed).
  Future<PlatformPermission> requestPermission() =>
      _platform.requestPermission();

  /// Motion permission of the step sensor (`ACTIVITY_RECOGNITION` on Android 10+,
  /// Motion & Fitness on iOS). Only needed with `createSession(stepsEnabled: true)`.
  Future<PlatformPermission> stepsPermissionStatus() =>
      _platform.stepsPermissionStatus();

  /// Asks while the app is in the foreground. Recording works without it.
  Future<PlatformPermission> requestStepsPermission() =>
      _platform.requestStepsPermission();

  /// A recording. Create one per activity.
  ///
  /// [presenter] is where an app plugs in a Live Activity / ongoing notification
  /// from another package; null keeps the SDK's default (Android: its own
  /// foreground notification; iOS: nothing extra). See [TrackingPresenter].
  TrackingSession createSession({
    TrackingConfig config = const TrackingConfig(),
    bool adaptiveSampling = false,
    bool batterySaver = false,
    String notificationTitle = 'Recording',
    String notificationText = 'Tracking your activity. Tap to return to the app.',
    bool stepsEnabled = false,
    int initialSteps = 0,
    TrackingPresenter? presenter,
    void Function(Object error, StackTrace stack)? onPresenterError,
  }) => TrackingSession(
    platform: _platform,
    config: config,
    adaptiveSampling: adaptiveSampling,
    batterySaver: batterySaver,
    notificationTitle: notificationTitle,
    notificationText: notificationText,
    stepsEnabled: stepsEnabled,
    initialSteps: initialSteps,
    presenter: presenter,
    onPresenterError: onPresenterError,
  );
}
