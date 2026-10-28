import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

import '../helpers.dart';

GpsDistanceEngine newEngine({TrackingConfig config = const TrackingConfig()}) {
  final e = GpsDistanceEngine(config: config)..start('s', t0);
  return e;
}

void main() {
  group('good GPS', () {
    test('first fix anchors, later fixes add the distance between them', () {
      final e = newEngine();
      expect(e.onFix(fixAt(0, 0, 0), at(0)).verdict, FixVerdict.anchor);
      final r = e.onFix(fixAt(1, 1, 3, speed: 3), at(1));
      expect(r.verdict, FixVerdict.accepted);
      expect(e.totalMeters, closeTo(3, 0.05));
      e.onFix(fixAt(2, 2, 7, speed: 4), at(2));
      expect(e.totalMeters, closeTo(7, 0.1));
      expect(e.ledger.meters(DistanceSource.gps), closeTo(7, 0.1));
    });

    test('Doppler speed under the limit means no distance, anchor kept', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0, speed: 0), at(0));
      final r = e.onFix(fixAt(1, 1, 6, speed: 0.1), at(1));
      expect(r.verdict, FixVerdict.stationary);
      expect(e.totalMeters, 0);
    });

    test('drift inside the noise radius is not counted and cannot accumulate', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0, accuracy: 10), at(0));
      // 1.5 m steps with accuracy 10 m: noise radius 5 m.
      for (var i = 1; i <= 3; i++) {
        e.onFix(fixAt(i, i.toDouble(), i * 1.5, accuracy: 10), at(i.toDouble()));
      }
      expect(e.totalMeters, 0);
    });

    test('an impossible jump is rejected and the anchor does not move', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      final r = e.onFix(fixAt(1, 1, 500, speed: 3), at(1));
      expect(r.verdict, FixVerdict.jump);
      expect(e.totalMeters, 0);
      // The next normal fix is measured from the original anchor.
      e.onFix(fixAt(2, 2, 6, speed: 3), at(2));
      expect(e.totalMeters, closeTo(6, 0.1));
    });

    test('repeated jumps re-anchor without adding distance', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      for (var i = 1; i <= 3; i++) {
        e.onFix(fixAt(i, i.toDouble(), 500 + i * 3.0, speed: 3), at(i.toDouble()));
      }
      expect(e.totalMeters, 0);
      e.onFix(fixAt(4, 4, 515, speed: 3), at(4));
      expect(e.totalMeters, greaterThan(0));
    });

    test('duplicate, stale, mock and wrong-segment fixes are dropped', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      expect(e.onFix(fixAt(0, 1, 5), at(1)).verdict, FixVerdict.duplicate);
      expect(e.onFix(fixAt(1, 1, 5, mock: true), at(1)).verdict, FixVerdict.mock);
      expect(e.onFix(fixAt(2, 2, 5, segment: 3), at(2)).verdict, FixVerdict.wrongSegment);
      expect(e.onFix(fixAt(3, 3, 5), at(100)).verdict, FixVerdict.stale);
    });

    test('a poor fix tells the quality and never the distance', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      final r = e.onFix(fixAt(1, 1, 50, accuracy: 80), at(1));
      expect(r.verdict, FixVerdict.lowAccuracy);
      expect(e.quality, GpsQuality.degraded);
      expect(e.totalMeters, 0);
    });
  });

  group('GPS gap', () {
    test('silence marks GPS unavailable', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      expect(e.onTick(at(5)), GpsQuality.good);
      expect(e.onTick(at(11)), GpsQuality.unavailable);
    });

    test('the straight line across the gap is credited once, minus its uncertainty', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0, accuracy: 5), at(0));
      e.onTick(at(15));
      final r = e.onFix(fixAt(1, 20, 100, accuracy: 5), at(20));
      expect(r.verdict, FixVerdict.recovered);
      expect(e.totalMeters, closeTo(90, 0.5)); // 100 - (5 + 5)
      expect(e.ledger.meters(DistanceSource.gapBridge), closeTo(90, 0.5));
      expect(e.quality, GpsQuality.recovering);
    });

    test('an implausible gap is not bridged', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      e.onTick(at(15));
      e.onFix(fixAt(1, 20, 5000), at(20)); // 250 m/s
      expect(e.totalMeters, 0);
    });

    test('a gap longer than the bridge limit starts over', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      e.onTick(at(15));
      e.onFix(fixAt(1, 300, 400), at(300));
      expect(e.totalMeters, 0);
    });
  });

  group('step estimates (PDR)', () {
    test('a shaken phone: estimate with no GPS to confirm it adds 0 m', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      e.onTick(at(15));
      e.submitPdr(at(30), 130);
      expect(e.snapshot.pendingPdrMeters, 130);
      expect(e.totalMeters, 0); // pending is not distance
      // GPS returns at the same place: the phone did not travel.
      e.onFix(fixAt(1, 100, 1), at(100));
      expect(e.totalMeters, 0);
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('a real run through a tunnel: the straight line confirms the estimate', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      e.onTick(at(15));
      e.submitPdr(at(40), 100);
      // 100 m estimated, 90 m straight line (winding route): confirmed.
      final r = e.onFix(fixAt(1, 60, 90), at(60));
      expect(r.source, DistanceSource.pdr);
      expect(e.totalMeters, closeTo(100, 0.5));
    });

    test('the gap is never credited twice (estimate and line are not added)', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      e.onTick(at(15));
      e.submitPdr(at(40), 100);
      e.onFix(fixAt(1, 60, 100), at(60));
      expect(e.totalMeters, lessThanOrEqualTo(100.5));
    });

    test('an estimate is ignored while GPS is good', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      e.submitPdr(at(1), 50);
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('with the policy off nothing is held', () {
      final e = newEngine(config: const TrackingConfig(pdrPolicy: PdrPolicy.off))
        ..onFix(fixAt(0, 0, 0), at(0));
      e.onTick(at(15));
      e.submitPdr(at(30), 50);
      expect(e.snapshot.pendingPdrMeters, 0);
    });
  });

  group('lifecycle', () {
    test('nothing is bridged across a pause', () {
      final e = newEngine()
        ..onFix(fixAt(0, 0, 0), at(0))
        ..onFix(fixAt(1, 1, 5, speed: 4), at(1));
      final before = e.totalMeters;
      e.pause(at(2));
      e.resume(at(600), segmentId: 1);
      // Far away after the pause: first fix only anchors.
      final r = e.onFix(fixAt(0, 601, 5000, segment: 1), at(601));
      expect(r.verdict, FixVerdict.anchor);
      expect(e.totalMeters, before);
    });

    test('a pause drops a pending estimate', () {
      final e = newEngine()..onFix(fixAt(0, 0, 0), at(0));
      e.onTick(at(15));
      e.submitPdr(at(20), 80);
      e.pause(at(21));
      expect(e.snapshot.pendingPdrMeters, 0);
    });

    test('after finish nothing changes', () {
      final e = newEngine()
        ..onFix(fixAt(0, 0, 0), at(0))
        ..onFix(fixAt(1, 1, 5, speed: 4), at(1));
      e.finish(at(2));
      final total = e.totalMeters;
      expect(e.onFix(fixAt(2, 3, 50, speed: 4), at(3)).verdict, FixVerdict.notRunning);
      expect(e.totalMeters, total);
    });

    test('the total never decreases', () {
      final e = newEngine();
      var last = 0.0;
      e.onFix(fixAt(0, 0, 0), at(0));
      for (var i = 1; i < 40; i++) {
        e.onFix(
          fixAt(i, i.toDouble(), (i * 4 + (i.isEven ? 30 : -30)).toDouble(), speed: i % 5 == 0 ? 0 : 3),
          at(i.toDouble()),
        );
        expect(e.totalMeters, greaterThanOrEqualTo(last));
        last = e.totalMeters;
      }
    });
  });
}
