import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_tracking_gps/src/api/presenter_dispatcher.dart';
import 'package:flutter_tracking_gps/src/api/tracking_presenter.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_config.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_fix.dart';
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
    this.notificationText = 'Tracking your activity. Tap to return to the app.',
    this.stepsEnabled = false,
    this.initialSteps = 0,
    this.pedalCadence = false,
    this.presenter,
    this.onPresenterError,
    this.presenterTimeout = const Duration(seconds: 5),
  }) : _platform = platform;

  final TrackingPlatform _platform;
  final TrackingConfig config;

  /// Let native change the update interval with the speed (and keep its silence
  /// limit consistent). Starting value, not measured on devices.
  final bool adaptiveSampling;
  final bool batterySaver;

  /// Android notification of the SDK's foreground service (ignored on iOS).
  /// Shown as is while recording; tapping it returns to the app. No live data is
  /// shown in it. The service always runs, whatever the [presenter] does.
  final String notificationTitle;
  final String notificationText;

  /// Count steps natively and let the engine turn them into a PENDING distance
  /// estimate while GPS is weak (it only counts if the GPS that returns
  /// confirms it). Needs the motion permission (see
  /// `FlutterTrackingGps.requestStepsPermission`); without it the recording goes
  /// on and `snapshot.stepsStatus` says so. iOS shows the Motion & Fitness prompt
  /// at the first start when it was not asked before.
  final bool stepsEnabled;

  /// Session steps to restore after a process death (the device counter is never
  /// added to it); 0 for a new session.
  final int initialSteps;

  /// Estimate the pedalling cadence (rpm) from the accelerometer of a phone carried ON THE LEG (trouser pocket,
  /// thigh); read it from `snapshot.pedalCadenceRpm`. It needs no permission. Live only: it is not journaled and
  /// does not affect distance; a restore does not carry it back until a new window has been seen. It means nothing
  /// with the phone on the handlebar, and it is an estimate that is not validated on a real bike. For a precise
  /// rpm use a BLE cadence sensor, which this package does not support.
  final bool pedalCadence;

  /// Shows the recording outside the app (a Live Activity, an ongoing
  /// notification...). null = none: iOS shows nothing extra and Android shows the
  /// SDK's own notification. Events are delivered in order, updates are
  /// coalesced, and a slow or failing presenter never affects the recording.
  final TrackingPresenter? presenter;

  /// Called when the [presenter] throws or times out; the recording is not
  /// affected. Defaults to a `dart:developer` log.
  final void Function(Object error, StackTrace stack)? onPresenterError;

  /// Longest a single presenter call may take before it is reported and skipped.
  final Duration presenterTimeout;

  late final PresenterDispatcher? _dispatcher = presenter == null
      ? null
      : PresenterDispatcher(
          presenter!,
          onError: onPresenterError,
          callTimeout: presenterTimeout,
        );

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
        _last.state == RecordingState.paused ||
        _last.state == RecordingState.finishing) {
      throw TrackingStateError('a recording is already open');
    }
    if (_last.state == RecordingState.finished) {
      // One session, one recording: the presenter and the final snapshot belong to it.
      throw TrackingStateError(
        'this TrackingSession already finished; create a new one for the next recording',
      );
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
      stepsEnabled: stepsEnabled,
      initialSteps: initialSteps,
      pedalCadence: pedalCadence,
    );
    _requireRunning(native);
    _apply(native);
    _present(PresenterEvent.start);
  }

  Future<void> pause() async {
    final id = _requireOpen(RecordingState.running);
    _apply(await _platform.pause(id));
    _present(PresenterEvent.pause);
  }

  Future<void> resume() async {
    final id = _requireOpen(RecordingState.paused);
    final native = await _platform.resume(id);
    _requireRunning(native);
    _apply(native);
    _present(PresenterEvent.resume);
  }

  /// Ends the recording. Native answers only after its journal is durable and
  /// the step count was finalized (see [TrackingSnapshot.stepsFinal]), so the
  /// returned snapshot is final.
  Future<TrackingSnapshot> finish() async {
    final id = _sessionId;
    if (id == null || _last.state == RecordingState.idle) {
      throw TrackingStateError('no recording to finish');
    }
    final native = await _platform.stop(id);
    if (native.state != PlatformState.stopped) {
      // Not final (still finishing, or native failed): say so, keep the session as it is.
      _apply(native);
      throw TrackingStateError(
        'finish did not complete: native state is ${native.state.name}'
        '${native.error == null ? '' : ' (${native.error})'}',
      );
    }
    await _sub?.cancel();
    _sub = null;
    _apply(native);
    _present(PresenterEvent.finish);
    return _last;
  }

  /// The raw fixes of this session, for drawing the route live.
  Stream<TrackingFix> get fixes =>
      _platform.fixes.where((f) => f.sessionId == _sessionId);

  /// The raw fixes persisted for this session, oldest first (after the writer
  /// reached the disk): the route to draw or export once the recording is over,
  /// or after a restore.
  Future<List<TrackingFix>> persistedFixes({int afterSeq = -1}) async {
    final id = _sessionId;
    if (id == null) return const [];
    return _platform.readPersistedFixes(id, afterSeq);
  }

  /// Frees what the platform persisted for this session (it holds
  /// coordinates). Call after the activity was saved.
  Future<void> clearPersisted() async {
    final id = _sessionId;
    if (id != null) await _platform.clearSession(id);
  }

  /// After a process death: native replays the session's journal with the
  /// configuration it ran with (steps, pending estimates, stride, gaps and times
  /// come back exactly; segments are never joined). The session comes back
  /// PAUSED; [resume] continues it in a new segment. If native still runs it,
  /// nothing is replayed. A damaged last record is dropped and reported in
  /// [TrackingSnapshot.error]; an unusable journal throws.
  ///
  /// With [recoverRunning] a session that was RECORDING when its process died continues: the calendar time that
  /// passed is credited to the clock (see [TrackingSnapshot.recoveredGap]), the recording continues in a NEW
  /// segment, and no distance is invented for the dead interval. A session that was paused stays paused. A dead
  /// interval longer than `TrackingConfig.maxRecoverGap` is not credited and the session comes back paused.
  Future<void> restore({required String sessionId, bool recoverRunning = false}) async {
    _sessionId = sessionId;
    _listen();
    final native = await _platform.restoreSession(
      sessionId: sessionId,
      config: config,
      interval: config.updateInterval,
      minDistanceMeters: config.minDistanceMeters,
      adaptiveSampling: adaptiveSampling,
      batterySaver: batterySaver,
      stepsEnabled: stepsEnabled,
      initialSteps: initialSteps,
      recoverRunning: recoverRunning,
      notificationTitle: notificationTitle,
      notificationText: notificationText,
    );
    _apply(native);
  }

  /// Android: replaces the text of the SDK's foreground notification (iOS: no-op).
  /// The SDK keeps the channel, the tap action and the lifecycle; a presenter
  /// that wants a richer notification calls this instead of posting its own.
  Future<void> updateNotification({required String title, required String text}) =>
      _platform.updateNotification(title, text);

  /// A step-based estimate for a stretch without GPS; held as pending by native
  /// until the GPS that returns confirms it (see [PdrPolicy]).
  Future<void> submitPdr(double meters) async {
    final id = _sessionId;
    if (id == null) return;
    // Tagged with the segment it was measured in: native refuses one that
    // arrives after a resume.
    await _platform.submitPdr(id, _last.segmentId, meters);
  }

  /// Tells the SDK that the HOST connected to (or lost) an external cadence sensor. The SDK does not scan, pair or
  /// reconnect: a BLE component of the app does, and reports here. [sourceId] names the device; [connectionId] is a
  /// number the app increases for every new connection to it, so the counters of an old connection are never mixed
  /// with a new one. Losing the sensor makes `pedalCadenceRpm` fall back to the phone estimate at once.
  Future<void> setExternalCadenceConnected({
    required String sourceId,
    required int connectionId,
    required bool connected,
  }) async {
    final id = _sessionId;
    if (id == null) return;
    await _platform.setExternalCadenceConnected(id, sourceId, connectionId, connected);
  }

  /// One raw Bluetooth CSC Measurement (characteristic 0x2A5B, the bytes of the notification) from the external
  /// sensor. The SDK decodes it and handles the 16-bit rollover, wheel-only packets and a reset sensor. Not journaled;
  /// no effect on distance. Dropped when [sourceId]/[connectionId] are not the open connection.
  Future<void> pushCscMeasurement(
    Uint8List data, {
    required String sourceId,
    required int connectionId,
  }) async {
    final id = _sessionId;
    if (id == null) return;
    await _platform.pushCscMeasurement(id, sourceId, connectionId, data);
  }

  /// A cadence already in rpm from a source that is not CSC (for example a power meter). 0 means the cranks are
  /// still; a missing measurement must not be sent as 0.
  Future<void> pushExternalCadence(
    double rpm, {
    required String sourceId,
    required int connectionId,
  }) async {
    final id = _sessionId;
    if (id == null) return;
    await _platform.pushExternalCadence(id, sourceId, connectionId, rpm);
  }

  Future<void> dispose() async {
    await _sub?.cancel();
    _sub = null;
    await _dispatcher?.close(); // bounded: never hangs on a presenter that does not answer
    await _updates.close();
  }

  // ── Internals ───────────────────────────────────────────────────────────

  void _listen() {
    _sub ??= _platform.snapshots.listen(
      (native) {
        if (native.sessionId != _sessionId) return;
        _apply(native);
        if (!_last.hasNativeFailure &&
            (_last.state == RecordingState.running ||
                _last.state == RecordingState.paused)) {
          _presentUpdate();
        }
      },
      onError: (Object error, StackTrace stack) {
        if (!_updates.isClosed) _updates.addError(error, stack);
      },
    );
  }

  void _apply(PlatformSnapshot native) {
    final d = native.distance;
    final next = TrackingSnapshot(
      sessionId: native.sessionId,
      state: switch (native.state) {
        PlatformState.idle => RecordingState.idle,
        PlatformState.running => RecordingState.running,
        PlatformState.paused => RecordingState.paused,
        PlatformState.finishing => RecordingState.finishing,
        PlatformState.stopped => RecordingState.finished,
        PlatformState.permissionDenied || PlatformState.error => _last.state,
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
      steps: d.steps,
      strideMeters: d.strideMeters,
      stepsStatus: native.stepsStatus,
      stepsFinal: native.stepsFinal,
      journalPending: native.journalPending,
      // The error text is a second signal: a write failure can never look durable.
      journalFailed:
          native.journalFailed ||
          (native.error?.startsWith('STORE_WRITE_FAILED') ?? false),
      restoreState: native.restoreState,
      engineChanged: native.engineChanged,
      elapsedTime: native.elapsedTime,
      recoveredGap: native.recoveredGap,
      clockAnomaly: native.clockAnomaly,
      collection: native.collection,
      pedalCadenceRpm: native.pedalCadenceRpm,
      cadenceStatus: native.cadenceStatus,
      cadenceSource: native.cadenceSource,
      distanceEligibleSteps: d.distanceEligibleSteps,
      activeTime: d.activeTime,
      bridgedGap: d.bridgedGap,
      uncreditedGap: d.uncreditedGap,
      uncreditedGaps: d.uncreditedGaps,
      ambiguousSteps: d.ambiguousSteps,
      failureState: switch (native.state) {
        PlatformState.permissionDenied => TrackingFailureState.permissionDenied,
        PlatformState.error => TrackingFailureState.error,
        _ => TrackingFailureState.none,
      },
      error: native.error,
    );
    // Whole-snapshot equality: a field added later can never be forgotten here.
    final changed = next != _last;
    _last = next;
    if (changed && !_updates.isClosed) _updates.add(next);
  }

  // ── Presenter ───────────────────────────────────────────────────────────

  DateTime? _lastPresentedAt;

  /// Presenter updates are throttled to about once per second.
  static const _presentEvery = Duration(seconds: 1);

  void _presentUpdate() {
    final target = _dispatcher;
    if (target == null) return;
    final now = DateTime.now();
    final last = _lastPresentedAt;
    if (last != null && now.difference(last) < _presentEvery) return;
    _lastPresentedAt = now;
    target.update(_last);
  }

  void _present(PresenterEvent event) {
    final target = _dispatcher;
    if (target == null) return;
    _lastPresentedAt = DateTime.now();
    target.lifecycle(event, _last);
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
