package tracker.engine

import tracker.algorithms.filtering.Kalman2D
import tracker.algorithms.filtering.OutlierRejector
import tracker.algorithms.fusion.GapReconciler
import tracker.algorithms.geometry.GeoMath
import tracker.algorithms.motion.StopDetector
import tracker.model.DistanceSource
import tracker.model.FixResult
import tracker.model.FixVerdict
import tracker.model.GpsQuality
import tracker.model.PdrPolicy
import tracker.model.RecordingState
import tracker.model.Smoothing
import tracker.model.TrackingConfig
import tracker.model.TrackingFix
import tracker.model.TrackingSnapshot
import tracker.util.f1

/**
 * Decides what counts as distance. Pure and deterministic: time is passed in,
 * nothing is read from the platform, so a recording can be replayed fix by fix.
 *
 * Every time given to the engine (`fix.timeMs`, `nowMs`, `atMs`) must come from
 * the SAME clock. The native layers use a monotonic one, so a wall-clock jump
 * can neither reorder nor age a fix.
 *
 * Rules:
 *  - GPS is the truth. A step estimate is only ever PENDING until the GPS that
 *    returns confirms it; a phone shaken without GPS therefore adds 0 m.
 *  - Distance is measured on RAW accepted fixes. The Kalman filter (optional) is
 *    only a quality gate; it never supplies coordinates.
 *  - One source per stretch: a gap is credited once, never as a sum.
 *  - Nothing is bridged across a pause or a segment boundary, and a fix or a
 *    step estimate of another session or segment is refused.
 *  - A Doppler speed below the stationary limit means no distance.
 *  - A GPS gap is decided from the time of the last HEALTHY fix, not from the
 *    accounting anchor (which stays put on purpose while standing).
 *  - The total only grows.
 *
 * Not thread-safe: drive it from one serial context.
 */
