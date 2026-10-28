/// Dart twin of `tracker.algorithms.motion.StrideEstimator` (tracking-core).
///
/// Learns metres per step from stretches where GPS is good: steps and GPS
/// metres of the SAME window are accumulated and only turned into a sample once
/// both are big enough. A window with many steps and almost no GPS distance
/// (standing, phone shaken in the hand) is abandoned; a sample outside
/// [minStride, maxStride] is discarded. Exponential moving average.
///
/// Every number is an unmeasured starting value. Change a rule here AND in the
/// Kotlin core, and add it to a test so the two cannot drift apart.
class StrideEstimator {
  StrideEstimator({
    double? initialStrideMeters,
    this.minSteps = 20,
    this.minMeters = 20,
    this.minStride = 0.3,
    this.maxStride = 2.5,
    this.alpha = 0.3,
    this.abandonSteps = 200,
  }) : _initial = initialStrideMeters,
       strideMeters = initialStrideMeters {
    if (initialStrideMeters != null &&
        !(initialStrideMeters >= minStride && initialStrideMeters <= maxStride)) {
      throw ArgumentError('initial stride outside the plausible range');
    }
  }

  final int minSteps;
  final double minMeters;
  final double minStride;
  final double maxStride;
  final double alpha;
  final int abandonSteps;

  final double? _initial;
  int _windowSteps = 0;
  double _windowMeters = 0;

  /// Metres per step; null until the first plausible window (or an initial value).
  double? strideMeters;

  /// How many windows taught the stride in this session.
  int sampleCount = 0;

  void addSteps(int steps) {
    if (steps <= 0) return;
    _windowSteps += steps;
    _flushIfReady();
  }

  void addMeters(double meters) {
    if (!meters.isFinite || meters <= 0) return;
    _windowMeters += meters;
    _flushIfReady();
  }

  /// The GPS quality changed: steps and metres no longer describe the same
  /// stretch. The learned stride is kept.
  void breakWindow() {
    _windowSteps = 0;
    _windowMeters = 0;
  }

  /// New session: back to the configured value.
  void reset() {
    breakWindow();
    strideMeters = _initial;
    sampleCount = 0;
  }

  void _flushIfReady() {
    if (_windowSteps >= minSteps && _windowMeters >= minMeters) {
      final sample = _windowMeters / _windowSteps;
      if (sample >= minStride && sample <= maxStride) {
        final current = strideMeters;
        strideMeters = current == null
            ? sample
            : current + alpha * (sample - current);
        sampleCount++;
      }
      breakWindow();
    } else if (_windowSteps >= abandonSteps && _windowMeters < minMeters) {
      breakWindow();
    }
  }
}
