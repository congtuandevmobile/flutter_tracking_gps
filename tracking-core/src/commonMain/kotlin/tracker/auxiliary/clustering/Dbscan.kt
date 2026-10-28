package tracker.auxiliary.clustering

import tracker.algorithms.geometry.GeoMath
import tracker.algorithms.geometry.LatLon

/**
 * OFFLINE analysis only (not part of the distance): groups points that are
 * close in SPACE. It cannot tell a continuous rest from a runner who passes the
 * same place twice; for a stop during recording use `StopDetector`.
 * O(n²): fine for a recorded trace, not for a city.
 */
object Dbscan {
    /** Indices of the points of each cluster; noise points belong to none. */
    fun cluster(points: List<LatLon>, epsilonMeters: Double, minPoints: Int): List<List<Int>> {
        require(epsilonMeters > 0.0 && minPoints > 0) { "epsilon and minPoints must be positive" }
        val visited = BooleanArray(points.size)
        val assigned = BooleanArray(points.size)
        val clusters = ArrayList<List<Int>>()

        fun neighbors(i: Int): List<Int> = points.indices.filter {
            GeoMath.haversineMeters(
                points[i].latitude, points[i].longitude, points[it].latitude, points[it].longitude,
            ) <= epsilonMeters
        }

        for (i in points.indices) {
            if (visited[i]) continue
            visited[i] = true
            val near = neighbors(i)
            if (near.size < minPoints) continue

            val members = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            val queued = HashSet<Int>()
            for (j in near) if (queued.add(j)) queue.addLast(j)
            while (queue.isNotEmpty()) {
                val j = queue.removeFirst()
                if (!visited[j]) {
                    visited[j] = true
                    val around = neighbors(j)
                    if (around.size >= minPoints) for (k in around) if (queued.add(k)) queue.addLast(k)
                }
                if (!assigned[j]) {
                    assigned[j] = true
                    members.add(j)
                }
            }
            clusters.add(members.sorted())
        }
        return clusters
    }
}
