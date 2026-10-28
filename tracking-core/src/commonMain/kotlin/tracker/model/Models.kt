package tracker.model

/** The recording lifecycle. Not the same thing as GPS quality or movement. */
enum class RecordingState { IDLE, RUNNING, PAUSED, FINISHED }

enum class GpsQuality {
    UNKNOWN,
    GOOD,
    DEGRADED,
    UNAVAILABLE,
    /** The first good fix after a gap: the gap was just reconciled. */
    RECOVERING,
}

/** Where a metre of the total came from. One source per stretch of time. */
enum class DistanceSource { GPS, GAP_BRIDGE, PDR }

enum class FixVerdict {
    ANCHOR, ACCEPTED, RECOVERED, NOISE, STATIONARY, JUMP, OUTLIER,
    LOW_ACCURACY, STALE, DUPLICATE, WRONG_SEGMENT, MOCK, NOT_RUNNING,
    /** The fix belongs to another recording session. */
    WRONG_SESSION,
    /** A coordinate, accuracy or speed is not a finite, plausible number. */
    INVALID,
    /** Measured later than the arrival clock allows (clocks out of step). */
    FUTURE,
    /** Too many implausible jumps: a fresh anchor was set, nothing was credited. */
    REANCHORED,
}

enum class PdrPolicy {
    OFF,

    /** A step estimate only counts as far as the returning GPS corroborates it. */
    CONFIRMED_BY_GPS,

    /**
     * Like [CONFIRMED_BY_GPS], but once the stride has been calibrated on good
     * GPS the estimate for a gap is credited as ESTIMATED distance (source `PDR`)
     * even when the gap is long or the route curves, bounded by the speed
     * ceiling. Opt-in: a phone shaken in the hand during a gap can then be
     * credited, which the conservative policies never do.
     */
    TRUSTED_WHEN_CALIBRATED,
}

enum class Smoothing { OFF, KALMAN }

/** One raw location reading. `null` = the platform did not know the value. */
data class TrackingFix(
    val sessionId: String,
    val segmentId: Int,
    val seq: Long,
    /** When the position was measured, epoch ms. */
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Double? = null,
    val speed: Double? = null,
    val speedAccuracy: Double? = null,
    val course: Double? = null,
    val altitude: Double? = null,
    val verticalAccuracy: Double? = null,
    val provider: String = "unknown",
    val isMock: Boolean = false,
)

/**
 * Every number is a STARTING VALUE chosen by reasoning, not measured on
 * devices. Tune from real recordings, never by feel.
 */
data class TrackingConfig(
    val maxAccuracyMeters: Double = 40.0,
    val maxFixAgeMs: Long = 30_000,
    val silenceAfterMs: Long = 10_000,
    val maxSpeedMps: Double = 10.0,
    val minStepMeters: Double = 2.0,
    val accuracyNoiseFactor: Double = 0.5,
    val stationarySpeedMps: Double = 0.4,
    val maxBridgeMs: Long = 120_000,
    val reanchorAfterRejects: Int = 3,
    val rejectMock: Boolean = true,
    val pdrPolicy: PdrPolicy = PdrPolicy.CONFIRMED_BY_GPS,
    val pdrConfirmRatio: Double = 0.5,
    val smoothing: Smoothing = Smoothing.OFF,
    /** Stay-point detector used for moving time. */
    val stopRadiusMeters: Double = 15.0,
    val stopMinDwellMs: Long = 30_000,
    /**
     * Stride (metres per step) to start from, e.g. the one learned in the user's
     * last recording. null = none: a step count is not turned into a distance
     * estimate until GPS has taught the engine a stride in this session.
     */
    val initialStrideMeters: Double? = null,
    /**
     * With a working step sensor and a pedestrian policy, a GPS gap at least this
     * long in which the sensor measured NO steps is credited 0 m (the person was
     * standing). Shorter gaps are not judged: Android may deliver steps ~10 s
     * late. Zero or negative disables the rule.
     */
    val recoveryStepEvidenceAfterMs: Long = 30_000,
) {
    init {
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0.0) { "maxAccuracyMeters" }
        require(maxFixAgeMs > 0 && silenceAfterMs > 0 && maxBridgeMs > 0) { "durations must be positive" }
        require(maxSpeedMps.isFinite() && maxSpeedMps > 0.0) { "maxSpeedMps" }
        require(minStepMeters.isFinite() && minStepMeters >= 0.0) { "minStepMeters" }
        require(accuracyNoiseFactor.isFinite() && accuracyNoiseFactor >= 0.0) { "accuracyNoiseFactor" }
        require(stationarySpeedMps.isFinite() && stationarySpeedMps >= 0.0) { "stationarySpeedMps" }
        require(reanchorAfterRejects > 0) { "reanchorAfterRejects" }
        require(pdrConfirmRatio.isFinite() && pdrConfirmRatio > 0.0 && pdrConfirmRatio <= 1.0) { "pdrConfirmRatio" }
        require(stopRadiusMeters.isFinite() && stopRadiusMeters > 0.0 && stopMinDwellMs > 0) { "stop detector" }
        require(initialStrideMeters == null || (initialStrideMeters.isFinite() && initialStrideMeters > 0.0)) { "initialStrideMeters" }
    }

    companion object {
        fun forSport(sport: Sport): TrackingConfig = TrackingConfig(
            maxSpeedMps = when (sport) {
                Sport.CYCLING -> 25.0
                Sport.RUNNING, Sport.WALKING -> 10.0
                Sport.OTHER -> 12.0
            },
            // A bike has no steps: never hold a step estimate for it.
            pdrPolicy = if (sport == Sport.CYCLING) PdrPolicy.OFF else PdrPolicy.CONFIRMED_BY_GPS,
        )
    }
}

enum class Sport { RUNNING, WALKING, CYCLING, OTHER }

class FixResult(
    val verdict: FixVerdict,
    val addedMeters: Double = 0.0,
    val source: DistanceSource? = null,
)

data class LedgerEntry(
    val segmentId: Int,
    val source: DistanceSource,
    val meters: Double,
    val atMs: Long,
)

/** What the app shows. Immutable. */
data class TrackingSnapshot(
    val sessionId: String?,
    val state: RecordingState,
    val quality: GpsQuality,
    /** Accepted distance. Never decreases while recording. */
    val totalMeters: Double,
    val gpsMeters: Double,
    val gapBridgeMeters: Double,
    val pdrMeters: Double,
    /** Step estimates waiting for GPS to confirm them: NOT part of [totalMeters]. */
    val pendingPdrMeters: Double,
    val segmentId: Int,
    val fixCount: Int,
    val speedMps: Double?,
    val lastFixAtMs: Long?,
    val movingTimeMs: Long,
    val isStopped: Boolean,
    /** Learned (or configured) metres per step; null while unknown. */
    val strideMeters: Double? = null,
    /** Workout time: the running legs only, pauses excluded. */
    val activeTimeMs: Long = 0,
    /** GPS gaps that were credited (bridge or estimate): their time. */
    val bridgedGapMs: Long = 0,
    /** GPS gaps and unmeasured intervals that were NOT credited: their time. */
    val uncreditedGapMs: Long = 0,
    val uncreditedGaps: Int = 0,
    /** Steps measured in a batch that straddles a GPS-quality change: kept in the total, used for no estimate. */
    val ambiguousSteps: Long = 0,
    /** Steps that taught the stride or became a pending estimate. */
    val distanceEligibleSteps: Long = 0,
)
