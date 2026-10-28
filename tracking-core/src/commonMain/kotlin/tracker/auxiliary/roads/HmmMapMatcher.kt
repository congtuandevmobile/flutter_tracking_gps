package tracker.auxiliary.roads

import tracker.algorithms.geometry.GeoMath
import tracker.algorithms.geometry.LatLon
import kotlin.math.abs

/**
 * OFFLINE Viterbi map matching (Newson and Krumm style, reduced): emission by
 * the lateral distance to the road, transition by how well the road distance
 * matches the straight distance between two samples. A sample with no road
 * within the radius, or no legal transition, splits the trace.
 *
 * Reduced: no time or heading, no confidence, no spatial index. Use a routing
 * engine (OSRM Match, Valhalla) for production; split the trace at every Pause
 * before calling this.
 */
class HmmMapMatcher(
    private val graph: RoadGraph,
    private val sigmaMeters: Double = 12.0,
    private val betaMeters: Double = 40.0,
) {
    init {
        require(sigmaMeters.isFinite() && sigmaMeters > 0.0 && betaMeters.isFinite() && betaMeters > 0.0) {
            "sigma and beta must be positive"
        }
    }

    /** One entry per sample: the matched road position, or null when unmatched. */
    fun match(samples: List<LatLon>, radiusMeters: Double = 50.0): List<RoadCandidate?> {
        require(radiusMeters.isFinite() && radiusMeters > 0.0) { "radius must be positive" }
        val answers = MutableList<RoadCandidate?>(samples.size) { null }
        var offset = 0
        while (offset < samples.size) {
            val first = graph.candidates(samples[offset].latitude, samples[offset].longitude, radiusMeters)
            if (first.isEmpty()) {
                offset++
                continue
            }
            val stages = arrayListOf(first)
            val back = ArrayList<IntArray>()
            var scores = first.map { emission(it) }
            var end = offset + 1

            while (end < samples.size) {
                val next = graph.candidates(samples[end].latitude, samples[end].longitude, radiusMeters)
                if (next.isEmpty()) break
                val observed = GeoMath.haversineMeters(
                    samples[end - 1].latitude, samples[end - 1].longitude,
                    samples[end].latitude, samples[end].longitude,
                )
                val nextScores = MutableList(next.size) { Double.NEGATIVE_INFINITY }
                val pointers = IntArray(next.size) { -1 }
                for (j in next.indices) {
                    for (i in stages.last().indices) {
                        if (!scores[i].isFinite()) continue
                        val road = graph.travelDistance(stages.last()[i], next[j]) ?: continue
                        val score = scores[i] + emission(next[j]) - abs(road - observed) / betaMeters
                        if (score > nextScores[j]) {
                            nextScores[j] = score
                            pointers[j] = i
                        }
                    }
                }
                if (nextScores.none { it.isFinite() }) break
                stages.add(next)
                back.add(pointers)
                scores = nextScores
                end++
            }

            var index = scores.indices.maxByOrNull { scores[it] } ?: 0
            for (stage in stages.lastIndex downTo 0) {
                answers[offset + stage] = stages[stage][index]
                if (stage > 0) index = back[stage - 1][index]
            }
            offset += stages.size
        }
        return answers
    }

    private fun emission(c: RoadCandidate): Double = -0.5 * (c.lateralMeters / sigmaMeters) * (c.lateralMeters / sigmaMeters)
}
