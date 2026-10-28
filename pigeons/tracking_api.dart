import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartPackageName: 'flutter_tracking_gps',
    dartOut: 'lib/src/platform/tracking_api.g.dart',
    kotlinOut:
        'android/src/main/kotlin/com/uprace/flutter_tracking_gps/TrackingApi.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.uprace.flutter_tracking_gps'),
    swiftOut: 'ios/Classes/TrackingApi.g.swift',
  ),
)

enum NativePermission {
  granted,
  denied,

  /// iOS only: restricted by parental controls / MDM.
  restricted,

  /// The user was not asked yet.
  notDetermined,

  /// Location services are switched off on the device.
  servicesDisabled,
}

enum NativeTrackingState {
  idle,
  running,
  paused,

  /// `stop` was asked and is being finalized (live data closed, the exact step
  /// count requested, the journal flushed). Not final yet: wait for [stopped].
  finishing,
  stopped,

  /// Location permission is missing, so no fix can arrive.
  permissionDenied,

  /// The native layer failed to start (see [NativeSnapshot.error]).
  error,
}

/// Whether the native step sensor is feeding the engine.
enum NativeStepsStatus {
  /// Not requested (`StartConfig.stepsEnabled` is false) or not started yet.
  off,
  active,

  /// Motion permission (`ACTIVITY_RECOGNITION` on Android, Motion & Fitness on
  /// iOS) is missing: the recording goes on without steps.
  permissionDenied,

  /// The device has no step sensor.
  unavailable,

  /// The sensor reported a runtime error (see `NativeSnapshot.error`).
  failed,
}

/// What a restore found in the session's journal.
enum NativeRestoreState {
  /// The snapshot was not produced by a restore.
  notRestored,

  /// Every record was intact.
  complete,

  /// Only the last record was incomplete (a kill mid-write) and was dropped.
  tailRepaired,

  /// A record before the last was damaged: ONLY the part before it was restored;
  /// everything after it was NOT applied (the original file is kept as
  /// `<file>.corrupt`). The result is partial and says so.
  partial,
}

/// One raw location reading, exactly as the platform produced it. No filter,
/// no smoothing, no odometer: the Dart engine decides what counts as distance.
class NativeFix {
  NativeFix({
    required this.sessionId,
    required this.segmentId,
    required this.seq,
    required this.timeMs,
    required this.elapsedMs,
    required this.latitude,
    required this.longitude,
    required this.horizontalAccuracy,
    required this.speed,
    required this.speedAccuracy,
    required this.course,
    required this.altitude,
    required this.verticalAccuracy,
    required this.provider,
    required this.isMock,
  });

  String sessionId;

  /// 0 for the first segment, +1 on every resume. The engine never bridges
  /// two segments.
  int segmentId;

  /// Increases by one per fix within a session; used to detect holes after a
  /// restart and to replay persisted fixes.
  int seq;

  /// Time the position was measured, epoch ms (not the delivery time).
  int timeMs;

  /// Monotonic clock (ms since boot) at the measurement; immune to wall-clock
  /// jumps. Falls back to the delivery time where the platform gives none.
  int elapsedMs;

  double latitude;
  double longitude;

  /// 1-sigma horizontal accuracy in metres; negative = unknown.
  double horizontalAccuracy;

  /// Doppler speed in m/s; negative = unknown.
  double speed;

  /// Accuracy of [speed] in m/s; negative = unknown.
  double speedAccuracy;

  /// Degrees from true north; negative = unknown.
  double course;

  /// Metres; [verticalAccuracy] negative = unknown.
  double altitude;
  double verticalAccuracy;

  /// `gps`, `fused`, `network`, `passive`, `unknown`.
  String provider;

  /// The platform flags the fix as simulated by software.
  bool isMock;
}

/// How the native engine decides what counts as distance. Mirrors
/// `TrackingConfig`; every number is an unmeasured starting value.
class EngineConfig {
  EngineConfig({
    required this.maxAccuracyMeters,
    required this.maxFixAgeMs,
    required this.silenceAfterMs,
    required this.maxSpeedMps,
    required this.minStepMeters,
    required this.accuracyNoiseFactor,
    required this.stationarySpeedMps,
    required this.maxBridgeMs,
    required this.reanchorAfterRejects,
    required this.rejectMock,
    required this.pdrEnabled,
    required this.pdrConfirmRatio,
    required this.kalman,
    required this.stopRadiusMeters,
    required this.stopMinDwellMs,
    required this.initialStrideMeters,
    required this.pdrTrustedWhenCalibrated,
    required this.recoveryStepEvidenceAfterMs,
  });

  double maxAccuracyMeters;
  int maxFixAgeMs;
  int silenceAfterMs;
  double maxSpeedMps;
  double minStepMeters;
  double accuracyNoiseFactor;
  double stationarySpeedMps;
  int maxBridgeMs;
  int reanchorAfterRejects;
  bool rejectMock;

