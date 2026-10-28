import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/src/api/presenter_dispatcher.dart';
import 'package:flutter_tracking_gps/src/api/tracking_presenter.dart';
import 'package:flutter_tracking_gps/src/api/tracking_session.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_snapshot.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';
import 'package:flutter_tracking_gps/src/platform/tracking_platform.dart';

import '../../tool/review_checks.dart' as support;

Future<void> wait([int ms = 5]) => Future<void>.delayed(Duration(milliseconds: ms));

PlatformSnapshot snap({int active = 0, int gaps = 0}) => PlatformSnapshot(
  sessionId: 's',
  state: PlatformState.running,
  segmentId: 0,
  fixCount: 0,
  distance: PlatformDistance(
    quality: GpsQuality.good,
    totalMeters: 0,
    gpsMeters: 0,
    gapBridgeMeters: 0,
    pdrMeters: 0,
    pendingPdrMeters: 0,
    movingTime: Duration.zero,
    isStopped: false,
    activeTime: Duration(seconds: active),
    uncreditedGaps: gaps,
  ),
);

class _Fake extends support.FakePlatform {
  @override
  Future<PlatformSnapshot> stop(String sessionId) async =>
      support.snap(state: PlatformState.stopped);
}

class _StuckPresenter extends TrackingPresenter {
  final gate = Completer<void>();
  int pauses = 0;
  @override
  Future<void> onStart(TrackingSnapshot s) => gate.future;
  @override
  Future<void> onPause(TrackingSnapshot s) async {
    pauses++;
  }
}

/// The recheck report's probes (R04 aside, which live in the engine tests).
void main() {
  test('an activeTime-only change is emitted (R12)', () async {
    final p = _Fake();
    final s = TrackingSession(platform: p);
    final events = <TrackingSnapshot>[];
    final sub = s.updates.listen(events.add);
    await s.start(sessionId: 's');
    await wait();
    events.clear();
    p.controller.add(snap(active: 1));
    await wait();
    expect(events.length, 1);
    await s.dispose();
    await sub.cancel();
    await p.controller.close();
  });

  test('an uncreditedGaps-only change is emitted (R12)', () async {
    final p = _Fake();
    final s = TrackingSession(platform: p);
    final events = <TrackingSnapshot>[];
    final sub = s.updates.listen(events.add);
    await s.start(sessionId: 's');
    await wait();
    p.controller.add(snap(gaps: 0));
    await wait();
    events.clear();
    p.controller.add(snap(gaps: 1));
    await wait();
    expect(events.length, 1);
    await s.dispose();
    await sub.cancel();
    await p.controller.close();
  });

  test('an identical snapshot is not re-emitted', () async {
    final p = _Fake();
    final s = TrackingSession(platform: p);
    final events = <TrackingSnapshot>[];
    final sub = s.updates.listen(events.add);
    await s.start(sessionId: 's');
    p.controller.add(snap(active: 1));
    await wait();
    events.clear();
    p.controller.add(snap(active: 1));
    await wait();
    expect(events, isEmpty);
    await s.dispose();
    await sub.cancel();
    await p.controller.close();
  });

  test('the dispatcher never calls the presenter after close (R13)', () async {
    final p = _StuckPresenter();
    final d = PresenterDispatcher(
      p,
      callTimeout: const Duration(milliseconds: 5),
      onError: (_, _) {},
    );
    d.lifecycle(PresenterEvent.start, const TrackingSnapshot.idle());
    d.lifecycle(PresenterEvent.pause, const TrackingSnapshot.idle());
    await wait(20);
    await d.close(maxWait: const Duration(milliseconds: 1));
    p.gate.complete();
    await wait(20);
    expect(p.pauses, 0);
  });

  test('a TrackingSession records once: start after finish is refused', () async {
    final p = _Fake();
    final s = TrackingSession(platform: p);
    await s.start(sessionId: 's');
    await wait();
    await s.finish();
    await wait();
    expect(
      () => s.start(sessionId: 's'),
      throwsA(isA<TrackingStateError>()),
    );
    await s.dispose();
    await p.controller.close();
  });

  test('a write failure never looks durable', () async {
    final p = _Fake();
    final s = TrackingSession(platform: p);
    await s.start(sessionId: 's');
    await wait();
    p.controller.add(
      const PlatformSnapshot(
        sessionId: 's',
        state: PlatformState.running,
        segmentId: 0,
        fixCount: 0,
        error: 'STORE_WRITE_FAILED: disk full',
      ),
    );
    await wait();
    expect(s.snapshot.isDurable, isFalse);
    await s.dispose();
    await p.controller.close();
  });

  test('journalFailed makes the snapshot not durable even with nothing pending', () async {
    final p = _Fake();
    final s = TrackingSession(platform: p);
    await s.start(sessionId: 's');
    await wait();
    p.controller.add(
      const PlatformSnapshot(
        sessionId: 's',
        state: PlatformState.running,
        segmentId: 0,
        fixCount: 0,
        journalFailed: true,
      ),
    );
    await wait();
    expect(s.snapshot.journalFailed, isTrue);
    expect(s.snapshot.isDurable, isFalse);
    await s.dispose();
    await p.controller.close();
  });
}
