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

  /// A recording. Create one per activity.
  TrackingSession createSession({
    TrackingConfig config = const TrackingConfig(),
    bool adaptiveSampling = false,
    bool batterySaver = false,
    String notificationTitle = 'Recording',
    String notificationText = 'Tracking your activity',
  }) => TrackingSession(
    platform: _platform,
    config: config,
    adaptiveSampling: adaptiveSampling,
    batterySaver: batterySaver,
    notificationTitle: notificationTitle,
    notificationText: notificationText,
  );
}
