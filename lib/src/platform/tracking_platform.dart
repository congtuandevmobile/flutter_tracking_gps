import 'package:flutter_tracking_gps/src/domain/tracking_config.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_fix.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';

enum PlatformPermission {
  granted,
  denied,
  restricted,
  notDetermined,
  servicesDisabled,
}

enum PlatformState {
  idle,
  running,
  paused,
  finishing,
  stopped,
  permissionDenied,
  error,
}

/// What the native distance engine has decided.
class PlatformDistance {
  const PlatformDistance({
    required this.quality,
    required this.totalMeters,
    required this.gpsMeters,
    required this.gapBridgeMeters,
    required this.pdrMeters,
    required this.pendingPdrMeters,
    required this.movingTime,
    required this.isStopped,
    this.speedMps,
    this.lastAcceptedFixAt,
    this.steps = 0,
    this.strideMeters,
    this.activeTime = Duration.zero,
    this.bridgedGap = Duration.zero,
    this.uncreditedGap = Duration.zero,
    this.uncreditedGaps = 0,
    this.ambiguousSteps = 0,
    this.distanceEligibleSteps = 0,
  });

  const PlatformDistance.empty()
    : quality = GpsQuality.unknown,
      totalMeters = 0,
      gpsMeters = 0,
      gapBridgeMeters = 0,
      pdrMeters = 0,
      pendingPdrMeters = 0,
      movingTime = Duration.zero,
      isStopped = false,
      speedMps = null,
      lastAcceptedFixAt = null,
      steps = 0,
      strideMeters = null,
      activeTime = Duration.zero,
      bridgedGap = Duration.zero,
      uncreditedGap = Duration.zero,
      uncreditedGaps = 0,
      ambiguousSteps = 0,
      distanceEligibleSteps = 0;

  final GpsQuality quality;
  final double totalMeters;
  final double gpsMeters;
  final double gapBridgeMeters;
  final double pdrMeters;
  final double pendingPdrMeters;
  final Duration movingTime;
  final bool isStopped;
  final double? speedMps;
  final DateTime? lastAcceptedFixAt;

  /// Accepted steps of the session (zero without a step sensor).
  final int steps;

  /// Learned or configured metres per step; null while unknown.
  final double? strideMeters;
  final Duration activeTime;
  final Duration bridgedGap;
  final Duration uncreditedGap;
  final int uncreditedGaps;
  final int ambiguousSteps;
  final int distanceEligibleSteps;
}

class PlatformSnapshot {
  const PlatformSnapshot({
    required this.state,
    required this.segmentId,
    required this.fixCount,
    this.distance = const PlatformDistance.empty(),
    this.stepsStatus = StepsStatus.off,
    this.stepsFinal = false,
    this.journalPending = 0,
    this.journalFailed = false,
    this.restoreState = RestoreState.notRestored,
    this.engineChanged = false,
    this.sessionId,
    this.error,
  });

  final String? sessionId;
  final PlatformState state;
  final int segmentId;
  final int fixCount;
  final PlatformDistance distance;
  final StepsStatus stepsStatus;
  final bool stepsFinal;

  /// Events accepted but not on disk yet (see `TrackingSnapshot.isDurable`).
  final int journalPending;

  /// A journal write or sync failed (sticky): the lost events are never durable.
  final bool journalFailed;
  final RestoreState restoreState;
  final bool engineChanged;
  final String? error;
}

/// The native side, in domain terms. The real one talks to Pigeon; tests use a
/// fake. Native owns the session AND the distance engine.
abstract interface class TrackingPlatform {
  /// Raw fixes (for drawing a route); the distance does not need them.
  Stream<TrackingFix> get fixes;

  /// The engine state, about once per second and on every change.
  Stream<PlatformSnapshot> get snapshots;

  Future<PlatformPermission> permissionStatus();
  Future<PlatformPermission> requestPermission();

  /// Motion permission of the step sensor (`ACTIVITY_RECOGNITION` / Motion &
  /// Fitness); granted when the device needs none.
  Future<PlatformPermission> stepsPermissionStatus();
  Future<PlatformPermission> requestStepsPermission();

  Future<PlatformSnapshot> start({
    required String sessionId,
    required int startSeq,
    required int startSegmentId,
    required TrackingConfig config,
    required Duration interval,
    required double minDistanceMeters,
    required bool adaptiveSampling,
    required bool batterySaver,
    required String notificationTitle,
    required String notificationText,
    required bool stepsEnabled,
    required int initialSteps,
  });

  /// Rebuilds the native engine of [sessionId] from the persisted fixes after a
  /// process death. The session comes back paused.
  Future<PlatformSnapshot> restoreSession({
    required String sessionId,
    required TrackingConfig config,
    required Duration interval,
    required double minDistanceMeters,
    required bool adaptiveSampling,
    required bool batterySaver,
    required bool stepsEnabled,
    required int initialSteps,
  });

  Future<PlatformSnapshot> pause(String sessionId);
  Future<PlatformSnapshot> resume(String sessionId);
  Future<PlatformSnapshot> stop(String sessionId);
  Future<PlatformSnapshot> snapshot();

  /// A step-based estimate for a stretch without GPS (held as pending).
  Future<void> submitPdr(String sessionId, int segmentId, double meters);

  /// Coordinate-free timeline of what the native engine decided.
  Future<String> report();

  /// Fixes persisted natively with `seq > afterSeq`, oldest first.
  Future<List<TrackingFix>> readPersistedFixes(String sessionId, int afterSeq);

  Future<void> clearSession(String sessionId);

  /// The ids of the sessions persisted on this device (recovery after a kill).
  Future<List<String>> listSessions();

  /// Android: replaces the text of the SDK's foreground notification. No-op on iOS.
  Future<void> updateNotification(String title, String text);
}
