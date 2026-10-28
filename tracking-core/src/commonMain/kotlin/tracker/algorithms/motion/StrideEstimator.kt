package tracker.algorithms.motion

/**
 * Learns the user's stride length (metres per step) from stretches where GPS is
 * good, so a step count can later stand in for distance while GPS is weak.
 *
 * It accumulates steps and GPS metres of the SAME window and only produces a
 * sample once both are big enough to average out step batching (the OS reports
 * steps in lots, up to ~10 s late on Android) and GPS noise. A window with many
 * steps but almost no GPS distance (standing still, phone shaken in the hand) is
 * abandoned, and a sample outside [minStride, maxStride] is discarded: neither
 * can pollute the estimate.
 *
 * The estimate is an exponential moving average, so one odd window moves it only
 * a little. Not measured on devices: every number is a starting value.
 *
 * Not thread-safe; O(1) per call.
 */
class StrideEstimator(
    initialStrideMeters: Double? = null,
    private val minSteps: Long = 20,
    private val minMeters: Double = 20.0,
    private val minStride: Double = 0.3,
    private val maxStride: Double = 2.5,
    private val alpha: Double = 0.3,
    private val abandonSteps: Long = 200,
) {
    init {
        require(minSteps > 0 && abandonSteps >= minSteps) { "step thresholds" }
        require(minMeters.isFinite() && minMeters > 0.0) { "minMeters" }
        require(minStride.isFinite() && minStride > 0.0 && maxStride.isFinite() && maxStride > minStride) { "stride bounds" }
        require(alpha.isFinite() && alpha > 0.0 && alpha <= 1.0) { "alpha" }
        require(initialStrideMeters == null || (initialStrideMeters.isFinite() && initialStrideMeters in minStride..maxStride)) {
            "initial stride outside the plausible range"
        }
    }

    private val initial = initialStrideMeters
    private var windowSteps = 0L
    private var windowMeters = 0.0

    /** Metres per step, or null until the first plausible window (or an initial value). */
    var strideMeters: Double? = initialStrideMeters
        private set

    /** How many windows taught the stride in this session. */
    var sampleCount: Int = 0
        private set

    fun addSteps(steps: Long) {
        if (steps <= 0L) return
        windowSteps += steps
        flushIfReady()
    }

    fun addMeters(meters: Double) {
        if (!meters.isFinite() || meters <= 0.0) return
        windowMeters += meters
        flushIfReady()
    }

    /**
     * Drops the open window: the GPS quality changed, so steps and metres no
     * longer describe the same stretch. The learned stride is kept.
     */
    fun breakWindow() {
        windowSteps = 0
        windowMeters = 0.0
    }

    /** New session: forgets what was learned and starts from the configured value. */
    fun reset() {
        breakWindow()
        strideMeters = initial
        sampleCount = 0
    }

    private fun flushIfReady() {
        if (windowSteps >= minSteps && windowMeters >= minMeters) {
            val sample = windowMeters / windowSteps
            if (sample in minStride..maxStride) {
                val current = strideMeters
                strideMeters = if (current == null) sample else current + alpha * (sample - current)
                sampleCount++
            }
            breakWindow()
        } else if (windowSteps >= abandonSteps && windowMeters < minMeters) {
            // Plenty of steps, hardly any GPS distance: standing or shaking.
            breakWindow()
        }
    }
}
