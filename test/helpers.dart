import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

final t0 = DateTime(2026, 1, 1, 10);

/// Metres of latitude per degree (close enough for tests).
const _metersPerDegree = 111195.0;

/// A fix [northMeters] north of the origin, [seconds] after [t0].
TrackingFix fixAt(
  int seq,
  double seconds,
  double northMeters, {
  int segment = 0,
  double accuracy = 5,
  double? speed,
  bool mock = false,
  String session = 's',
}) => TrackingFix(
  sessionId: session,
  segmentId: segment,
  seq: seq,
  time: t0.add(Duration(milliseconds: (seconds * 1000).round())),
  elapsed: Duration(milliseconds: (seconds * 1000).round()),
  latitude: 10 + northMeters / _metersPerDegree,
  longitude: 106,
  horizontalAccuracy: accuracy,
  speed: speed,
  isMock: mock,
);

DateTime at(double seconds) =>
    t0.add(Duration(milliseconds: (seconds * 1000).round()));
