package tracker.algorithms.filtering

/**
 * Exponential moving average for scalar signals (speed, altitude) AFTER the
 * outliers were removed. Do not stack it on top of [Kalman2D] output: that only
 * adds delay.
 */
class LowPassFilter(private val alpha: Double) {
    init {
        require(alpha in 0.0..1.0) { "alpha must be in 0..1" }
    }

    private var estimate: Double? = null

    fun push(value: Double): Double {
        require(value.isFinite()) { "value must be finite" }
        val previous = estimate
        val result = if (previous == null) value else alpha * value + (1 - alpha) * previous
        estimate = result
        return result
    }

    fun reset() {
        estimate = null
    }
}
