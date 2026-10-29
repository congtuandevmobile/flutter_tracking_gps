import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_tracking_gps/src/engine/gps_distance_engine.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_config.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_fix.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_snapshot.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';
import 'package:flutter_tracking_gps/src/api/tracking_session.dart';
import 'package:flutter_tracking_gps/src/api/tracking_presenter.dart';
import 'package:flutter_tracking_gps/src/platform/tracking_platform.dart';

int checks = 0;
void check(bool value, String label) {
  if (!value) throw StateError('FAIL: $label');
  checks++;
  stdout.writeln('PASS: $label');
}

Future<void> settle() => Future<void>.delayed(Duration.zero);

class FakePlatform implements TrackingPlatform {
  final controller = StreamController<PlatformSnapshot>.broadcast();
  @override
  Stream<PlatformSnapshot> get snapshots => controller.stream;
  @override
  Stream<TrackingFix> get fixes => const Stream.empty();
  @override
  Future<PlatformPermission> permissionStatus() async =>
      PlatformPermission.granted;
  @override
  Future<void> pushCscMeasurement(String sessionId, String sourceId, int connectionId, Uint8List data) async {}
  @override
  Future<void> pushExternalCadence(String sessionId, String sourceId, int connectionId, double rpm) async {}
  @override
  Future<void> setExternalCadenceConnected(String sessionId, String sourceId, int connectionId, bool connected) async {}
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
    bool pedalCadence = false,
  }) async => snap();
  @override
  Future<PlatformSnapshot> pause(String sessionId) async =>
      snap(state: PlatformState.paused);
  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError('${invocation.memberName}');
}

PlatformSnapshot snap({
  PlatformState state = PlatformState.running,
  StepsStatus status = StepsStatus.off,
  double? stride,
  String? error,
}) => PlatformSnapshot(
  sessionId: 's',
  state: state,
  segmentId: 0,
  fixCount: 0,
  stepsStatus: status,
  error: error,
  distance: PlatformDistance(
    quality: GpsQuality.good,
    totalMeters: 0,
    gpsMeters: 0,
    gapBridgeMeters: 0,
    pdrMeters: 0,
    pendingPdrMeters: 0,
    movingTime: Duration.zero,
    isStopped: false,
    strideMeters: stride,
  ),
);

class ThrowingPresenter extends TrackingPresenter {
  int pauses = 0;
  @override
  Future<void> onStart(TrackingSnapshot snapshot) async {
    throw StateError('presenter');
  }

  @override
  Future<void> onPause(TrackingSnapshot snapshot) async {
    pauses++;
  }
}

Future<void> main(List<String> args) async {
  final base = DateTime.fromMillisecondsSinceEpoch(1700000000000);
  DateTime at(String sec) =>
      base.add(Duration(milliseconds: (double.parse(sec) * 1000).round()));
  final files =
      Directory('${args[0]}/test/fixtures')
          .listSync()
          .whereType<File>()
          .where((f) => f.path.endsWith('.csv'))
          .toList()
        ..sort((a, b) => a.path.compareTo(b.path));
  check(files.length == 11, 'all eleven shared fixtures discovered');
  for (final file in files) {
    final e = GpsDistanceEngine(config: const TrackingConfig())
      ..start('s', base);
    double? expected;
    double tolerance = 0.5;
    for (final raw in file.readAsLinesSync()) {
      final line = raw.trim();
      if (line.isEmpty || line.startsWith('#')) continue;
      final p = line.split(',');
      switch (p[0]) {
        case 'fix':
          final time = at(p[3]);
          e.onFix(
            TrackingFix(
              sessionId: 's',
              segmentId: int.parse(p[2]),
              seq: int.parse(p[1]),
              time: time,
              elapsed: time.difference(base),
              latitude: 10 + double.parse(p[4]) / 111195.0,
              longitude: 106,
              horizontalAccuracy: double.parse(p[5]),
              speed: p.length > 6 && p[6].isNotEmpty
                  ? double.parse(p[6])
                  : null,
            ),
            time,
          );
        case 'tick':
          e.onTick(at(p[1]));
        case 'pdr':
          e.submitPdr(at(p[1]), double.parse(p[2]), segmentId: e.segmentId);
        case 'pause':
          e.pause(at(p[1]));
        case 'resume':
          e.resume(at(p[1]), segmentId: int.parse(p[2]));
        case 'finish':
          e.finish(at(p[1]));
        case 'expect':
          expected = double.parse(p[1]);
          tolerance = double.parse(p[2]);
        default:
          throw StateError(line);
      }
    }
    check(
      expected != null && (e.totalMeters - expected).abs() <= tolerance,
      '${file.uri.pathSegments.last}: ${e.totalMeters} expected $expected',
    );
  }
  final e = GpsDistanceEngine(
    config: const TrackingConfig(initialStrideMeters: 1),
  )..start('s', base);
  e.onSteps(base.subtract(const Duration(milliseconds: 1)), 30, segmentId: 0);
  check(e.snapshot.pendingPdrMeters == 0, 'pre-leg steps ignored');
  check(
    TrackingConfig.forSport(TrackingSport.cycling).pdrPolicy == PdrPolicy.off,
    'cycling factory disables walking PDR',
  );
  final p = FakePlatform();
  final presenter = ThrowingPresenter();
  final session = TrackingSession(
    platform: p,
    presenter: presenter,
    onPresenterError: (_, _) {
      throw StateError('error handler');
    },
  );
  final events = <TrackingSnapshot>[];
  final errors = <Object>[];
  final sub = session.updates.listen(
    events.add,
    onError: (Object e, StackTrace s) => errors.add(e),
  );
  await session.start(sessionId: 's');
  await settle();
  events.clear();
  p.controller.add(snap(status: StepsStatus.active));
  await settle();
  check(
    events.length == 1 && events.last.stepsStatus == StepsStatus.active,
    'step-status-only change emitted',
  );
  p.controller.add(snap(status: StepsStatus.active, stride: 0.8));
  await settle();
  check(
    events.length == 2 && events.last.strideMeters == 0.8,
    'stride-only change emitted',
  );
  p.controller.add(
    snap(state: PlatformState.permissionDenied, error: 'denied'),
  );
  await settle();
  check(
    session.snapshot.failureState == TrackingFailureState.permissionDenied &&
        session.snapshot.hasNativeFailure &&
        session.snapshot.error == 'denied',
    'native permission failure exposed',
  );
  p.controller.add(snap(state: PlatformState.error, error: 'provider failed'));
  await settle();
  check(
    session.snapshot.failureState == TrackingFailureState.error &&
        session.snapshot.error == 'provider failed',
    'native runtime failure exposed',
  );
  p.controller.addError(StateError('transport'));
  await settle();
  check(errors.length == 1, 'transport stream error forwarded');
  p.controller.add(snap());
  await settle();
  check(
    !session.snapshot.hasNativeFailure,
    'failure clears on healthy native snapshot',
  );
  await session.pause();
  await settle();
  check(
    presenter.pauses == 1,
    'presenter queue survives throwing error handler',
  );
  await session.dispose();
  await sub.cancel();
  await p.controller.close();
  stdout.writeln('PASS $checks checks (Dart VM, no Flutter runtime)');
}
