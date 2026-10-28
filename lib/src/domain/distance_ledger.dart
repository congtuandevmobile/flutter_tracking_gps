import 'package:flutter_tracking_gps/src/domain/tracking_state.dart';

/// One accepted stretch of distance.
class LedgerEntry {
  const LedgerEntry({
    required this.segmentId,
    required this.source,
    required this.meters,
    required this.at,
  });

  final int segmentId;
  final DistanceSource source;
  final double meters;
  final DateTime at;
}

/// Append-only record of accepted distance. The total is the sum of its
/// entries, so it can only grow, and every metre has a source.
class DistanceLedger {
  final _entries = <LedgerEntry>[];
  double _total = 0;
  final _bySource = <DistanceSource, double>{};

  double get totalMeters => _total;
  double meters(DistanceSource source) => _bySource[source] ?? 0;
  List<LedgerEntry> get entries => List.unmodifiable(_entries);

  /// Adds [meters] (ignored when not positive or not finite).
  void add({
    required int segmentId,
    required DistanceSource source,
    required double meters,
    required DateTime at,
  }) {
    if (!meters.isFinite || meters <= 0) return;
    _entries.add(
      LedgerEntry(segmentId: segmentId, source: source, meters: meters, at: at),
    );
    _total += meters;
    _bySource[source] = (_bySource[source] ?? 0) + meters;
  }

  void clear() {
    _entries.clear();
    _total = 0;
    _bySource.clear();
  }
}
