package tracker.journal

import tracker.bridge.BridgePlan
import tracker.bridge.BridgeSnapshot
import tracker.bridge.TrackingCoreBridge

/**
 * Where the journal goes. The host implements it with ONE serial writer: [append]
 * must not block or reorder, and [flush] must return only when every appended
 * line is on disk (it is the barrier before a read, a clear or a finish).
 */
interface JournalSink {
    fun append(line: String)
    fun flush()
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
)

/**
 * The engine plus its journal, in ONE place, so Android, iOS and a watch record
 * and restore identically. Every input is written to the journal BEFORE it is
 * applied (write-ahead), and the engine is a pure function of those inputs, so
 * replaying the journal reproduces the live result exactly: the distance, the
 * pending estimates, the stride, the step totals, the gaps and the times.
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

    private var lastQuality = 0

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun pause(atMs: Long) = record(JournalRecord.Pause(atMs)) { bridge.pause(atMs) }

    fun resume(atMs: Long, segmentId: Int) = record(JournalRecord.Resume(atMs, segmentId)) { bridge.resume(atMs, segmentId) }

    fun finish(atMs: Long) = record(JournalRecord.Finish(atMs)) { bridge.finish(atMs) }

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
        return applyFix(r)
    }

    /** A 1 Hz tick. Written to the journal only when it changed the state. Returns the quality code. */
    fun onTick(nowMs: Long): Int {
        val q = bridge.onTick(nowMs)
        if (q != lastQuality && q == UNAVAILABLE) sink.append(JournalCodec.encode(JournalRecord.Tick(nowMs)))
        lastQuality = q
        return q
    }

    fun setStepSensorActive(active: Boolean) = record(JournalRecord.StepSensor(active)) { bridge.setStepSensorActive(active) }

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

    /** Barrier: returns when everything appended so far is durable. Call before reading, clearing or reporting a final result. */
    fun flush() = sink.flush()

    // ── Internals ───────────────────────────────────────────────────────────

    private inline fun record(r: JournalRecord, apply: () -> Unit) {
        sink.append(JournalCodec.encode(r))
        apply()
    }

    private fun applyFix(r: JournalRecord.Fix): Int {
        val verdict = bridge.onFix(
            r.sessionId, r.segmentId, r.seq, r.timeMs, r.latitude, r.longitude,
            r.horizontalAccuracy, r.speed, r.speedAccuracy, r.isMock, r.nowMs, false,
        )
        if (settings.adaptiveSampling) lastPlan = bridge.samplingPlan(r.nowMs)
        return verdict
    }

    private fun applyReplay(r: JournalRecord) {
        when (r) {
            is JournalRecord.Header -> Unit
            is JournalRecord.Pause -> bridge.pause(r.atMs)
            is JournalRecord.Resume -> bridge.resume(r.atMs, r.segmentId)
            is JournalRecord.Finish -> bridge.finish(r.atMs)
            is JournalRecord.Fix -> applyFix(r)
            is JournalRecord.Tick -> lastQuality = bridge.onTick(r.atMs)
            is JournalRecord.StepCounter -> bridge.onStepCounter(r.segmentId, r.measuredAtMs, r.deliveredAtMs, r.raw)
            is JournalRecord.SegmentSteps -> bridge.onSegmentSteps(r.segmentId, r.measuredAtMs, r.deliveredAtMs, r.count)
            is JournalRecord.StepsCorrection -> bridge.correctClosedSegment(r.exactSteps)
            is JournalRecord.StepSensor -> bridge.setStepSensorActive(r.active)
            is JournalRecord.Pdr -> bridge.submitPdr(r.segmentId, r.atMs, r.meters)
        }
    }

    companion object {
        private const val UNAVAILABLE = 3

        /**
         * Starts a new session: writes the header (configuration, identity and the
         * host's [extras]) and starts the engine.
         */
        fun start(
            sink: JournalSink, settings: BridgeSettings, sessionId: String, atMs: Long, wallAtMs: Long,
            segmentId: Int, initialSteps: Long, extras: Map<String, String>,
        ): SessionRecorder {
            val bridge = settings.toBridge()
            val header = JournalRecord.Header(
                JournalCodec.VERSION, JournalCodec.ENGINE_VERSION, sessionId, atMs, wallAtMs, segmentId, initialSteps, settings, extras,
            )
            sink.append(JournalCodec.encode(header))
            bridge.start(sessionId, atMs, segmentId, initialSteps)
            return SessionRecorder(sink, settings, bridge)
        }

        /**
         * Rebuilds a session from its journal lines by replaying every record with
         * the configuration stored in the header. A session that was running comes
         * back PAUSED: a `Pause` is appended at the time of the LAST journaled
         * event, not at "now", so the time the process was dead is never counted as
         * workout time, and a reboot (the monotonic clock restarts) cannot make the
         * leg's duration negative. `resume` continues in a new segment, so nothing
         * measured on the old clock is ever joined to the new one. [recorder] is null
         * when nothing usable was found. Restore REBUILDS what was shown; to compute
         * it again with other settings use [reprocess].
         */
        fun restore(sink: JournalSink, lines: List<String>): RestoreResult {
            val contents = JournalCodec.read(lines)
            val header = contents.records.firstOrNull() as? JournalRecord.Header
            if (header == null) {
                return RestoreResult(null, contents.status, contents.damagedLine, "", 0, 0, 0, -1, emptyMap())
            }
            val recorder = SessionRecorder(sink, header.settings, header.settings.toBridge())
            recorder.bridge.start(header.sessionId, header.atMs, header.segmentId, header.initialSteps)
            var nextSeq = 0L
            var fixes = 0
            var lastWall = -1L
            var segment = header.segmentId
            var lastEventMs = header.atMs
            for (r in contents.records) {
                recorder.applyReplay(r)
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
            if (s.state == 1) recorder.pause(lastEventMs) // it was recording when the process died
            return RestoreResult(
                recorder, contents.status, contents.damagedLine, header.sessionId, segment, nextSeq, fixes, lastWall,
                header.extras, header.engineVersion, header.engineVersion != JournalCodec.ENGINE_VERSION,
            )
        }

        /**
         * REPROCESS: replays the journal's inputs through the CURRENT engine, optionally with
         * other [settings] than the ones it was recorded with, and returns the result without
         * touching any file. Unlike [restore] the result may differ from what was shown live;
         * use it to recompute after a rule change, never to resume a session.
         */
        fun reprocess(lines: List<String>, settings: BridgeSettings? = null): BridgeSnapshot? {
            val contents = JournalCodec.read(lines)
            val header = contents.records.firstOrNull() as? JournalRecord.Header ?: return null
            val used = settings ?: header.settings
            val sink = object : JournalSink {
                override fun append(line: String) {}
                override fun flush() {}
            }
            val recorder = SessionRecorder(sink, used, used.toBridge())
            recorder.bridge.start(header.sessionId, header.atMs, header.segmentId, header.initialSteps)
            for (r in contents.records) recorder.applyReplay(r)
            return recorder.bridge.snapshot()
        }

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
            is JournalRecord.StepsCorrection, is JournalRecord.StepSensor -> null
        }

        /** The raw fixes of a journal, oldest first (for drawing or exporting the route). */
        fun fixes(lines: List<String>): List<JournalRecord.Fix> = JournalCodec.read(lines).records.filterIsInstance<JournalRecord.Fix>()
    }
}
