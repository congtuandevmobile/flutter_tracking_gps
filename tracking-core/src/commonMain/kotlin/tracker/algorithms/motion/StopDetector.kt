package tracker.algorithms.motion

import tracker.algorithms.geometry.GeoMath

/**
 * Online stay-point detector. The phone is STOPPED once it stayed within
 * [radiusMeters] of one point AND showed no sign of motion (no Doppler speed at
 * or above [stationarySpeedMps]) for at least [minDwellMs], without a hole in
 * the observations longer than [maxObservationGapMs]. Leaving the radius, or
 * moving at speed, ends the stop at once and starts a new window.
 *
 * It is a CANDIDATE signal for moving time, not the sole authority. O(1) per
 * fix; a runner passing the same place twice is not a stop.
 */
class StopDetector(
    private val radiusMeters: Double = 15.0,
    private val minDwellMs: Long = 30_000,
    private val stationarySpeedMps: Double = 0.4,
    private val maxObservationGapMs: Long = 10_000,
) {
    init {
        require(radiusMeters.isFinite() && radiusMeters > 0.0) { "radius" }
        require(minDwellMs > 0 && maxObservationGapMs > 0) { "durations" }
        require(stationarySpeedMps.isFinite() && stationarySpeedMps >= 0.0) { "speed" }
    }

    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorMs = 0L
    private var latestMs = 0L
    private var hasAnchor = false

    /** True while the current stay lasted at least [minDwellMs]. */
    var isStopped: Boolean = false
        private set

    fun push(timeMs: Long, latitude: Double, longitude: Double, speedMps: Double?): Boolean {
        if (!GeoMath.valid(latitude, longitude) || timeMs < 0L) return isStopped
        if (hasAnchor && timeMs <= latestMs) return isStopped // out of order: ignore
        if (!hasAnchor || timeMs - latestMs > maxObservationGapMs) {
            start(timeMs, latitude, longitude)
            return false
        }
        latestMs = timeMs
        val inside = GeoMath.haversineMeters(anchorLat, anchorLon, latitude, longitude) <= radiusMeters
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0.0 }
        val moving = speed != null && speed >= stationarySpeedMps
        if (inside && !moving) {
            isStopped = timeMs - anchorMs >= minDwellMs
        } else {
            start(timeMs, latitude, longitude)
        }
        return isStopped
    }

    fun reset() {
        hasAnchor = false
        isStopped = false
        latestMs = 0L
    }

    private fun start(timeMs: Long, lat: Double, lon: Double) {
        anchorLat = lat
        anchorLon = lon
        anchorMs = timeMs
        latestMs = timeMs
        hasAnchor = true
        isStopped = false
    }
}
