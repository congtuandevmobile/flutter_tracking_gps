package tracker.auxiliary.roads

import tracker.algorithms.geometry.GeoMath

data class RoadNode(val id: String, val latitude: Double, val longitude: Double)

/** A DIRECTED road segment; a two-way road needs an edge each way. */
data class RoadEdge(val id: String, val from: String, val to: String, val lengthMeters: Double)

data class RoutePath(val lengthMeters: Double, val nodes: List<String>)

/** A GPS point projected on one road segment. */
data class RoadCandidate(
    val edge: RoadEdge,
    val fraction: Double,
    val lateralMeters: Double,
    val latitude: Double,
    val longitude: Double,
)

/**
 * OFFLINE / auxiliary: a directed road graph for map matching and routing. It
 * never feeds the recorded distance (matched coordinates must not replace the
 * real ones without a reconciliation and ground-truth tests). Needs a graph with
 * real road or trail data; the app has none, so this is for server-side or
 * future use.
 */
class RoadGraph(val nodes: List<RoadNode>, val edges: List<RoadEdge>) {
    private val byId = nodes.associateBy { it.id }
    private val adjacency = edges.groupBy { it.from }

    init {
        require(byId.size == nodes.size) { "duplicate node id" }
        require(nodes.all { it.id.isNotBlank() && GeoMath.valid(it.latitude, it.longitude) }) { "invalid node" }
        require(edges.map { it.id }.toSet().size == edges.size) { "duplicate edge id" }
        for (e in edges) {
            val a = byId[e.from]
            val b = byId[e.to]
            require(a != null && b != null) { "edge ${e.id} references an unknown node" }
            require(e.lengthMeters.isFinite() && e.lengthMeters > 0.0) { "edge ${e.id} has no valid length" }
            // The straight line is a valid A* heuristic only if no edge is shorter.
            require(e.lengthMeters + 1e-6 >= GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)) {
                "edge ${e.id} is shorter than the straight line"
            }
        }
    }

    fun node(id: String): RoadNode? = byId[id]

    /** Geometric matching: the nearest segments within [radiusMeters], closest first. */
    fun candidates(latitude: Double, longitude: Double, radiusMeters: Double, limit: Int = 5): List<RoadCandidate> {
        require(GeoMath.valid(latitude, longitude) && radiusMeters.isFinite() && radiusMeters > 0.0 && limit > 0) {
            "invalid candidate query"
        }
        return edges.map { edge ->
            val a = byId.getValue(edge.from)
            val b = byId.getValue(edge.to)
            val p = GeoMath.project(latitude, longitude, a.latitude, a.longitude, b.latitude, b.longitude)
            RoadCandidate(edge, p.fraction, p.distanceMeters, p.latitude, p.longitude)
        }.filter { it.lateralMeters <= radiusMeters }
            .sortedBy { it.lateralMeters }
            .take(limit)
    }

    /** Shortest path. [useAStar] false = Dijkstra; true = A* with the straight-line heuristic. */
    fun route(start: String, goal: String, useAStar: Boolean = true): RoutePath? {
        val goalNode = byId[goal] ?: return null
        if (start !in byId) return null

        fun heuristic(id: String): Double {
            if (!useAStar) return 0.0
            val n = byId.getValue(id)
            return GeoMath.haversineMeters(n.latitude, n.longitude, goalNode.latitude, goalNode.longitude)
        }

        val dist = HashMap<String, Double>()
        val previous = HashMap<String, String>()
        val heap = MinHeap()
        dist[start] = 0.0
        heap.push(start, heuristic(start))

        while (!heap.isEmpty()) {
            val (id, priority) = heap.pop()
            val best = dist[id] ?: continue
            if (priority > best + heuristic(id) + 1e-6) continue // stale entry
            if (id == goal) {
                val path = ArrayList<String>()
                var cur: String? = goal
                while (cur != null) {
                    path.add(cur)
                    cur = if (cur == start) null else previous[cur] ?: return null
                }
                path.reverse()
                return RoutePath(best, path)
            }
            for (e in adjacency[id].orEmpty()) {
                val next = best + e.lengthMeters
                if (next < (dist[e.to] ?: Double.POSITIVE_INFINITY)) {
                    dist[e.to] = next
                    previous[e.to] = id
                    heap.push(e.to, next + heuristic(e.to))
                }
            }
        }
        return null
    }

    /**
     * Topological matching: the road distance from [a] to [b] along connected,
     * correctly directed segments, or null when no legal path exists.
     */
    fun travelDistance(a: RoadCandidate, b: RoadCandidate): Double? {
        if (a.edge.id == b.edge.id && b.fraction >= a.fraction) {
            return (b.fraction - a.fraction) * a.edge.lengthMeters
        }
        val middle = route(a.edge.to, b.edge.from)?.lengthMeters ?: return null
        return (1 - a.fraction) * a.edge.lengthMeters + middle + b.fraction * b.edge.lengthMeters
    }
}

/** Binary min-heap on a Double priority (no `java.util.PriorityQueue` in common Kotlin). */
internal class MinHeap {
    private val ids = ArrayList<String>()
    private val priorities = ArrayList<Double>()

    fun isEmpty(): Boolean = ids.isEmpty()

    fun push(id: String, priority: Double) {
        ids.add(id)
        priorities.add(priority)
        var i = ids.lastIndex
        while (i > 0) {
            val parent = (i - 1) / 2
            if (priorities[parent] <= priorities[i]) break
            swap(i, parent)
            i = parent
        }
    }

    fun pop(): Pair<String, Double> {
        val top = ids[0] to priorities[0]
        val lastId = ids.removeAt(ids.lastIndex)
        val lastP = priorities.removeAt(priorities.lastIndex)
        if (ids.isNotEmpty()) {
            ids[0] = lastId
            priorities[0] = lastP
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var smallest = i
                if (l < ids.size && priorities[l] < priorities[smallest]) smallest = l
                if (r < ids.size && priorities[r] < priorities[smallest]) smallest = r
                if (smallest == i) break
                swap(i, smallest)
                i = smallest
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val id = ids[a]; ids[a] = ids[b]; ids[b] = id
        val p = priorities[a]; priorities[a] = priorities[b]; priorities[b] = p
    }
}
