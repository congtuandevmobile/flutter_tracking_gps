package tracker.journal

import tracker.bridge.BridgePlan
import tracker.bridge.BridgeSnapshot
import tracker.bridge.TrackingCoreBridge

/**
 * Where the journal goes. The host implements it with ONE serial writer: [append] must not block or
 * reorder, and [flush] must return only when every appended line is durable. It returns `true` ONLY when
 * that is so: a write or sync failure, or a barrier that timed out, is `false` and stays `false` (the host
 * keeps the failure sticky), so a lost line can never be reported as saved.
 */
interface JournalSink {
    fun append(line: String)
    fun flush(): Boolean
}

/** What a restore found. */
class RestoreResult(
    val recorder: SessionRecorder?,
    val status: JournalStatus,
    val damagedLine: Int,
    val sessionId: String,
    val segmentId: Int,
    /** The next seq to give a fix. */
    val nextSeq: Long,
    val fixCount: Int,
    /** Wall-clock time of the last restored fix, or -1. */
    val lastFixWallMs: Long,
    val extras: Map<String, String>,
    /** The engine rules the journal was recorded with. */
    val engineVersion: Int = 0,
    /** True when [engineVersion] differs from this code's: the replay may not equal what was shown live. */
    val engineChanged: Boolean = false,
    /** The journal ended with `Finish`: the session is over and read-only (it is NOT paused). */
    val finished: Boolean = false,
    /** Only meaningful when [finished]: the step total was settled (see `JournalRecord.Finalized`). */
    val stepsFinal: Boolean = false,
    /** The journal ended while RECORDING (the process died, it was not paused or finished). */
    val wasRunning: Boolean = false,
    /** Engine time of the last journaled event. */
    val lastEventMs: Long = 0,
)

/** What [SessionRecorder.recoverAfterProcessDeath] did. */
class RecoveryResult(
    /** The dead interval was added to the session's clock (and the recording continues in a NEW segment). */
    val recovered: Boolean,
    /** Calendar time that passed while nothing ran (>= 0). */
    val gapMs: Long,
    /** The calendar clock went backwards: the gap could not be measured and counts as 0. */
    val clockAnomaly: Boolean,
    /** The gap exceeded the limit: the session was parked as PAUSED at the last event instead. */
    val gapTooLong: Boolean,
    /** The segment the session is in after recovery. */
    val segmentId: Int,
)

/**
 * What a restore must do to a journal BEFORE anything is appended to it (see [SessionRecorder.plan]).
 * [lines] is the trusted, replayable prefix (migrated header included): rewrite the file with it when
 * [needsRewrite], keep a `.corrupt` copy when [keepBackup], and only then open the writer and restore.
 */
class RepairPlan(
    /** The lines to keep: every one is CRC-valid AND applies in the engine. Empty when [usable] is false. */
    val lines: List<String>,
    /** The verdict of the whole check, codec AND replay (a CRC-valid but impossible record is `CORRUPT`). */
    val status: JournalStatus,
    /** Zero-based index of the first record that was dropped, or -1. */
    val damagedLine: Int,
    /** The header was written by an older format and is rewritten as the current one (new records need it). */
    val migrated: Boolean,
    val usable: Boolean,
) {
    /** The file on disk must be replaced by [lines] before the writer is opened. */
    val needsRewrite: Boolean get() = usable && (status != JournalStatus.OK || migrated)

    /** Damage in the middle, or a migration, drops or changes data: keep the original next to the new file. */
    val keepBackup: Boolean get() = usable && ((status != JournalStatus.OK && status != JournalStatus.TRUNCATED_TAIL) || migrated)
}

/** A start that cannot throw across the platform boundary (Kotlin exceptions are not catchable from Swift). */
class StartResult(val recorder: SessionRecorder?, val error: String?)

/**
 * The engine plus its journal, in ONE place, so Android, iOS and a watch record and restore identically. Every
 * input is written to the journal BEFORE it is applied (write-ahead), and the engine is a pure function of
 * those inputs, so replaying the journal reproduces the live result exactly: the distance, the pending
 * estimates, the stride, the step totals, the gaps and the times (EVERY tick is an input: the active time of a
 * running leg depends on it).
 *
 * An input that is invalid in the current state (a pause while not recording, a resume that does not open a new
 * segment...) is NOT journaled: a journal only ever holds inputs the engine accepts, so it can always be
 * replayed. No public method throws: a refused input sets [lastFailure] instead, because a Kotlin exception
 * would crash an iOS app.
 *
 * Not thread-safe: call it from one serial context.
 */
