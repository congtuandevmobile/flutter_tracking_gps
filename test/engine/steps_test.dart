import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';
import 'package:flutter_tracking_gps/src/engine/stride_estimator.dart';

import '../helpers.dart';

/// Mirrors tracking-core's StepsTest.kt: the two engines must agree.
GpsDistanceEngine _engine([TrackingConfig config = const TrackingConfig()]) =>
    GpsDistanceEngine(config: config)..start('s', t0);

/// A steady 3 m/s, 2 steps/s run: teaches the engine ~1.5 m per step.
int _run(GpsDistanceEngine e, int from, int seconds) {
  var seq = from;
  for (var t = from; t <= from + seconds; t++) {
    e.onFix(fixAt(seq++, t.toDouble(), t * 3.0, speed: 3), at(t.toDouble()));
    if (t > from) e.onSteps(at(t.toDouble()), 2, segmentId: 0);
  }
  return seq;
}

void main() {
  group('StrideEstimator', () {
    test('needs both steps and metres before learning', () {
      final s = StrideEstimator()..addSteps(50);
      expect(s.strideMeters, isNull);
      s.addMeters(10);
      expect(s.strideMeters, isNull);
      s.addMeters(40);
      expect(s.strideMeters, closeTo(1.0, 1e-9));
    });

    test('standing or shaking never teaches a stride', () {
      final s = StrideEstimator()
        ..addSteps(500)
        ..addMeters(30)
        ..addSteps(10);
      expect(s.strideMeters, isNull);
    });

    test('an implausible stride is discarded', () {
      final s = StrideEstimator()
        ..addSteps(100)
        ..addMeters(20);
      expect(s.strideMeters, isNull);
      s
        ..addSteps(10)
        ..addMeters(100);
      expect(s.strideMeters, isNull);
    });

    test('moving average; breakWindow keeps the stride', () {
      final s = StrideEstimator()
        ..addSteps(100)
        ..addMeters(100)
        ..addSteps(100)
        ..addMeters(150);
      expect(s.strideMeters, closeTo(1.15, 1e-9));
      s
        ..addSteps(10)
        ..breakWindow()
        ..addMeters(100);
      expect(s.strideMeters, closeTo(1.15, 1e-9));
    });
  });

  group('GpsDistanceEngine steps', () {
    test('stride is learned from good GPS; steps are not distance then', () {
      final e = _engine();
      _run(e, 0, 30);
      expect(e.snapshot.strideMeters, closeTo(1.5, 0.2));
      expect(e.snapshot.pendingPdrMeters, 0);
      expect(e.totalMeters, closeTo(90, 1));
      expect(e.ledger.meters(DistanceSource.pdr), 0);
    });

    test('without a stride, weak-GPS steps estimate nothing', () {
      final e = _engine()
        ..onFix(fixAt(0, 0, 0, speed: 3), at(0))
        ..onTick(at(15))
        ..onSteps(at(20), 200, segmentId: 0);
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('steps in a GPS gap become pending and are confirmed once', () {
      final e = _engine();
      final seq = _run(e, 0, 30);
      e.onTick(at(45));
      expect(e.quality, GpsQuality.unavailable);
      e.onSteps(at(50), 40, segmentId: 0);
      expect(e.snapshot.pendingPdrMeters, closeTo(60, 12));
      final before = e.totalMeters;
      e.onFix(fixAt(seq, 50, 150, speed: 3), at(50));
      final credited = e.totalMeters - before;
      expect(credited, inInclusiveRange(45, 60.5));
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('shaking without GPS after learning still adds nothing', () {
      final e = _engine();
      final seq = _run(e, 0, 30);
      e
        ..onTick(at(45))
        ..onSteps(at(60), 300, segmentId: 0);
      final before = e.totalMeters;
      e.onFix(fixAt(seq, 70, 91, speed: 0.1), at(70));
      expect(e.totalMeters - before, lessThan(1));
    });

    test('an estimate is capped by the corroborated straight line', () {
      final e = _engine();
      final seq = _run(e, 0, 30);
      e
        ..onTick(at(45))
        ..onSteps(at(55), 133, segmentId: 0);
      final before = e.totalMeters;
      e.onFix(fixAt(seq, 56, 130, speed: 3), at(56));
      expect(e.totalMeters - before, inInclusiveRange(55, 60.5));
    });

    test('steps of another segment or while paused are refused', () {
      final e = _engine();
      _run(e, 0, 30);
      e
        ..pause(at(31))
        ..onSteps(at(32), 50, segmentId: 0)
        ..resume(at(40), segmentId: 1)
        ..onTick(at(60))
        ..onSteps(at(61), 50, segmentId: 0);
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('an initial stride estimates a weak start immediately', () {
      final e = _engine(const TrackingConfig(initialStrideMeters: 1))
        ..onFix(fixAt(0, 0, 0, speed: 3), at(0))
        ..onTick(at(15))
        ..onSteps(at(20), 30, segmentId: 0);
      expect(e.snapshot.pendingPdrMeters, closeTo(30, 1e-9));
    });
  });
}
