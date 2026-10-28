import 'package:flutter_tracking_gps/src/engine/geo.dart';

/// Dart twin of `tracker.algorithms.motion.StopDetector` (tracking-core).
///
/// Online stay-point detection: the phone is STOPPED once it stayed within
/// [radiusMeters] of one point AND showed no sign of motion for at least
/// [minDwell], without a hole in the observations longer than [maxObservationGap].
class StopDetector {
  StopDetector({
    this.radiusMeters = 15,
    this.minDwell = const Duration(seconds: 30),
    this.stationarySpeedMps = 0.4,
    this.maxObservationGap = const Duration(seconds: 10),
  });

  final double radiusMeters;
  final Duration minDwell;
  final double stationarySpeedMps;
  final Duration maxObservationGap;

  double _anchorLat = 0;
  double _anchorLon = 0;
  DateTime? _anchorAt;
  DateTime? _latestAt;
  bool isStopped = false;

  bool push(DateTime at, double lat, double lon, double? speedMps) {
    final latest = _latestAt;
    if (latest != null && !at.isAfter(latest)) return isStopped;
    if (_anchorAt == null || at.difference(latest!) > maxObservationGap) {
      _start(at, lat, lon);
      return false;
    }
    _latestAt = at;
    final inside =
        haversineMeters(_anchorLat, _anchorLon, lat, lon) <= radiusMeters;
    final speed = (speedMps != null && speedMps.isFinite && speedMps >= 0)
        ? speedMps
        : null;
    final moving = speed != null && speed >= stationarySpeedMps;
    if (inside && !moving) {
      isStopped = at.difference(_anchorAt!) >= minDwell;
    } else {
      _start(at, lat, lon);
    }
    return isStopped;
  }

  void reset() {
    _anchorAt = null;
    _latestAt = null;
    isStopped = false;
  }

  void _start(DateTime at, double lat, double lon) {
    _anchorLat = lat;
    _anchorLon = lon;
    _anchorAt = at;
    _latestAt = at;
    isStopped = false;
  }
}
