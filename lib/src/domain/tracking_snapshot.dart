import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';

/// What the app shows. Immutable.
class TrackingSnapshot {
  const TrackingSnapshot({
    required this.state,
    required this.quality,
    required this.totalMeters,
    required this.gpsMeters,
    required this.gapBridgeMeters,
    required this.pdrMeters,
    required this.pendingPdrMeters,
    required this.segmentId,
    required this.fixCount,
    this.sessionId,
    this.speedMps,
    this.lastFixAt,
    this.movingTime = Duration.zero,
    this.isStopped = false,
    this.steps = 0,
    this.strideMeters,
    this.stepsStatus = StepsStatus.off,
    this.failureState = TrackingFailureState.none,
    this.error,
    this.stepsFinal = false,
    this.journalPending = 0,
    this.journalFailed = false,
    this.restoreState = RestoreState.notRestored,
    this.engineChanged = false,
    this.elapsedTime = Duration.zero,
    this.recoveredGap = Duration.zero,
    this.clockAnomaly = false,
    this.collection = CollectionState.notCollecting,
    this.distanceEligibleSteps = 0,
    this.activeTime = Duration.zero,
    this.bridgedGap = Duration.zero,
    this.uncreditedGap = Duration.zero,
    this.uncreditedGaps = 0,
    this.ambiguousSteps = 0,
    this.pedalCadenceRpm,
    this.cadenceStatus = PedalCadenceStatus.off,
    this.cadenceSource = CadenceSource.none,
  });

  const TrackingSnapshot.idle()
    : state = RecordingState.idle,
      quality = GpsQuality.unknown,
      totalMeters = 0,
      gpsMeters = 0,
      gapBridgeMeters = 0,
      pdrMeters = 0,
      pendingPdrMeters = 0,
      segmentId = 0,
      fixCount = 0,
      sessionId = null,
      speedMps = null,
      lastFixAt = null,
      movingTime = Duration.zero,
      isStopped = false,
      steps = 0,
      strideMeters = null,
      stepsStatus = StepsStatus.off,
      failureState = TrackingFailureState.none,
      error = null,
      stepsFinal = false,
      journalPending = 0,
      journalFailed = false,
      restoreState = RestoreState.notRestored,
      engineChanged = false,
      elapsedTime = Duration.zero,
      recoveredGap = Duration.zero,
      clockAnomaly = false,
      collection = CollectionState.notCollecting,
      distanceEligibleSteps = 0,
      activeTime = Duration.zero,
      bridgedGap = Duration.zero,
      uncreditedGap = Duration.zero,
      uncreditedGaps = 0,
      ambiguousSteps = 0,
      pedalCadenceRpm = null,
      cadenceStatus = PedalCadenceStatus.off,
      cadenceSource = CadenceSource.none;

  final String? sessionId;
  final RecordingState state;
  final GpsQuality quality;

  /// Accepted distance. Never decreases while recording.
  final double totalMeters;
  final double gpsMeters;
  final double gapBridgeMeters;
  final double pdrMeters;

  /// Step estimates waiting for GPS to confirm them: NOT part of [totalMeters].
  final double pendingPdrMeters;
  final int segmentId;
  final int fixCount;

  /// Doppler speed of the latest accepted fix, when known.
  final double? speedMps;
  final DateTime? lastFixAt;

  /// Time spent moving (not standing at one place for 30 s or more), computed by
  /// the native engine. Zero when the Dart reference engine produced this.
  final Duration movingTime;

  /// The phone has stayed in one place long enough to count as stopped.
  final bool isStopped;

  /// Accepted steps of the session (native step sensor). Zero when the Dart
  /// reference engine produced this.
  final int steps;

  /// Learned (or configured) metres per step; null while unknown.
  final double? strideMeters;

  /// Pedalling cadence in revolutions per minute from the best available source ([cadenceSource]); null = unknown.
  ///  - A healthy external sensor that the host feeds (`TrackingSession.pushCscMeasurement` / `pushExternalCadence`)
  ///    wins: it is a MEASUREMENT, and 0 means the cranks are still (not "no data").
  ///  - Otherwise the phone accelerometer estimate (`createSession(pedalCadence: true)`), an ESTIMATE that only means
  ///    something with the phone on the leg (pocket, thigh), never on the handlebar, and not validated on a real bike.
  ///  - Otherwise null (no source, warming up, no clear rhythm, a paused session).
  /// Live only: not journaled, no effect on distance, null again after a restore until new data arrives.
  final double? pedalCadenceRpm;

  /// State of the native accelerometer feeding the phone estimate (not about an external sensor).
  final PedalCadenceStatus cadenceStatus;

  /// Which source [pedalCadenceRpm] came from.
  final CadenceSource cadenceSource;

  /// State of the native step sensor (off unless `stepsEnabled` was asked for).
  final StepsStatus stepsStatus;

  /// Check this before treating a running recording as operational.
  final TrackingFailureState failureState;
  final String? error;
  bool get hasNativeFailure => failureState != TrackingFailureState.none;

