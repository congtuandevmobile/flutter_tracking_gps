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
      isStopped = false;

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
}
