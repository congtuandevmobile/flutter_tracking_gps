package tracker.engine

import tracker.model.DistanceSource
import tracker.model.LedgerEntry

/**
 * Append-only record of accepted distance. The total is the sum of its entries,
 * so it can only grow, and every metre has a source.
 */
class DistanceLedger {
    private val items = ArrayList<LedgerEntry>()
    private val bySource = HashMap<DistanceSource, Double>()

    var totalMeters: Double = 0.0
        private set

    val entries: List<LedgerEntry> get() = items.toList()

    fun meters(source: DistanceSource): Double = bySource[source] ?: 0.0

    /**
     * Adds [meters]. A negative or non-finite amount is a bug in the caller and
     * throws; zero is ignored. The total must stay finite.
     */
    fun add(segmentId: Int, source: DistanceSource, meters: Double, atMs: Long) {
        require(meters.isFinite() && meters >= 0.0) { "distance must be finite and not negative" }
        if (meters == 0.0) return
        val updated = totalMeters + meters
        require(updated.isFinite()) { "distance overflow" }
        items.add(LedgerEntry(segmentId, source, meters, atMs))
        totalMeters = updated
        bySource[source] = meters(source) + meters
    }

    fun clear() {
        items.clear()
        bySource.clear()
        totalMeters = 0.0
    }
}
