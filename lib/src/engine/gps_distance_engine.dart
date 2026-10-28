import 'package:flutter_tracking_gps/src/diagnostics/tracking_log.dart';
import 'package:flutter_tracking_gps/src/domain/distance_ledger.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_config.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_fix.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_snapshot.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';
import 'package:flutter_tracking_gps/src/engine/geo.dart';
import 'package:flutter_tracking_gps/src/engine/stride_estimator.dart';

/// Result of feeding one fix.
class FixResult {
  const FixResult(this.verdict, {this.addedMeters = 0, this.source});

  final FixVerdict verdict;
  final double addedMeters;
  final DistanceSource? source;
}

/// The pure-Dart REFERENCE of the distance rules. The shipped path is the Kotlin
/// core running natively (`tracking-core`); this class is kept so the same
/// fixtures run on both (test/fixtures) and for pure-Dart use. It has no Kalman
/// smoothing, stop detector or adaptive sampling.
///
/// Decides what counts as distance. Pure and deterministic: time is passed in,
/// nothing is read from the platform, so a recording can be replayed.
///
/// Rules (see docs/FLOW.md):
///  * GPS is the truth. A step estimate is only ever PENDING until the GPS that
///    returns confirms it; a phone shaken without GPS therefore adds 0 m.
///  * One source per stretch: a gap is credited once, as the larger of the
///    straight line and the confirmed estimate, never their sum.
///  * Nothing is bridged across a pause or a segment boundary.
///  * Doppler speed below the stationary limit means no distance.
///  * The total only grows.
class GpsDistanceEngine {
  GpsDistanceEngine({required this.config, TrackingLog? log})
    : log = log ?? TrackingLog(),
      _stride = StrideEstimator(initialStrideMeters: config.initialStrideMeters);

  final TrackingConfig config;
  final TrackingLog log;
  final ledger = DistanceLedger();
  final StrideEstimator _stride;

  RecordingState _state = RecordingState.idle;
  GpsQuality _quality = GpsQuality.unknown;
  int _segmentId = 0;
  String? _sessionId;

  TrackingFix? _anchor;
  int _lastSeq = -1;
  DateTime? _lastFixTime;
  DateTime? _lastReceivedAt;
  DateTime? _lastAcceptedFixAt;
  DateTime? _lastHealthyAt;
  DateTime? _legStartedAt;
  double? _speedMps;
  int _fixCount = 0;
  int _rejectStreak = 0;
  double _pendingPdr = 0;

  RecordingState get state => _state;
  GpsQuality get quality => _quality;
  int get segmentId => _segmentId;
  double get totalMeters => ledger.totalMeters;

  TrackingSnapshot get snapshot => TrackingSnapshot(
    sessionId: _sessionId,
    state: _state,
    quality: _quality,
    totalMeters: ledger.totalMeters,
    gpsMeters: ledger.meters(DistanceSource.gps),
    gapBridgeMeters: ledger.meters(DistanceSource.gapBridge),
    pdrMeters: ledger.meters(DistanceSource.pdr),
    pendingPdrMeters: _pendingPdr,
    segmentId: _segmentId,
    fixCount: _fixCount,
    speedMps: _speedMps,
    lastFixAt: _lastAcceptedFixAt,
    strideMeters: _stride.strideMeters,
  );

  // ── Lifecycle ───────────────────────────────────────────────────────────

  void start(String sessionId, DateTime at, {int segmentId = 0}) {
    ledger.clear();
    log.clear();
    _sessionId = sessionId;
    _state = RecordingState.running;
    _segmentId = segmentId;
    _quality = GpsQuality.unknown;
    _anchor = null;
    _lastSeq = -1;
    _lastFixTime = null;
    _lastReceivedAt = at;
    _lastAcceptedFixAt = null;
    _lastHealthyAt = null;
    _legStartedAt = at;
    _speedMps = null;
    _fixCount = 0;
    _rejectStreak = 0;
    _pendingPdr = 0;
    _stride.reset();
    log.note(at, 'start segment $segmentId');
  }

