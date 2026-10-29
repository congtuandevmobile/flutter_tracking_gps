package tracker.algorithms.cadence

/**
 * What a native engine holds for cadence that does NOT come from the phone: it takes the raw CSC notifications (or a
 * ready rpm from any other source, such as a power meter) that a host-owned BLE component pushes, and answers with the
 * chosen cadence together with the phone estimate. The SDK never scans, pairs or reconnects: the host does, and tells the
 * hub through [onConnection].
 *
 * A source is identified by `(sourceId, connectionId)`. `connectionId` is a number the host bumps for every new
 * connection to the device, so a late notification of an old connection is dropped and the counters of a new connection
 * are never compared with those of the old one. Data of a connection that was closed is ignored until the host opens a new
 * one. Live only: nothing here is journaled and nothing touches distance. All times come from one monotonic clock.
 * Not thread-safe (the engine calls it under its own lock/queue).
 */
class ExternalCadenceHub(config: CadenceResolverConfig = CadenceResolverConfig()) {
    private val parser = CscCadenceParser()
    private val resolver = CadenceResolver(config)
    private var sourceId: String? = null
    private var connectionId = -1L
    private var closed = false

    /** The host connected to (or lost) the device. A different identity replaces the current source. */
    fun onConnection(sourceId: String, connectionId: Long, connected: Boolean) {
        if (connected) {
            if (!isCurrent(sourceId, connectionId) || closed) switchTo(sourceId, connectionId)
            resolver.onExternalConnection(true)
        } else if (isCurrent(sourceId, connectionId)) {
            closed = true
            parser.reset()
            resolver.onExternalConnection(false)
        }
    }

    /** A raw CSC Measurement notification. */
    fun onCsc(sourceId: String, connectionId: Long, bytes: ByteArray, nowMs: Long) {
        if (!accepts(sourceId, connectionId)) return
        val rpm = parser.onMeasurement(bytes, nowMs)
        if (rpm >= 0.0) resolver.onExternal(rpm, nowMs)
    }

    /** A cadence already in rpm, from a source that is not CSC. 0 is a measurement. */
    fun onRpm(sourceId: String, connectionId: Long, rpm: Double, nowMs: Long) {
        if (!accepts(sourceId, connectionId) || !rpm.isFinite() || rpm < 0.0 || rpm > MAX_RPM) return
        resolver.onExternal(rpm, nowMs)
    }

    /** The cadence to show: the external source when healthy, else the phone estimate ([phoneRpm] negative if none). */
    fun resolve(phoneRpm: Double, nowMs: Long): CadenceChoice {
        resolver.onPhone(phoneRpm, nowMs)
        return resolver.result(nowMs)
    }

    /** True while an external source is connected (it keeps the cadence available when the phone one is off). */
    fun hasExternalSource(): Boolean = sourceId != null && !closed

    fun reset() {
        parser.reset()
        resolver.reset()
        sourceId = null
        connectionId = -1L
        closed = false
    }

    private fun isCurrent(sourceId: String, connectionId: Long) = this.sourceId == sourceId && this.connectionId == connectionId

    /** Data counts for the current open connection; an unknown identity is an implicit new connection. */
    private fun accepts(sourceId: String, connectionId: Long): Boolean {
        if (isCurrent(sourceId, connectionId)) return !closed
        if (this.sourceId == sourceId && connectionId < this.connectionId) return false // an old connection's notification
        switchTo(sourceId, connectionId)
        resolver.onExternalConnection(true)
        return true
    }

    private fun switchTo(sourceId: String, connectionId: Long) {
        parser.reset()
        resolver.onExternalConnection(false)
        this.sourceId = sourceId
        this.connectionId = connectionId
        closed = false
    }

    companion object {
        fun standard(): ExternalCadenceHub = ExternalCadenceHub()
        private const val MAX_RPM = 300.0
    }
}
