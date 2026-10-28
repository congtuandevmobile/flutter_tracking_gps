package tracker.engine

/**
 * Bounded, coordinate-free timeline of what the engine decided. Coordinates
 * never enter it: it is meant to be copied out of a device and shared.
 */
class TrackingLog(private val capacity: Int = 600) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = ArrayDeque<Pair<Long, String>>()
    private var dropped = 0

    val size: Int get() = entries.size

    fun note(atMs: Long, text: String) {
        if (entries.size >= capacity) {
            entries.removeFirst()
            dropped++
        }
        entries.addLast(atMs to text)
    }

    fun clear() {
        entries.clear()
        dropped = 0
    }

    fun report(): String {
        val b = StringBuilder()
        b.append("TIMELINE: ").append(entries.size).append(" entries")
        if (dropped > 0) b.append(" (+").append(dropped).append(" older dropped)")
        b.append('\n')
        for ((t, text) in entries) b.append(t).append(' ').append(text).append('\n')
        return b.toString()
    }
}
