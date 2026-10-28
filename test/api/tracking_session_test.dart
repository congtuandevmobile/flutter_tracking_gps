import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

import '../helpers.dart';

/// Stands in for the native side: it owns a Dart reference engine and publishes
/// snapshots like the real one does.
class FakePlatform implements TrackingPlatform {
  final fixController = StreamController<TrackingFix>.broadcast();
  final snapshotController = StreamController<PlatformSnapshot>.broadcast();
  PlatformPermission permission = PlatformPermission.granted;
  PlatformState state = PlatformState.idle;
  List<TrackingFix> persisted = [];
  final calls = <String>[];
  bool? lastStepsEnabled;
  late GpsDistanceEngine engine;
  String? sessionId;
  int segment = 0;

  @override
  Stream<TrackingFix> get fixes => fixController.stream;

  @override
  Stream<PlatformSnapshot> get snapshots => snapshotController.stream;

  @override
  Future<PlatformPermission> permissionStatus() async => permission;

  @override
  Future<PlatformPermission> requestPermission() async => permission;

  @override
  Future<PlatformPermission> stepsPermissionStatus() async => permission;

  @override
  Future<PlatformPermission> requestStepsPermission() async => permission;

  PlatformSnapshot _snap() {
    final s = engine.snapshot;
    return PlatformSnapshot(
      sessionId: sessionId,
      state: state,
      segmentId: segment,
      fixCount: s.fixCount,
      distance: PlatformDistance(
        quality: s.quality,
        totalMeters: s.totalMeters,
        gpsMeters: s.gpsMeters,
        gapBridgeMeters: s.gapBridgeMeters,
        pdrMeters: s.pdrMeters,
        pendingPdrMeters: s.pendingPdrMeters,
        movingTime: Duration.zero,
        isStopped: false,
        speedMps: s.speedMps,
      ),
    );
  }

  /// A fix arrives natively: the engine decides, then a snapshot is published.
  void push(TrackingFix fix) {
    engine.onFix(fix, fix.time, replay: true);
    snapshotController.add(_snap());
  }

  @override
  Future<PlatformSnapshot> start({
    required String sessionId,
    required int startSeq,
    required int startSegmentId,
    required TrackingConfig config,
    required Duration interval,
    required double minDistanceMeters,
    required bool adaptiveSampling,
    required bool batterySaver,
    required String notificationTitle,
    required String notificationText,
    required bool stepsEnabled,
    required int initialSteps,
  }) async {
    calls.add('start');
    lastStepsEnabled = stepsEnabled;
    this.sessionId = sessionId;
    segment = startSegmentId;
    engine = GpsDistanceEngine(config: config)..start(sessionId, t0);
    state = PlatformState.running;
    return _snap();
  }

  @override
  Future<PlatformSnapshot> restoreSession({
    required String sessionId,
    required TrackingConfig config,
    required Duration interval,
    required double minDistanceMeters,
    required bool adaptiveSampling,
    required bool batterySaver,
    required bool stepsEnabled,
    required int initialSteps,
  }) async {
    calls.add('restore');
    this.sessionId = sessionId;
    engine = GpsDistanceEngine(config: config)
      ..start(sessionId, persisted.isEmpty ? t0 : persisted.first.time);
    var current = engine.segmentId;
    for (final fix in persisted) {
      if (fix.segmentId != current) {
        engine
          ..pause(fix.time)
          ..resume(fix.time, segmentId: fix.segmentId);
        current = fix.segmentId;
      }
      engine.onFix(fix, fix.time, replay: true);
    }
    segment = current;
    engine.pause(t0);
    state = PlatformState.paused;
    return _snap();
  }

  @override
  Future<PlatformSnapshot> pause(String sessionId) async {
    calls.add('pause');
    engine.pause(t0);
    state = PlatformState.paused;
    return _snap();
  }

  @override
  Future<PlatformSnapshot> resume(String sessionId) async {
    calls.add('resume');
    segment += 1;
    engine.resume(t0, segmentId: segment);
    state = PlatformState.running;
    return _snap();
  }

  @override
  Future<PlatformSnapshot> stop(String sessionId) async {
    calls.add('stop');
    engine.finish(t0);
    state = PlatformState.stopped;
    return _snap();
  }

  @override
  Future<PlatformSnapshot> snapshot() async => _snap();

  @override
  Future<void> submitPdr(String sessionId, int segmentId, double meters) async {
    calls.add('pdr');
    engine.submitPdr(t0, meters, segmentId: segmentId);
    snapshotController.add(_snap());
  }

  @override
  Future<String> report() async => engine.log.report();

  @override
  Future<List<TrackingFix>> readPersistedFixes(String sessionId, int afterSeq) async =>
      persisted.where((f) => f.seq > afterSeq).toList();

  @override
  Future<void> clearSession(String sessionId) async => calls.add('clear');

  @override
  Future<List<String>> listSessions() async => [?sessionId];

  @override
  Future<void> updateNotification(String title, String text) async =>
      calls.add('notify:$title');
}

Future<void> pumpEvents() => Future<void>.delayed(Duration.zero);