class SessionRecorder private constructor(
    private val sink: JournalSink,
    private val settings: BridgeSettings,
    private val bridge: TrackingCoreBridge,
) {
    /** The sampling plan after the latest fix (adaptive sampling only), or null. */
    var lastPlan: BridgePlan? = null
        private set

    /** True once the step total of a FINISHED session was settled (journaled by [markFinalized]). */
    var stepsFinal: Boolean = false
        private set

    /** Why the latest input was refused, or null. Sticky until the next refusal; never thrown. */
    var lastFailure: String? = null
        private set

    private var lastAcceptedWallMs = -1L

    // The session's own clock. Every input is journaled on the ENGINE's time line; after a process death the host's
    // monotonic clock is not that time line any more, so live inputs are shifted by [offsetMs].
    private var offsetMs = 0L
    private var startedAtMs = 0L
    private var finishedAtMs = -1L
    private var lastEventMs = 0L

    // One PAIR of clocks describing the SAME instant (engine time, calendar time). A recovery measures the dead
    // interval against it. Never mix the time an event was received with the calendar time it was measured at.
    private var anchorEngineMs = -1L
    private var anchorWallMs = -1L

    /** Calendar time that passed while the process was dead, summed over every recovery. */
    var recoveredGapMs: Long = 0
        private set

    /** The calendar clock went backwards across a recovery at least once. */
    var clockAnomaly: Boolean = false
        private set

    private fun t(hostMs: Long): Long = hostMs + offsetMs

    private fun noteTime(eventMs: Long?) {
        if (eventMs != null && eventMs > lastEventMs) lastEventMs = eventMs
    }

    /** [engineMs] and [wallMs] must describe the same instant. Only moves forward in engine time. */
    private fun noteAnchor(engineMs: Long, wallMs: Long) {
        if (wallMs >= 0 && engineMs >= anchorEngineMs) {
            anchorEngineMs = engineMs
            anchorWallMs = wallMs
        }
    }

    /** Segments in which the step sensor was active, and the ones whose exact count was obtained. */
    private val steppedSegments = HashSet<Int>()
    private val exactSegments = HashSet<Int>()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun pause(atMs: Long) = pauseAt(t(atMs))

    fun resume(atMs: Long, segmentId: Int) = resumeAt(t(atMs), segmentId)

    fun finish(atMs: Long) {
        val state = bridge.snapshot().state
        if (state == STATE_IDLE || state == STATE_FINISHED) return
        val at = t(atMs)
        record(JournalRecord.Finish(at)) {
            bridge.finish(at)
            finishedAtMs = at
        }
    }

    private fun pauseAt(at: Long) {
        if (bridge.snapshot().state != STATE_RUNNING) return refuse("pause while not recording")
        record(JournalRecord.Pause(at)) { bridge.pause(at) }
    }

    private fun resumeAt(at: Long, segmentId: Int) {
        val s = bridge.snapshot()
        if (s.state != STATE_PAUSED) return refuse("resume while not paused")
        if (segmentId <= s.segmentId) return refuse("a resume must open a NEW segment")
        record(JournalRecord.Resume(at, segmentId)) { bridge.resume(at, segmentId) }
    }

    /**
     * Settles a finished session: [stepsFinal] says whether the step total is exact or only the live count.
     * Journaled, so a restore of a finished session reports the same finality as the live result. Call once,
     * after [finish] and after any [correctClosedSegment].
     */
    fun markFinalized(stepsFinal: Boolean) = record(JournalRecord.Finalized(stepsFinal)) { this.stepsFinal = stepsFinal }

    /**
     * The exact step count of the CLOSED [segmentId] was obtained ([exact] true) or could not be. Journaled per
     * segment: the session's steps are exact ([allStepsExact]) only when every segment with an active sensor is.
     */
    fun markSegmentSettled(segmentId: Int, exact: Boolean) =
        record(JournalRecord.SegmentSettled(segmentId, exact)) { if (exact) exactSegments.add(segmentId) else exactSegments.remove(segmentId) }

    /** True when every segment in which the step sensor was active has an exact count (vacuously true with none). */
    fun allStepsExact(): Boolean = steppedSegments.all { it in exactSegments }

    // ── Inputs ──────────────────────────────────────────────────────────────

    /** Returns the `FixVerdict` code. Exactly `-1.0` is unknown for the accuracies, speed and course. */
    fun onFix(
        sessionId: String, segmentId: Int, seq: Long, timeMs: Long, wallTimeMs: Long,
        latitude: Double, longitude: Double, horizontalAccuracy: Double, speed: Double, speedAccuracy: Double,
        course: Double, altitude: Double, verticalAccuracy: Double, provider: String, isMock: Boolean, nowMs: Long,
    ): Int {
        val r = JournalRecord.Fix(
            sessionId, segmentId, seq, t(timeMs), wallTimeMs, latitude, longitude, horizontalAccuracy, speed,
            speedAccuracy, course, altitude, verticalAccuracy, provider, isMock, t(nowMs),
        )
        sink.append(JournalCodec.encode(r))
        return guarded(-1) { applyFix(r) }
    }

    /**
     * A 1 Hz tick. EVERY tick is journaled: it moves the running leg's active time and detects silence, so a
     * restore that did not see it would lose time that was shown. Returns the quality code.
     */
    fun onTick(nowMs: Long): Int = onTick(nowMs, -1L)

    /** As [onTick], with the CALENDAR time of the tick (journaled: it is how a process-death recovery measures the gap). */
    fun onTick(nowMs: Long, wallMs: Long): Int = tickAt(t(nowMs), wallMs)

    private fun tickAt(at: Long, wallMs: Long): Int {
        if (bridge.snapshot().state != STATE_RUNNING) return bridge.snapshot().quality
        sink.append(JournalCodec.encode(JournalRecord.Tick(at, wallMs)))
        noteTime(at)
        noteAnchor(at, wallMs)
        return guarded(bridge.snapshot().quality) { bridge.onTick(at) }
    }

    fun setStepSensorActive(active: Boolean) = record(JournalRecord.StepSensor(active)) {
        bridge.setStepSensorActive(active)
        if (active) steppedSegments.add(bridge.snapshot().segmentId)
    }

    fun onStepCounter(segmentId: Int, measuredAtMs: Long, deliveredAtMs: Long, raw: Long) =
        t(measuredAtMs).let { m -> t(deliveredAtMs).let { d ->
            record(JournalRecord.StepCounter(segmentId, m, d, raw)) { bridge.onStepCounter(segmentId, m, d, raw) }
        } }

    fun onSegmentSteps(segmentId: Int, measuredAtMs: Long, deliveredAtMs: Long, count: Long) =
        t(measuredAtMs).let { m -> t(deliveredAtMs).let { d ->
            record(JournalRecord.SegmentSteps(segmentId, m, d, count)) { bridge.onSegmentSteps(segmentId, m, d, count) }
        } }

    fun correctClosedSegment(exactSteps: Long) =
        record(JournalRecord.StepsCorrection(exactSteps)) { bridge.correctClosedSegment(exactSteps) }

    fun submitPdr(segmentId: Int, atMs: Long, meters: Double) =
        t(atMs).let { at -> record(JournalRecord.Pdr(segmentId, at, meters)) { bridge.submitPdr(segmentId, at, meters) } }

    // ── Recovery after a process death ───────────────────────────────────────

    /**
     * Continues a session whose process died (restored with `pauseIfRunning = false`). The calendar time that passed
     * since the last journaled moment is the DEAD INTERVAL: it is added to the session's clock, so the elapsed time
     * and (when the session was recording) the active time include it. What the person did in it is unknown, so no
     * distance is invented: the interval is closed as a GPS gap (reported in `uncreditedGapMs`) and a recording
     * continues in a NEW segment, which is never joined to the old one.
     *
     *  * a FINISHED or idle session is untouched;
     *  * a PAUSED session keeps its active time (only the elapsed time grows) and stays paused;
     *  * when the calendar clock went backwards the gap counts as 0 and [RecoveryResult.clockAnomaly] is set;
     *  * a gap above [maxGapMs] (> 0) is NOT credited: the session is parked PAUSED at the last event, as a plain
     *    restore does ([RecoveryResult.gapTooLong]).
     *
     * [nowMonoMs] / [nowWallMs] are the host's CURRENT clocks. The result is journaled as absolute records, so
     * recovering twice at the same moment adds nothing the second time. Call it once per restore.
     */
    fun recoverAfterProcessDeath(nowMonoMs: Long, nowWallMs: Long, maxGapMs: Long): RecoveryResult {
        val s = bridge.snapshot()
        val none = RecoveryResult(false, 0, false, false, s.segmentId)
        if (s.state != STATE_RUNNING && s.state != STATE_PAUSED) return none
        if (anchorWallMs < 0 || nowWallMs < 0) {
            // No calendar reference (a journal written before it existed): the safe old behaviour.
            if (s.state == STATE_RUNNING) pauseAt(lastEventMs)
            return RecoveryResult(false, 0, false, false, bridge.snapshot().segmentId)
        }
        val delta = nowWallMs - anchorWallMs
        val anomaly = delta < 0
        val gap = maxOf(0L, delta)
        if (maxGapMs > 0 && gap > maxGapMs) {
            if (s.state == STATE_RUNNING) pauseAt(lastEventMs)
            return RecoveryResult(false, gap, anomaly, true, bridge.snapshot().segmentId)
        }
        // The engine time NOW, from the pair of clocks that describe the same instant; never before an event already seen.
        val target = maxOf(checkedAdd(anchorEngineMs, gap), lastEventMs)
        val running = s.state == STATE_RUNNING
        val r = JournalRecord.Recovered(target, target - nowMonoMs, gap, running, s.segmentId + 1, anomaly, nowWallMs)
        sink.append(JournalCodec.encode(r))
        guarded(Unit) { applyRecovered(r) }
        return RecoveryResult(true, gap, anomaly, false, bridge.snapshot().segmentId)
    }

    /**
     * Applies a recovery COMPLETELY, for live and for replay alike, so that no later record is needed to make the
     * result correct: the clock moves, the unmeasured interval is recorded, a recording leg is closed and a new
     * segment opens (a paused session just keeps its state). Throws for an impossible record (negative gap, a clock
     * that goes backwards, an overflow, a segment that is not new): replay then stops there (CORRUPT).
     */
    private fun applyRecovered(r: JournalRecord.Recovered) {
        val before = bridge.snapshot()
        // EVERYTHING is checked before anything changes: a refused record must leave the recorder exactly as the
        // valid prefix left it.
        require(r.gapMs >= 0) { "recovered gap must not be negative" }
        require(r.targetMs >= lastEventMs) { "recovery must not move the clock backwards" }
        require(r.wallMs >= 0) { "recovery needs a calendar time" }
        require(if (r.wasRunning) before.state == STATE_RUNNING else before.state == STATE_PAUSED) { "recovery does not match the state" }
        if (r.wasRunning) require(r.nextSegmentId > before.segmentId) { "recovery must open a NEW segment" }
        val gapSum = checkedAdd(recoveredGapMs, r.gapMs)
        offsetMs = r.offsetMs
        recoveredGapMs = gapSum
        clockAnomaly = clockAnomaly || r.anomaly
        if (r.wasRunning) {
            bridge.onTick(r.targetMs)
            bridge.markInterruption(r.gapMs)
            bridge.pause(r.targetMs)
            bridge.resume(r.targetMs, r.nextSegmentId)
        }
        noteTime(r.targetMs)
        anchorEngineMs = r.targetMs
        anchorWallMs = r.wallMs
    }

    private fun checkedAdd(a: Long, b: Long): Long {
        require(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b) { "time overflow" }
        return a + b
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    fun snapshot(): BridgeSnapshot = bridge.snapshot()
    fun report(): String = bridge.report()
    fun samplingPlan(nowMs: Long): BridgePlan = bridge.samplingPlan(t(nowMs))

    /**
     * Time from the start to the finish, PAUSES INCLUDED: it keeps running until [finish]. [nowMs] is the host's
     * monotonic clock; it is shifted onto the engine's time line like every other live input.
     */
    fun elapsedTimeMs(nowMs: Long): Long {
        val end = if (finishedAtMs >= 0) finishedAtMs else maxOf(lastEventMs, t(nowMs))
        return maxOf(0L, end - startedAtMs)
    }

    /**
     * The CALENDAR time of the latest accepted fix, taken from that fix at ingest (so it never moves when the user
     * changes the system clock) and rebuilt from the journal on restore; -1 when there is none.
     */
    fun lastAcceptedFixWallMs(): Long = if (bridge.snapshot().lastFixAtMs < 0) -1 else lastAcceptedWallMs

    /** Barrier: true only when everything appended so far is DURABLE. Call before reading, clearing or reporting a final result. */
    fun flush(): Boolean = sink.flush()

    // ── Internals ───────────────────────────────────────────────────────────

    private fun refuse(why: String) {
        lastFailure = why
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        lastFailure = e.message ?: "illegal argument"
        fallback
    } catch (e: IllegalStateException) {
        lastFailure = e.message ?: "illegal state"
        fallback
    }

    private inline fun record(r: JournalRecord, apply: () -> Unit) {
        sink.append(JournalCodec.encode(r))
        noteTime(r.eventTimeMs())
        guarded(Unit) { apply() }
    }

    private fun applyFix(r: JournalRecord.Fix): Int {
        val verdict = bridge.onFix(
            r.sessionId, r.segmentId, r.seq, r.timeMs, r.latitude, r.longitude,
            r.horizontalAccuracy, r.speed, r.speedAccuracy, r.isMock, r.nowMs, false,
        )
        if (bridge.snapshot().lastFixAtMs == r.timeMs) lastAcceptedWallMs = r.wallTimeMs
        noteTime(r.nowMs)
        // The fix's engine time and its calendar time are the SAME instant (when it was measured), but only a fix
        // the core did not refuse for its identity or its clock can vouch for that: a measurement dated after it
        // was received (FUTURE) or from another segment is no evidence of what time it is.
        if (r.timeMs in 0..r.nowMs && verdict !in NO_CLOCK_EVIDENCE) {
            noteAnchor(r.timeMs, r.wallTimeMs)
        }
        if (settings.adaptiveSampling) lastPlan = bridge.samplingPlan(r.nowMs)
        return verdict
    }

    /** Applies a journaled record; throws only when the record is semantically impossible (valid CRC, invalid content). */
    private fun applyReplay(r: JournalRecord) {
        when (r) {
            is JournalRecord.Header -> {
                startedAtMs = r.atMs
                noteTime(r.atMs)
                noteAnchor(r.atMs, r.wallAtMs)
            }
            is JournalRecord.Pause -> { bridge.pause(r.atMs); noteTime(r.atMs) }
            is JournalRecord.Resume -> { bridge.resume(r.atMs, r.segmentId); noteTime(r.atMs) }
            is JournalRecord.Finish -> { bridge.finish(r.atMs); finishedAtMs = r.atMs; noteTime(r.atMs) }
            is JournalRecord.Fix -> applyFix(r)
            is JournalRecord.Tick -> { bridge.onTick(r.atMs); noteTime(r.atMs); noteAnchor(r.atMs, r.wallMs) }
            is JournalRecord.Recovered -> applyRecovered(r)
            is JournalRecord.StepCounter -> bridge.onStepCounter(r.segmentId, r.measuredAtMs, r.deliveredAtMs, r.raw)
            is JournalRecord.SegmentSteps -> bridge.onSegmentSteps(r.segmentId, r.measuredAtMs, r.deliveredAtMs, r.count)
            is JournalRecord.StepsCorrection -> bridge.correctClosedSegment(r.exactSteps)
            is JournalRecord.StepSensor -> {
                bridge.setStepSensorActive(r.active)
                if (r.active) steppedSegments.add(bridge.snapshot().segmentId)
            }
            is JournalRecord.Pdr -> bridge.submitPdr(r.segmentId, r.atMs, r.meters)
            is JournalRecord.Finalized -> stepsFinal = r.stepsFinal
            is JournalRecord.SegmentSettled -> if (r.exact) exactSegments.add(r.segmentId) else exactSegments.remove(r.segmentId)
        }
    }

    companion object {
        // Stable verdict codes of `TrackingCoreBridge` that mean "this fix says nothing about the clock": not running,
        // another segment or session, invalid data, a measurement dated in the future.
        private val NO_CLOCK_EVIDENCE = setOf(10, 12, 13, 14, 15)

        private const val STATE_IDLE = 0
        private const val STATE_RUNNING = 1
        private const val STATE_PAUSED = 2
        private const val STATE_FINISHED = 3

        /**
         * Starts a new session: writes the header (configuration, identity and the host's [extras]) and starts the
         * engine. Throws on invalid settings; the platform layers use [tryStart], which never throws.
         */
        fun start(
            sink: JournalSink, settings: BridgeSettings, sessionId: String, atMs: Long, wallAtMs: Long,
            segmentId: Int, initialSteps: Long, extras: Map<String, String>,
        ): SessionRecorder {
            val bridge = settings.toBridge()
            // The engine is started BEFORE the header is written: a header is only ever written for a session
            // the engine accepted, so a journal never starts with an input that cannot be replayed.
            bridge.start(sessionId, atMs, segmentId, initialSteps)
            val header = JournalRecord.Header(
                JournalCodec.VERSION, JournalCodec.ENGINE_VERSION, sessionId, atMs, wallAtMs, segmentId, initialSteps, settings, extras,
            )
            sink.append(JournalCodec.encode(header))
            return SessionRecorder(sink, settings, bridge).also {
                it.startedAtMs = atMs
                it.lastEventMs = atMs
                it.noteAnchor(atMs, wallAtMs)
            }
        }

        /** [start] without a throw: invalid settings or ids come back as [StartResult.error] and nothing is written. */
        fun tryStart(
            sink: JournalSink, settings: BridgeSettings, sessionId: String, atMs: Long, wallAtMs: Long,
            segmentId: Int, initialSteps: Long, extras: Map<String, String>,
        ): StartResult = try {
            StartResult(start(sink, settings, sessionId, atMs, wallAtMs, segmentId, initialSteps, extras), null)
        } catch (e: IllegalArgumentException) {
            StartResult(null, e.message ?: "invalid settings")
        } catch (e: IllegalStateException) {
            StartResult(null, e.message ?: "invalid state")
        }

        /**
         * Rebuilds a session from its journal lines by replaying every record with the configuration stored in
         * the header. A session that was running comes back PAUSED: a `Pause` is appended at the time of the LAST
         * journaled event, not at "now", so the time the process was dead is never counted as workout time, and a
         * reboot (the monotonic clock restarts) cannot make the leg's duration negative. `resume` continues in a
         * new segment, so nothing measured on the old clock is ever joined to the new one.
         *
         * Replay STOPS at the first record that cannot be applied (valid CRC but impossible content): everything
         * after it is NOT applied, the status becomes `CORRUPT` and `damagedLine` points at it. [recorder] is null
         * when nothing usable was found. Restore REBUILDS what was shown; to compute it again with other settings
         * use [reprocess].
         */
        fun restore(sink: JournalSink, lines: List<String>): RestoreResult = restore(sink, lines, true)

        /**
         * As [restore]. With [pauseIfRunning] false a session that was RECORDING when the process died is left
         * recording (`RestoreResult.wasRunning`), so the host can call [recoverAfterProcessDeath] to credit the dead
         * interval and continue; the default pauses it at the last event, which never counts the dead interval.
         */
        fun restore(sink: JournalSink, lines: List<String>, pauseIfRunning: Boolean): RestoreResult {
            val contents = JournalCodec.read(lines)
            val header = contents.records.firstOrNull() as? JournalRecord.Header
                ?: return RestoreResult(null, contents.status, contents.damagedLine, "", 0, 0, 0, -1, emptyMap())
            val bridge = try {
                header.settings.toBridge().also { it.start(header.sessionId, header.atMs, header.segmentId, header.initialSteps) }
            } catch (e: IllegalArgumentException) {
                return RestoreResult(null, JournalStatus.CORRUPT, 0, "", 0, 0, 0, -1, emptyMap())
            }
            val recorder = SessionRecorder(sink, header.settings, bridge)
            var nextSeq = 0L
            var fixes = 0
            var lastWall = -1L
            var lastEventMs = header.atMs
            var status = contents.status
            var damaged = contents.damagedLine
            for ((index, r) in contents.records.withIndex()) {
                try {
                    recorder.applyReplay(r)
                } catch (e: IllegalArgumentException) {
                    status = JournalStatus.CORRUPT; damaged = index; break
                } catch (e: IllegalStateException) {
                    status = JournalStatus.CORRUPT; damaged = index; break
                }
                r.eventTimeMs()?.let { lastEventMs = it }
                when (r) {
                    is JournalRecord.Fix -> {
                        if (r.seq + 1 > nextSeq) nextSeq = r.seq + 1
                        fixes++
                        lastWall = r.wallTimeMs
                    }
                    else -> Unit
                }
            }
            val s = recorder.bridge.snapshot()
            val segment = s.segmentId // the CORE's segment: a replayed recovery opens a new one without a Resume record
            val wasRunning = s.state == STATE_RUNNING
            if (wasRunning && pauseIfRunning) recorder.pauseAt(recorder.lastEventMs) // it was recording when the process died
            return RestoreResult(
                recorder, status, damaged, header.sessionId, segment, nextSeq, fixes, lastWall,
                header.extras, header.engineVersion, header.engineVersion != JournalCodec.ENGINE_VERSION,
                finished = s.state == STATE_FINISHED, stepsFinal = recorder.stepsFinal,
                wasRunning = wasRunning && !pauseIfRunning, lastEventMs = recorder.lastEventMs,
            )
        }

        /**
         * Checks a journal BEFORE it is restored or appended to: runs the replay on a sink that writes nothing and
         * keeps the longest prefix that is both intact (CRC) and applicable (the engine accepts every record).
         * The status is the WORST of the codec's and the replay's, so a CRC-valid record that cannot be applied
         * (for example a resume that reuses a segment) is `CORRUPT` here, and nothing is ever appended after it.
         * An older header version is migrated to the current one, because records of the current format must
         * never follow an older header (an old reader would take them for damage instead of refusing the file).
         * A FINISHED journal is read-only and is not migrated.
         */
        fun plan(lines: List<String>): RepairPlan {
            val contents = JournalCodec.read(lines)
            val header = contents.records.firstOrNull() as? JournalRecord.Header
                ?: return RepairPlan(emptyList(), contents.status, contents.damagedLine, false, false)
            val nonBlank = lines.filter { it.isNotBlank() }
            val probe = restore(object : JournalSink {
                override fun append(line: String) {}
                override fun flush(): Boolean = true
            }, lines)
            if (probe.recorder == null) return RepairPlan(emptyList(), probe.status, probe.damagedLine, false, false)
            // The replay stopped EARLIER than the codec's reading only when a CRC-valid record was impossible.
            val semantic = probe.damagedLine != contents.damagedLine
            val kept = if (semantic) probe.damagedLine else contents.records.size
            val keptLines = nonBlank.take(kept).toMutableList()
            val migrated = header.version < JournalCodec.VERSION && !probe.finished
            if (migrated) keptLines[0] = JournalCodec.encode(header.copy(version = JournalCodec.VERSION))
            val status = if (semantic) JournalStatus.CORRUPT else contents.status
            val damaged = if (semantic) probe.damagedLine else contents.damagedLine
            return RepairPlan(keptLines, status, damaged, migrated, true)
        }

        /**
         * REPROCESS: replays the journal's inputs through the CURRENT engine, optionally with other [settings] than
         * the ones it was recorded with, and returns the result without touching any file. Unlike [restore] the
         * result may differ from what was shown live; use it to recompute after a rule change, never to resume.
         */
        fun reprocess(lines: List<String>, settings: BridgeSettings? = null): BridgeSnapshot? {
            val contents = JournalCodec.read(lines)
            val header = contents.records.firstOrNull() as? JournalRecord.Header ?: return null
            val used = settings ?: header.settings
            val sink = object : JournalSink {
                override fun append(line: String) {}
                override fun flush(): Boolean = true
            }
            val recorder = try {
                SessionRecorder(sink, used, used.toBridge())
            } catch (e: IllegalArgumentException) {
                return null
            }
            recorder.bridge.start(header.sessionId, header.atMs, header.segmentId, header.initialSteps)
            for (r in contents.records) {
                try {
                    recorder.applyReplay(r)
                } catch (e: IllegalArgumentException) {
                    break
                } catch (e: IllegalStateException) {
                    break
                }
            }
            return recorder.bridge.snapshot()
        }

        /** The raw fixes of a journal, oldest first (for drawing or exporting the route). */
        fun fixes(lines: List<String>): List<JournalRecord.Fix> = JournalCodec.read(lines).records.filterIsInstance<JournalRecord.Fix>()

        /** The time a record carries on the engine's clock, or null when it carries none. */
        private fun JournalRecord.eventTimeMs(): Long? = when (this) {
            is JournalRecord.Header -> atMs
            is JournalRecord.Pause -> atMs
            is JournalRecord.Resume -> atMs
            is JournalRecord.Finish -> atMs
            is JournalRecord.Fix -> nowMs
            is JournalRecord.Tick -> atMs
            is JournalRecord.StepCounter -> deliveredAtMs
            is JournalRecord.SegmentSteps -> deliveredAtMs
            is JournalRecord.Pdr -> atMs
            is JournalRecord.StepsCorrection, is JournalRecord.StepSensor, is JournalRecord.Finalized,
            is JournalRecord.SegmentSettled, is JournalRecord.Recovered -> null
        }
    }
}
