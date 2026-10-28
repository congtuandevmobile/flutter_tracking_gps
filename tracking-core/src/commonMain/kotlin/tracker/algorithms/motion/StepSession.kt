package tracker.algorithms.motion

/**
 * Session-relative step accounting, the same policy on both platforms.
 *
 *  - Steps of closed segments are ACCEPTED and never change again (except by
 *    [correctClosedSegment], which replaces a segment's estimate with an exact count).
 *  - The first device-counter reading of a segment is only a BASELINE: it adds
 *    nothing, so steps taken before it (or while paused) are never counted.
 *  - A counter that goes backwards (reboot, sensor reset) keeps the accepted
 *    total and becomes the new baseline.
 *  - A segment-relative count (iOS `CMPedometer` counting from a start date) is
 *    cumulative within the segment: a lower reading is ignored.
 *
 * Each `on...` returns the steps it ADDED to [total] (never negative), which is
 * what the engine needs for distance. Not thread-safe.
 */
class StepSession {
    private var accepted = 0L
    private var segmentSteps = 0L
    private var baselineRaw: Long? = null
    private var lastRaw: Long? = null
    private var lastClosedSteps = 0L
    private var open = false
    private var hasClosedSegment = false

    /** Accepted steps of closed segments plus the open one. */
    val total: Long get() = accepted + segmentSteps

    /** New session. [initialSteps] restores a saved total (the device counter is never added to it). */
    fun reset(initialSteps: Long = 0) {
        accepted = initialSteps.coerceAtLeast(0)
        segmentSteps = 0
        baselineRaw = null
        lastRaw = null
        lastClosedSteps = 0
        open = false
        hasClosedSegment = false
    }

    /** A new segment: fresh baseline, accepted total kept. */
    fun openSegment() {
        segmentSteps = 0
        baselineRaw = null
        lastRaw = null
        open = true
    }

    /** A reading of the device's cumulative counter (Android `TYPE_STEP_COUNTER`). */
    fun onCounter(raw: Long): Long {
        if (!open || raw < 0) return 0
        val before = total
        val base = baselineRaw
        val previous = lastRaw
        when {
            base == null -> {
                baselineRaw = raw
                segmentSteps = 0
            }
            previous != null && raw < previous -> {
                accepted += segmentSteps
                segmentSteps = 0
                baselineRaw = raw
            }
            else -> segmentSteps = raw - base
        }
        lastRaw = raw
        return total - before
    }

    /** A count since the segment started (iOS). A lower reading is ignored. */
    fun onSegmentCount(steps: Long): Long {
        if (!open || steps < segmentSteps) return 0
        val before = total
        segmentSteps = steps
        return total - before
    }

    /**
     * Folds the open segment into the accepted total. A no-op when no segment is
     * open (finishing a session that is already paused): it must not overwrite
     * what [correctClosedSegment] needs to know about the last closed segment.
     */
    fun closeSegment() {
        if (!open) return
        open = false
        accepted += segmentSteps
        lastClosedSteps = segmentSteps
        hasClosedSegment = true
        segmentSteps = 0
        baselineRaw = null
        lastRaw = null
    }

    /**
     * Replaces the just-closed segment's count with [exactSteps] (iOS asks the
     * OS for the exact range at a boundary). Ignored once the next segment has
     * counted steps, or for a negative value. Returns the change to [total].
     */
    fun correctClosedSegment(exactSteps: Long): Long {
        if (!hasClosedSegment || exactSteps < 0 || segmentSteps != 0L) return 0
        val change = exactSteps - lastClosedSteps
        accepted += change
        lastClosedSteps = exactSteps
        return change
    }
}
