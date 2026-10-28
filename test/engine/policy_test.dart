import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

import '../helpers.dart';

/// Mirrors tracking-core's PolicyTest.kt (the distance rules): the two engines must agree.
GpsDistanceEngine _engine([TrackingConfig config = const TrackingConfig()]) =>
    GpsDistanceEngine(config: config)..start('s', t0);

/// A steady 3 m/s run with 2 steps/s: teaches ~1.5 m per step.
int _run(GpsDistanceEngine e, int seconds) {
  var seq = 0;
  for (var t = 0; t <= seconds; t++) {
    e.onFix(fixAt(seq++, t.toDouble(), t * 3.0, speed: 3), at(t.toDouble()));
    if (t > 0) {
      e.onSteps(
        at(t.toDouble()),
        2,
        segmentId: 0,
        from: at(t - 1.0),
        to: at(t.toDouble()),
        deliveredAt: at(t.toDouble()),
      );
    }
  }
  return seq;
}

GpsDistanceEngine _gap({required bool sensor, TrackingConfig? config}) {
  final e = _engine(config ?? const TrackingConfig(initialStrideMeters: 1))
    ..setStepSensorActive(active: sensor)
    ..onFix(fixAt(0, 0, 0, speed: 3), at(0))
    ..onTick(at(15));
  return e;
}

