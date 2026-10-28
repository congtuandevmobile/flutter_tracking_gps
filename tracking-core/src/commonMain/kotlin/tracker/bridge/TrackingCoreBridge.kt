package tracker.bridge

import tracker.algorithms.power.AdaptiveSamplingPolicy
import tracker.algorithms.motion.StepSession
import tracker.algorithms.power.SamplingPlan
import tracker.engine.GpsDistanceEngine
import tracker.model.FixVerdict
import tracker.model.GpsQuality
import tracker.model.PdrPolicy
import tracker.model.RecordingState
import tracker.model.Smoothing
import tracker.model.TrackingConfig
import tracker.model.TrackingFix

/** A flat copy of `TrackingSnapshot`: only primitives, so Swift and Kotlin read it the same way. */
class BridgeSnapshot(
    /** 0 idle, 1 running, 2 paused, 3 finished (stable codes, see [TrackingCoreBridge]). */
    val state: Int,
    /** 0 unknown, 1 good, 2 degraded, 3 unavailable, 4 recovering. */
    val quality: Int,
    val totalMeters: Double,
    val gpsMeters: Double,
    val gapBridgeMeters: Double,
    val pdrMeters: Double,
    val pendingPdrMeters: Double,
    val segmentId: Int,
    val fixCount: Int,
    /** Negative = unknown. */
    val speedMps: Double,
    /** Negative = none yet. */
    val lastFixAtMs: Long,
    val movingTimeMs: Long,
    val isStopped: Boolean,
    /** Accepted steps of the session (closed segments + the open one). */
    val steps: Long,
    /** Learned or configured metres per step; negative = unknown. */
    val strideMeters: Double,
)

/** What to ask the OS for (see `AdaptiveSamplingPolicy`). */
class BridgePlan(
    val intervalMs: Long,
    val minDistanceMeters: Double,
    val highAccuracy: Boolean,
    val silenceAfterMs: Long,
)

/**
 * The one door through which Swift and Kotlin-Android reach the engine. Kotlin
 * default arguments, nullable numbers and enums are awkward from Swift, so
 * everything here is a primitive (unknown values are negative) and every
 * parameter is explicit.
 *
 * Not thread-safe: call it from one lock or one serial context. It also owns the
 * adaptive sampling policy, so the plan follows the same speed the engine sees.
 */
