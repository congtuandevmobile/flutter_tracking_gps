package tracker.algorithms.cadence

/**
 * Decodes the Bluetooth "CSC Measurement" characteristic (0x2A5B) and turns the crank counters into a cadence.
 * Pure Kotlin: no Bluetooth, no clock of its own. The host (a BLE plugin) owns the connection and hands the raw bytes.
 *
 * Packet: flags (1 byte; bit 0 = wheel data, bit 1 = crank data), then wheel revolutions (uint32) and last wheel event
 * time (uint16) when bit 0 is set, then crank revolutions (uint16) and last crank event time (uint16, 1/1024 s) when
 * bit 1 is set. A wheel-only packet carries no cadence.
 *
 * [onMeasurement] answers with a cadence in rpm, [ZERO] when the cranks are verified stopped, or [UNKNOWN]:
 *  - A value is computed between two consecutive crank EVENTS, so the 16-bit counters may wrap freely.
 *  - "Stopped" needs valid notifications that keep arriving with an unchanged crank counter for longer than a
 *    revolution can take ([zeroAfterMs], or twice the last revolution time when that is longer). The lack of
 *    notifications is NOT zero: it is "unknown" once [staleMs] passes (see [rpm]).
 *  - The 16-bit event clock wraps every 64 s: when the previous revolution is [eventClockSpanMs] or more in the past the
 *    two samples are not comparable, so a new baseline is taken and no value is given.
 *  - A counter jump the elapsed time cannot explain (a sensor reset, a reconnect) takes a new baseline too.
 * Call [reset] for every new connection: the last sample of an old connection must never be compared with the first
 * of a new one. All `nowMs` must come from the same monotonic clock. Not thread-safe.
 */
class CscCadenceParser(
    private val staleMs: Long = 5_000,
    private val zeroAfterMs: Long = 3_000,
    private val minRpm: Double = 15.0,
    private val maxRpm: Double = 220.0,
    private val silenceResetMs: Long = 15_000,
    private val eventClockSpanMs: Long = 60_000,
) {
    init {
        require(staleMs > 0 && zeroAfterMs > 0 && silenceResetMs > 0 && eventClockSpanMs > 0) { "durations" }
        require(minRpm.isFinite() && maxRpm.isFinite() && minRpm > 0.0 && maxRpm > minRpm) { "rpm range" }
    }

    private var lastCount = -1
    private var lastTicks = -1
    private var lastRxMs = -1L
    private var lastRotationRxMs = -1L
    private var lastPeriodMs = 0L
    private var value = UNKNOWN

    /** Forgets the baseline and the value (a new connection, a closed session). */
    fun reset() {
        lastCount = -1
        lastTicks = -1
        lastRxMs = -1L
        lastRotationRxMs = -1L
        lastPeriodMs = 0L
        value = UNKNOWN
    }

    /** One notification. Malformed and wheel-only packets are ignored (the answer is [UNKNOWN], the state untouched). */
    fun onMeasurement(bytes: ByteArray, nowMs: Long): Double {
        val crank = decodeCrank(bytes) ?: return UNKNOWN
        return onCrank(crank[0], crank[1], nowMs)
    }

    /** The crank counters of one notification (already decoded), for a caller that has no `ByteArray` at hand. */
    fun onCrank(count: Int, eventTicks: Int, nowMs: Long): Double {
        if (count !in 0..MAX_U16 || eventTicks !in 0..MAX_U16 || nowMs < 0L) return UNKNOWN
        if (lastRxMs >= 0L && nowMs < lastRxMs) { // the clock went back: nothing before it can be compared
            reset()
            return UNKNOWN
        }
        if (lastRxMs >= 0L && nowMs - lastRxMs >= silenceResetMs) reset()
        val previousCount = lastCount
        val previousTicks = lastTicks
        lastRxMs = nowMs
        lastCount = count
        lastTicks = eventTicks
        if (previousCount < 0) { // the first notification of a connection is only a baseline
            lastRotationRxMs = nowMs
            value = UNKNOWN
            return UNKNOWN
        }

        val deltaRevolutions = (count - previousCount) and MAX_U16
        val sinceRotationMs = nowMs - lastRotationRxMs
        if (deltaRevolutions == 0) {
            val stoppedAfter = maxOf(zeroAfterMs, 2 * lastPeriodMs)
            if (sinceRotationMs >= stoppedAfter) value = ZERO
            return value
        }

        lastRotationRxMs = nowMs
        val deltaTicks = (eventTicks - previousTicks) and MAX_U16
        // The previous revolution is too old for a 16-bit clock to tell the elapsed time, or the counter moved by more
        // than the elapsed time allows at the fastest cadence: not comparable, start over.
        if (sinceRotationMs >= eventClockSpanMs || deltaRevolutions > maxRevolutionsIn(sinceRotationMs)) {
            value = UNKNOWN
            lastPeriodMs = 0L
            return UNKNOWN
        }
        if (deltaTicks == 0) {
            value = UNKNOWN
            return UNKNOWN
        }
        val rpm = deltaRevolutions * 60.0 * TICKS_PER_SECOND / deltaTicks
        if (!rpm.isFinite() || rpm < minRpm || rpm > maxRpm) {
            value = UNKNOWN
            lastPeriodMs = 0L
            return UNKNOWN
        }
        lastPeriodMs = (deltaTicks * 1000L / TICKS_PER_SECOND.toLong()) / deltaRevolutions
        value = rpm
        return rpm
    }

    /** The last answer while notifications keep arriving, [UNKNOWN] once none came for [staleMs] (never [ZERO]). */
    fun rpm(nowMs: Long): Double {
        if (lastRxMs < 0L || nowMs < lastRxMs || nowMs - lastRxMs > staleMs) return UNKNOWN
        return value
    }

    private fun maxRevolutionsIn(elapsedMs: Long): Long =
        (maxRpm * (elapsedMs + SLACK_MS) / 60_000.0).toLong() + 1L

    companion object {
        /** A parser with the starting values (Swift cannot use Kotlin default arguments). */
        fun standard(): CscCadenceParser = CscCadenceParser()

        /** Returned when there is no usable cadence. */
        const val UNKNOWN = -1.0

        /** Returned when the cranks are verified stopped. */
        const val ZERO = 0.0

        private const val MAX_U16 = 0xFFFF
        private const val TICKS_PER_SECOND = 1024.0
        private const val SLACK_MS = 1_500L

        /**
         * The crank revolution counter and the last crank event time of a CSC Measurement, or null for a packet that
         * is empty, truncated, or has no crank data (wheel only).
         */
        fun decodeCrank(bytes: ByteArray): IntArray? {
            if (bytes.isEmpty()) return null
            val flags = bytes[0].toInt() and 0xFF
            var offset = 1
            if (flags and 0x01 != 0) offset += 6
            if (flags and 0x02 == 0 || bytes.size < offset + 4) return null
            fun u16(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            return intArrayOf(u16(offset), u16(offset + 2))
        }
    }
}
