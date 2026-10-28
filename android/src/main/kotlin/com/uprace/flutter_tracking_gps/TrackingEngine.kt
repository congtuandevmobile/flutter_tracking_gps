package com.uprace.flutter_tracking_gps

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tracker.bridge.BridgePlan
import tracker.bridge.BridgeSnapshot
import tracker.journal.BridgeSettings
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import tracker.journal.JournalStatus
import tracker.journal.SessionRecorder
import java.io.IOException

/** The engine configuration the app sent, as the primitives the core stores in the journal header. */
internal fun settingsOf(e: EngineConfig, adaptiveSampling: Boolean, batterySaver: Boolean) = BridgeSettings(
    maxAccuracyMeters = e.maxAccuracyMeters, maxFixAgeMs = e.maxFixAgeMs, silenceAfterMs = e.silenceAfterMs,
    maxSpeedMps = e.maxSpeedMps, minStepMeters = e.minStepMeters, accuracyNoiseFactor = e.accuracyNoiseFactor,
    stationarySpeedMps = e.stationarySpeedMps, maxBridgeMs = e.maxBridgeMs, reanchorAfterRejects = e.reanchorAfterRejects.toInt(),
    rejectMock = e.rejectMock, pdrEnabled = e.pdrEnabled, pdrConfirmRatio = e.pdrConfirmRatio, kalman = e.kalman,
    stopRadiusMeters = e.stopRadiusMeters, stopMinDwellMs = e.stopMinDwellMs, initialStrideMeters = e.initialStrideMeters,
    pdrTrustedWhenCalibrated = e.pdrTrustedWhenCalibrated, recoveryStepEvidenceAfterMs = e.recoveryStepEvidenceAfterMs,
    adaptiveSampling = adaptiveSampling, batterySaver = batterySaver,
)

/**
 * Collects raw fixes and steps for one session at a time and feeds them to the
 * shared Kotlin core through a [SessionRecorder], which writes every input to a
 * journal BEFORE applying it. It outlives the UI on purpose: if the UI dies, the
 * SDK keeps recording AND keeps computing the distance.
 *
 *  - Commands ([start], [pause], [resume], [stop], [restore]) are serialized by a
 *    mutex and wait for the jobs they stop (`cancelAndJoin`).
 *  - EVERY callback (location, tick, steps) carries the generation it was
 *    registered under and is dropped, under the core lock, when that generation
 *    is no longer current: a late callback can never touch a newer session,
 *    segment or state.
 *  - Fixes are numbered inside the core lock, so a numbering and its journal
 *    record can never interleave with a command.
 *  - The core runs on a MONOTONIC clock (`elapsedRealtime`, fix time from
 *    `elapsedRealtimeNanos`); the wall clock is only used for display.
 *  - The journal has ONE writer thread; [stop], [readPersisted] and [clearSession]
 *    first wait for it to reach the disk (flush barrier).
 *  - A failure is never swallowed: it is logged and surfaced in the snapshot.
 */