  /// Closes the segment. Nothing is carried across the pause: the anchor is
  /// forgotten and a pending estimate is dropped.
  void pause(DateTime at) {
    if (_state != RecordingState.running) return;
    _state = RecordingState.paused;
    _dropPending(at, 'pause');
    _stride.breakWindow();
    _anchor = null;
    _lastHealthyAt = null;
    _quality = GpsQuality.unknown;
    _speedMps = null;
    log.note(at, 'pause at ${totalMeters.toStringAsFixed(1)} m');
  }

  void resume(DateTime at, {required int segmentId}) {
    if (_state != RecordingState.paused) return;
    _state = RecordingState.running;
    _segmentId = segmentId;
    _anchor = null;
    _lastSeq = -1;
    _lastFixTime = null;
    _lastReceivedAt = at;
    _lastHealthyAt = null;
    _legStartedAt = at;
    _pendingPdr = 0;
    _quality = GpsQuality.unknown;
    _rejectStreak = 0;
    _stride.breakWindow();
    log.note(at, 'resume segment $segmentId');
  }

  /// Freezes the result. Later fixes are ignored.
  void finish(DateTime at) {
    if (_state == RecordingState.finished || _state == RecordingState.idle) {
      return;
    }
    _dropPending(at, 'finish');
    _stride.breakWindow();
    _state = RecordingState.finished;
    log.note(at, 'finish at ${totalMeters.toStringAsFixed(1)} m');
  }

  // ── Step estimates (optional) ───────────────────────────────────────────

  /// A step-based estimate for a stretch where GPS is not measuring. It is held
  /// as pending; it only counts if the GPS that returns confirms it.
  void submitPdr(DateTime at, double meters, {int? segmentId}) {
    if (config.pdrPolicy == PdrPolicy.off) return;
    if (_state != RecordingState.running) return;
    if (segmentId != null && segmentId != _segmentId) return;
    if (!meters.isFinite || meters <= 0) return;
    _addPending(at, meters);
  }

  /// New steps of [segmentId] (a DELTA, not a running total). While GPS is good
  /// they teach the stride (with the GPS metres of the same window) and are not
  /// distance. While it is not good and a stride is known they become a PENDING
  /// estimate (`steps * stride`) that the returning GPS must confirm, like
  /// [submitPdr]. With no known stride nothing is estimated. Steps of another
  /// segment, or while not running, are refused.
  void onSteps(DateTime at, int steps, {required int segmentId}) {
    if (_state != RecordingState.running ||
        segmentId != _segmentId ||
        steps <= 0) {
      return;
    }
    if (_quality == GpsQuality.good) {
      _stride.addSteps(steps);
      return;
    }
    _stride.breakWindow();
    if (config.pdrPolicy == PdrPolicy.off) return;
    final perStep = _stride.strideMeters;
    if (perStep == null) return;
    _addPending(at, steps * perStep);
  }

  void _addPending(DateTime at, double meters) {
    // While GPS is measuring, GPS already has this stretch.
    if (_quality == GpsQuality.good || _quality == GpsQuality.recovering) {
      return;
    }
    _pendingPdr += meters;
    log.note(
      at,
      'pdr pending +${meters.toStringAsFixed(1)} m '
      '(pending ${_pendingPdr.toStringAsFixed(1)} m)',
    );
  }

  // ── Time ────────────────────────────────────────────────────────────────

  /// Call about once per second. Detects GPS silence.
  GpsQuality onTick(DateTime now) {
    if (_state != RecordingState.running) return _quality;
    final last = _lastReceivedAt;
    if (last != null &&
        now.difference(last) > config.silenceAfter &&
        _quality != GpsQuality.unavailable) {
      _quality = GpsQuality.unavailable;
      log.note(now, 'gps unavailable (silent ${config.silenceAfter.inSeconds}s)');
    }
    return _quality;
  }

  // ── Fixes ───────────────────────────────────────────────────────────────

