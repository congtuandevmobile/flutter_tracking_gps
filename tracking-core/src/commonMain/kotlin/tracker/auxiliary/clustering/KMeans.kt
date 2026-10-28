package tracker.auxiliary.clustering

import tracker.algorithms.geometry.GeoMath
import tracker.algorithms.geometry.LatLon
import tracker.algorithms.geometry.XY
import kotlin.math.hypot

/**
 * OFFLINE analysis only: splits a set of places into [k] regions. Centers start
 * evenly spread over the input order, so the result is deterministic.
 */
object KMeans {
    fun centers(points: List<LatLon>, k: Int, maxIterations: Int = 40): List<LatLon> {
        require(points.isNotEmpty() && k in 1..points.size && maxIterations > 0) { "invalid k or input" }
        val origin = points.first()
        val values = points.map { GeoMath.toXY(origin.latitude, origin.longitude, it.latitude, it.longitude) }
        var centers = List(k) { values[it * values.size / k] }

        repeat(maxIterations) {
            val groups = List(k) { ArrayList<XY>() }
            for (p in values) {
                var best = 0
                var bestD = Double.MAX_VALUE
                for (j in centers.indices) {
                    val d = (p.x - centers[j].x) * (p.x - centers[j].x) + (p.y - centers[j].y) * (p.y - centers[j].y)
                    if (d < bestD) {
                        bestD = d
                        best = j
                    }
                }
                groups[best].add(p)
            }
            val updated = centers.indices.map { i ->
                val g = groups[i]
                if (g.isEmpty()) centers[i] else XY(g.sumOf { it.x } / g.size, g.sumOf { it.y } / g.size)
            }
            val converged = updated.indices.all { hypot(updated[it].x - centers[it].x, updated[it].y - centers[it].y) < 0.01 }
            centers = updated
            if (converged) return toLatLon(origin, centers)
        }
        return toLatLon(origin, centers)
    }

    private fun toLatLon(origin: LatLon, centers: List<XY>): List<LatLon> = centers.map {
        val (lat, lon) = GeoMath.fromXY(origin.latitude, origin.longitude, it)
        LatLon(lat, lon)
    }
}