  /// True when the step total is final: after `finish`, the platform's exact
  /// count was obtained (or there is no step sensor). False after a finish means
  /// it is the live count (see [error] for why).
  final bool stepsFinal;

  /// Events the engine accepted that are NOT on disk yet. Zero means everything
  /// shown here is durable; a crash while this is above zero can lose that many
  /// of the latest events.
  final int journalPending;

  /// A journal write or sync FAILED (sticky for the session): those events are
  /// not on disk and never will be, even though [journalPending] may look small.
  final bool journalFailed;

  /// Everything this snapshot shows has reached the disk: nothing pending AND
  /// no write ever failed.
  bool get isDurable => journalPending == 0 && !journalFailed;

  /// What the last restore found. [RestoreState.partial] means ONLY the part
  /// before a damaged record was restored: the numbers here are incomplete.
  final RestoreState restoreState;

  /// Start to finish, PAUSES INCLUDED: it keeps running until the session finishes ([activeTime] is the workout
  /// clock without the pauses).
  final Duration elapsedTime;

  /// Calendar time credited after the process died and came back (summed). The person's movement in it is
  /// unknown, so no distance was invented for it; the silence is part of [uncreditedGap].
  final Duration recoveredGap;

  /// The calendar clock went backwards across a recovery: that gap could not be measured and counted as zero.
  final bool clockAnomaly;

  /// Whether location is actually being collected now. After a recovery this can be
  /// [CollectionState.blocked] while the clock is back: do not present such a recording as measuring.
  final CollectionState collection;

  /// The journal was recorded with other engine rules than this code's: the
  /// restored numbers may differ from what was shown live.
  final bool engineChanged;

  /// Steps that taught the stride or became a pending estimate. [steps] is what
  /// the platform reported for the active segments; this is the part that was
  /// ELIGIBLE to be used for distance (the rest is kept but used for nothing).
  final int distanceEligibleSteps;

  /// Workout time: the running legs only, pauses excluded. The headline timer.
  final Duration activeTime;

  /// Time of GPS gaps that were credited (a bridge or an estimate).
  final Duration bridgedGap;

  /// TIME (not a distance) of GPS gaps and unmeasured intervals (a weak start, a
  /// gap still open at the end) that were NOT credited. It is part of
  /// [activeTime]. The SDK does not know how far the person went in it: the
  /// distance is unknown, not zero and not "lost".
  final Duration uncreditedGap;
  final int uncreditedGaps;

  /// Steps measured in a batch that straddles a GPS-quality change: used for nothing.
  final int ambiguousSteps;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is TrackingSnapshot &&
          other.sessionId == sessionId &&
          other.state == state &&
          other.quality == quality &&
          other.totalMeters == totalMeters &&
          other.gpsMeters == gpsMeters &&
          other.gapBridgeMeters == gapBridgeMeters &&
          other.pdrMeters == pdrMeters &&
          other.pendingPdrMeters == pendingPdrMeters &&
          other.segmentId == segmentId &&
          other.fixCount == fixCount &&
          other.speedMps == speedMps &&
          other.lastFixAt == lastFixAt &&
          other.movingTime == movingTime &&
          other.isStopped == isStopped &&
          other.steps == steps &&
          other.strideMeters == strideMeters &&
          other.stepsStatus == stepsStatus &&
          other.failureState == failureState &&
          other.error == error &&
          other.stepsFinal == stepsFinal &&
          other.journalPending == journalPending &&
          other.journalFailed == journalFailed &&
          other.restoreState == restoreState &&
          other.engineChanged == engineChanged &&
          other.elapsedTime == elapsedTime &&
          other.recoveredGap == recoveredGap &&
          other.clockAnomaly == clockAnomaly &&
          other.collection == collection &&
          other.distanceEligibleSteps == distanceEligibleSteps &&
          other.activeTime == activeTime &&
          other.bridgedGap == bridgedGap &&
          other.uncreditedGap == uncreditedGap &&
          other.uncreditedGaps == uncreditedGaps &&
          other.ambiguousSteps == ambiguousSteps &&
          other.pedalCadenceRpm == pedalCadenceRpm &&
          other.cadenceStatus == cadenceStatus &&
          other.cadenceSource == cadenceSource;

  @override
  int get hashCode => Object.hashAll([
        sessionId,
        state,
        quality,
        totalMeters,
        gpsMeters,
        gapBridgeMeters,
        pdrMeters,
        pendingPdrMeters,
        segmentId,
        fixCount,
        speedMps,
        lastFixAt,
        movingTime,
        isStopped,
        steps,
        strideMeters,
        stepsStatus,
        failureState,
        error,
        stepsFinal,
        journalPending,
        journalFailed,
        restoreState,
        engineChanged,
        elapsedTime,
        recoveredGap,
        clockAnomaly,
        collection,
        distanceEligibleSteps,
        activeTime,
        bridgedGap,
        uncreditedGap,
        uncreditedGaps,
        ambiguousSteps,
        pedalCadenceRpm,
        cadenceStatus,
        cadenceSource,
      ]);
}
