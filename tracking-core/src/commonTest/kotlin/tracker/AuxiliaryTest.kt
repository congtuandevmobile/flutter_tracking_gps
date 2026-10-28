package tracker

import tracker.algorithms.geometry.LatLon
import tracker.auxiliary.clustering.Dbscan
import tracker.auxiliary.clustering.KMeans
import tracker.auxiliary.roads.HmmMapMatcher
import tracker.auxiliary.roads.RoadEdge
import tracker.auxiliary.roads.RoadGraph
import tracker.auxiliary.roads.RoadNode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STEP = 0.001 // about 111 m of latitude

private fun graph(): RoadGraph {
    // a -> b -> c -> d north; plus a long detour a -> x -> d; one-way only.
    val nodes = listOf(
        RoadNode("a", 10.0, 106.0), RoadNode("b", 10.0 + STEP, 106.0),
        RoadNode("c", 10.0 + 2 * STEP, 106.0), RoadNode("d", 10.0 + 3 * STEP, 106.0),
        RoadNode("x", 10.0 + 1.5 * STEP, 106.01),
    )
    fun len(i: RoadNode, j: RoadNode) = tracker.algorithms.geometry.GeoMath
        .haversineMeters(i.latitude, i.longitude, j.latitude, j.longitude) * 1.001
    val n = nodes.associateBy { it.id }
    fun e(id: String, f: String, t: String) = RoadEdge(id, f, t, len(n.getValue(f), n.getValue(t)))
    return RoadGraph(nodes, listOf(e("ab", "a", "b"), e("bc", "b", "c"), e("cd", "c", "d"), e("ax", "a", "x"), e("xd", "x", "d")))
}

class AuxiliaryTest {
    @Test fun dbscanFindsTwoClustersAndIgnoresNoise() {
        val a = List(6) { LatLon(10.0 + it * 0.000005, 106.0) }
        val b = List(6) { LatLon(10.01 + it * 0.000005, 106.0) }
        val noise = listOf(LatLon(10.005, 106.0))
        val clusters = Dbscan.cluster(a + b + noise, 20.0, 4)
        assertEquals(2, clusters.size)
        assertTrue(clusters.none { 12 in it })
        assertFailsWith<IllegalArgumentException> { Dbscan.cluster(a, 0.0, 3) }
    }

    @Test fun kmeansSeparatesTwoRegions() {
        val pts = List(5) { LatLon(10.0, 106.0 + it * 0.00001) } + List(5) { LatLon(10.1, 106.0 + it * 0.00001) }
        val centers = KMeans.centers(pts, 2).sortedBy { it.latitude }
        assertTrue(abs(centers[0].latitude - 10.0) < 0.001)
        assertTrue(abs(centers[1].latitude - 10.1) < 0.001)
    }

    @Test fun dijkstraAndAStarAgreeAndOneWayRoadsAreRespected() {
        val g = graph()
        val dij = assertNotNull(g.route("a", "d", useAStar = false))
        val star = assertNotNull(g.route("a", "d", useAStar = true))
        assertEquals(listOf("a", "b", "c", "d"), dij.nodes)
        assertEquals(dij.nodes, star.nodes)
        assertTrue(abs(dij.lengthMeters - star.lengthMeters) < 1e-6)
        assertNull(g.route("d", "a"), "there is no reverse edge")
        assertNull(g.route("a", "zzz"))
    }

    @Test fun anEdgeShorterThanTheStraightLineIsRejected() {
        val nodes = listOf(RoadNode("a", 10.0, 106.0), RoadNode("b", 10.01, 106.0))
        assertFailsWith<IllegalArgumentException> { RoadGraph(nodes, listOf(RoadEdge("ab", "a", "b", 5.0))) }
    }

    @Test fun geometricCandidatesAreClosestFirstAndTopologyUsesDirection() {
        val g = graph()
        val near = g.candidates(10.0 + 0.5 * STEP, 106.00005, 30.0)
        assertEquals("ab", near.first().edge.id)
        val back = g.candidates(10.0 + 2.5 * STEP, 106.0, 30.0).first()
        val front = g.candidates(10.0 + 0.5 * STEP, 106.0, 30.0).first()
        assertNull(g.travelDistance(back, front), "cannot drive against a one-way road")
        assertNotNull(g.travelDistance(front, back))
    }

    @Test fun hmmMatchesNoisySamplesToTheRoadTheyFollow() {
        val g = graph()
        val samples = (0..5).map { LatLon(10.0 + it * 0.5 * STEP, 106.00008) }
        val matched = HmmMapMatcher(g).match(samples)
        assertEquals(samples.size, matched.size)
        assertTrue(matched.all { it != null })
        assertTrue(matched.filterNotNull().all { it.edge.id in setOf("ab", "bc", "cd") })
        val unmatched = HmmMapMatcher(g).match(listOf(LatLon(20.0, 120.0)))
        assertNull(unmatched.single())
    }
}
