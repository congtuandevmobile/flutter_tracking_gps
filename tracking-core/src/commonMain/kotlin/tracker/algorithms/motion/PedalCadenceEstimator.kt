package tracker.algorithms.motion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Estimates the pedalling cadence (revolutions per minute) from a phone accelerometer carried on the rider's leg
 * (trouser pocket, thigh). The leg moves once per crank revolution, so the acceleration seen by the phone is periodic
 * with the cadence; the period is found by autocorrelation.
 *
 * Pipeline, every [recomputeEveryMs]:
 *  1. The last [windowMs] of samples are averaged into uniform bins of [binMs] (a box filter, which also keeps vibration
 *     above the bin rate from aliasing); empty bins are interpolated, too many empty bins means "not enough data".
 *  2. The mean of each axis is removed (gravity and a fixed orientation), then the axis of greatest variance is found
 *     (power iteration on the 3x3 covariance) and the signal is projected on it. The phone's orientation in the pocket
 *     is unknown, so no fixed axis is assumed, and a projection (not the vector magnitude) keeps the fundamental
 *     frequency instead of doubling it.
 *  3. A signal weaker than [minAmplitude] (m/s^2, standard deviation) means no pedalling motion.
 *  4. The normalized autocorrelation is searched for peaks between [minRpm] and [maxRpm]. Among the peaks near the
 *     highest one the SMALLEST lag wins, so a peak at twice the period (a sub-harmonic) is not chosen. A best
 *     correlation below [minCorrelation] means the signal is not periodic enough to trust.
 *  5. The peak is refined by parabolic interpolation and smoothed with an exponential average.
 *
 * It answers with a negative number when it does not know, never with a guess: warming up (less than 75% of the window),
 * a stale feed, no motion, no clear period. A value is held for [holdMs] after the last good estimate.
 *
 * Limits: only meaningful with the phone on the leg (a handlebar mount sees road vibration, not the legs); coasting
 * with the legs still, or standing and pedalling out of rhythm, gives "unknown". Every number below is a STARTING
 * value chosen by reasoning, not measured on a bike: tune it from recordings.
 *
 * All times (`timeMs`, `nowMs`) must come from the SAME clock. Not thread-safe; O(window) per recompute.
 */
