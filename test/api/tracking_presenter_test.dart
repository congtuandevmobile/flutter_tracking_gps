import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

import '../helpers.dart';
import 'tracking_session_test.dart' show FakePlatform, pumpEvents;

/// Records every call so a test can assert the order.
class RecordingPresenter extends TrackingPresenter {
  RecordingPresenter({this.ownsForeground = false, this.failOn});

  final bool ownsForeground;
  final String? failOn;
  final calls = <String>[];
  final snapshots = <TrackingSnapshot>[];

  @override
  bool get ownsAndroidForegroundService => ownsForeground;

  Future<void> _log(String name, TrackingSnapshot s) async {
    calls.add(name);
    snapshots.add(s);
    if (failOn == name) throw StateError('boom in $name');
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

void main() {
  test('a presenter sees start, pause, resume and finish in order', () async {
    final platform = FakePlatform();
    final presenter = RecordingPresenter();
    final session = TrackingSession(platform: platform, presenter: presenter);

    await session.start(sessionId: 's');
    await session.pause();
    await session.resume();
    await session.finish();
    await session.dispose(); // waits for the presenter queue

    expect(
      presenter.calls.where((c) => c != 'update'),
      ['start', 'pause', 'resume', 'finish'],
    );
    expect(presenter.snapshots.first.state, RecordingState.running);
    expect(presenter.snapshots[1].state, RecordingState.paused);
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
    // start() already presented; ten snapshots arrive within one second.
    expect(presenter.calls.where((c) => c == 'update').length, lessThanOrEqualTo(1));
  });

  test('a throwing presenter never breaks the recording', () async {
    final platform = FakePlatform();
    final errors = <Object>[];
    final session = TrackingSession(
      platform: platform,
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
    expect(errors, hasLength(1));
    expect(errors.single, isA<StateError>());
  });

  test('no presenter: Android keeps its own foreground notification', () async {
    final platform = FakePlatform();
    final session = TrackingSession(platform: platform);
    await session.start(sessionId: 's');
    expect(platform.lastForegroundMode, AndroidForegroundMode.builtIn);
    await session.dispose();
  });

  test('a presenter that does not own a service keeps the built-in one', () async {
    final platform = FakePlatform();
    final session = TrackingSession(
      platform: platform,
      presenter: RecordingPresenter(),
    );
    await session.start(sessionId: 's');
    expect(platform.lastForegroundMode, AndroidForegroundMode.builtIn);
    await session.dispose();
  });

  test('a presenter that owns the foreground service replaces the built-in one', () async {
    final platform = FakePlatform();
    final session = TrackingSession(
      platform: platform,
      presenter: RecordingPresenter(ownsForeground: true),
    );
    await session.start(sessionId: 's');
    expect(platform.lastForegroundMode, AndroidForegroundMode.external);
    await session.dispose();
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
}
