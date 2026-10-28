import 'package:flutter_tracking_gps/src/domain/tracking_config.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_fix.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';
import 'package:flutter_tracking_gps/src/platform/tracking_api.g.dart';
import 'package:flutter_tracking_gps/src/platform/tracking_platform.dart';

/// [TrackingPlatform] over the Pigeon channels.
class PigeonTrackingPlatform implements TrackingPlatform {
  PigeonTrackingPlatform({
    TrackingHostApi? api,
    Stream<NativeFix> Function()? fixSource,
    Stream<NativeSnapshot> Function()? snapshotSource,
  }) : _api = api ?? TrackingHostApi(),
       _fixSource = fixSource ?? streamFixes,
       _snapshotSource = snapshotSource ?? streamSnapshots;

  final TrackingHostApi _api;
  final Stream<NativeFix> Function() _fixSource;
  final Stream<NativeSnapshot> Function() _snapshotSource;
  Stream<TrackingFix>? _fixes;
  Stream<PlatformSnapshot>? _snapshots;

  @override
  Stream<TrackingFix> get fixes =>
      _fixes ??= _fixSource().map(_toFix).asBroadcastStream();

  @override
  Stream<PlatformSnapshot> get snapshots =>
      _snapshots ??= _snapshotSource().map(_snapshot).asBroadcastStream();

  @override
  Future<PlatformPermission> permissionStatus() async =>
      _permission(await _api.permissionStatus());

  @override
  Future<PlatformPermission> requestPermission() async =>
      _permission(await _api.requestPermission());

  @override
  Future<PlatformPermission> stepsPermissionStatus() async =>
      _permission(await _api.stepsPermissionStatus());

  @override
  Future<PlatformPermission> requestStepsPermission() async =>
      _permission(await _api.requestStepsPermission());