  /// Hold step estimates as pending until the returning GPS confirms them.
  bool pdrEnabled;
  double pdrConfirmRatio;

  /// Smooth positions with a Kalman filter before measuring distance.
  bool kalman;
  double stopRadiusMeters;
  int stopMinDwellMs;

  /// Metres per step to start from; zero or negative = none. Without a stride a
  /// step count is not turned into a distance estimate until GPS taught one.
  double initialStrideMeters;

  /// Credit a calibrated step estimate for a long or curved gap as ESTIMATED
  /// distance (opt-in). See `PdrPolicy.trustedWhenCalibrated`.
  bool pdrTrustedWhenCalibrated;

  /// A GPS gap at least this long in which the step sensor measured no steps is
  /// credited 0 m; zero or negative disables the rule.
  int recoveryStepEvidenceAfterMs;
}

class StartConfig {
  StartConfig({
    required this.sessionId,
    required this.startSeq,
    required this.startSegmentId,
    required this.intervalMs,
    required this.minDistanceMeters,
    required this.notificationTitle,
    required this.notificationText,
    required this.engine,
    required this.adaptiveSampling,
    required this.batterySaver,
    required this.stepsEnabled,
    required this.initialSteps,
  });

  /// Count steps natively (Android `TYPE_STEP_COUNTER`, iOS `CMPedometer`) and
  /// let the engine turn them into a pending estimate while GPS is weak. Without
  /// the motion permission the recording goes on and `NativeSnapshot.stepsStatus`
  /// says so.
  bool stepsEnabled;

  /// Session steps to restore after a process death (the device counter is never
  /// added to it); 0 for a new session.
  int initialSteps;

  /// How the native engine measures distance.
  EngineConfig engine;

  /// Let the native engine change the update interval / minimum displacement
  /// with the speed (the silence limit follows it). [intervalMs] and
  /// [minDistanceMeters] are then only the starting request.
  bool adaptiveSampling;

  /// Sparser and less accurate sampling while standing still.
  bool batterySaver;

  /// Chosen by the caller; every fix carries it so late fixes of an older
  /// session can be dropped.
  String sessionId;

  /// First [NativeFix.seq] / [NativeFix.segmentId] to use: 0 for a new session,
  /// the next values for one restored after a process death.
  int startSeq;
  int startSegmentId;

  /// Requested update interval; the OS may deliver faster or slower.
  int intervalMs;

  /// Minimum displacement between updates (iOS `distanceFilter`, Android
  /// `minUpdateDistanceMeters`); 0 = none.
  double minDistanceMeters;

  /// Android foreground-service notification (ignored on iOS).
  String notificationTitle;
  String notificationText;
}

enum NativeGpsQuality { unknown, good, degraded, unavailable, recovering }

/// What the native distance engine has decided so far.
class NativeDistance {
  NativeDistance({
    required this.quality,
    required this.totalMeters,
    required this.gpsMeters,
    required this.gapBridgeMeters,
    required this.pdrMeters,
    required this.pendingPdrMeters,
    required this.movingTimeMs,
    required this.isStopped,
    required this.speedMps,
    required this.lastAcceptedFixAtMs,
    required this.steps,
    required this.strideMeters,
    required this.activeTimeMs,
    required this.bridgedGapMs,
    required this.uncreditedGapMs,
    required this.uncreditedGaps,
    required this.ambiguousSteps,
    required this.distanceEligibleSteps,
  });

  NativeGpsQuality quality;

  /// Accepted distance. Never decreases while recording.
  double totalMeters;
  double gpsMeters;
  double gapBridgeMeters;
  double pdrMeters;

  /// Step estimates waiting for GPS to confirm them: NOT part of [totalMeters].
  double pendingPdrMeters;
  int movingTimeMs;
  bool isStopped;

  /// Doppler speed of the latest accepted fix; negative = unknown.
  double speedMps;

  /// Wall-clock time (epoch ms) of the latest accepted fix; negative = none yet.
  /// Estimated from the monotonic measurement time at snapshot time.
  int lastAcceptedFixAtMs;

  /// Accepted steps of the session (zero without a step sensor).
  int steps;

  /// Learned or configured metres per step; negative = unknown.
  double strideMeters;

  /// Workout time: the running legs only, pauses excluded.
  int activeTimeMs;

  /// Time of GPS gaps that were credited (bridge or estimate).
  int bridgedGapMs;

  /// Time of GPS gaps and unmeasured intervals that were NOT credited: reported,
  /// never hidden.
  int uncreditedGapMs;
  int uncreditedGaps;

  /// Steps measured in a batch that straddles a GPS-quality change: kept in the
  /// total and in the journal, used for no estimate.
  int ambiguousSteps;

