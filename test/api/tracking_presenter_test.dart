import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

import '../helpers.dart';
import 'tracking_session_test.dart' show FakePlatform, pumpEvents;

/// Records every call so a test can assert the order; can be slow or throw.
class RecordingPresenter extends TrackingPresenter {
  RecordingPresenter({this.failOn, this.delay = Duration.zero, this.hangOn});

  /// Completing it ends a hung call.
  final hang = Completer<void>();
  var running = 0;
  var maxRunning = 0;
  final String? failOn;
  final Duration delay;
  final String? hangOn;
  final calls = <String>[];
  final snapshots = <TrackingSnapshot>[];

  Future<void> _log(String name, TrackingSnapshot s) async {
    calls.add(name);
    snapshots.add(s);
    running++;
    if (running > maxRunning) maxRunning = running;
    try {
      if (hangOn == name) await hang.future;
      if (delay > Duration.zero) await Future<void>.delayed(delay);
      if (failOn == name) throw StateError('boom in $name');
    } finally {
      running--;
    }
  }

  @override
  Future<void> onStart(TrackingSnapshot s) => _log('start', s);
  @override
  Future<void> onUpdate(TrackingSnapshot s) => _log('update', s);
  @override
  Future<void> onPause(TrackingSnapshot s) => _log('pause', s);
  @override
  Future<void> onResume(TrackingSnapshot s) => _log('resume', s);
  @override
  Future<void> onFinish(TrackingSnapshot s) => _log('finish', s);
}

TrackingSnapshot _snap(double meters) => TrackingSnapshot(
  state: RecordingState.running,
  quality: GpsQuality.good,
  totalMeters: meters,
  gpsMeters: meters,
  gapBridgeMeters: 0,
  pdrMeters: 0,
  pendingPdrMeters: 0,
  segmentId: 0,
  fixCount: 0,
);