  /// [now] is when the fix arrived. [replay] skips the age check (a fix read
  /// back from storage is old by definition).
  FixResult onFix(TrackingFix fix, DateTime now, {bool replay = false}) {
    if (_state != RecordingState.running) {
      return const FixResult(FixVerdict.notRunning);
    }
    if (fix.sessionId != _sessionId) {
      return const FixResult(FixVerdict.wrongSession);
    }
    if (fix.segmentId != _segmentId) {
      return const FixResult(FixVerdict.wrongSegment);
    }
    if (_isInvalid(fix)) {
      log.note(now, 'fix rejected: invalid values');
      return const FixResult(FixVerdict.invalid);
    }
    final lastTime = _lastFixTime;
    if (fix.seq <= _lastSeq || (lastTime != null && !fix.time.isAfter(lastTime))) {
      return const FixResult(FixVerdict.duplicate);
    }
    if (!replay) {
      if (fix.time.isAfter(now.add(const Duration(seconds: 2)))) {
        return const FixResult(FixVerdict.future);
      }
      final started = _legStartedAt;
      if (now.difference(fix.time) > config.maxFixAge ||
          (started != null && fix.time.isBefore(started))) {
        log.note(now, 'fix rejected: stale');
        return const FixResult(FixVerdict.stale);
      }
    }
    _lastSeq = fix.seq;
    _lastFixTime = fix.time;
    if (config.rejectMock && fix.isMock) {
      log.note(now, 'fix rejected: mock');
      return const FixResult(FixVerdict.mock);
    }
    _fixCount++;
    _lastReceivedAt = now;

    final accuracy = fix.horizontalAccuracy;
    if (accuracy == null || accuracy < 0 || accuracy > config.maxAccuracyMeters) {
      if (_quality != GpsQuality.degraded) {
        log.note(
          now,
          'gps degraded (accuracy ${accuracy?.toStringAsFixed(0) ?? '?'} m)',
        );
      }
      _quality = GpsQuality.degraded;
      return const FixResult(FixVerdict.lowAccuracy);
    }

    final anchor = _anchor;
    if (anchor == null) {
      _anchor = fix;
      _markGood(now, fix);
      if (_pendingPdr > 0) _dropPending(now, 'no anchor to confirm it');
      log.note(now, 'anchor set');
      return const FixResult(FixVerdict.anchor);
    }

    final dt = fix.time.difference(anchor.time).inMilliseconds / 1000.0;
    final distance = haversineMeters(
      anchor.latitude,
      anchor.longitude,
      fix.latitude,
      fix.longitude,
    );
    final uncertainty = (anchor.horizontalAccuracy ?? accuracy) + accuracy;
    // A real gap is silence measured from the last HEALTHY fix. The anchor can
    // be old on purpose (it stays put while standing), so it must not decide this.
    final healthy = _lastHealthyAt;
    final sinceHealthy = healthy == null
        ? dt
        : fix.time.difference(healthy).inMilliseconds / 1000.0;
    final wasGap =
        _quality == GpsQuality.unavailable ||
        sinceHealthy > config.silenceAfter.inSeconds;
    if (wasGap) return _recover(fix, anchor, distance, dt, now);

    // Normal stretch between two good fixes (the GPS error of both ends is
    // allowed for when judging the speed).
    final slack = distance - uncertainty;
    if (dt <= 0 || (slack > 0 ? slack : 0) / dt > config.maxSpeedMps) {
      _rejectStreak++;
      log.note(
        now,
        'jump rejected ${distance.toStringAsFixed(1)} m in '
        '${dt.toStringAsFixed(1)} s',
      );
      if (_rejectStreak >= config.reanchorAfterRejects) {
        _anchor = fix;
        _rejectStreak = 0;
        log.note(now, 're-anchored after repeated jumps');
      }
      return const FixResult(FixVerdict.jump);
    }
    _rejectStreak = 0;
    _markGood(now, fix);

    final speed = _trustedSpeed(fix);
    if (speed != null && speed < config.stationarySpeedMps) {
      return const FixResult(FixVerdict.stationary);
    }
    final noise = _noiseRadius(anchor, fix);
    if (distance < noise) return const FixResult(FixVerdict.noise);

    ledger.add(
      segmentId: _segmentId,
      source: DistanceSource.gps,
      meters: distance,
      at: now,
    );
    _stride.addMeters(distance);
    _anchor = fix;
    return FixResult(
      FixVerdict.accepted,
      addedMeters: distance,
      source: DistanceSource.gps,
    );
  }

  // ── Internals ───────────────────────────────────────────────────────────

  double _noiseRadius(TrackingFix a, TrackingFix b) {
    final acc = (a.horizontalAccuracy ?? 0) > (b.horizontalAccuracy ?? 0)
        ? a.horizontalAccuracy!
        : (b.horizontalAccuracy ?? 0);
    final byAccuracy = config.accuracyNoiseFactor * acc;
    return byAccuracy > config.minStepMeters ? byAccuracy : config.minStepMeters;
  }

