/// What the engine is recording; only used to pick the speed ceiling.
enum TrackingSport { running, walking, cycling, other }

/// Whether positions are smoothed before distance is measured.
enum PositionSmoothing {
  /// Raw positions (default).
  off,

  /// A Kalman filter: smoother track, but sharp turns and loops are cut short.
  /// Not validated on devices.
  kalman,
}

/// How steps estimated while GPS is silent (PDR) may become distance.
enum PdrPolicy {
  /// Estimates submitted with `submitPdr` are ignored.
  off,

  /// An estimate is held as PENDING and only counts when the GPS that returns
  /// corroborates it (see [TrackingConfig.pdrConfirmRatio]). Without GPS, or
  /// when the returning GPS shows the phone did not travel, it counts as 0 m.
  /// This is what stops a phone shaken in the hand from becoming distance.
  confirmedByGps,

  /// Like [confirmedByGps], but once the stride has been calibrated on good GPS
  /// the estimate for a gap is credited as ESTIMATED distance even when the gap
  /// is long or the route curves, bounded by the speed ceiling. Opt-in: a phone
  /// shaken in the hand during a gap can then be credited.
  trustedWhenCalibrated,
}

/// Every number here is a STARTING VALUE chosen by reasoning, not measured on
/// devices. Tune from real recordings, never by feel.
class TrackingConfig {
  const TrackingConfig({
    this.maxAccuracyMeters = 40,
    this.maxFixAge = const Duration(seconds: 30),
    this.silenceAfter = const Duration(seconds: 10),
    this.maxSpeedMps = 10,
    this.minStepMeters = 2,
    this.accuracyNoiseFactor = 0.5,
    this.stationarySpeedMps = 0.4,
    this.maxBridge = const Duration(minutes: 2),
    this.reanchorAfterRejects = 3,
    this.rejectMock = true,
    this.pdrPolicy = PdrPolicy.confirmedByGps,
    this.pdrConfirmRatio = 0.5,
    this.smoothing = PositionSmoothing.off,
    this.stopRadiusMeters = 15,
    this.stopMinDwell = const Duration(seconds: 30),
    this.updateInterval = const Duration(seconds: 1),
    this.minDistanceMeters = 0,
    this.initialStrideMeters,
    this.recoveryStepEvidenceAfter = const Duration(seconds: 30),
  });

  /// Speed ceiling per sport (m/s). Running 10 and cycling 25 are the values
  /// the UpRace app uses today.
  factory TrackingConfig.forSport(TrackingSport sport) => TrackingConfig(
    maxSpeedMps: switch (sport) {
      TrackingSport.cycling => 25,
      TrackingSport.running || TrackingSport.walking => 10,
      TrackingSport.other => 12,
    },
    pdrPolicy: sport == TrackingSport.cycling
        ? PdrPolicy.off
        : PdrPolicy.confirmedByGps,
  );

  /// A fix with a worse horizontal accuracy (or an unknown one) is DEGRADED:
  /// it tells the quality state, never distance.
  final double maxAccuracyMeters;

  /// A fix measured longer ago than this when it arrives is dropped.
  final Duration maxFixAge;

  /// No fix for this long = GPS unavailable.
  final Duration silenceAfter;

  /// Implied speed above this between two good fixes is a jump: rejected.
  final double maxSpeedMps;

  /// Displacement below `max(minStepMeters, accuracyNoiseFactor * accuracy)`
  /// is noise: not counted and the anchor does not move, so drift cannot
  /// accumulate step by step.
  final double minStepMeters;
  final double accuracyNoiseFactor;

  /// A Doppler speed below this means "not moving": no distance, whatever the
  /// positions say. Ignored when the platform gives no speed.
  final double stationarySpeedMps;

  /// Longest GPS gap that may be bridged with the straight line between its
  /// ends. A longer one starts over with a fresh anchor.
  final Duration maxBridge;

  /// After this many consecutive jump rejections the engine re-anchors on the
  /// latest good fix (no distance), so one bad anchor cannot block forever.
  final int reanchorAfterRejects;

  final bool rejectMock;

  final PdrPolicy pdrPolicy;

  /// A pending PDR estimate P is confirmed when the straight-line distance
  /// across the gap is at least `pdrConfirmRatio * P`.
  final double pdrConfirmRatio;

  /// Smooth positions with a Kalman filter before measuring distance.
  final PositionSmoothing smoothing;

  /// The phone counts as stopped (moving time pauses) once it stayed within
  /// [stopRadiusMeters] of one point for [stopMinDwell].
  final double stopRadiusMeters;
  final Duration stopMinDwell;

  /// Native request: update interval and minimum displacement.
  final Duration updateInterval;
  final double minDistanceMeters;

  /// Metres per step to start from (for example the stride learned in the
  /// user's last recording). null = none: steps are not turned into a distance
  /// estimate until GPS has taught the engine a stride in this session.
  final double? initialStrideMeters;

  /// With a working step sensor and a pedestrian policy, a GPS gap at least this
  /// long in which the sensor measured NO steps is credited 0 m (the person was
  /// standing). Shorter gaps are not judged: Android may deliver steps ~10 s
  /// late. [Duration.zero] disables the rule.
  final Duration recoveryStepEvidenceAfter;
}