class GpsDistanceEngine(
    val config: TrackingConfig = TrackingConfig(),
    val log: TrackingLog = TrackingLog(),
) {
    val ledger = DistanceLedger()

    /**
     * How long without a fix means "GPS is silent". Starts at the configured
     * value; an adaptive sampling plan may raise it so sparse fixes are not
     * mistaken for an outage.
     */
    var silenceAfterMs: Long = config.silenceAfterMs
        set(value) {
            require(value > 0L) { "silence limit must be positive" }
            field = value
        }

    var state: RecordingState = RecordingState.IDLE
        private set
    var quality: GpsQuality = GpsQuality.UNKNOWN
        private set
    var segmentId: Int = 0
        private set

    private var sessionId: String? = null
    private var legStartedAtMs = 0L
    private var anchor: TrackingFix? = null
    private var lastSeq = -1L
    private var lastFixTimeMs: Long? = null
    private var lastReceivedAtMs: Long? = null
    private var lastAcceptedFixAtMs: Long? = null
    private var lastHealthyMs: Long? = null
    private var speedMps: Double? = null
    private var fixCount = 0
    private var rejectStreak = 0
    private var pendingPdr = 0.0
    private var movingTimeMs = 0L

    private val stops = StopDetector(
        config.stopRadiusMeters, config.stopMinDwellMs, config.stationarySpeedMps,
        maxOf(config.silenceAfterMs, MIN_OBSERVATION_GAP_MS),
    )
    private val smoother: Kalman2D? = if (config.smoothing == Smoothing.KALMAN) Kalman2D() else null

    val totalMeters: Double get() = ledger.totalMeters

    val snapshot: TrackingSnapshot
        get() = TrackingSnapshot(
            sessionId = sessionId,
            state = state,
            quality = quality,
            totalMeters = ledger.totalMeters,
            gpsMeters = ledger.meters(DistanceSource.GPS),
            gapBridgeMeters = ledger.meters(DistanceSource.GAP_BRIDGE),
            pdrMeters = ledger.meters(DistanceSource.PDR),
            pendingPdrMeters = pendingPdr,
            segmentId = segmentId,
            fixCount = fixCount,
            speedMps = speedMps,
            lastFixAtMs = lastAcceptedFixAtMs,
            movingTimeMs = movingTimeMs,
            isStopped = stops.isStopped,
        )

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun start(sessionId: String, atMs: Long, segmentId: Int = 0) {
        require(sessionId.isNotBlank()) { "sessionId is blank" }
        require(segmentId >= 0) { "segmentId must not be negative" }
        ledger.clear()
        log.clear()
        this.sessionId = sessionId
        state = RecordingState.RUNNING
        this.segmentId = segmentId
        legStartedAtMs = atMs
        quality = GpsQuality.UNKNOWN
        anchor = null
        lastSeq = -1
        lastFixTimeMs = null
        lastReceivedAtMs = atMs
        lastAcceptedFixAtMs = null
        lastHealthyMs = null
        speedMps = null
        fixCount = 0
        rejectStreak = 0
        pendingPdr = 0.0
        movingTimeMs = 0
        silenceAfterMs = config.silenceAfterMs
        stops.reset()
        smoother?.reset()
        log.note(atMs, "start segment $segmentId")
    }

    /** Closes the segment: the anchor is forgotten and a pending estimate dropped. */
    fun pause(atMs: Long) {
        if (state != RecordingState.RUNNING) return
        state = RecordingState.PAUSED
        closeLeg(atMs, "pause")
    }

    fun resume(atMs: Long, segmentId: Int) {
        if (state != RecordingState.PAUSED) return
        require(segmentId > this.segmentId) { "a resume must open a NEW segment" }
        state = RecordingState.RUNNING
        this.segmentId = segmentId
        legStartedAtMs = atMs
        anchor = null
        lastSeq = -1
        lastFixTimeMs = null
        lastReceivedAtMs = atMs
        lastAcceptedFixAtMs = null
        lastHealthyMs = null
        quality = GpsQuality.UNKNOWN
        rejectStreak = 0
        pendingPdr = 0.0
        speedMps = null
        stops.reset()
        smoother?.reset()
        log.note(atMs, "resume segment $segmentId")
    }

    /** Freezes the result. Later fixes are ignored. */
    fun finish(atMs: Long) {
        if (state == RecordingState.FINISHED || state == RecordingState.IDLE) return
        if (state == RecordingState.RUNNING) closeLeg(atMs, "finish")
        state = RecordingState.FINISHED
        log.note(atMs, "finish at ${totalMeters.f1()} m")
    }

    private fun closeLeg(atMs: Long, reason: String) {
        dropPending(atMs, reason)
        anchor = null
        quality = GpsQuality.UNKNOWN
        speedMps = null
        lastHealthyMs = null
        lastAcceptedFixAtMs = null
        stops.reset()
        smoother?.reset()
        log.note(atMs, "$reason at ${totalMeters.f1()} m")
    }

    // ── Step estimates (optional) ───────────────────────────────────────────

    /**
     * A step-based estimate for a stretch without GPS, for [segmentId]. It is
     * refused when it belongs to another segment (a late estimate must not leak
     * into the segment that follows a resume), and held as pending only while
     * GPS is not good. It only counts if the GPS that returns confirms it.
     */
    fun submitPdr(segmentId: Int, atMs: Long, meters: Double) {
        if (config.pdrPolicy == PdrPolicy.OFF || state != RecordingState.RUNNING) return
        if (segmentId != this.segmentId || atMs < legStartedAtMs) return
        if (!meters.isFinite() || meters <= 0.0) return
        // While GPS is measuring, GPS already has this stretch.
        if (quality == GpsQuality.GOOD || quality == GpsQuality.RECOVERING) return
        pendingPdr += meters
        log.note(atMs, "pdr pending +${meters.f1()} m (pending ${pendingPdr.f1()} m)")
    }

    // ── Time ────────────────────────────────────────────────────────────────

    /** Call about once per second. Detects GPS silence. */
    fun onTick(nowMs: Long): GpsQuality {
        if (state != RecordingState.RUNNING || nowMs < legStartedAtMs) return quality
        val last = lastReceivedAtMs ?: legStartedAtMs
        if (nowMs - last > silenceAfterMs && quality != GpsQuality.UNAVAILABLE) {
            quality = GpsQuality.UNAVAILABLE
            speedMps = null
            log.note(nowMs, "gps unavailable (silent ${silenceAfterMs / 1000}s)")
        }
        return quality
    }

    // ── Fixes ───────────────────────────────────────────────────────────────

    /**
     * [nowMs] is when the fix arrived, on the SAME clock as `fix.timeMs`.
     * [replay] skips the age and future checks (a fix read back from storage).
     * Never throws for data: a bad fix gets a verdict.
     */
    fun onFix(fix: TrackingFix, nowMs: Long, replay: Boolean = false): FixResult {
        if (state != RecordingState.RUNNING) return FixResult(FixVerdict.NOT_RUNNING)
        if (fix.sessionId != sessionId) return FixResult(FixVerdict.WRONG_SESSION)
        if (fix.segmentId != segmentId) return FixResult(FixVerdict.WRONG_SEGMENT)
        if (isInvalid(fix)) {
            log.note(nowMs, "fix rejected: invalid values")
            return FixResult(FixVerdict.INVALID)
        }
        val lastTime = lastFixTimeMs
        if (fix.seq <= lastSeq || (lastTime != null && fix.timeMs <= lastTime)) {
            return FixResult(FixVerdict.DUPLICATE)
        }
        if (!replay) {
            if (fix.timeMs > nowMs + FUTURE_TOLERANCE_MS) return FixResult(FixVerdict.FUTURE)
            if (nowMs - fix.timeMs > config.maxFixAgeMs || fix.timeMs < legStartedAtMs) {
                log.note(nowMs, "fix rejected: stale")
                return FixResult(FixVerdict.STALE)
            }
        }
        lastSeq = fix.seq
        lastFixTimeMs = fix.timeMs
        lastReceivedAtMs = nowMs
        fixCount++
        if (config.rejectMock && fix.isMock) {
            log.note(nowMs, "fix rejected: mock")
            return FixResult(FixVerdict.MOCK)
        }

        val accuracy = fix.horizontalAccuracy
        if (accuracy == null || accuracy > config.maxAccuracyMeters) {
            if (quality != GpsQuality.DEGRADED) {
                log.note(nowMs, "gps degraded (accuracy ${accuracy?.f1() ?: "?"} m)")
            }
            quality = GpsQuality.DEGRADED
            speedMps = null
            return FixResult(FixVerdict.LOW_ACCURACY)
        }

        val previous = anchor
        if (previous == null) {
            smoother?.reset()
            smoother?.push(fix.latitude, fix.longitude, accuracy, fix.timeMs)
            anchor = fix
            markHealthy(nowMs, fix)
            if (pendingPdr > 0.0) dropPending(nowMs, "no anchor to confirm it")
            log.note(nowMs, "anchor set")
            return FixResult(FixVerdict.ANCHOR)
        }

        val dtMs = fix.timeMs - previous.timeMs
        val dt = dtMs / 1000.0
        val chord = GeoMath.haversineMeters(previous.latitude, previous.longitude, fix.latitude, fix.longitude)
        val uncertainty = (previous.horizontalAccuracy ?: accuracy) + accuracy

        // A real GPS gap is silence measured from the last HEALTHY fix. The
        // anchor can be old on purpose (it stays put while standing), so it must
        // not be used to decide this.
        val sinceHealthy = lastHealthyMs?.let { fix.timeMs - it } ?: dtMs
        val wasGap = quality == GpsQuality.UNAVAILABLE || sinceHealthy > silenceAfterMs
        if (wasGap) {
            smoother?.reset()
            smoother?.push(fix.latitude, fix.longitude, accuracy, fix.timeMs)
            return recover(fix, previous, chord, uncertainty, dtMs, nowMs)
        }

        // Kalman gate: plausibility only. Coordinates are never taken from it.
        if (smoother != null) {
            val gate = smoother.push(fix.latitude, fix.longitude, accuracy, fix.timeMs)
            if (gate.discontinuity) {
                anchor = fix
                rejectStreak = 0
                markHealthy(nowMs, fix)
                log.note(nowMs, "kalman re-localized: fresh anchor, nothing credited")
                return FixResult(FixVerdict.REANCHORED)
            }
            if (!gate.accepted) return FixResult(FixVerdict.OUTLIER)
        }

        // Plausibility with the GPS error of both ends allowed for.
        if (OutlierRejector.isJump(maxOf(0.0, chord - uncertainty), dt, config.maxSpeedMps)) {
            rejectStreak++
            log.note(nowMs, "jump rejected ${chord.f1()} m in ${dt.f1()} s")
            if (rejectStreak >= config.reanchorAfterRejects) {
                anchor = fix
                rejectStreak = 0
                smoother?.reset()
                log.note(nowMs, "re-anchored after repeated jumps, nothing credited")
                return FixResult(FixVerdict.REANCHORED)
            }
            return FixResult(FixVerdict.JUMP)
        }
        rejectStreak = 0
        markHealthy(nowMs, fix)

        // Doppler is only believed when its own accuracy is decent.
        val dopplerSpeed = trustedSpeed(fix)
        if (dopplerSpeed != null && dopplerSpeed < config.stationarySpeedMps) {
            return FixResult(FixVerdict.STATIONARY)
        }
        // The anchor stays put inside the noise radius, so drift cannot add up
        // one small step at a time while real movement still accumulates.
        val noise = maxOf(config.minStepMeters, config.accuracyNoiseFactor * maxOf(previous.horizontalAccuracy ?: 0.0, accuracy))
        if (chord < noise) return FixResult(FixVerdict.NOISE)

        ledger.add(segmentId, DistanceSource.GPS, chord, nowMs)
        anchor = fix
        return FixResult(FixVerdict.ACCEPTED, chord, DistanceSource.GPS)
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun isInvalid(fix: TrackingFix): Boolean =
        !GeoMath.valid(fix.latitude, fix.longitude) || fix.seq < 0 || fix.timeMs < 0 ||
            fix.horizontalAccuracy?.let { !it.isFinite() || it < 0.0 } == true ||
            fix.speed?.let { !it.isFinite() || it < 0.0 } == true ||
            fix.speedAccuracy?.let { !it.isFinite() || it < 0.0 } == true

    private fun trustedSpeed(fix: TrackingFix): Double? {
        val s = fix.speed ?: return null
        val a = fix.speedAccuracy
        return if (a == null || a <= SPEED_ACCURACY_LIMIT_MPS) s else null
    }

    private fun markHealthy(nowMs: Long, fix: TrackingFix) {
        if (quality != GpsQuality.GOOD) log.note(nowMs, "gps good")
        quality = GpsQuality.GOOD
        lastAcceptedFixAtMs = fix.timeMs
        val speed = trustedSpeed(fix)
        speedMps = speed

        val stopped = stops.push(fix.timeMs, fix.latitude, fix.longitude, speed)
        val previousHealthy = lastHealthyMs
        val slow = speed != null && speed < config.stationarySpeedMps
        if (previousHealthy != null && !stopped && !slow) {
            val dt = fix.timeMs - previousHealthy
            if (dt in 1..silenceAfterMs) movingTimeMs += dt
        }
        lastHealthyMs = fix.timeMs
    }

    /** First good fix after a real gap: credits the gap once. */
    private fun recover(
        fix: TrackingFix, previous: TrackingFix, chord: Double, uncertainty: Double,
        gapMs: Long, nowMs: Long,
    ): FixResult {
        val pending = pendingPdr
        pendingPdr = 0.0
        rejectStreak = 0
        val result = GapReconciler.reconcile(chord, uncertainty, gapMs, pending, config)
        if (pending > 0.0) {
            log.note(nowMs, "${result.note}: estimate ${pending.f1()} m, straight line ${chord.f1()} m")
        } else if (result.creditMeters == 0.0) {
            log.note(nowMs, result.note)
        }
        if (result.creditMeters > 0.0) ledger.add(segmentId, result.source, result.creditMeters, nowMs)
        log.note(
            nowMs,
            "recovered: +${result.creditMeters.f1()} m (${result.source.name.lowercase()}) over " +
                "${gapMs / 1000}s, total ${totalMeters.f1()} m",
        )
        anchor = fix
        markHealthy(nowMs, fix)
        quality = GpsQuality.RECOVERING
        return FixResult(
            FixVerdict.RECOVERED,
            result.creditMeters,
            if (result.creditMeters > 0.0) result.source else null,
        )
    }

    private fun dropPending(atMs: Long, why: String) {
        if (pendingPdr > 0.0) log.note(atMs, "pdr dropped (${pendingPdr.f1()} m): $why")
        pendingPdr = 0.0
    }

    private companion object {
        const val FUTURE_TOLERANCE_MS = 2_000L
        const val MIN_OBSERVATION_GAP_MS = 10_000L

        /** A Doppler speed with a worse accuracy than this is not believed (unmeasured). */
        const val SPEED_ACCURACY_LIMIT_MPS = 1.5
    }
}