  void _markGood(DateTime now, TrackingFix fix) {
    if (_quality != GpsQuality.good) {
      log.note(now, 'gps good');
    }
    _quality = GpsQuality.good;
    _lastAcceptedFixAt = fix.time;
    _lastHealthyAt = fix.time;
    _speedMps = _trustedSpeed(fix);
  }

  bool _isInvalid(TrackingFix fix) {
    bool bad(double? v) => v != null && (!v.isFinite || v < 0);
    return !fix.latitude.isFinite ||
        !fix.longitude.isFinite ||
        fix.latitude < -90 ||
        fix.latitude > 90 ||
        fix.longitude < -180 ||
        fix.longitude > 180 ||
        fix.seq < 0 ||
        bad(fix.horizontalAccuracy) ||
        bad(fix.speed) ||
        bad(fix.speedAccuracy);
  }

  /// A Doppler speed with a worse accuracy than 1.5 m/s is not believed
  /// (unmeasured starting value).
  double? _trustedSpeed(TrackingFix fix) {
    final s = fix.speed;
    if (s == null) return null;
    final a = fix.speedAccuracy;
    return (a == null || a <= 1.5) ? s : null;
  }

  /// First good fix after a gap. Credits the gap once: the larger of the
  /// straight line (shrunk by its own uncertainty) and the CONFIRMED estimate.
  FixResult _recover(
    TrackingFix fix,
    TrackingFix anchor,
    double chord,
    double dt,
    DateTime now,
  ) {
    final pending = _pendingPdr;
    _pendingPdr = 0;
    _rejectStreak = 0;
    _stride.breakWindow();
    final gap = Duration(milliseconds: (dt * 1000).round());

    var credit = 0.0;
    var source = DistanceSource.gapBridge;
    final uncertainty =
        (anchor.horizontalAccuracy ?? 0) + (fix.horizontalAccuracy ?? 0);
    final lowerChord = chord > uncertainty ? chord - uncertainty : 0.0;
    final ceiling = config.maxSpeedMps * dt;
    if (dt <= 0 || gap > config.maxBridge) {
      log.note(now, 'gap ${gap.inSeconds}s too long: not bridged');
    } else if (lowerChord > ceiling) {
      log.note(now, 'gap implied speed too high: not bridged');
    } else {
      credit = lowerChord < ceiling ? lowerChord : ceiling;
      if (pending > 0 && lowerChord > 0) {
        // A large estimate is not taken just because GPS moved a little.
        final capByLine = lowerChord / config.pdrConfirmRatio;
        final cap = capByLine < ceiling ? capByLine : ceiling;
        final corroborated = pending < cap ? pending : cap;
        log.note(
          now,
          'pdr ${pending <= cap ? 'corroborated' : 'capped by the straight line'}: '
          'estimate ${pending.toStringAsFixed(1)} m, '
          'straight line ${chord.toStringAsFixed(1)} m',
        );
        if (corroborated > credit) {
          credit = corroborated;
          source = DistanceSource.pdr;
        }
      } else if (pending > 0) {
        log.note(
          now,
          'pdr NOT corroborated: estimate ${pending.toStringAsFixed(1)} m, '
          'straight line ${chord.toStringAsFixed(1)} m',
        );
      }
    }

    if (credit > 0) {
      ledger.add(
        segmentId: _segmentId,
        source: source,
        meters: credit,
        at: now,
      );
    }
    log.note(
      now,
      'recovered: +${credit.toStringAsFixed(1)} m (${source.name}) over '
      '${gap.inSeconds}s, total ${totalMeters.toStringAsFixed(1)} m',
    );
    _anchor = fix;
    _markGood(now, fix);
    _quality = GpsQuality.recovering;
    return FixResult(
      FixVerdict.recovered,
      addedMeters: credit,
      source: credit > 0 ? source : null,
    );
  }

  void _dropPending(DateTime at, String why) {
    if (_pendingPdr > 0) {
      log.note(
        at,
        'pdr dropped (${_pendingPdr.toStringAsFixed(1)} m): $why',
      );
    }
    _pendingPdr = 0;
  }
}
