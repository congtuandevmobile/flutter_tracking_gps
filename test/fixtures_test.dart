import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

/// Runs every `test/fixtures/*.csv` through the Dart engine. The Kotlin core
/// runs the SAME files (`FixtureParityTest`): both must land on the same total.
///
/// Lines: fix,seq,segment,tSec,northMeters,accuracy,speed(empty=unknown)
///        tick,tSec | pdr,tSec,meters | pause,tSec | resume,tSec,segment |
///        finish,tSec | expect,totalMeters,toleranceMeters
void main() {
  final base = DateTime.fromMillisecondsSinceEpoch(1700000000000);
  DateTime at(String seconds) =>
      base.add(Duration(milliseconds: (double.parse(seconds) * 1000).round()));

  final files = Directory('test/fixtures')
      .listSync()
      .whereType<File>()
      .where((f) => f.path.endsWith('.csv'))
      .toList()
    ..sort((a, b) => a.path.compareTo(b.path));

  test('there are fixtures', () => expect(files, isNotEmpty));

  for (final file in files) {
    test('fixture ${file.uri.pathSegments.last}', () {
      final engine = GpsDistanceEngine(config: const TrackingConfig())
        ..start('s', base);
      double? expected;
      var tolerance = 0.5;
      for (final raw in file.readAsLinesSync()) {
        final line = raw.trim();
        if (line.isEmpty || line.startsWith('#')) continue;
        final p = line.split(',');
        switch (p[0]) {
          case 'fix':
            final time = at(p[3]);
            engine.onFix(
              TrackingFix(
                sessionId: 's',
                segmentId: int.parse(p[2]),
                seq: int.parse(p[1]),
                time: time,
                elapsed: time.difference(base),
                latitude: 10 + double.parse(p[4]) / 111195.0,
                longitude: 106,
                horizontalAccuracy: double.parse(p[5]),
                speed: p.length > 6 && p[6].isNotEmpty ? double.parse(p[6]) : null,
              ),
              time,
            );
          case 'tick':
            engine.onTick(at(p[1]));
          case 'pdr':
            engine.submitPdr(at(p[1]), double.parse(p[2]), segmentId: engine.segmentId);
          case 'pause':
            engine.pause(at(p[1]));
          case 'resume':
            engine.resume(at(p[1]), segmentId: int.parse(p[2]));
          case 'finish':
            engine.finish(at(p[1]));
          case 'expect':
            expected = double.parse(p[1]);
            tolerance = double.parse(p[2]);
          default:
            fail('unknown line "$line"');
        }
      }
      expect(expected, isNotNull);
      expect(
        engine.totalMeters,
        closeTo(expected!, tolerance),
        reason: engine.log.report(),
      );
    });
  }
}