  /// Steps that taught the stride or became a pending estimate. `steps` is what
  /// the platform reported for the active segments; this is the part that was
  /// ELIGIBLE to be used for distance.
  int distanceEligibleSteps;
}

class NativeSnapshot {
  NativeSnapshot({
    required this.state,
    required this.segmentId,
    required this.fixCount,
    required this.updatedAtMs,
    required this.distance,
    required this.stepsStatus,
    required this.stepsFinal,
    required this.journalPending,
    required this.journalFailed,
    required this.restoreState,
    required this.engineChanged,
    this.sessionId,
    this.lastFixTimeMs,
    this.error,
  });

  /// State of the native step sensor.
  NativeStepsStatus stepsStatus;

  /// What the last restore found; [NativeRestoreState.notRestored] otherwise.
  NativeRestoreState restoreState;

  /// The journal was recorded with other engine rules than this code's: the
  /// restored numbers may differ from what was shown live.
  bool engineChanged;

  /// Events accepted by the engine that are NOT on disk yet (queued for the
  /// journal writer). Zero means everything shown here is durable; a crash while
  /// this is above zero can lose that many of the latest events.
  int journalPending;

  /// A journal write or sync FAILED (or its barrier timed out). Sticky: once true,
  /// what this snapshot shows is NOT guaranteed to survive a crash, whatever
  /// [journalPending] says, and the recording must be treated as not durable.
  bool journalFailed;

  /// True when the step total is final: after `stop`, the platform's exact count
  /// for the closed segment was obtained (or there is no step sensor). False
  /// means the total is the live count, which may still be corrected.
  bool stepsFinal;

  String? sessionId;
  NativeTrackingState state;
  int segmentId;

  /// Distance, quality and moving time decided by the native engine.
  NativeDistance distance;

  /// Fixes emitted in this session so far.
  int fixCount;
  int? lastFixTimeMs;
  int updatedAtMs;
  String? error;
}

@HostApi()
abstract class TrackingHostApi {
  NativePermission permissionStatus();

  /// Motion permission for the step sensor: `ACTIVITY_RECOGNITION` (Android 10+)
  /// or Motion & Fitness (iOS). [NativePermission.granted] when the device needs
  /// none.
  NativePermission stepsPermissionStatus();

  /// Asks for the motion permission while the app is in the foreground.
  @async
  NativePermission requestStepsPermission();

  /// Asks for location permission while the app is in the foreground (the
  /// "when in use" level is enough to record a workout). Resolves with the
  /// resulting status.
  @async
  NativePermission requestPermission();

  /// Starts a session and its first segment. Replaces a running session.
  @async
  NativeSnapshot start(StartConfig config);

  /// Stops location updates and closes the segment; the session stays open.
  @async
  NativeSnapshot pause(String sessionId);

  /// Opens a new segment ([NativeSnapshot.segmentId] + 1).
  @async
  NativeSnapshot resume(String sessionId);

  /// Ends the session. Persisted fixes stay until [clearSession].
  @async
  NativeSnapshot stop(String sessionId);

  NativeSnapshot getSnapshot();

  /// A step-based estimate for a stretch without GPS. Held as pending; it only
  /// counts if the GPS that returns confirms it. Ignored while GPS is good.
  ///
  /// [segmentId] is the segment the estimate belongs to: one of another segment
  /// (a late estimate after a resume) is refused.
  void submitPdr(String sessionId, int segmentId, double meters);

  /// Rebuilds the engine of [config]`.sessionId` after a process death by
  /// replaying the persisted fixes (segments are never joined). If the engine
  /// already runs this session, nothing is replayed. The session comes back
  /// PAUSED: [resume] continues it in a new segment.
  @async
  NativeSnapshot restoreSession(StartConfig config);

  /// Coordinate-free timeline of what the native engine decided.
  String getReport();

  /// Persisted fixes of [sessionId] with `seq > afterSeq`, oldest first.
  /// Used to rebuild the distance after the Flutter engine or the process
  /// died while native kept recording.
  @async
  List<NativeFix> readPersistedFixes(String sessionId, int afterSeq);

  /// Deletes the persisted journal of [sessionId] (it holds coordinates). Waits for
  /// the writer first, so nothing re-creates the file afterwards.
  @async
  void clearSession(String sessionId);

  /// Android only: replaces the text of the SDK's foreground notification while a
  /// session records. The SDK keeps the channel, the tap action and the lifecycle;
  /// a presenter that wants a richer notification calls this instead of posting its
  /// own with the SDK's id. A no-op on iOS and when no session records.
  void updateNotification(String title, String text);

  /// The ids of the sessions persisted on this device (for recovery after a kill).
  @async
  List<String> listSessions();
}

@EventChannelApi()
abstract class TrackingEvents {
  NativeFix streamFixes();

  /// The engine state, about once per second and on every change.
  NativeSnapshot streamSnapshots();
}
