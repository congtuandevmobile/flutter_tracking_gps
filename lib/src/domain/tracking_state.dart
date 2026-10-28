/// The recording lifecycle. Not the same thing as GPS quality or movement.
enum RecordingState { idle, running, paused, finished }

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