void main() {
  test('start, record, pause, resume (new segment), finish', () async {
    final platform = FakePlatform();
    final session = TrackingSession(platform: platform);

    await session.start(sessionId: 's');
    platform.push(fixAt(0, 0, 0));
    platform.push(fixAt(1, 1, 5, speed: 4));
    await pumpEvents();
    expect(session.snapshot.totalMeters, closeTo(5, 0.1));
    expect(session.snapshot.quality, GpsQuality.good);

    await session.pause();
    expect(session.snapshot.state, RecordingState.paused);
    await session.resume();
    expect(session.snapshot.segmentId, 1);

    final result = await session.finish();
    expect(result.state, RecordingState.finished);
    expect(platform.calls, ['start', 'pause', 'resume', 'stop']);
    await session.dispose();
  });

  test('start without permission fails with a clear error', () async {
    final platform = FakePlatform()..permission = PlatformPermission.denied;
    final session = TrackingSession(platform: platform);
    expect(() => session.start(sessionId: 's'), throwsA(isA<TrackingStateError>()));
  });

  test('a second start while a recording is open is refused', () async {
    final session = TrackingSession(platform: FakePlatform());
    await session.start(sessionId: 's');
    expect(() => session.start(sessionId: 'again'), throwsA(isA<TrackingStateError>()));
    await session.dispose();
  });

  test('snapshots of another session are ignored', () async {
    final platform = FakePlatform();
    final session = TrackingSession(platform: platform);
    await session.start(sessionId: 's');
    platform.snapshotController.add(
      const PlatformSnapshot(
        sessionId: 'other',
        state: PlatformState.running,
        segmentId: 0,
        fixCount: 99,
      ),
    );
    await pumpEvents();
    expect(session.snapshot.fixCount, 0);
    await session.dispose();
  });

  test('a step estimate goes to native and stays pending until GPS confirms it', () async {
    final platform = FakePlatform();
    final session = TrackingSession(platform: platform);
    await session.start(sessionId: 's');
    platform.push(fixAt(0, 0, 0));
    platform.engine.onTick(at(15));
    await session.submitPdr(130);
    await pumpEvents();
    expect(session.snapshot.pendingPdrMeters, 130);
    expect(session.snapshot.totalMeters, 0);
    await session.dispose();
  });

  test('restore asks native to rebuild and comes back paused', () async {
    final platform = FakePlatform()
      ..persisted = [
        fixAt(0, 0, 0),
        fixAt(1, 1, 5, speed: 4),
        fixAt(2, 2, 10, speed: 4),
      ];
    final session = TrackingSession(platform: platform);
    await session.restore(sessionId: 's');
    expect(session.snapshot.totalMeters, closeTo(10, 0.2));
    expect(session.snapshot.state, RecordingState.paused);
    expect(platform.calls, ['restore']);
    await session.dispose();
  });

  test('restore never joins two segments', () async {
    final platform = FakePlatform()
      ..persisted = [
        fixAt(0, 0, 0),
        fixAt(1, 1, 5, speed: 4),
        fixAt(2, 500, 9000, segment: 1),
      ];
    final session = TrackingSession(platform: platform);
    await session.restore(sessionId: 's');
    expect(session.snapshot.totalMeters, closeTo(5, 0.2));
    await session.dispose();
  });

  test('the report comes from native', () async {
    final platform = FakePlatform();
    final session = TrackingSession(platform: platform);
    await session.start(sessionId: 's');
    expect(await session.report(), contains('TIMELINE'));
    await session.dispose();
  });

  test('finishing is NOT final, and unsaved events are public', () async {
    final platform = FakePlatform();
    final session = TrackingSession(platform: platform);
    await session.start(sessionId: 's');
    platform.snapshotController.add(
      const PlatformSnapshot(
        sessionId: 's',
        state: PlatformState.finishing,
        segmentId: 0,
        fixCount: 0,
        journalPending: 3,
      ),
    );
    await pumpEvents();
    expect(session.snapshot.state, RecordingState.finishing);
    expect(session.snapshot.isDurable, isFalse);
    expect(session.snapshot.stepsFinal, isFalse);
    // A second start while finishing is refused, like while running.
    expect(() => session.start(sessionId: 'again'), throwsA(isA<TrackingStateError>()));
    platform.snapshotController.add(
      const PlatformSnapshot(
        sessionId: 's',
        state: PlatformState.stopped,
        segmentId: 0,
        fixCount: 0,
        stepsFinal: true,
      ),
    );
    await pumpEvents();
    expect(session.snapshot.state, RecordingState.finished);
    expect(session.snapshot.isDurable, isTrue);
    await session.dispose();
  });

  test('the restore outcome and the notification API reach the app', () async {
    final platform = FakePlatform();
    final session = TrackingSession(platform: platform);
    await session.start(sessionId: 's');
    platform.snapshotController.add(
      const PlatformSnapshot(
        sessionId: 's',
        state: PlatformState.paused,
        segmentId: 0,
        fixCount: 3,
        restoreState: RestoreState.partial,
        engineChanged: true,
        distance: PlatformDistance(
          quality: GpsQuality.unknown,
          totalMeters: 10,
          gpsMeters: 10,
          gapBridgeMeters: 0,
          pdrMeters: 0,
          pendingPdrMeters: 0,
          movingTime: Duration.zero,
          isStopped: false,
          distanceEligibleSteps: 7,
        ),
      ),
    );
    await pumpEvents();
    expect(session.snapshot.restoreState, RestoreState.partial);
    expect(session.snapshot.engineChanged, isTrue);
    expect(session.snapshot.distanceEligibleSteps, 7);
    await session.updateNotification(title: 'T', text: 'x');
    expect(platform.calls, contains('notify:T'));
    await session.dispose();
  });
}