void main() {
  group('step evidence in a gap', () {
    test('a long gap with no steps at all is standing still', () {
      final e = _gap(sensor: true)..onFix(fixAt(1, 40, 60, speed: 3), at(40));
      expect(e.totalMeters, 0);
    });

    test('a long gap with steps is bridged', () {
      final e = _gap(sensor: true)
        ..onSteps(at(36), 30, segmentId: 0, from: at(20), to: at(35), deliveredAt: at(36))
        ..onFix(fixAt(1, 40, 60, speed: 3), at(40));
      expect(e.totalMeters, closeTo(50, 1));
    });

    test('without a working sensor silence is not evidence', () {
      final e = _gap(sensor: false)..onFix(fixAt(1, 40, 60, speed: 3), at(40));
      expect(e.totalMeters, closeTo(50, 1));
    });

    test('a short gap is not judged', () {
      final e = _gap(sensor: true)..onFix(fixAt(1, 20, 60, speed: 3), at(20));
      expect(e.totalMeters, closeTo(50, 1));
    });

    test('cycling never needs step evidence', () {
      final e = _gap(
        sensor: true,
        config: const TrackingConfig(pdrPolicy: PdrPolicy.off),
      )..onFix(fixAt(1, 40, 60, speed: 3), at(40));
      expect(e.totalMeters, closeTo(50, 1));
    });
  });

  group('step intervals', () {
    GpsDistanceEngine good() {
      final e = _engine(const TrackingConfig(initialStrideMeters: 1));
      for (var t = 0; t <= 10; t++) {
        e.onFix(fixAt(t, t.toDouble(), t * 3.0, speed: 3), at(t.toDouble()));
      }
      return e;
    }

    test('a batch measured wholly in good GPS never becomes pending', () {
      final e = good()
        ..onSteps(at(9), 12, segmentId: 0, from: at(2), to: at(8), deliveredAt: at(9));
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('a batch measured wholly in a weak interval becomes pending', () {
      final e = good()
        ..onFix(fixAt(11, 12, 33, accuracy: 90), at(12))
        ..onSteps(at(15), 6, segmentId: 0, from: at(11), to: at(14), deliveredAt: at(15));
      expect(e.snapshot.pendingPdrMeters, closeTo(6, 1e-9));
    });

    test('a batch straddling a quality change is used for nothing', () {
      final e = good()
        ..onFix(fixAt(11, 12, 33, accuracy: 90), at(12))
        ..onSteps(at(14), 10, segmentId: 0, from: at(8), to: at(13), deliveredAt: at(14));
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('a weak batch delivered after GPS came back is too late to attach', () {
      final e = good()
        ..onFix(fixAt(11, 12, 33, accuracy: 90), at(12))
        ..onFix(fixAt(12, 16, 48, speed: 3), at(16))
        ..onSteps(at(20), 6, segmentId: 0, from: at(11), to: at(14), deliveredAt: at(20));
      expect(e.snapshot.pendingPdrMeters, 0);
    });
  });

  group('stationary policy', () {
    test('jitter after a stop is not distance; movement is credited in full', () {
      final e = _engine()..onFix(fixAt(0, 0, 0), at(0));
      for (var t = 1; t <= 40; t++) {
        e.onFix(fixAt(t, t.toDouble(), t.isEven ? 0.5 : -0.5), at(t.toDouble()));
      }
      expect(e.totalMeters, 0);
      expect(e.onFix(fixAt(41, 41, 3), at(41)).verdict, FixVerdict.stationary);
      expect(e.totalMeters, 0);
      e.onFix(fixAt(42, 43, 20), at(43));
      expect(e.totalMeters, closeTo(20, 0.5));
    });

    test('unknown speed falls back to the displacement speed', () {
      final e = _engine()..onFix(fixAt(0, 0, 0), at(0));
      expect(e.onFix(fixAt(1, 8, 3), at(8)).verdict, FixVerdict.stationary);
    });
  });

  group('trusted policy', () {
    test('a calibrated stride credits a long gap as estimated distance', () {
      final e = _engine(const TrackingConfig(pdrPolicy: PdrPolicy.trustedWhenCalibrated));
      final seq = _run(e, 40);
      e
        ..setStepSensorActive(active: true)
        ..onTick(at(55))
        ..onSteps(at(191), 150, segmentId: 0, from: at(60), to: at(190), deliveredAt: at(191))
        ..onFix(fixAt(seq, 200, 140, speed: 3), at(200));
      expect(e.ledger.meters(DistanceSource.pdr), greaterThan(150));
    });

    test('the conservative policy credits nothing for the same gap', () {
      final e = _engine();
      final seq = _run(e, 40);
      e
        ..setStepSensorActive(active: true)
        ..onTick(at(55))
        ..onSteps(at(191), 150, segmentId: 0, from: at(60), to: at(190), deliveredAt: at(191));
      final before = e.totalMeters;
      e.onFix(fixAt(seq, 200, 140, speed: 3), at(200));
      expect(e.totalMeters, before);
    });

    test('a configured stride alone is not calibration', () {
      final e = _engine(
        const TrackingConfig(
          pdrPolicy: PdrPolicy.trustedWhenCalibrated,
          initialStrideMeters: 1,
        ),
      )
        ..onFix(fixAt(0, 0, 0, speed: 3), at(0))
        ..onTick(at(15))
        ..onSteps(at(151), 150, segmentId: 0, from: at(20), to: at(150), deliveredAt: at(151));
      final before = e.totalMeters;
      e.onFix(fixAt(1, 200, 20, speed: 3), at(200));
      expect(e.totalMeters, before);
    });
  });

  group('weak interval identity (report R04)', () {
    test('a late batch of an old weak interval never funds a later gap', () {
      final e = _engine(const TrackingConfig(initialStrideMeters: 1))
        ..onFix(fixAt(0, 0, 0, speed: 3), at(0))
        ..onFix(fixAt(1, 10, 30, speed: 3), at(10))
        ..onFix(fixAt(2, 12, 33, accuracy: 90), at(12))
        ..onFix(fixAt(3, 16, 48, speed: 3), at(16))
        ..onTick(at(27))
        ..onSteps(at(28), 30, segmentId: 0, from: at(11), to: at(14), deliveredAt: at(28));
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('the step evidence of an old gap does not defeat the next gap\'s no-step rule', () {
      final e = _engine(const TrackingConfig(initialStrideMeters: 1))
        ..setStepSensorActive(active: true)
        ..onFix(fixAt(0, 0, 0, speed: 3), at(0))
        ..onFix(fixAt(1, 10, 30, speed: 3), at(10))
        ..onFix(fixAt(2, 12, 33, accuracy: 90), at(12))
        ..onSteps(at(15), 6, segmentId: 0, from: at(11), to: at(14), deliveredAt: at(15))
        ..onFix(fixAt(3, 16, 48, speed: 3), at(16));
      final before = e.totalMeters;
      e
        ..onTick(at(27))
        ..onFix(fixAt(4, 60, 98, speed: 3), at(60));
      expect(e.totalMeters, before);
    });
  });
}
