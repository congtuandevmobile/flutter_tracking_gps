package tracker.algorithms.filtering

/** Rejects a position change that implies an impossible speed. */
object OutlierRejector {
    /**
     * True when covering [distanceMeters] in [seconds] needs more than [maxSpeedMps].
     * A non-finite or negative input, or no elapsed time, is NOT a valid
     * measurement and counts as a jump.
     */
    fun isJump(distanceMeters: Double, seconds: Double, maxSpeedMps: Double): Boolean =
        !distanceMeters.isFinite() || distanceMeters < 0.0 ||
            !seconds.isFinite() || seconds <= 0.0 ||
            !maxSpeedMps.isFinite() || maxSpeedMps <= 0.0 ||
            distanceMeters / seconds > maxSpeedMps
}
