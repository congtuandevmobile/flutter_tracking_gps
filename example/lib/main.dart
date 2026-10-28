import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_tracking_gps/flutter_tracking_gps.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) => const MaterialApp(
    debugShowCheckedModeBanner: false,
    home: RecorderPage(),
  );
}

class RecorderPage extends StatefulWidget {
  const RecorderPage({super.key});

  @override
  State<RecorderPage> createState() => _RecorderPageState();
}

class _RecorderPageState extends State<RecorderPage> {
  final _gps = FlutterTrackingGps();
  TrackingSession? _session;
  StreamSubscription<TrackingSnapshot>? _sub;
  TrackingSnapshot _snapshot = const TrackingSnapshot.idle();
  PlatformPermission? _permission;
  String? _error;
  String _report = '';
  bool _adaptive = true;

  @override
  void initState() {
    super.initState();
    _refreshPermission();
  }

  @override
  void dispose() {
    _sub?.cancel();
    _session?.dispose();
    super.dispose();
  }

  Future<void> _refreshPermission() async {
    final status = await _gps.permissionStatus();
    if (mounted) setState(() => _permission = status);
  }

  Future<void> _run(Future<void> Function() action) async {
    try {
      await action();
      if (mounted) setState(() => _error = null);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  Future<void> _start() => _run(() async {
    if (_permission != PlatformPermission.granted) {
      final result = await _gps.requestPermission();
      setState(() => _permission = result);
      if (result != PlatformPermission.granted) return;
    }
    final session = _gps.createSession(
      config: TrackingConfig.forSport(TrackingSport.running),
      adaptiveSampling: _adaptive,
      notificationTitle: 'Recording',
      notificationText: 'Tracking your activity',
    );
    await _sub?.cancel();
    _sub = session.updates.listen((s) => setState(() => _snapshot = s));
    _session = session;
    await session.start(sessionId: 'demo-${DateTime.now().millisecondsSinceEpoch}');
  });

  Future<void> _finish() => _run(() async {
    final session = _session;
    if (session == null) return;
    final result = await session.finish();
    final report = await session.report();
    setState(() {
      _snapshot = result;
      _report = report;
    });
  });

  @override
  Widget build(BuildContext context) {
    final s = _snapshot;
    final session = _session;
    return Scaffold(
      appBar: AppBar(title: const Text('flutter_tracking_gps')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('permission: ${_permission?.name ?? '...'}'),
          Text('state: ${s.state.name}   gps: ${s.quality.name}'),
          Text(
            '${(s.totalMeters / 1000).toStringAsFixed(3)} km',
            style: Theme.of(context).textTheme.displaySmall,
          ),
          Text(
            'gps ${s.gpsMeters.toStringAsFixed(1)} m  '
            'bridge ${s.gapBridgeMeters.toStringAsFixed(1)} m  '
            'pdr ${s.pdrMeters.toStringAsFixed(1)} m  '
            'pending ${s.pendingPdrMeters.toStringAsFixed(1)} m',
          ),
          Text('segment ${s.segmentId}   fixes ${s.fixCount}'),
          Text(
            'moving ${s.movingTime.inSeconds}s   '
            '${s.isStopped ? 'stopped' : 'moving'}',
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Adaptive sampling'),
            value: _adaptive,
            onChanged: s.state == RecordingState.running ||
                    s.state == RecordingState.paused
                ? null
                : (v) => setState(() => _adaptive = v),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(_error!, style: const TextStyle(color: Colors.red)),
            ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 8,
            children: [
              FilledButton(
                onPressed: s.state == RecordingState.running ||
                        s.state == RecordingState.paused
                    ? null
                    : _start,
                child: const Text('Start'),
              ),
              OutlinedButton(
                onPressed: s.state == RecordingState.running
                    ? () => _run(() => session!.pause())
                    : null,
                child: const Text('Pause'),
              ),
              OutlinedButton(
                onPressed: s.state == RecordingState.paused
                    ? () => _run(() => session!.resume())
                    : null,
                child: const Text('Resume'),
              ),
              OutlinedButton(
                onPressed: s.state == RecordingState.running ||
                        s.state == RecordingState.paused
                    ? _finish
                    : null,
                child: const Text('Finish'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          if (_report.isNotEmpty) SelectableText(_report),
        ],
      ),
    );
  }
}
