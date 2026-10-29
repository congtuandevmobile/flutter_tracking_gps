package tracker.algorithms.cadence

/** Where a cadence value came from. */
enum class CadenceSource { NONE, PHONE, EXTERNAL }

/** The chosen cadence: [rpm] is [CscCadenceParser.UNKNOWN] (negative) when [source] is [CadenceSource.NONE]. */
class CadenceChoice(val rpm: Double, val source: CadenceSource)

/** Starting values, not measured. */
class CadenceResolverConfig(
    /** An external source with no valid value for this long is stale. */
    val externalStaleAfterMs: Long = 5_000,
    /** The phone estimate older than this is stale. */
    val phoneStaleAfterMs: Long = 3_000,
    /** After a (re)connect or a stale spell the external source needs this many valid values... */
    val externalRecoveryMinSamples: Int = 2,
    /** ...spread over at least this long, before it is preferred again. */
    val externalRecoveryMinMs: Long = 1_000,
) {
    init {
        require(externalStaleAfterMs > 0 && phoneStaleAfterMs > 0 && externalRecoveryMinMs >= 0) { "durations" }
        require(externalRecoveryMinSamples >= 1) { "samples" }
    }

    companion object {
        fun standard(): CadenceResolverConfig = CadenceResolverConfig()
    }
}

/**
 * Picks ONE cadence source: a healthy external sensor (a bike cadence sensor, a power meter) wins over the phone
 * estimate, which wins over nothing. Values are never mixed or averaged.
 *
 * Health is decided by connection and freshness, NEVER by the value: an external 0 rpm is a measurement (the cranks
 * are still) and keeps the source, it is not "no data" and must not fall back to the phone.
 *
 * To avoid flapping, an external source that was lost (disconnected, stale) is preferred again only after
 * [CadenceResolverConfig.externalRecoveryMinSamples] valid values over [CadenceResolverConfig.externalRecoveryMinMs].
 * All times come from one monotonic clock. Not thread-safe.
 */
class CadenceResolver(private val config: CadenceResolverConfig = CadenceResolverConfig()) {
    private var connected = false
    private var externalRpm = UNKNOWN
    private var externalAtMs = -1L
    private var healthySamples = 0
    private var healthySinceMs = -1L
    private var phoneRpm = UNKNOWN
    private var phoneAtMs = -1L

    /** The external source (dis)connected. A disconnect drops its value at once and forgets its recovery progress. */
    fun onExternalConnection(isConnected: Boolean) {
        if (connected == isConnected) return
        connected = isConnected
        dropExternal()
    }

    /** A measurement of the external source: [rpm] 0 or more is a value (0 = cranks still); anything else is ignored. */
    fun onExternal(rpm: Double, atMs: Long) {
        if (!connected || atMs < externalAtMs) return
        if (!rpm.isFinite() || rpm < 0.0) return
        if (externalAtMs >= 0L && atMs - externalAtMs > config.externalStaleAfterMs) dropExternal() // a stale spell passed
        externalRpm = rpm
        externalAtMs = atMs
        if (healthySamples == 0) healthySinceMs = atMs
        healthySamples += 1
    }

    /** The phone estimate. Pass a negative [rpm] when it does not know (it then takes no part). */
    fun onPhone(rpm: Double, atMs: Long) {
        if (atMs < phoneAtMs) return
        phoneAtMs = atMs
        phoneRpm = if (rpm.isFinite() && rpm >= 0.0) rpm else UNKNOWN
    }

    fun result(nowMs: Long): CadenceChoice {
        if (externalPreferred(nowMs)) return CadenceChoice(externalRpm, CadenceSource.EXTERNAL)
        if (phoneRpm >= 0.0 && phoneAtMs >= 0L && nowMs >= phoneAtMs && nowMs - phoneAtMs <= config.phoneStaleAfterMs) {
            return CadenceChoice(phoneRpm, CadenceSource.PHONE)
        }
        return CadenceChoice(UNKNOWN, CadenceSource.NONE)
    }

    fun reset() {
        connected = false
        dropExternal()
        phoneRpm = UNKNOWN
        phoneAtMs = -1L
    }

    private fun externalPreferred(nowMs: Long): Boolean {
        if (!connected || externalAtMs < 0L || nowMs < externalAtMs) return false
        if (nowMs - externalAtMs > config.externalStaleAfterMs) {
            dropExternal() // stale: it must earn its place again
            return false
        }
        return healthySamples >= config.externalRecoveryMinSamples && externalAtMs - healthySinceMs >= config.externalRecoveryMinMs
    }

    private fun dropExternal() {
        externalRpm = UNKNOWN
        externalAtMs = -1L
        healthySamples = 0
        healthySinceMs = -1L
    }

    companion object {
        private const val UNKNOWN = CscCadenceParser.UNKNOWN
        fun standard(): CadenceResolver = CadenceResolver()
    }
}
