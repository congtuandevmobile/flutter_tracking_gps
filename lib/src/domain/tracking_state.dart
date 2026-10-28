/// The recording lifecycle. Not the same thing as GPS quality or movement.
enum RecordingState {
  idle,
  running,
  paused,

  /// `finish` was asked and is being finalized (live data closed, the exact step
  /// count requested, the journal flushed). NOT final: wait for [finished].
  finishing,
  finished,
}

/// Native health is independent from the last commanded recording state.
enum TrackingFailureState { none, permissionDenied, error }

/// What a restore found in the session's journal.
enum RestoreState {
  /// The snapshot was not produced by a restore.
  notRestored,

  /// Every record was intact.
  complete,

  /// Only the last record was incomplete (a kill mid-write) and was dropped.
  tailRepaired,

  /// A record before the last was damaged: ONLY the part before it was restored,
  /// everything after it was NOT applied (the original file is kept next to it as
  /// `<file>.corrupt`). The result is partial and says so.
  partial,
}

/// State of the native step sensor.
enum StepsStatus {
  /// Not requested (`stepsEnabled` is false) or not started yet.
  off,
  active,

  /// The motion permission is missing: the recording goes on without steps.
  permissionDenied,

  /// The device has no step sensor.
  unavailable,

  /// The sensor reported a runtime or setup error (see `TrackingSnapshot.error`).
  failed,
}

enum GpsQuality {
  /// Nothing known yet (just started / resumed).
  unknown,

  /// Recent good fixes.
  good,

  /// Fixes arrive but too inaccurate to measure distance.
  degraded,

  /// No fix for longer than the silence limit.
  unavailable,

  /// The first good fix after a gap: the gap was just reconciled.
  recovering,
}

/// Where a metre of the total came from. One source per stretch of time.
enum DistanceSource {
  /// Two consecutive good fixes of the same segment.
  gps,

  /// The straight line across a GPS gap, shrunk by its own uncertainty.
  gapBridge,

  /// Step-based estimate confirmed by the GPS that returned.
  pdr,
}

/// What the engine did with a fix.
enum FixVerdict {
  /// First good fix of a segment: becomes the anchor, adds nothing.
  anchor,

  /// Counted as distance.
  accepted,

  /// First good fix after a gap: the gap was reconciled.
  recovered,

  /// Displacement inside the noise radius: not counted, anchor kept.
  noise,

  /// Doppler speed says the phone is not moving: not counted, anchor kept.
  stationary,

  /// Implied speed above the ceiling: rejected, anchor kept.
  jump,

  /// Too inaccurate: tells the quality only.
  lowAccuracy,

  /// Measured too long ago.
  stale,

  /// Not newer than the last fix (duplicate / out of order).
  duplicate,

  /// Belongs to another segment than the running one.
  wrongSegment,

  /// Flagged as simulated by the platform.
  mock,

  /// The engine is not recording.
  notRunning,

  /// The fix belongs to another recording session.
  wrongSession,

  /// A coordinate, accuracy or speed is not a finite, plausible number.
  invalid,

  /// Measured later than the arrival clock allows.
  future,

  /// Too many implausible jumps: a fresh anchor, nothing credited.
  reanchored,
}
