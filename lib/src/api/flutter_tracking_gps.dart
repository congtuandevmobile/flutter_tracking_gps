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

  /// The ids of the sessions persisted on this device (for example after the
  /// process was killed mid-recording): [TrackingSession.restore] one of them, or
  /// clear it once it was saved.
  Future<List<String>> listSessions() => _platform.listSessions();

  /// The id of the session that was recording or paused when it was last seen (the open-session pointer), or null.
  /// It is per DEVICE: decide whether it is yours before restoring it.
  Future<String?> activeSessionId() => _platform.activeSessionId();

  /// Brings the open session back, if there is one: the session that was recording or paused when it was last
  /// seen (typically after the process died). Returns null when there is none.
  ///
  /// When native already holds it (a service or the app launch recovered it before Flutter was ready) nothing is
  /// replayed twice: the returned session simply attaches to it. A session that was RECORDING continues (see
  /// [TrackingSession.restore] `recoverRunning`); the dead interval is credited to the clock, never to the distance.
  /// Do NOT call `start` on it, and do not add the dead time yourself: native already did.
  ///
  /// [accept] is asked with the open session's id; return false to leave it alone (another account's session).
  Future<TrackingSession?> attachOrRecover({
    TrackingConfig config = const TrackingConfig(),
    bool adaptiveSampling = false,
    bool batterySaver = false,
    String notificationTitle = 'Recording',
    String notificationText = 'Tracking your activity. Tap to return to the app.',
    bool stepsEnabled = false,
    TrackingPresenter? presenter,
    void Function(Object error, StackTrace stack)? onPresenterError,
    bool Function(String sessionId)? accept,
  }) async {
    final id = await _platform.activeSessionId();
    if (id == null) return null;
    // The open-session pointer is per device, not per user: the app decides whether this session is its to restore
    // (for example it belongs to the signed-in account). A refused session is left untouched.
    if (accept != null && !accept(id)) return null;
    final session = createSession(
      config: config,
      adaptiveSampling: adaptiveSampling,
      batterySaver: batterySaver,
      notificationTitle: notificationTitle,
      notificationText: notificationText,
      stepsEnabled: stepsEnabled,
      presenter: presenter,
      onPresenterError: onPresenterError,
    );
    try {
      await session.restore(sessionId: id, recoverRunning: true);
      return session;
    } catch (_) {
      // Nothing may keep listening to a session that could not be restored.
      await session.dispose();
      rethrow;
    }
  }

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
    Duration presenterTimeout = const Duration(seconds: 5),
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
    presenterTimeout: presenterTimeout,
  );
}