  @override
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
  }) async => _snapshot(
    await _api.start(
      _startConfig(
        sessionId: sessionId,
        startSeq: startSeq,
        startSegmentId: startSegmentId,
        config: config,
        interval: interval,
        minDistanceMeters: minDistanceMeters,
        adaptiveSampling: adaptiveSampling,
        batterySaver: batterySaver,
        notificationTitle: notificationTitle,
        notificationText: notificationText,
        stepsEnabled: stepsEnabled,
        initialSteps: initialSteps,
      ),
    ),
  );

  @override
  Future<PlatformSnapshot> restoreSession({
    required String sessionId,
    required TrackingConfig config,
    required Duration interval,
    required double minDistanceMeters,
    required bool adaptiveSampling,
    required bool batterySaver,
    required bool stepsEnabled,
    required int initialSteps,
  }) async => _snapshot(
    await _api.restoreSession(
      _startConfig(
        sessionId: sessionId,
        startSeq: 0,
        startSegmentId: 0,
        config: config,
        interval: interval,
        minDistanceMeters: minDistanceMeters,
        adaptiveSampling: adaptiveSampling,
        batterySaver: batterySaver,
        notificationTitle: '',
        notificationText: '',
        stepsEnabled: stepsEnabled,
        initialSteps: initialSteps,
      ),
    ),
  );

  @override
  Future<void> submitPdr(String sessionId, int segmentId, double meters) async =>
      _api.submitPdr(sessionId, segmentId, meters);

  @override
  Future<String> report() async => _api.getReport();

  @override
  Future<PlatformSnapshot> pause(String sessionId) async =>
      _snapshot(await _api.pause(sessionId));

  @override
  Future<PlatformSnapshot> resume(String sessionId) async =>
      _snapshot(await _api.resume(sessionId));

  @override
  Future<PlatformSnapshot> stop(String sessionId) async =>
      _snapshot(await _api.stop(sessionId));

  @override
  Future<PlatformSnapshot> snapshot() async =>
      _snapshot(await _api.getSnapshot());

  @override
  Future<List<TrackingFix>> readPersistedFixes(
    String sessionId,
    int afterSeq,
  ) async => (await _api.readPersistedFixes(sessionId, afterSeq)).map(_toFix).toList();

  @override
  Future<void> clearSession(String sessionId) => _api.clearSession(sessionId);

  @override
  Future<List<String>> listSessions() => _api.listSessions();

  static TrackingFix _toFix(NativeFix f) => TrackingFix(
    sessionId: f.sessionId,
    segmentId: f.segmentId,
    seq: f.seq,
    time: DateTime.fromMillisecondsSinceEpoch(f.timeMs),
    elapsed: Duration(milliseconds: f.elapsedMs),
    latitude: f.latitude,
    longitude: f.longitude,
    horizontalAccuracy: _known(f.horizontalAccuracy),
    speed: _known(f.speed),
    speedAccuracy: _known(f.speedAccuracy),
    course: _known(f.course),
    altitude: f.altitude,
    verticalAccuracy: _known(f.verticalAccuracy),
    provider: f.provider,
    isMock: f.isMock,
  );

  static StartConfig _startConfig({
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
  }) => StartConfig(
    sessionId: sessionId,
    startSeq: startSeq,
    startSegmentId: startSegmentId,
    intervalMs: interval.inMilliseconds,
    minDistanceMeters: minDistanceMeters,
    notificationTitle: notificationTitle,
    notificationText: notificationText,
    adaptiveSampling: adaptiveSampling,
    batterySaver: batterySaver,
    stepsEnabled: stepsEnabled,
    initialSteps: initialSteps,
    engine: EngineConfig(
      maxAccuracyMeters: config.maxAccuracyMeters,
      maxFixAgeMs: config.maxFixAge.inMilliseconds,
      silenceAfterMs: config.silenceAfter.inMilliseconds,
      maxSpeedMps: config.maxSpeedMps,
      minStepMeters: config.minStepMeters,
      accuracyNoiseFactor: config.accuracyNoiseFactor,
      stationarySpeedMps: config.stationarySpeedMps,
      maxBridgeMs: config.maxBridge.inMilliseconds,
      reanchorAfterRejects: config.reanchorAfterRejects,
      rejectMock: config.rejectMock,
      pdrEnabled: config.pdrPolicy != PdrPolicy.off,
      pdrTrustedWhenCalibrated:
          config.pdrPolicy == PdrPolicy.trustedWhenCalibrated,
      recoveryStepEvidenceAfterMs: config.recoveryStepEvidenceAfter.inMilliseconds,
      pdrConfirmRatio: config.pdrConfirmRatio,
      kalman: config.smoothing == PositionSmoothing.kalman,
      stopRadiusMeters: config.stopRadiusMeters,
      stopMinDwellMs: config.stopMinDwell.inMilliseconds,
      initialStrideMeters: config.initialStrideMeters ?? -1,
    ),
  );

  /// The platform uses a negative number for "unknown".
  static double? _known(double v) => v.isNaN || v < 0 ? null : v;

  static PlatformPermission _permission(NativePermission p) => switch (p) {
    NativePermission.granted => PlatformPermission.granted,
    NativePermission.denied => PlatformPermission.denied,
    NativePermission.restricted => PlatformPermission.restricted,
    NativePermission.notDetermined => PlatformPermission.notDetermined,
    NativePermission.servicesDisabled => PlatformPermission.servicesDisabled,
  };

  static PlatformSnapshot _snapshot(NativeSnapshot s) => PlatformSnapshot(
    sessionId: s.sessionId,
    state: switch (s.state) {
      NativeTrackingState.idle => PlatformState.idle,
      NativeTrackingState.running => PlatformState.running,
      NativeTrackingState.paused => PlatformState.paused,
      NativeTrackingState.finishing => PlatformState.finishing,
      NativeTrackingState.stopped => PlatformState.stopped,
      NativeTrackingState.permissionDenied => PlatformState.permissionDenied,
      NativeTrackingState.error => PlatformState.error,
    },
    segmentId: s.segmentId,
    fixCount: s.fixCount,
    distance: _distance(s.distance),
    stepsStatus: switch (s.stepsStatus) {
      NativeStepsStatus.off => StepsStatus.off,
      NativeStepsStatus.active => StepsStatus.active,
      NativeStepsStatus.permissionDenied => StepsStatus.permissionDenied,
      NativeStepsStatus.unavailable => StepsStatus.unavailable,
      NativeStepsStatus.failed => StepsStatus.failed,
    },
    stepsFinal: s.stepsFinal,
    journalPending: s.journalPending,
    error: s.error,
  );

  static PlatformDistance _distance(NativeDistance d) => PlatformDistance(
    quality: switch (d.quality) {
      NativeGpsQuality.unknown => GpsQuality.unknown,
      NativeGpsQuality.good => GpsQuality.good,
      NativeGpsQuality.degraded => GpsQuality.degraded,
      NativeGpsQuality.unavailable => GpsQuality.unavailable,
      NativeGpsQuality.recovering => GpsQuality.recovering,
    },
    totalMeters: d.totalMeters,
    gpsMeters: d.gpsMeters,
    gapBridgeMeters: d.gapBridgeMeters,
    pdrMeters: d.pdrMeters,
    pendingPdrMeters: d.pendingPdrMeters,
    movingTime: Duration(milliseconds: d.movingTimeMs),
    isStopped: d.isStopped,
    speedMps: _known(d.speedMps),
    lastAcceptedFixAt: d.lastAcceptedFixAtMs < 0
        ? null
        : DateTime.fromMillisecondsSinceEpoch(d.lastAcceptedFixAtMs),
    steps: d.steps,
    strideMeters: _known(d.strideMeters),
    activeTime: Duration(milliseconds: d.activeTimeMs),
    bridgedGap: Duration(milliseconds: d.bridgedGapMs),
    uncreditedGap: Duration(milliseconds: d.uncreditedGapMs),
    uncreditedGaps: d.uncreditedGaps,
    ambiguousSteps: d.ambiguousSteps,
  );
}