class PedalCadenceEstimator(
    private val windowMs: Long = 8_000,
    private val binMs: Long = 50,
    private val minRpm: Double = 30.0,
    private val maxRpm: Double = 130.0,
    private val minAmplitude: Double = 0.2,
    private val minCorrelation: Double = 0.4,
    private val subHarmonicRatio: Double = 0.85,
    private val smoothing: Double = 0.4,
    private val holdMs: Long = 5_000,
    private val recomputeEveryMs: Long = 1_000,
) {
    init {
        require(binMs > 0 && windowMs >= binMs * 40) { "window must hold at least 40 bins" }
        require(minRpm.isFinite() && maxRpm.isFinite() && minRpm > 0.0 && maxRpm > minRpm) { "rpm range" }
        require(minAmplitude.isFinite() && minAmplitude >= 0.0) { "minAmplitude" }
        require(minCorrelation.isFinite() && minCorrelation > 0.0 && minCorrelation < 1.0) { "minCorrelation" }
        require(subHarmonicRatio.isFinite() && subHarmonicRatio > 0.0 && subHarmonicRatio <= 1.0) { "subHarmonicRatio" }
        require(smoothing.isFinite() && smoothing > 0.0 && smoothing <= 1.0) { "smoothing" }
        require(holdMs >= 0 && recomputeEveryMs > 0) { "durations" }
    }

    private val capacity = 2048
    private val times = LongArray(capacity)
    private val xs = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private val zs = DoubleArray(capacity)
    private var head = 0 // index of the oldest sample
    private var size = 0

    private var estimate: Double? = null
    private var estimateAtMs = 0L
    private var lastComputeAtMs = Long.MIN_VALUE

    /** Feeds one reading (m/s^2, any orientation). Invalid or not-newer samples are ignored. */
    fun push(timeMs: Long, x: Double, y: Double, z: Double) {
        if (timeMs < 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (size > 0 && timeMs <= timeAt(size - 1)) return
        val slot: Int
        if (size == capacity) {
            slot = head
            head = (head + 1) % capacity
        } else {
            slot = (head + size) % capacity
            size++
        }
        times[slot] = timeMs
        xs[slot] = x
        ys[slot] = y
        zs[slot] = z
    }

    /** Forgets every sample and the last estimate (a new segment, a pause). */
    fun reset() {
        head = 0
        size = 0
        estimate = null
        estimateAtMs = 0L
        lastComputeAtMs = Long.MIN_VALUE
    }

    /** The cadence in revolutions per minute at [nowMs], or a NEGATIVE number when unknown. */
    fun rpm(nowMs: Long): Double {
        if (size == 0) return UNKNOWN
        val lastSampleMs = timeAt(size - 1)
        if (nowMs - lastSampleMs > STALE_FEED_MS) {
            // The feed stopped: whatever was held is no longer about now.
            estimate = null
            return UNKNOWN
        }
        if (lastComputeAtMs == Long.MIN_VALUE || nowMs - lastComputeAtMs >= recomputeEveryMs || nowMs < lastComputeAtMs) {
            lastComputeAtMs = nowMs
            compute(lastSampleMs)
        }
        val held = estimate ?: return UNKNOWN
        return if (lastSampleMs - estimateAtMs <= holdMs) held else UNKNOWN
    }

    private fun timeAt(i: Int): Long = times[(head + i) % capacity]
    private fun xAt(i: Int): Double = xs[(head + i) % capacity]
    private fun yAt(i: Int): Double = ys[(head + i) % capacity]
    private fun zAt(i: Int): Double = zs[(head + i) % capacity]

    private fun compute(endMs: Long) {
        val startMs = endMs - windowMs
        val bins = (windowMs / binMs).toInt()
        // A warm-up window: the oldest sample must reach back at least 75% of the window.
        if (timeAt(0) > startMs + windowMs / 4) return

        val bx = DoubleArray(bins)
        val by = DoubleArray(bins)
        val bz = DoubleArray(bins)
        val count = IntArray(bins)
        for (i in 0 until size) {
            val t = timeAt(i)
            if (t <= startMs) continue
            val b = ((t - startMs - 1) / binMs).toInt()
            if (b < 0 || b >= bins) continue
            bx[b] += xAt(i)
            by[b] += yAt(i)
            bz[b] += zAt(i)
            count[b]++
        }
        var empty = 0
        for (b in 0 until bins) {
            if (count[b] > 0) {
                bx[b] /= count[b]
                by[b] /= count[b]
                bz[b] /= count[b]
            } else {
                empty++
            }
        }
        if (empty > bins / 4) return
        if (empty > 0) interpolateEmpty(bx, by, bz, count)

        removeMean(bx)
        removeMean(by)
        removeMean(bz)
        val signal = project(bx, by, bz)
        var energy = 0.0
        for (v in signal) energy += v * v
        val std = sqrt(energy / bins)
        if (std < minAmplitude) return

        val fs = 1000.0 / binMs
        val minLag = maxOf(2, ceil(60.0 * fs / maxRpm).toInt())
        val maxLag = floor(60.0 * fs / minRpm).toInt().coerceAtMost(bins - bins / 4)
        if (maxLag <= minLag + 1) return

        // r[lag] for minLag-1 .. maxLag+1, normalized so a perfectly periodic signal peaks at about 1.
        val r = DoubleArray(maxLag + 2)
        for (lag in (minLag - 1)..(maxLag + 1)) {
            var sum = 0.0
            for (i in 0 until bins - lag) sum += signal[i] * signal[i + lag]
            r[lag] = (sum / (bins - lag)) / (energy / bins)
        }

        var best = 0.0
        for (lag in minLag..maxLag) if (isPeak(r, lag) && r[lag] > best) best = r[lag]
        if (best < minCorrelation) return
        var chosen = -1
        for (lag in minLag..maxLag) {
            if (isPeak(r, lag) && r[lag] >= best * subHarmonicRatio) {
                chosen = lag
                break
            }
        }
        if (chosen < 0) return

        val a = r[chosen - 1]
        val c = r[chosen + 1]
        val denom = a - 2.0 * r[chosen] + c
        val refined = if (abs(denom) > 1e-9) chosen + 0.5 * (a - c) / denom else chosen.toDouble()
        val rpm = 60.0 * fs / refined
        if (!rpm.isFinite() || rpm < minRpm * 0.9 || rpm > maxRpm * 1.1) return

        val previous = estimate
        estimate = if (previous == null) rpm else previous + smoothing * (rpm - previous)
        estimateAtMs = endMs
    }

    private fun isPeak(r: DoubleArray, lag: Int): Boolean = r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > 0.0

    private fun interpolateEmpty(bx: DoubleArray, by: DoubleArray, bz: DoubleArray, count: IntArray) {
        val n = count.size
        var i = 0
        while (i < n) {
            if (count[i] > 0) {
                i++
                continue
            }
            var j = i
            while (j < n && count[j] == 0) j++
            val left = i - 1
            val right = j
            for (k in i until j) {
                when {
                    left >= 0 && right < n -> {
                        val f = (k - left).toDouble() / (right - left)
                        bx[k] = bx[left] + f * (bx[right] - bx[left])
                        by[k] = by[left] + f * (by[right] - by[left])
                        bz[k] = bz[left] + f * (bz[right] - bz[left])
                    }
                    left >= 0 -> { bx[k] = bx[left]; by[k] = by[left]; bz[k] = bz[left] }
                    right < n -> { bx[k] = bx[right]; by[k] = by[right]; bz[k] = bz[right] }
                }
            }
            i = j
        }
    }

    private fun removeMean(v: DoubleArray) {
        var s = 0.0
        for (x in v) s += x
        val m = s / v.size
        for (i in v.indices) v[i] -= m
    }

    /** Projects the three axes on the direction of greatest variance. */
    private fun project(x: DoubleArray, y: DoubleArray, z: DoubleArray): DoubleArray {
        val n = x.size
        var cxx = 0.0; var cxy = 0.0; var cxz = 0.0; var cyy = 0.0; var cyz = 0.0; var czz = 0.0
        for (i in 0 until n) {
            cxx += x[i] * x[i]; cxy += x[i] * y[i]; cxz += x[i] * z[i]
            cyy += y[i] * y[i]; cyz += y[i] * z[i]; czz += z[i] * z[i]
        }
        var vx = 1.0; var vy = 1.0; var vz = 1.0
        for (iteration in 0 until 24) {
            val nx = cxx * vx + cxy * vy + cxz * vz
            val ny = cxy * vx + cyy * vy + cyz * vz
            val nz = cxz * vx + cyz * vy + czz * vz
            val norm = sqrt(nx * nx + ny * ny + nz * nz)
            if (norm < 1e-12) break
            vx = nx / norm; vy = ny / norm; vz = nz / norm
        }
        return DoubleArray(n) { i -> vx * x[i] + vy * y[i] + vz * z[i] }
    }

    companion object {
        /** An estimator with the starting values (Swift cannot use Kotlin default arguments). */
        fun standard(): PedalCadenceEstimator = PedalCadenceEstimator()

        /** What [rpm] returns when it does not know. */
        const val UNKNOWN = -1.0

        /** No sample for this long: the feed stopped. */
        const val STALE_FEED_MS = 3_000L
    }
}
