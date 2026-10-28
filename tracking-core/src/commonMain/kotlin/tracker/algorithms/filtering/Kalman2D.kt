package tracker.algorithms.filtering

import tracker.algorithms.geometry.GeoMath
import tracker.algorithms.geometry.XY
import kotlin.math.max
import kotlin.math.pow

/**
 * Constant-velocity Kalman filter on a local tangent plane (one independent
 * filter per axis), fed by GPS positions weighted by their accuracy. In the
 * engine it is a QUALITY GATE: it says whether an observation is plausible, and
 * the distance is always measured on the RAW accepted fixes, never on the
 * filtered coordinates (smoothing would cut corners and lose distance).
 *
 * An observation too far from the prediction (chi-square gate) is rejected;
 * after [resetAfterRejects] in a row, or after a silence longer than
 * [maxGapMs], the filter re-localizes and reports a DISCONTINUITY, which the
 * caller must treat as a new anchor, never as a distance step.
 *
 * Limits: no accelerometer or gyroscope fusion, one motion model for every sport,
 * and sharp turns can be rejected.
 */
class Kalman2D(
    private val accelerationSigma: Double = 2.0,
    private val gateChiSquare: Double = 13.82,
    private val maxGapMs: Long = 30_000,
    private val resetAfterRejects: Int = 5,
) {
    enum class Status { INITIALIZED, UPDATED, REJECTED, OUT_OF_ORDER, GAP_RESET, RELOCALIZED }

    class Output(
        val latitude: Double,
        val longitude: Double,
        /** False when the observation was gated out (the position is the prediction). */
        val accepted: Boolean,
        /** True when the filter re-localized: do not measure distance across it. */
        val discontinuity: Boolean,
        val status: Status,
    )

    init {
        require(accelerationSigma.isFinite() && accelerationSigma >= 0.0) { "accelerationSigma" }
        require(gateChiSquare.isFinite() && gateChiSquare > 0.0) { "gateChiSquare" }
        require(maxGapMs > 0 && resetAfterRejects > 0) { "gap and reject limits" }
    }

    private class Axis(position: Double, variance: Double) {
        var p = position
        var v = 0.0
        var p00 = variance
        var p01 = 0.0
        var p11 = 25.0

        fun predict(dt: Double, q: Double) {
            p += v * dt
            val a = p00
            val b = p01
            val c = p11
            val dt2 = dt * dt
            p00 = a + 2 * dt * b + dt2 * c + q * dt2 * dt2 / 4
            p01 = b + dt * c + q * dt2 * dt / 2
            p11 = c + q * dt2
        }

        fun update(measurement: Double, r: Double) {
            val a = p00
            val b = p01
            val c = p11
            val s = a + r
            val k0 = a / s
            val k1 = b / s
            val residual = measurement - p
            p += k0 * residual
            v += k1 * residual
            // Joseph form: stays symmetric and non-negative.
            val m = 1.0 - k0
            p00 = max(0.0, m * m * a + k0 * k0 * r)
            p01 = m * (b - k1 * a) + k0 * k1 * r
            p11 = max(0.0, c - 2 * k1 * b + k1 * k1 * (a + r))
        }
    }

    private var originLat = 0.0
    private var originLon = 0.0
    private var hasOrigin = false
    private var predictionAtMs = 0L
    private var acceptedAtMs = 0L
    private var x: Axis? = null
    private var y: Axis? = null
    private var rejects = 0

    fun reset() {
        hasOrigin = false
        x = null
        y = null
        originLat = 0.0
        originLon = 0.0
        predictionAtMs = 0L
        acceptedAtMs = 0L
        rejects = 0
    }

    fun push(latitude: Double, longitude: Double, accuracyMeters: Double, timeMs: Long): Output {
        require(GeoMath.valid(latitude, longitude)) { "invalid coordinate" }
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0) { "invalid accuracy" }
        require(timeMs >= 0L) { "invalid time" }
        val r = max(accuracyMeters, MIN_ACCURACY).pow(2)

        val xa = x
        val ya = y
        if (!hasOrigin || xa == null || ya == null) {
            localize(latitude, longitude, r, timeMs)
            return Output(latitude, longitude, accepted = true, discontinuity = false, status = Status.INITIALIZED)
        }
        if (timeMs <= predictionAtMs) return current(accepted = false, discontinuity = false, status = Status.OUT_OF_ORDER)
        if (timeMs - acceptedAtMs > maxGapMs) {
            localize(latitude, longitude, r, timeMs)
            return Output(latitude, longitude, accepted = true, discontinuity = true, status = Status.GAP_RESET)
        }

        val dt = (timeMs - predictionAtMs) / 1000.0
        val q = accelerationSigma * accelerationSigma
        xa.predict(dt, q)
        ya.predict(dt, q)
        predictionAtMs = timeMs

        val z = GeoMath.toXY(originLat, originLon, latitude, longitude)
        val chi2 = (z.x - xa.p).pow(2) / (xa.p00 + r) + (z.y - ya.p).pow(2) / (ya.p00 + r)
        if (!chi2.isFinite() || chi2 > gateChiSquare) {
            rejects++
            if (rejects >= resetAfterRejects) {
                localize(latitude, longitude, r, timeMs)
                return Output(latitude, longitude, accepted = true, discontinuity = true, status = Status.RELOCALIZED)
            }
            return current(accepted = false, discontinuity = false, status = Status.REJECTED)
        }
        rejects = 0
        xa.update(z.x, r)
        ya.update(z.y, r)
        acceptedAtMs = timeMs
        return current(accepted = true, discontinuity = false, status = Status.UPDATED)
    }

    private fun localize(lat: Double, lon: Double, r: Double, timeMs: Long) {
        originLat = lat
        originLon = lon
        hasOrigin = true
        x = Axis(0.0, r)
        y = Axis(0.0, r)
        predictionAtMs = timeMs
        acceptedAtMs = timeMs
        rejects = 0
    }

    private fun current(accepted: Boolean, discontinuity: Boolean, status: Status): Output {
        val xa = checkNotNull(x)
        val ya = checkNotNull(y)
        val (lat, lon) = GeoMath.fromXY(originLat, originLon, XY(xa.p, ya.p))
        return Output(lat, lon, accepted, discontinuity, status)
    }

    private companion object {
        /** A claimed accuracy below this is not believed. */
        const val MIN_ACCURACY = 3.0
    }
}
