package tracker.algorithms.geometry

/** A coordinate pair for polyline work. */
data class LatLon(val latitude: Double, val longitude: Double)

/**
 * Polyline simplification for DISPLAY and UPLOAD size. Never use the simplified
 * line to measure distance: it is shorter than the real route by design.
 * Iterative (no recursion), so a long route cannot overflow the stack.
 */
object DouglasPeucker {
    fun simplify(points: List<LatLon>, toleranceMeters: Double): List<LatLon> {
        require(toleranceMeters >= 0.0) { "tolerance must not be negative" }
        if (points.size <= 2) return points.toList()

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var maxDistance = -1.0
            var split = -1
            for (i in start + 1 until end) {
                val d = GeoMath.project(
                    points[i].latitude, points[i].longitude,
                    points[start].latitude, points[start].longitude,
                    points[end].latitude, points[end].longitude,
                ).distanceMeters
                if (d > maxDistance) {
                    maxDistance = d
                    split = i
                }
            }
            if (split != -1 && maxDistance > toleranceMeters) {
                keep[split] = true
                stack.addLast(start to split)
                stack.addLast(split to end)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }
}