class TrackingCoreBridge(
    maxAccuracyMeters: Double,
    maxFixAgeMs: Long,
    silenceAfterMs: Long,
    maxSpeedMps: Double,
    minStepMeters: Double,
    accuracyNoiseFactor: Double,
    stationarySpeedMps: Double,
    maxBridgeMs: Long,
    reanchorAfterRejects: Int,
    rejectMock: Boolean,
    pdrEnabled: Boolean,
    pdrConfirmRatio: Double,
    kalman: Boolean,
    stopRadiusMeters: Double,
    stopMinDwellMs: Long,
    /** Metres per step to start from; zero or negative = none. */
    initialStrideMeters: Double,
    private val adaptiveSampling: Boolean,
    batterySaver: Boolean,
) {
    private val config = TrackingConfig(
        maxAccuracyMeters = maxAccuracyMeters,
        maxFixAgeMs = maxFixAgeMs,
        silenceAfterMs = silenceAfterMs,
        maxSpeedMps = maxSpeedMps,
        minStepMeters = minStepMeters,
        accuracyNoiseFactor = accuracyNoiseFactor,
        stationarySpeedMps = stationarySpeedMps,
        maxBridgeMs = maxBridgeMs,
        reanchorAfterRejects = reanchorAfterRejects,
        rejectMock = rejectMock,
        pdrPolicy = if (pdrEnabled) PdrPolicy.CONFIRMED_BY_GPS else PdrPolicy.OFF,
        pdrConfirmRatio = pdrConfirmRatio,
        smoothing = if (kalman) Smoothing.KALMAN else Smoothing.OFF,
        stopRadiusMeters = stopRadiusMeters,
        stopMinDwellMs = stopMinDwellMs,
        initialStrideMeters = if (initialStrideMeters > 0.0) initialStrideMeters else null,
    )
    private val engine = GpsDistanceEngine(config)
    private val sampling = AdaptiveSamplingPolicy(batterySaver)
    private val fixedPlan = SamplingPlan(1_000, 2.0, true)
    private val steps = StepSession()

    /** [initialSteps] restores a saved session total (the device counter is never added to it). */
    fun start(sessionId: String, atMs: Long, segmentId: Int, initialSteps: Long = 0) {
        sampling.reset()
        engine.silenceAfterMs = config.silenceAfterMs
        engine.start(sessionId, atMs, segmentId)
        steps.reset(initialSteps)
        steps.openSegment()
    }

    fun pause(atMs: Long) {
        engine.pause(atMs)
        steps.closeSegment()
    }

    fun resume(atMs: Long, segmentId: Int) {
        engine.resume(atMs, segmentId)
        steps.openSegment()
    }

    fun finish(atMs: Long) {
        engine.finish(atMs)
        steps.closeSegment()
    }

    /**
     * A reading of the device's CUMULATIVE step counter (Android
     * `TYPE_STEP_COUNTER`) in [segmentId]. The first reading of a segment is a
     * baseline. The steps it adds go to the engine as a delta (see
     * `GpsDistanceEngine.onSteps`). [atMs] is the arrival time on the engine's clock.
     */
    fun onStepCounter(segmentId: Int, atMs: Long, raw: Long) =
        feedSteps(segmentId, atMs, steps.onCounter(raw))

    /** A step count since the segment started (iOS `CMPedometer` from a start date). */
    fun onSegmentSteps(segmentId: Int, atMs: Long, segmentSteps: Long) =
        feedSteps(segmentId, atMs, steps.onSegmentCount(segmentSteps))

    /**
     * An exact count for the segment that was just paused or finished (iOS asks
     * the OS for the range up to the boundary). Changes the displayed total only:
     * distance is never derived from it.
     */
    fun correctClosedSegment(exactSteps: Long) {
        steps.correctClosedSegment(exactSteps)
    }

    private fun feedSteps(segmentId: Int, atMs: Long, added: Long) {
        if (added > 0L) engine.onSteps(segmentId, atMs, added)
    }

    /** A step estimate for [segmentId]; one of another segment is refused. */
    fun submitPdr(segmentId: Int, atMs: Long, meters: Double) = engine.submitPdr(segmentId, atMs, meters)

    /** Returns the GPS quality code. */
    fun onTick(nowMs: Long): Int = qualityCode(engine.onTick(nowMs))

    /**
     * Feeds one fix; returns the `FixVerdict` code. Exactly `-1.0` means unknown
     * for [horizontalAccuracy], [speed] and [speedAccuracy]; NaN, infinity or any
     * other negative value is INVALID, not unknown. [timeMs] and [nowMs] must come
     * from the SAME (monotonic) clock. [replay] skips the age and future checks.
     */
    fun onFix(
        sessionId: String,
        segmentId: Int,
        seq: Long,
        timeMs: Long,
        latitude: Double,
        longitude: Double,
        horizontalAccuracy: Double,
        speed: Double,
        speedAccuracy: Double,
        isMock: Boolean,
        nowMs: Long,
        replay: Boolean,
    ): Int = verdictCode(engine.onFix(
        TrackingFix(
            sessionId = sessionId,
            segmentId = segmentId,
            seq = seq,
            timeMs = timeMs,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracy = unknownOr(horizontalAccuracy),
            speed = unknownOr(speed),
            speedAccuracy = unknownOr(speedAccuracy),
            isMock = isMock,
        ),
        nowMs,
        replay,
    ).verdict)

    fun snapshot(): BridgeSnapshot {
        val s = engine.snapshot
        return BridgeSnapshot(
            state = stateCode(s.state),
            quality = qualityCode(s.quality),
            totalMeters = s.totalMeters,
            gpsMeters = s.gpsMeters,
            gapBridgeMeters = s.gapBridgeMeters,
            pdrMeters = s.pdrMeters,
            pendingPdrMeters = s.pendingPdrMeters,
            segmentId = s.segmentId,
            fixCount = s.fixCount,
            speedMps = s.speedMps ?: -1.0,
            lastFixAtMs = s.lastFixAtMs ?: -1L,
            movingTimeMs = s.movingTimeMs,
            isStopped = s.isStopped,
            steps = steps.total,
            strideMeters = s.strideMeters ?: -1.0,
        )
    }

    /** Coordinate-free timeline of the engine's decisions. */
    fun report(): String = engine.log.report()

    /**
     * The sampling plan for now. With adaptive sampling off it is always the same
     * fixed plan. With it on, the engine's silence limit follows the plan so a
     * sparse plan is never mistaken for an outage.
     */
    fun samplingPlan(nowMs: Long): BridgePlan {
        val plan = if (adaptiveSampling) sampling.plan(engine.snapshot.speedMps, nowMs) else fixedPlan
        val silence = maxOf(config.silenceAfterMs, plan.silenceAfterMs(config.silenceAfterMs))
        engine.silenceAfterMs = silence
        return BridgePlan(plan.intervalMs, plan.minDistanceMeters, plan.highAccuracy, silence)
    }

    private fun unknownOr(value: Double): Double? = if (value == UNKNOWN) null else value

    // Codes are written out, never taken from `ordinal`: reordering an enum must
    // not silently change what Swift reads.
    private fun stateCode(v: RecordingState): Int = when (v) {
        RecordingState.IDLE -> 0
        RecordingState.RUNNING -> 1
        RecordingState.PAUSED -> 2
        RecordingState.FINISHED -> 3
    }

    private fun qualityCode(v: GpsQuality): Int = when (v) {
        GpsQuality.UNKNOWN -> 0
        GpsQuality.GOOD -> 1
        GpsQuality.DEGRADED -> 2
        GpsQuality.UNAVAILABLE -> 3
        GpsQuality.RECOVERING -> 4
    }

    private fun verdictCode(v: FixVerdict): Int = when (v) {
        FixVerdict.ANCHOR -> 0
        FixVerdict.ACCEPTED -> 1
        FixVerdict.RECOVERED -> 2
        FixVerdict.NOISE -> 3
        FixVerdict.STATIONARY -> 4
        FixVerdict.JUMP -> 5
        FixVerdict.OUTLIER -> 6
        FixVerdict.LOW_ACCURACY -> 7
        FixVerdict.STALE -> 8
        FixVerdict.DUPLICATE -> 9
        FixVerdict.WRONG_SEGMENT -> 10
        FixVerdict.MOCK -> 11
        FixVerdict.NOT_RUNNING -> 12
        FixVerdict.WRONG_SESSION -> 13
        FixVerdict.INVALID -> 14
        FixVerdict.FUTURE -> 15
        FixVerdict.REANCHORED -> 16
    }

    private companion object {
        const val UNKNOWN = -1.0
    }
}