void main() {
  group('session and presenter', () {
    test('a presenter sees start, pause, resume and finish in order', () async {
      final presenter = RecordingPresenter();
      final session = TrackingSession(
        platform: FakePlatform(),
        presenter: presenter,
      );
      await session.start(sessionId: 's');
      await session.pause();
      await session.resume();
      await session.finish();
      await session.dispose();
      expect(presenter.calls.where((c) => c != 'update'), [
        'start',
        'pause',
        'resume',
        'finish',
      ]);
      expect(presenter.snapshots.last.state, RecordingState.finished);
    });

    test('native snapshots reach the presenter as throttled updates', () async {
      final platform = FakePlatform();
      final presenter = RecordingPresenter();
      final session = TrackingSession(platform: platform, presenter: presenter);
      await session.start(sessionId: 's');
      for (var i = 0; i < 10; i++) {
        platform.push(fixAt(i, i.toDouble(), i * 3.0, speed: 3));
      }
      await pumpEvents();
      await session.dispose();
      expect(
        presenter.calls.where((c) => c == 'update').length,
        lessThanOrEqualTo(1),
      );
    });

    test('a throwing presenter never breaks the recording', () async {
      final errors = <Object>[];
      final session = TrackingSession(
        platform: FakePlatform(),
        presenter: RecordingPresenter(failOn: 'pause'),
        onPresenterError: (e, _) => errors.add(e),
      );
      await session.start(sessionId: 's');
      await session.pause();
      expect(session.snapshot.state, RecordingState.paused);
      await session.resume();
      final result = await session.finish();
      await session.dispose();
      expect(result.state, RecordingState.finished);
      expect(errors.single, isA<StateError>());
    });

    test('a hung presenter cannot make dispose hang', () async {
      final session = TrackingSession(
        platform: FakePlatform(),
        presenter: RecordingPresenter(hangOn: 'start'),
        presenterTimeout: const Duration(milliseconds: 50),
        onPresenterError: (_, _) {},
      );
      await session.start(sessionId: 's');
      await session.finish();
      await session.dispose().timeout(const Duration(seconds: 2));
    });

    test('stepsEnabled is passed to native and off by default', () async {
      final platform = FakePlatform();
      final off = TrackingSession(platform: platform);
      await off.start(sessionId: 'a');
      expect(platform.lastStepsEnabled, false);
      await off.finish();
      await off.dispose();
      final on = TrackingSession(platform: platform, stepsEnabled: true);
      await on.start(sessionId: 'b');
      expect(platform.lastStepsEnabled, true);
      await on.dispose();
    });
  });

  group('PresenterDispatcher', () {
    test('updates are coalesced: only the newest of a burst is delivered', () async {
      final presenter = RecordingPresenter(delay: const Duration(milliseconds: 30));
      final d = PresenterDispatcher(presenter);
      d.update(_snap(1)); // starts delivering at once
      for (var i = 2; i <= 50; i++) {
        d.update(_snap(i.toDouble()));
      }
      await d.close();
      expect(presenter.calls.length, 2, reason: 'the first, then only the newest');
      expect(presenter.snapshots.last.totalMeters, 50);
    });

    test('lifecycle events are never dropped or reordered, even when slow', () async {
      final presenter = RecordingPresenter(delay: const Duration(milliseconds: 10));
      final d = PresenterDispatcher(presenter)
        ..lifecycle(PresenterEvent.start, _snap(0))
        ..lifecycle(PresenterEvent.pause, _snap(1))
        ..lifecycle(PresenterEvent.resume, _snap(2))
        ..lifecycle(PresenterEvent.finish, _snap(3));
      await d.close();
      expect(presenter.calls, ['start', 'pause', 'resume', 'finish']);
    });

    test('a pending update is discarded once finish is queued', () async {
      final presenter = RecordingPresenter(delay: const Duration(milliseconds: 30));
      final d = PresenterDispatcher(presenter)
        ..update(_snap(1))
        ..update(_snap(2)) // pending behind the first
        ..lifecycle(PresenterEvent.finish, _snap(3))
        ..update(_snap(4)); // refused: the recording is over
      await d.close();
      expect(presenter.calls, ['update', 'finish']);
    });

    test('a hung call is reported and never overlapped; the rest follows once it ends', () async {
      final errors = <Object>[];
      final presenter = RecordingPresenter(hangOn: 'start');
      final d = PresenterDispatcher(
        presenter,
        onError: (e, _) => errors.add(e),
        callTimeout: const Duration(milliseconds: 40),
      )
        ..lifecycle(PresenterEvent.start, _snap(0))
        ..lifecycle(PresenterEvent.finish, _snap(1));
      await Future<void>.delayed(const Duration(milliseconds: 150));
      expect(errors.single, isA<TimeoutException>());
      expect(presenter.calls, ['start'], reason: 'finish waits: no second call while the first is still running');
      expect(presenter.maxRunning, 1);
      presenter.hang.complete(); // the adapter finally answers
      await d.close(maxWait: const Duration(seconds: 2));
      expect(presenter.calls, ['start', 'finish']);
      expect(presenter.maxRunning, 1, reason: 'never two adapter calls at once');
    });

    test('an error handler that throws cannot poison the queue', () async {
      final presenter = RecordingPresenter(failOn: 'pause');
      final d = PresenterDispatcher(presenter, onError: (_, _) => throw StateError('handler'))
        ..lifecycle(PresenterEvent.pause, _snap(0))
        ..lifecycle(PresenterEvent.resume, _snap(1));
      await d.close();
      expect(presenter.calls, ['pause', 'resume']);
    });

    test('the queue is bounded: overflow drops the oldest and says so', () async {
      final errors = <Object>[];
      final presenter = RecordingPresenter(hangOn: 'start');
      final d = PresenterDispatcher(
        presenter,
        onError: (e, _) => errors.add(e),
        callTimeout: const Duration(milliseconds: 200),
        maxQueued: 3,
      )..lifecycle(PresenterEvent.start, _snap(0)); // hangs
      await Future<void>.delayed(const Duration(milliseconds: 10));
      for (var i = 0; i < 6; i++) {
        d.lifecycle(i.isEven ? PresenterEvent.pause : PresenterEvent.resume, _snap(i.toDouble()));
      }
      expect(errors.whereType<StateError>(), isNotEmpty);
      presenter.hang.complete();
      await d.close(maxWait: const Duration(seconds: 3));
    });
  });
}
