package tracker.algorithms.power

/** What to ask the OS for. The OS may still deliver faster or slower. */
data class SamplingPlan(val intervalMs: Long, val minDistanceMeters: Double, val highAccuracy: Boolean) {
    init {
        require(intervalMs > 0 && minDistanceMeters.isFinite() && minDistanceMeters >= 0.0) { "invalid plan" }
    }

    /**
     * How long without a fix means "GPS is silent": at least four intervals,
     * never below [floorMs]. A sparse plan must not be mistaken for an outage.
     */
    fun silenceAfterMs(floorMs: Long = 10_000): Long = maxOf(floorMs, intervalMs * 4)
}

/**
 * Picks a sampling plan from the speed, with hysteresis so it does not flap.
 * An invalid or unknown speed, or a clock that went backwards, resets the
 * standstill timer and returns the moving plan (never a sparse one). Starting
 * values to benchmark, not measured. Geofencing is deliberately not used while
 * recording: the workout needs continuous fixes.
 */
class AdaptiveSamplingPolicy(
    private val batterySaver: Boolean = false,
    private val stillAfterMs: Long = 20_000,
) {
    init {
        require(stillAfterMs > 0) { "stillAfterMs" }
    }

    private var stillSinceMs: Long? = null
    private var lastAtMs: Long? = null

    fun plan(speedMps: Double?, nowMs: Long): SamplingPlan {
        val last = lastAtMs
        if (nowMs < 0 || (last != null && nowMs < last)) reset()
        if (nowMs < 0) return MOVING
        lastAtMs = nowMs
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0.0 }
        if (speed == null) {
            stillSinceMs = null
            return MOVING
        }
        if (speed >= STILL_SPEED_MPS) {
            stillSinceMs = null
            return if (speed >= FAST_SPEED_MPS) FAST else MOVING
        }
        val since = stillSinceMs ?: nowMs.also { stillSinceMs = it }
        if (nowMs - since < stillAfterMs) return MOVING
        return if (batterySaver) STILL_SAVER else STILL
    }

    fun reset() {
        stillSinceMs = null
        lastAtMs = null
    }

    private companion object {
        const val STILL_SPEED_MPS = 0.4
        const val FAST_SPEED_MPS = 6.0
        val FAST = SamplingPlan(1_000, 3.0, true)
        val MOVING = SamplingPlan(1_000, 2.0, true)
        val STILL = SamplingPlan(3_000, 3.0, true)
        val STILL_SAVER = SamplingPlan(5_000, 5.0, false)
    }
}
