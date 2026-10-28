package tracker.engine

import tracker.algorithms.filtering.Kalman2D
import tracker.algorithms.filtering.OutlierRejector
import tracker.algorithms.fusion.GapReconciler
import tracker.algorithms.geometry.GeoMath
import tracker.algorithms.motion.StopDetector
import tracker.algorithms.motion.StrideEstimator
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
 *  - Time is split like workout apps do: ACTIVE time (running legs, pauses
 *    excluded), MOVING time (speed or credited gaps) and UNCREDITED gap time
 *    (GPS gaps nothing was credited for), which is reported, never hidden.
 *  - A step batch carries the interval it was MEASURED in and is classified by
 *    the GPS quality during that interval: wholly good teaches the stride,
 *    wholly weak becomes a pending estimate, a batch straddling a quality change
 *    is counted as ambiguous and used for nothing.
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
    private var lastHealthyFix: TrackingFix? = null
    private var speedMps: Double? = null
    private var fixCount = 0
    private var rejectStreak = 0
    private var pendingPdr = 0.0
    private var movingTimeMs = 0L
    private var activeClosedMs = 0L
    private var lastNowMs = 0L
    private var bridgedGapMs = 0L
    private var uncreditedGapMs = 0L
    private var uncreditedGaps = 0
    private var ambiguousSteps = 0L
    private var eligibleSteps = 0L
    private var stepSensorActive = false
    private var stepsInGap = 0L

    /** When the CURRENT weak interval began (GPS not measuring), or null while GPS is measuring. Step batches attach only to it. */
    private var openWeakStartMs: Long? = null

    /** Where GPS was measuring (true) or not (false), from the given time on. Bounded. */
    private class Mark(val atMs: Long, val good: Boolean)
    private val marks = ArrayDeque<Mark>()

    private val stops = StopDetector(
        config.stopRadiusMeters, config.stopMinDwellMs, config.stationarySpeedMps,
        maxOf(config.silenceAfterMs, MIN_OBSERVATION_GAP_MS),
    )
    private val stride = StrideEstimator(config.initialStrideMeters)
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
            strideMeters = stride.strideMeters,
            activeTimeMs = activeTimeMs,
            bridgedGapMs = bridgedGapMs,
            uncreditedGapMs = uncreditedGapMs,
            uncreditedGaps = uncreditedGaps,
            ambiguousSteps = ambiguousSteps,
            distanceEligibleSteps = eligibleSteps,
        )

    /** Workout time: running legs only, up to the latest time the engine saw. */
    val activeTimeMs: Long
        get() = if (state == RecordingState.RUNNING) activeClosedMs + maxOf(0L, lastNowMs - legStartedAtMs) else activeClosedMs

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
        lastHealthyFix = null
        speedMps = null
        fixCount = 0
        rejectStreak = 0
        pendingPdr = 0.0
        movingTimeMs = 0
        openWeakStartMs = null
        activeClosedMs = 0
        lastNowMs = atMs
        bridgedGapMs = 0
        uncreditedGapMs = 0
        uncreditedGaps = 0
        ambiguousSteps = 0
        eligibleSteps = 0
        stepSensorActive = false
        stepsInGap = 0
        resetMarks(atMs)
        silenceAfterMs = config.silenceAfterMs
        stops.reset()
        stride.reset()
        smoother?.reset()
        log.note(atMs, "start segment $segmentId")
    }

    /**
     * The process died for [gapMs] while recording. If GPS was healthy nothing opened a gap, so the silence is
     * recorded here as an unmeasured interval; if a gap was already open, closing the leg counts it (never twice).
     */
    fun markInterruption(gapMs: Long) {
        require(gapMs >= 0) { "gap must not be negative" }
        if (state != RecordingState.RUNNING || gapMs == 0L) return
        if (quality == GpsQuality.GOOD || quality == GpsQuality.RECOVERING) {
            uncreditedGapMs += gapMs
            uncreditedGaps++
            log.note(lastNowMs, "interrupted for $gapMs ms (process death)")
        }
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
        lastNowMs = atMs
        anchor = null
        lastSeq = -1
        lastFixTimeMs = null
        lastReceivedAtMs = atMs
        lastAcceptedFixAtMs = null
        lastHealthyMs = null
        lastHealthyFix = null
        quality = GpsQuality.UNKNOWN
        rejectStreak = 0
        pendingPdr = 0.0
        stepsInGap = 0
        speedMps = null
        resetMarks(atMs)
        stops.reset()
        stride.breakWindow()
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
        // An interval GPS never measured (weak start, or a gap still open at the
        // boundary) is reported as uncredited, not silently dropped.
        if (quality != GpsQuality.GOOD && quality != GpsQuality.RECOVERING) {
            val from = lastHealthyMs ?: legStartedAtMs
            if (atMs - from > silenceAfterMs) {
                uncreditedGapMs += atMs - from
                uncreditedGaps++
            }
        }
        activeClosedMs += maxOf(0L, atMs - legStartedAtMs)
        dropPending(atMs, reason)
        anchor = null
        quality = GpsQuality.UNKNOWN
        speedMps = null
        lastHealthyMs = null
        lastHealthyFix = null
        lastAcceptedFixAtMs = null
        stops.reset()
        stride.breakWindow()
        marks.clear()
        stepsInGap = 0
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
        addPending(atMs, meters)
    }

    /** The step sensor is working (hardware and permission): its silence is evidence. */
    fun setStepSensorActive(active: Boolean) {
        stepSensorActive = active
    }

    /**
     * New steps of [segmentId] measured in `[fromMs, toMs]` (a DELTA, not a
     * running total) and delivered at [deliveredAtMs]. The batch is judged by the
     * GPS quality DURING its interval, never by the quality at delivery:
     *  - wholly GPS-good: the steps teach the stride (with the GPS metres of the
     *    same window); they are not distance;
     *  - wholly weak, and a stride known: `steps * stride` becomes a PENDING
     *    estimate that the returning GPS must confirm (see [submitPdr]);
     *  - straddling a quality change: counted in `ambiguousSteps`, used for nothing.
     * A batch that ends before the leg started, another segment, or a non-running
     * engine is refused.
     */
    fun onSteps(segmentId: Int, fromMs: Long, toMs: Long, deliveredAtMs: Long, steps: Long) {
        if (state != RecordingState.RUNNING || segmentId != this.segmentId || steps <= 0L) return
        if (toMs < fromMs || toMs < legStartedAtMs || deliveredAtMs < legStartedAtMs) return
        lastNowMs = maxOf(lastNowMs, deliveredAtMs)
        val from = maxOf(fromMs, legStartedAtMs)
        val goodMs = goodOverlapMs(from, toMs)
        val weakMs = (toMs - from) - goodMs
        val wholly = when {
            toMs == from -> if (stateAt(from)) 1 else -1 // an instant
            weakMs == 0L -> 1
            goodMs == 0L -> -1
            else -> 0
        }
        when (wholly) {
            1 -> {
                stride.addSteps(steps)
                eligibleSteps += steps // taught the stride
            }
            -1 -> {
                stride.breakWindow()
                val open = openWeakStartMs
                if (open == null || from < open) {
                    log.note(deliveredAtMs, "steps batch of an earlier weak interval: kept in the total, not attached to this gap")
                    return
                }
                if (quality != GpsQuality.GOOD && quality != GpsQuality.RECOVERING) stepsInGap += steps
                if (config.pdrPolicy == PdrPolicy.OFF) return
                val perStep = stride.strideMeters ?: return
                if (addPending(deliveredAtMs, steps * perStep)) eligibleSteps += steps // became a pending estimate
            }
            else -> {
                ambiguousSteps += steps
                stride.breakWindow()
                log.note(deliveredAtMs, "steps batch straddles a GPS-quality change: ambiguous (+$steps)")
            }
        }
    }

    /** A batch measured and delivered at [atMs] (an instant). */
    fun onSteps(segmentId: Int, atMs: Long, steps: Long) = onSteps(segmentId, atMs, atMs, atMs, steps)

    /** True when the estimate was attached (GPS is not measuring now). */
    private fun addPending(atMs: Long, meters: Double): Boolean {
        // While GPS is measuring, GPS already has this stretch.
        if (quality == GpsQuality.GOOD || quality == GpsQuality.RECOVERING) return false
        pendingPdr += meters
        log.note(atMs, "pdr pending +${meters.f1()} m (pending ${pendingPdr.f1()} m)")
        return true
    }

    // ── GPS-quality timeline (bounded) ──────────────────────────────────────

    private fun resetMarks(atMs: Long) {
        marks.clear()
        marks.addLast(Mark(atMs, false))
        openWeakStartMs = atMs
    }

    /** Records that GPS measuring ([good]) changed at [atMs]; times never go backwards. */
    private fun mark(atMs: Long, good: Boolean) {
        val last = marks.lastOrNull()
        if (last != null && last.good == good) return
        val at = if (last != null && atMs < last.atMs) last.atMs else atMs
        // A weak interval has an identity: its start. A batch measured before the OPEN one's start belongs to an
        // interval GPS already owns again and must never fund a later gap.
        openWeakStartMs = if (good) null else at
        marks.addLast(Mark(at, good))
        // Keep two minutes of history (batches arrive up to ~10 s late) plus the mark before it.
        while (marks.size > 2 && marks[1].atMs < at - TIMELINE_KEEP_MS) marks.removeFirst()
    }

    private fun stateAt(atMs: Long): Boolean {
        var good = false
        for (m in marks) {
            if (m.atMs <= atMs) good = m.good else break
        }
        return good
    }

    /** Milliseconds of `[fromMs, toMs]` during which GPS was measuring. */
    private fun goodOverlapMs(fromMs: Long, toMs: Long): Long {
        var total = 0L
        var i = 0
        while (i < marks.size) {
            val m = marks[i]
            val end = if (i + 1 < marks.size) marks[i + 1].atMs else Long.MAX_VALUE
            if (m.good) {
                val a = maxOf(fromMs, m.atMs)
                val b = minOf(toMs, end)
                if (b > a) total += b - a
            }
            i++
        }
        return total
    }

    // ── Time ────────────────────────────────────────────────────────────────

    /** Call about once per second. Detects GPS silence. */
    fun onTick(nowMs: Long): GpsQuality {
        if (state != RecordingState.RUNNING || nowMs < legStartedAtMs) return quality
        lastNowMs = maxOf(lastNowMs, nowMs)
        val last = lastReceivedAtMs ?: legStartedAtMs
        if (nowMs - last > silenceAfterMs && quality != GpsQuality.UNAVAILABLE) {
            stride.breakWindow()
            // GPS stopped measuring when the last healthy fix was taken, not when we noticed.
            mark(lastHealthyMs ?: legStartedAtMs, false)
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
        lastNowMs = maxOf(lastNowMs, nowMs)
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
            stride.breakWindow()
            mark(lastHealthyMs ?: fix.timeMs, false)
            quality = GpsQuality.DEGRADED
            speedMps = null
            return FixResult(FixVerdict.LOW_ACCURACY)
        }

        val previous = anchor
        if (previous == null) {
            smoother?.reset()
            smoother?.push(fix.latitude, fix.longitude, accuracy, fix.timeMs)
            // The interval from the leg start (or the last healthy fix) to this first good fix was NOT measured:
            // report it as uncredited, once, exactly like a gap (short startup latency below the silence limit is not).
            val unmeasuredMs = fix.timeMs - (lastHealthyMs ?: legStartedAtMs)
            if (unmeasuredMs > silenceAfterMs) {
                uncreditedGapMs += unmeasuredMs
                uncreditedGaps++
                log.note(nowMs, "first good fix after ${unmeasuredMs / 1000}s without GPS: that interval is uncredited")
            }
            anchor = fix
            markHealthy(nowMs, fix)
            stepsInGap = 0
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
            // A stationary/noise accounting anchor can be much older than the
            // real outage. Bridge from the latest healthy spatial observation.
            val from = lastHealthyFix ?: previous
            val recoveryChord = GeoMath.haversineMeters(from.latitude, from.longitude, fix.latitude, fix.longitude)
            val recoveryUncertainty = (from.horizontalAccuracy ?: accuracy) + accuracy
            return recover(fix, from, recoveryChord, recoveryUncertainty, fix.timeMs - from.timeMs, nowMs)
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

        // Validate against the latest healthy observation as well as the
        // accounting anchor, which deliberately stays put while stationary.
        val recent = lastHealthyFix ?: previous
        val recentChord = GeoMath.haversineMeters(recent.latitude, recent.longitude, fix.latitude, fix.longitude)
        val recentUncertainty = (recent.horizontalAccuracy ?: accuracy) + accuracy
        val recentDt = (fix.timeMs - recent.timeMs) / 1000.0
        val recentJump = OutlierRejector.isJump(maxOf(0.0, recentChord - recentUncertainty), recentDt, config.maxSpeedMps)
        if (recentJump || OutlierRejector.isJump(maxOf(0.0, chord - uncertainty), dt, config.maxSpeedMps)) {
            rejectStreak++
            log.note(nowMs, "jump rejected ${chord.f1()} m in ${dt.f1()} s")
            if (rejectStreak >= config.reanchorAfterRejects) {
                anchor = fix
                lastHealthyFix = null
                rejectStreak = 0
                smoother?.reset()
                log.note(nowMs, "re-anchored after repeated jumps, nothing credited")
                return FixResult(FixVerdict.REANCHORED)
            }
            return FixResult(FixVerdict.JUMP)
        }
        rejectStreak = 0
        val movingCandidateMs = markHealthy(nowMs, fix)
        // The normal GPS interval already owns this displacement. Keeping an estimate (or the step evidence)
        // of a short degraded period would leak it into a later gap.
        dropPending(nowMs, "normal GPS interval covers the short degraded period")
        stepsInGap = 0

        // Doppler is only believed when its own accuracy is decent.
        val dopplerSpeed = trustedSpeed(fix)
        if (dopplerSpeed != null && dopplerSpeed < config.stationarySpeedMps) {
            return FixResult(FixVerdict.STATIONARY)
        }
        // No usable Doppler: judge the speed from the displacement itself.
        if (dopplerSpeed == null && recentDt > 0.0 && recentChord / recentDt < config.stationarySpeedMps) {
            return FixResult(FixVerdict.STATIONARY)
        }
        // Standing for a while and still inside the stay radius: this is jitter. The
        // anchor stays put, so real movement is credited in full once it leaves.
        if (stops.isStopped && chord < config.stopRadiusMeters) {
            return FixResult(FixVerdict.STATIONARY)
        }
        // The anchor stays put inside the noise radius, so drift cannot add up
        // one small step at a time while real movement still accumulates.
        val noise = maxOf(config.minStepMeters, config.accuracyNoiseFactor * maxOf(previous.horizontalAccuracy ?: 0.0, accuracy))
        // Only a fix the motion decision did not call STATIONARY counts as moving time.
        movingTimeMs += movingCandidateMs
        if (chord < noise) return FixResult(FixVerdict.NOISE)

        ledger.add(segmentId, DistanceSource.GPS, chord, nowMs)
        stride.addMeters(chord)
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

    /**
     * Records that GPS is measuring. Returns the milliseconds this fix would add to the MOVING time (zero when the
     * Doppler says slow, a stop is in effect, or the previous healthy fix is too old): the CALLER adds them only
     * once the motion decision (stationary or not) is made, so a fix judged stationary never counts as moving.
     */
    private fun markHealthy(nowMs: Long, fix: TrackingFix): Long {
        if (quality != GpsQuality.GOOD) log.note(nowMs, "gps good")
        mark(fix.timeMs, true)
        quality = GpsQuality.GOOD
        lastAcceptedFixAtMs = fix.timeMs
        val speed = trustedSpeed(fix)
        speedMps = speed

        val stopped = stops.push(fix.timeMs, fix.latitude, fix.longitude, speed)
        val previousHealthy = lastHealthyMs
        val slow = speed != null && speed < config.stationarySpeedMps
        var candidate = 0L
        if (previousHealthy != null && !stopped && !slow) {
            val dt = fix.timeMs - previousHealthy
            if (dt in 1..silenceAfterMs) candidate = dt
        }
        lastHealthyMs = fix.timeMs
        lastHealthyFix = fix
        return candidate
    }

    /** First good fix after a real gap: credits the gap once. */
    private fun recover(
        fix: TrackingFix, previous: TrackingFix, chord: Double, uncertainty: Double,
        gapMs: Long, nowMs: Long,
    ): FixResult {
        val pending = pendingPdr
        pendingPdr = 0.0
        rejectStreak = 0
        stride.breakWindow()
        val evidence = stepsInGap
        stepsInGap = 0
        // A long gap with a working step sensor that delivered NO step event: nothing shows the
        // person moved, so nothing is credited. This is absence of evidence, NOT proof of standing
        // still (a sensor can be late or not cover the whole gap), so the gap is reported as
        // UNCREDITED (unknown), never as "measured zero". Shorter gaps are not judged.
        val noSteps = config.recoveryStepEvidenceAfterMs > 0 && stepSensorActive &&
            config.pdrPolicy != PdrPolicy.OFF && gapMs >= config.recoveryStepEvidenceAfterMs && evidence == 0L
        val result = if (noSteps) {
            tracker.algorithms.fusion.GapResult(0.0, DistanceSource.GAP_BRIDGE, "no step events in a long gap: not credited (no evidence of movement; this is NOT proof of standing still)")
        } else {
            GapReconciler.reconcile(chord, uncertainty, gapMs, pending, config, stride.sampleCount >= CALIBRATED_SAMPLES)
        }
        if (pending > 0.0) {
            log.note(nowMs, "${result.note}: estimate ${pending.f1()} m, straight line ${chord.f1()} m")
        } else if (result.creditMeters == 0.0) {
            log.note(nowMs, result.note)
        }
        if (result.creditMeters > 0.0) {
            ledger.add(segmentId, result.source, result.creditMeters, nowMs)
            bridgedGapMs += gapMs
            // The person kept moving through the gap: its time counts as moving time.
            if (gapMs > 0 && result.creditMeters / (gapMs / 1000.0) >= config.stationarySpeedMps) movingTimeMs += gapMs
        } else {
            uncreditedGapMs += gapMs
            uncreditedGaps++
        }
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
        const val TIMELINE_KEEP_MS = 120_000L
        const val CALIBRATED_SAMPLES = 3

        /** A Doppler speed with a worse accuracy than this is not believed (unmeasured). */
        const val SPEED_ACCURACY_LIMIT_MPS = 1.5
    }
}