internal class TrackingEngine(
    private val source: LocationSource,
    private val store: JournalStore,
    private val scope: CoroutineScope,
    private val stepSource: StepSource = NoStepSource,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val commands = Mutex()
    private val coreLock = Any()
    private val _fixes = MutableSharedFlow<NativeFix>(extraBufferCapacity = 512)
    private val _snapshots = MutableSharedFlow<NativeSnapshot>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // a snapshot is a state: the newest wins
    )

    /** Every fix, in order, after it was queued for persistence. */
    val fixes: SharedFlow<NativeFix> = _fixes

    /** The engine state, about once per second and on every fix. */
    val snapshots: SharedFlow<NativeSnapshot> = _snapshots

    // State below is written only under [coreLock] (or by a command holding [commands]).
    @Volatile private var generation = 0L
    @Volatile private var recorder: SessionRecorder? = null
    @Volatile private var sink: DurableSink? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var state = NativeTrackingState.IDLE
    @Volatile private var segmentId = 0L
    @Volatile private var fixCount = 0L
    @Volatile private var lastFixTimeMs: Long? = null
    @Volatile private var lastError: String? = null
    @Volatile private var stepsStatus = NativeStepsStatus.OFF
    @Volatile private var extras: Map<String, String> = emptyMap()
    @Volatile private var restoreState = NativeRestoreState.NOT_RESTORED
    @Volatile private var engineChanged = false

    /** Set by a restore of a FINISHED session: whether its step total was settled. Null for a live session. */
    @Volatile private var restoredStepsFinal: Boolean? = null
    private var seq = 0L
    private var collector: Job? = null
    private var ticker: Job? = null
    private var stepJob: Job? = null
    private var adaptive = false
    private var stepsEnabled = false
    private var request: Request? = null
    private var reconfiguring = false

    private data class Request(val intervalMs: Long, val minDistanceMeters: Float)

    /** What the app asked for at start (notification text, intervals...), restored with the session. */
    fun sessionExtras(): Map<String, String> = extras

    fun snapshot(): NativeSnapshot {
        val failure = sink?.failure
        val error = lastError ?: failure?.let { "${TrackingErrorCode.STORE_WRITE_FAILED}: ${it.message ?: "I/O error"}" }
        return NativeSnapshot(
            stepsStatus = stepsStatus,
            stepsFinal = state == NativeTrackingState.STOPPED && (restoredStepsFinal ?: true),
            journalPending = (sink?.pending ?: 0).toLong(),
            restoreState = restoreState,
            engineChanged = engineChanged,
            state = state,
            segmentId = segmentId,
            fixCount = fixCount,
            updatedAtMs = wallClockMs(),
            distance = distance(),
            sessionId = sessionId,
            lastFixTimeMs = lastFixTimeMs,
            error = error,
        )
    }

    suspend fun start(config: StartConfig): NativeSnapshot = commands.withLock {
        haltJobs()
        // A fresh journal: a new start must never be mixed with an old one of the same id.
        withContext(Dispatchers.IO) { store.clear(config.sessionId) }
        val now = monotonicMs()
        val newSink = store.open(config.sessionId)
        val newExtras = mapOf(
            "title" to config.notificationTitle, "text" to config.notificationText,
            "intervalMs" to config.intervalMs.toString(), "minDistanceMeters" to config.minDistanceMeters.toString(),
            "stepsEnabled" to config.stepsEnabled.toString(),
        )
        val rec = SessionRecorder.start(
            newSink, settingsOf(config.engine, config.adaptiveSampling, config.batterySaver), config.sessionId,
            now, wallClockMs(), config.startSegmentId.toInt(), config.initialSteps, newExtras,
        )
        synchronized(coreLock) {
            recorder = rec
            sink = newSink
            sessionId = config.sessionId
            seq = config.startSeq
            segmentId = config.startSegmentId
            fixCount = 0
            lastFixTimeMs = null
            lastError = null
            extras = newExtras
            restoreState = NativeRestoreState.NOT_RESTORED
            engineChanged = false
            restoredStepsFinal = null
            adaptive = config.adaptiveSampling
            stepsEnabled = config.stepsEnabled
            request = Request(config.intervalMs, config.minDistanceMeters.toFloat())
            state = NativeTrackingState.RUNNING
        }
        beginCollecting()
        beginSteps()
        publish()
        snapshot()
    }

    suspend fun pause(id: String): NativeSnapshot = commands.withLock {
        if (id == sessionId && state == NativeTrackingState.RUNNING) {
            haltJobs()
            synchronized(coreLock) {
                state = NativeTrackingState.PAUSED
                coreStrict { it.pause(monotonicMs()) }
            }
            publish()
        }
        snapshot()
    }

    suspend fun resume(id: String): NativeSnapshot = commands.withLock {
        if (id == sessionId && state == NativeTrackingState.PAUSED) {
            synchronized(coreLock) {
                segmentId += 1
                coreStrict { it.resume(monotonicMs(), segmentId.toInt()) }
                state = NativeTrackingState.RUNNING
            }
            beginCollecting()
            beginSteps()
            publish()
        }
        snapshot()
    }

    /** Finishes the session and returns only after its journal is durable, so the result is final. */
    suspend fun stop(id: String): NativeSnapshot = commands.withLock {
        if (id == sessionId && state != NativeTrackingState.IDLE && state != NativeTrackingState.STOPPED &&
            state != NativeTrackingState.FINISHING
        ) {
            haltJobs()
            // FINISHING until the journal is durable: nothing published meanwhile may pass for final.
            synchronized(coreLock) {
                state = NativeTrackingState.FINISHING
                coreStrict {
                    it.finish(monotonicMs())
                    // Android has no exact-count query: the live count is all there is, and it is final.
                    it.markFinalized(true)
                }
            }
            publish()
            withContext(Dispatchers.IO) { recorder?.flush() }
            synchronized(coreLock) { state = NativeTrackingState.STOPPED }
            publish()
        }
        snapshot()
    }

    /**
     * A step estimate for [segment] from the app's own source: held as pending
     * until the GPS that returns confirms it. One of another segment is refused.
     */
    fun submitPdr(id: String, segment: Int, meters: Double) {
        withCoreIf(generation, id) { it.submitPdr(segment, monotonicMs(), meters) }
        publish()
    }

    /** Coordinate-free timeline of the core's decisions. */
    fun report(): String = synchronized(coreLock) { recorder?.report() } ?: ""

    /**
     * After a process death: replays the session's journal (with the
     * configuration stored in ITS header, not the one the app passes now) and
     * leaves the session PAUSED. A damaged last record is dropped and the journal
     * repaired so new records can be appended; damage in the middle keeps only
     * the intact part and says so in the snapshot. If this session already runs,
     * nothing is replayed.
     */
    @Throws(IOException::class)
    suspend fun restore(config: StartConfig): NativeSnapshot = commands.withLock {
        val running = state == NativeTrackingState.RUNNING || state == NativeTrackingState.PAUSED
        if (config.sessionId == sessionId && running) return@withLock snapshot()
        haltJobs()
        val lines = withContext(Dispatchers.IO) { store.read(config.sessionId) }
        val contents = JournalCodec.read(lines)
        if (contents.records.isEmpty() || contents.records.first() !is JournalRecord.Header) {
            throw IOException("no usable journal for this session (${contents.status})")
        }
        val usable = lines.filter { it.isNotBlank() }.take(contents.records.size)
        if (contents.status != JournalStatus.OK) {
            // Only a damaged LAST record is a normal crash artefact; damage in the middle drops
            // everything after it, so the original is kept next to the repaired file.
            val keepOriginal = contents.status != JournalStatus.TRUNCATED_TAIL
            withContext(Dispatchers.IO) { store.rewrite(config.sessionId, usable, keepOriginal) }
        }
        val newSink = store.open(config.sessionId)
        val result = SessionRecorder.restore(newSink, usable)
        val rec = result.recorder ?: throw IOException("journal replay failed (${result.status})")
        synchronized(coreLock) {
            recorder = rec
            sink = newSink
            sessionId = result.sessionId
            seq = result.nextSeq
            segmentId = result.segmentId.toLong()
            fixCount = result.fixCount.toLong()
            lastFixTimeMs = if (result.lastFixWallMs >= 0) result.lastFixWallMs else null
            extras = result.extras
            adaptive = settingsAdaptive(contents)
            stepsEnabled = result.extras["stepsEnabled"] == "true"
            stepsStatus = NativeStepsStatus.OFF
            val interval = result.extras["intervalMs"]?.toLongOrNull() ?: config.intervalMs
            val minDistance = result.extras["minDistanceMeters"]?.toFloatOrNull() ?: config.minDistanceMeters.toFloat()
            request = Request(interval, minDistance)
            lastError = if (result.engineChanged) {
                "${TrackingErrorCode.ENGINE_CHANGED}: the journal was recorded with engine ${result.engineVersion}; " +
                    "the replay may differ from what was shown live"
            } else null
            lastError = lastError ?: when (contents.status) {
                JournalStatus.OK -> null
                JournalStatus.TRUNCATED_TAIL -> "${TrackingErrorCode.JOURNAL_REPAIRED}: the last record was incomplete and was dropped"
                else -> "${TrackingErrorCode.JOURNAL_CORRUPT}: damage at line ${contents.damagedLine}; only the intact part was restored"
            }
            engineChanged = result.engineChanged
            restoreState = when (contents.status) {
                JournalStatus.OK -> NativeRestoreState.COMPLETE
                JournalStatus.TRUNCATED_TAIL -> NativeRestoreState.TAIL_REPAIRED
                else -> NativeRestoreState.PARTIAL
            }
            // A journal that ended at Finish is a finished session: read-only, never "paused".
            if (result.finished) {
                restoredStepsFinal = result.stepsFinal
                state = NativeTrackingState.STOPPED
            } else {
                restoredStepsFinal = null
                state = NativeTrackingState.PAUSED
            }
        }
        publish()
        snapshot()
    }

    /** Reads the persisted fixes (after the writer reached the disk); blocking I/O runs on `Dispatchers.IO`. */
    @Throws(IOException::class)
    suspend fun readPersisted(id: String, afterSeq: Long): List<NativeFix> = withContext(Dispatchers.IO) {
        if (id == sessionId) recorder?.flush()
        SessionRecorder.fixes(store.read(id)).filter { it.seq > afterSeq }.map { it.toNative() }
    }

    /** Deletes the journal; the writer is closed and drained first, so nothing re-creates the file. */
    @Throws(IOException::class)
    suspend fun clearSession(id: String) = withContext(Dispatchers.IO) {
        if (id == sessionId) {
            commands.withLock {
                if (state == NativeTrackingState.RUNNING || state == NativeTrackingState.PAUSED ||
                    state == NativeTrackingState.FINISHING
                ) {
                    throw IOException("session $id is still open: finish it first")
                }
                recorder = null
                sink = null
            }
        }
        store.clear(id)
    }

    @Throws(IOException::class)
    suspend fun listSessions(): List<String> = withContext(Dispatchers.IO) { store.list() }

    /** Marks the session as failed for a reason found outside the engine. */
    fun markPermissionDenied() {
        state = NativeTrackingState.PERMISSION_DENIED
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun settingsAdaptive(contents: tracker.journal.JournalContents): Boolean =
        (contents.records.first() as JournalRecord.Header).settings.adaptiveSampling

    /**
     * Retires every callback of the current generation, then waits for the jobs to
     * end. The generation is bumped FIRST, under the core lock, so a callback that
     * is already running finishes before and one that arrives later is dropped.
     */
    private suspend fun haltJobs() {
        synchronized(coreLock) { generation += 1 }
        collector?.cancelAndJoin()
        ticker?.cancelAndJoin()
        stepJob?.cancelAndJoin()
        collector = null
        ticker = null
        stepJob = null
        reconfiguring = false
    }

    private fun beginCollecting() {
        val req = request ?: return
        val gen = generation
        collector = scope.launch {
            source.locations(req.intervalMs, req.minDistanceMeters)
                .catch { error -> onSourceFailure(gen, error) }
                .collect { location -> onLocation(gen, location) }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                synchronized(coreLock) {
                    if (gen != generation || state != NativeTrackingState.RUNNING) return@synchronized
                    guardedCore { it.onTick(monotonicMs()) }
                }
                publish()
            }
        }
    }

    /**
     * Registers the step sensor for the open segment. A failed or missing sensor
     * (no permission, no hardware) never stops the recording: it only sets
     * [stepsStatus]. The generation and segment are captured now, so a reading
     * that arrives after a pause or resume cannot be fed to the wrong one.
     */
    private fun beginSteps() {
        if (!stepsEnabled) {
            stepsStatus = NativeStepsStatus.OFF
            return
        }
        val status = stepSource.status()
        stepsStatus = status
        synchronized(coreLock) { guardedCore { it.setStepSensorActive(status == NativeStepsStatus.ACTIVE) } }
        if (status != NativeStepsStatus.ACTIVE) return
        val gen = generation
        val segment = segmentId.toInt()
        stepJob = scope.launch {
            stepSource.counts()
                .catch { error ->
                    if (error is CancellationException) throw error
                    Log.e(LOG_TAG, "step sensor failed", error)
                    synchronized(coreLock) {
                        if (gen != generation) return@synchronized
                        stepsStatus = NativeStepsStatus.FAILED
                        lastError = "${TrackingErrorCode.STEPS_FAILED}: ${error.message ?: error::class.java.simpleName}"
                        guardedCore { it.setStepSensorActive(false) }
                    }
                    publish()
                }
                .collect { sample ->
                    synchronized(coreLock) {
                        if (gen != generation || state != NativeTrackingState.RUNNING) return@synchronized
                        guardedCore { it.onStepCounter(segment, sample.elapsedMs, monotonicMs(), sample.raw) }
                    }
                    publish()
                }
        }
    }

    private fun onLocation(gen: Long, l: RawLocation) {
        var fix: NativeFix? = null
        var plan: BridgePlan? = null
        synchronized(coreLock) {
            val rec = recorder
            val id = sessionId
            if (gen != generation || state != NativeTrackingState.RUNNING || rec == null || id == null) return
            val n = seq++
            fixCount += 1
            lastFixTimeMs = l.timeMs
            fix = l.toFix(id, segmentId, n)
            guardedCore {
                it.onFix(
                    id, segmentId.toInt(), n, l.elapsedMs, l.timeMs, l.latitude, l.longitude, l.horizontalAccuracy, l.speed,
                    l.speedAccuracy, l.course, l.altitude, l.verticalAccuracy, l.provider, l.isMock, monotonicMs(),
                )
            }
            if (adaptive) plan = rec.lastPlan
        }
        fix?.let { _fixes.tryEmit(it) }
        publish()
        plan?.let { applyPlan(gen, it) }
    }

    /** Changes the location request when the adaptive plan changed. Only the collector and ticker restart. */
    private fun applyPlan(gen: Long, plan: BridgePlan) {
        val wanted = Request(plan.intervalMs, plan.minDistanceMeters.toFloat())
        if (wanted == request || reconfiguring) return
        reconfiguring = true
        scope.launch {
            commands.withLock {
                if (gen == generation && state == NativeTrackingState.RUNNING) {
                    collector?.cancelAndJoin()
                    ticker?.cancelAndJoin()
                    request = wanted
                    reconfiguring = false
                    beginCollecting()
                } else {
                    reconfiguring = false
                }
            }
        }
    }

    private fun onSourceFailure(gen: Long, error: Throwable) {
        if (error is CancellationException) throw error
        Log.e(LOG_TAG, "location updates failed", error)
        synchronized(coreLock) {
            if (gen != generation) return
            lastError = "${TrackingErrorCode.LOCATION_UPDATES_FAILED}: ${error.message ?: error::class.java.simpleName}"
            state = if (error is SecurityException) NativeTrackingState.PERMISSION_DENIED else NativeTrackingState.ERROR
        }
        publish()
    }

    private fun publish() {
        _snapshots.tryEmit(snapshot())
    }

    /**
     * For data coming from the OS (a fix, a tick, a step): a failure of the core
     * must never crash the collector, so it is logged and shown in `lastError`.
     * Call under [coreLock].
     */
    private fun guardedCore(block: (SessionRecorder) -> Unit) {
        val rec = recorder ?: return
        try {
            block(rec)
        } catch (e: IllegalArgumentException) {
            reportCoreFailure(e)
        } catch (e: IllegalStateException) {
            reportCoreFailure(e)
        }
    }

    /** For commands: a failure propagates to the caller (and reaches Dart as a platform error). Call under [coreLock]. */
    private fun coreStrict(block: (SessionRecorder) -> Unit) {
        val rec = recorder ?: return
        block(rec)
    }

    private fun withCoreIf(gen: Long, id: String, block: (SessionRecorder) -> Unit) {
        synchronized(coreLock) {
            if (gen != generation || id != sessionId || state != NativeTrackingState.RUNNING) return
            guardedCore(block)
        }
    }

    private fun reportCoreFailure(e: Exception) {
        Log.e(LOG_TAG, "the distance core refused an input", e)
        lastError = "${TrackingErrorCode.CORE_REJECTED}: ${e.message ?: e::class.java.simpleName}"
    }

    private fun distance(): NativeDistance {
        val s: BridgeSnapshot = synchronized(coreLock) { recorder?.snapshot() } ?: return emptyDistance()
        val wallNow = wallClockMs()
        val monoNow = monotonicMs()
        return NativeDistance(
            quality = qualityOf(s.quality),
            totalMeters = s.totalMeters,
            gpsMeters = s.gpsMeters,
            gapBridgeMeters = s.gapBridgeMeters,
            pdrMeters = s.pdrMeters,
            pendingPdrMeters = s.pendingPdrMeters,
            movingTimeMs = s.movingTimeMs,
            isStopped = s.isStopped,
            speedMps = s.speedMps,
            // The core measures on the monotonic clock; the app wants a calendar time.
            lastAcceptedFixAtMs = if (s.lastFixAtMs >= 0) wallNow - (monoNow - s.lastFixAtMs) else -1,
            steps = s.steps,
            strideMeters = s.strideMeters,
            activeTimeMs = s.activeTimeMs,
            bridgedGapMs = s.bridgedGapMs,
            uncreditedGapMs = s.uncreditedGapMs,
            uncreditedGaps = s.uncreditedGaps.toLong(),
            ambiguousSteps = s.ambiguousSteps,
            distanceEligibleSteps = s.distanceEligibleSteps,
        )
    }

    private fun qualityOf(ordinal: Int): NativeGpsQuality = when (ordinal) {
        1 -> NativeGpsQuality.GOOD
        2 -> NativeGpsQuality.DEGRADED
        3 -> NativeGpsQuality.UNAVAILABLE
        4 -> NativeGpsQuality.RECOVERING
        else -> NativeGpsQuality.UNKNOWN
    }

    private fun RawLocation.toFix(id: String, segment: Long, n: Long) = NativeFix(
        sessionId = id, segmentId = segment, seq = n, timeMs = timeMs, elapsedMs = elapsedMs, latitude = latitude,
        longitude = longitude, horizontalAccuracy = horizontalAccuracy, speed = speed, speedAccuracy = speedAccuracy,
        course = course, altitude = altitude, verticalAccuracy = verticalAccuracy, provider = provider, isMock = isMock,
    )

    private fun JournalRecord.Fix.toNative() = NativeFix(
        sessionId = sessionId, segmentId = segmentId.toLong(), seq = seq, timeMs = wallTimeMs, elapsedMs = timeMs,
        latitude = latitude, longitude = longitude, horizontalAccuracy = horizontalAccuracy, speed = speed,
        speedAccuracy = speedAccuracy, course = course, altitude = altitude, verticalAccuracy = verticalAccuracy,
        provider = provider, isMock = isMock,
    )

    private companion object {
        const val LOG_TAG = "FlutterTrackingGps"
        const val TICK_MS = 1_000L
    }
}

/** The distance block of a snapshot when there is no engine (idle / detached). */
internal fun emptyDistance(): NativeDistance = NativeDistance(
    quality = NativeGpsQuality.UNKNOWN,
    totalMeters = 0.0,
    gpsMeters = 0.0,
    gapBridgeMeters = 0.0,
    pdrMeters = 0.0,
    pendingPdrMeters = 0.0,
    movingTimeMs = 0,
    isStopped = false,
    speedMps = -1.0,
    lastAcceptedFixAtMs = -1,
    steps = 0,
    strideMeters = -1.0,
    activeTimeMs = 0,
    bridgedGapMs = 0,
    uncreditedGapMs = 0,
    uncreditedGaps = 0,
    ambiguousSteps = 0,
    distanceEligibleSteps = 0,
)
