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

    /** Segments in which the step sensor was active, and the ones whose exact count was obtained. */
    private val steppedSegments = HashSet<Int>()
    private val exactSegments = HashSet<Int>()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun pause(atMs: Long) {
        if (bridge.snapshot().state != STATE_RUNNING) return refuse("pause while not recording")
        record(JournalRecord.Pause(atMs)) { bridge.pause(atMs) }
    }

    fun resume(atMs: Long, segmentId: Int) {
        val s = bridge.snapshot()
        if (s.state != STATE_PAUSED) return refuse("resume while not paused")
        if (segmentId <= s.segmentId) return refuse("a resume must open a NEW segment")
        record(JournalRecord.Resume(atMs, segmentId)) { bridge.resume(atMs, segmentId) }
    }

    fun finish(atMs: Long) {
        val state = bridge.snapshot().state
        if (state == STATE_IDLE || state == STATE_FINISHED) return
        record(JournalRecord.Finish(atMs)) { bridge.finish(atMs) }
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
            sessionId, segmentId, seq, timeMs, wallTimeMs, latitude, longitude, horizontalAccuracy, speed,
            speedAccuracy, course, altitude, verticalAccuracy, provider, isMock, nowMs,
        )
        sink.append(JournalCodec.encode(r))
        return guarded(-1) { applyFix(r) }
    }

    /**
     * A 1 Hz tick. EVERY tick is journaled: it moves the running leg's active time and detects silence, so a
     * restore that did not see it would lose time that was shown. Returns the quality code.
     */
    fun onTick(nowMs: Long): Int {
        if (bridge.snapshot().state != STATE_RUNNING) return bridge.snapshot().quality
        sink.append(JournalCodec.encode(JournalRecord.Tick(nowMs)))
        return guarded(bridge.snapshot().quality) { bridge.onTick(nowMs) }
    }

    fun setStepSensorActive(active: Boolean) = record(JournalRecord.StepSensor(active)) {
        bridge.setStepSensorActive(active)
        if (active) steppedSegments.add(bridge.snapshot().segmentId)
    }

    fun onStepCounter(segmentId: Int, measuredAtMs: Long, deliveredAtMs: Long, raw: Long) =
        record(JournalRecord.StepCounter(segmentId, measuredAtMs, deliveredAtMs, raw)) {
            bridge.onStepCounter(segmentId, measuredAtMs, deliveredAtMs, raw)
        }

    fun onSegmentSteps(segmentId: Int, measuredAtMs: Long, deliveredAtMs: Long, count: Long) =
        record(JournalRecord.SegmentSteps(segmentId, measuredAtMs, deliveredAtMs, count)) {
            bridge.onSegmentSteps(segmentId, measuredAtMs, deliveredAtMs, count)
        }

    fun correctClosedSegment(exactSteps: Long) =
        record(JournalRecord.StepsCorrection(exactSteps)) { bridge.correctClosedSegment(exactSteps) }

    fun submitPdr(segmentId: Int, atMs: Long, meters: Double) =
        record(JournalRecord.Pdr(segmentId, atMs, meters)) { bridge.submitPdr(segmentId, atMs, meters) }

    // ── Reads ───────────────────────────────────────────────────────────────

    fun snapshot(): BridgeSnapshot = bridge.snapshot()
    fun report(): String = bridge.report()
    fun samplingPlan(nowMs: Long): BridgePlan = bridge.samplingPlan(nowMs)

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
        guarded(Unit) { apply() }
    }

    private fun applyFix(r: JournalRecord.Fix): Int {
        val verdict = bridge.onFix(
            r.sessionId, r.segmentId, r.seq, r.timeMs, r.latitude, r.longitude,
            r.horizontalAccuracy, r.speed, r.speedAccuracy, r.isMock, r.nowMs, false,
        )
        if (bridge.snapshot().lastFixAtMs == r.timeMs) lastAcceptedWallMs = r.wallTimeMs
        if (settings.adaptiveSampling) lastPlan = bridge.samplingPlan(r.nowMs)
        return verdict
    }

    /** Applies a journaled record; throws only when the record is semantically impossible (valid CRC, invalid content). */
    private fun applyReplay(r: JournalRecord) {
        when (r) {
            is JournalRecord.Header -> Unit
            is JournalRecord.Pause -> bridge.pause(r.atMs)
            is JournalRecord.Resume -> bridge.resume(r.atMs, r.segmentId)
            is JournalRecord.Finish -> bridge.finish(r.atMs)
            is JournalRecord.Fix -> applyFix(r)
            is JournalRecord.Tick -> bridge.onTick(r.atMs)
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
            return SessionRecorder(sink, settings, bridge)
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
        fun restore(sink: JournalSink, lines: List<String>): RestoreResult {
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
            var segment = header.segmentId
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
                    is JournalRecord.Resume -> segment = r.segmentId
                    else -> Unit
                }
            }
            val s = recorder.bridge.snapshot()
            if (s.state == STATE_RUNNING) recorder.pause(lastEventMs) // it was recording when the process died
            return RestoreResult(
                recorder, status, damaged, header.sessionId, segment, nextSeq, fixes, lastWall,
                header.extras, header.engineVersion, header.engineVersion != JournalCodec.ENGINE_VERSION,
                finished = s.state == STATE_FINISHED, stepsFinal = recorder.stepsFinal,
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
            is JournalRecord.SegmentSettled -> null
        }
    }
}
