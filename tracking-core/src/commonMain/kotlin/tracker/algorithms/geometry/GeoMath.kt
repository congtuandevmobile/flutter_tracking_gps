package tracker.algorithms.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on a local tangent plane, metres east / north of an origin. */
data class XY(val x: Double, val y: Double)

/** Result of projecting a point onto a segment. */
data class Projection(val fraction: Double, val distanceMeters: Double, val latitude: Double, val longitude: Double)

/**
 * Geodesy helpers on a sphere: accurate to well under a metre per kilometre for
 * the haversine; the local tangent plane is for SHORT ranges only (a few
 * kilometres) and refuses the poles. Longitudes wrap at +-180 degrees.
 */
object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val POLE_LIMIT = 1e-8

    /** True for a finite coordinate on Earth. */
    fun valid(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** Great-circle distance in metres. Throws for an invalid coordinate. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        require(valid(lat1, lon1) && valid(lat2, lon2)) { "invalid coordinate" }
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dp = rad(lat2 - lat1)
        val dl = rad(wrapDelta(lon2 - lon1))
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    fun metersPerDegreeLat(): Double = EARTH_RADIUS_METERS * PI / 180.0

    fun metersPerDegreeLon(latitude: Double): Double = metersPerDegreeLat() * cos(rad(latitude))

    /** Coordinates -> metres east/north of the origin (short ranges, not at the poles). */
    fun toXY(originLat: Double, originLon: Double, lat: Double, lon: Double): XY {
        require(valid(originLat, originLon) && valid(lat, lon)) { "invalid coordinate" }
        val scale = metersPerDegreeLon(originLat)
        require(abs(scale) > POLE_LIMIT) { "projection undefined near the poles" }
        return XY(wrapDelta(lon - originLon) * scale, (lat - originLat) * metersPerDegreeLat())
    }

    /** Metres east/north of the origin -> (latitude, longitude). */
    fun fromXY(originLat: Double, originLon: Double, p: XY): Pair<Double, Double> {
        require(valid(originLat, originLon) && p.x.isFinite() && p.y.isFinite()) { "invalid input" }
        val scale = metersPerDegreeLon(originLat)
        require(abs(scale) > POLE_LIMIT) { "projection undefined near the poles" }
        val lat = originLat + p.y / metersPerDegreeLat()
        require(lat in -90.0..90.0) { "projected latitude is outside the Earth" }
        return Pair(lat, wrap(originLon + p.x / scale))
    }

    /** Projects a point onto the segment a-b, clamped to its ends. Short segments only. */
    fun project(
        lat: Double, lon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
    ): Projection {
        val b = toXY(aLat, aLon, bLat, bLon)
        val p = toXY(aLat, aLon, lat, lon)
        val d2 = b.x * b.x + b.y * b.y
        val t = if (d2 <= 1e-12) 0.0 else ((p.x * b.x + p.y * b.y) / d2).coerceIn(0.0, 1.0)
        val foot = XY(b.x * t, b.y * t)
        val (fLat, fLon) = fromXY(aLat, aLon, foot)
        return Projection(t, hypot(p.x - foot.x, p.y - foot.y), fLat, fLon)
    }

    /** Difference of two longitudes folded into [-180, 180). */
    private fun wrapDelta(degrees: Double): Double = ((degrees + 540.0) % 360.0) - 180.0

    /** A longitude folded into [-180, 180). */
    private fun wrap(degrees: Double): Double = ((degrees + 540.0) % 360.0) - 180.0

    private fun rad(deg: Double): Double = deg * PI / 180.0
}
