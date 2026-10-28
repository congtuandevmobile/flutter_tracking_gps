/// One raw location reading. `null` means the platform did not know the value.
class TrackingFix {
  const TrackingFix({
    required this.sessionId,
    required this.segmentId,
    required this.seq,
    required this.time,
    required this.elapsed,
    required this.latitude,
    required this.longitude,
    this.horizontalAccuracy,
    this.speed,
    this.speedAccuracy,
    this.course,
    this.altitude,
    this.verticalAccuracy,
    this.provider = 'unknown',
    this.isMock = false,
  });

  final String sessionId;
  final int segmentId;
  final int seq;

  /// When the position was measured (not when it was delivered).
  final DateTime time;

  /// Monotonic time since boot at the measurement.
  final Duration elapsed;

  final double latitude;
  final double longitude;
  final double? horizontalAccuracy;

  /// Doppler speed, m/s.
  final double? speed;
  final double? speedAccuracy;
  final double? course;
  final double? altitude;
  final double? verticalAccuracy;
  final String provider;
  final bool isMock;
}
