import 'dart:async';

import 'package:flutter_tracking_gps/src/domain/tracking_config.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_snapshot.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';
import 'package:flutter_tracking_gps/src/platform/tracking_platform.dart';

/// Thrown when an operation is not allowed in the current state.
class TrackingStateError extends StateError {
  TrackingStateError(super.message);
}

/// One recording. The NATIVE side owns the session and the distance engine (it
/// keeps recording and keeps measuring when the Flutter engine is gone); this
/// class sends the commands and turns what native reports into snapshots.
///
/// Start → pause/resume → finish, plus [restore] after the process died.
class TrackingSession {
  TrackingSession({
    required TrackingPlatform platform,
    this.config = const TrackingConfig(),
    this.adaptiveSampling = false,
    this.batterySaver = false,
    this.notificationTitle = 'Recording',
    this.notificationText = 'Tracking your activity',
  }) : _platform = platform;

  final TrackingPlatform _platform;
  final TrackingConfig config;

  /// Let native change the update interval with the speed (and keep its silence
  /// limit consistent). Starting value, not measured on devices.
  final bool adaptiveSampling;
  final bool batterySaver;
  final String notificationTitle;
  final String notificationText;

  StreamSubscription<PlatformSnapshot>? _sub;
  String? _sessionId;
  final _updates = StreamController<TrackingSnapshot>.broadcast();
  TrackingSnapshot _last = const TrackingSnapshot.idle();

  /// The state, about once per second and on every change.
  Stream<TrackingSnapshot> get updates => _updates.stream;
  TrackingSnapshot get snapshot => _last;
  String? get sessionId => _sessionId;

  /// Coordinate-free timeline of what the native engine decided.
  Future<String> report() => _platform.report();

  // ── Lifecycle ───────────────────────────────────────────────────────────

  Future<void> start({required String sessionId}) async {
    if (_last.state == RecordingState.running ||
        _last.state == RecordingState.paused) {
      throw TrackingStateError('a recording is already open');
    }
    final permission = await _platform.permissionStatus();
    if (permission != PlatformPermission.granted) {
      throw TrackingStateError('location permission: ${permission.name}');
    }
    _sessionId = sessionId;
    _listen();
    final native = await _platform.start(
      sessionId: sessionId,
      startSeq: 0,
      startSegmentId: 0,
      config: config,
      interval: config.updateInterval,
      minDistanceMeters: config.minDistanceMeters,
      adaptiveSampling: adaptiveSampling,
      batterySaver: batterySaver,
      notificationTitle: notificationTitle,
      notificationText: notificationText,
    );
    _requireRunning(native);
    _apply(native);
  }

  Future<void> pause() async {
    final id = _requireOpen(RecordingState.running);
    _apply(await _platform.pause(id));
  }

  Future<void> resume() async {
    final id = _requireOpen(RecordingState.paused);
    final native = await _platform.resume(id);
    _requireRunning(native);
    _apply(native);
  }

  /// Ends the recording. The returned snapshot is final.
  Future<TrackingSnapshot> finish() async {
    final id = _sessionId;
    if (id == null || _last.state == RecordingState.idle) {
      throw TrackingStateError('no recording to finish');
    }
    final native = await _platform.stop(id);
    await _sub?.cancel();
    _sub = null;
    _apply(native);
    return _last;
  }

  /// Frees what the platform persisted for this session (it holds
  /// coordinates). Call after the activity was saved.
  Future<void> clearPersisted() async {
    final id = _sessionId;
    if (id != null) await _platform.clearSession(id);
  }

  /// After a process death: native rebuilds its engine from the persisted fixes
  /// (segments are never joined). The session comes back PAUSED; [resume]
  /// continues it in a new segment. If native still runs it, nothing is replayed.
  Future<void> restore({required String sessionId}) async {
    _sessionId = sessionId;
    _listen();
    final native = await _platform.restoreSession(
      sessionId: sessionId,
      config: config,
      interval: config.updateInterval,
      minDistanceMeters: config.minDistanceMeters,
      adaptiveSampling: adaptiveSampling,
      batterySaver: batterySaver,
    );
    _apply(native);
  }

  /// A step-based estimate for a stretch without GPS; held as pending by native
  /// until the GPS that returns confirms it (see [PdrPolicy]).
  Future<void> submitPdr(double meters) async {
    final id = _sessionId;
    if (id == null) return;
    // Tagged with the segment it was measured in: native refuses one that
    // arrives after a resume.
    await _platform.submitPdr(id, _last.segmentId, meters);
  }

  Future<void> dispose() async {
    await _sub?.cancel();
    _sub = null;
    await _updates.close();
  }

  // ── Internals ───────────────────────────────────────────────────────────

  void _listen() {
    _sub ??= _platform.snapshots.listen((native) {
      if (native.sessionId != _sessionId) return;
      _apply(native);
    });
  }

  void _apply(PlatformSnapshot native) {
    final d = native.distance;
    final next = TrackingSnapshot(
      sessionId: native.sessionId,
      state: switch (native.state) {
        PlatformState.idle => RecordingState.idle,
        PlatformState.running => RecordingState.running,
        PlatformState.paused => RecordingState.paused,
        PlatformState.stopped => RecordingState.finished,
        PlatformState.permissionDenied ||
        PlatformState.error => _last.state,
      },
      quality: d.quality,
      totalMeters: d.totalMeters,
      gpsMeters: d.gpsMeters,
      gapBridgeMeters: d.gapBridgeMeters,
      pdrMeters: d.pdrMeters,
      pendingPdrMeters: d.pendingPdrMeters,
      segmentId: native.segmentId,
      fixCount: native.fixCount,
      speedMps: d.speedMps,
      lastFixAt: d.lastAcceptedFixAt,
      movingTime: d.movingTime,
      isStopped: d.isStopped,
    );
    final changed =
        next.state != _last.state ||
        next.quality != _last.quality ||
        next.totalMeters != _last.totalMeters ||
        next.pendingPdrMeters != _last.pendingPdrMeters ||
        next.segmentId != _last.segmentId ||
        next.speedMps != _last.speedMps ||
        next.movingTime != _last.movingTime ||
        next.isStopped != _last.isStopped ||
        next.fixCount != _last.fixCount;
    _last = next;
    if (changed && !_updates.isClosed) _updates.add(next);
  }

  String _requireOpen(RecordingState expected) {
    final id = _sessionId;
    if (id == null || _last.state != expected) {
      throw TrackingStateError(
        'expected ${expected.name}, was ${_last.state.name}',
      );
    }
    return id;
  }

  void _requireRunning(PlatformSnapshot native) {
    if (native.state != PlatformState.running) {
      throw TrackingStateError(
        'native did not start: ${native.state.name}'
        '${native.error == null ? '' : ' (${native.error})'}',
      );
    }
  }
}
