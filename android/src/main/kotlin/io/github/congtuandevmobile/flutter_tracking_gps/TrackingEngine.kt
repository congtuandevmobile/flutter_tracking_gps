package io.github.congtuandevmobile.flutter_tracking_gps

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
import tracker.algorithms.cadence.CadenceChoice
import tracker.algorithms.cadence.CadenceSource
import tracker.algorithms.cadence.ExternalCadenceHub
import tracker.algorithms.motion.PedalCadenceEstimator
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
    private val accelerometerSource: AccelerometerSource = NoAccelerometerSource,
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
    @Volatile private var cadenceStatus = NativeMotionStatus.OFF
    @Volatile private var extras: Map<String, String> = emptyMap()
    @Volatile private var restoreState = NativeRestoreState.NOT_RESTORED
    @Volatile private var engineChanged = false

    /** Set by a restore of a FINISHED session: whether its step total was settled. Null for a live session. */
    @Volatile private var restoredStepsFinal: Boolean? = null
    private var seq = 0L
    private var collector: Job? = null
    private var ticker: Job? = null
    private var stepJob: Job? = null
    private var cadenceJob: Job? = null
    private var pedalCadence = false

    /** Live only (never journaled, no effect on distance). Touched under [coreLock]. */
    private val cadence = PedalCadenceEstimator()
    private val externalCadence = ExternalCadenceHub()
    private var adaptive = false
    private var stepsEnabled = false
    private var request: Request? = null
    private var reconfiguring = false

    /** Location updates failed (permission revoked, provider error): a recording that cannot collect says so. */
    @Volatile private var collectorFailed = false

    /** `BridgeSnapshot.state` code of a session that is recording (see `TrackingCoreBridge`). */
    private val STATE_CODE_RUNNING = 1
    private val DEFAULT_INTERVAL_MS = 1000L

    private data class Request(val intervalMs: Long, val minDistanceMeters: Float)

    /** What the app asked for at start (notification text, intervals...), restored with the session. */
    fun sessionExtras(): Map<String, String> = extras

    fun snapshot(): NativeSnapshot {
        val failure = sink?.failure
        val error = lastError ?: failure?.let { "${TrackingErrorCode.STORE_WRITE_FAILED}: ${it.message ?: "I/O error"}" }
        val choice = resolveCadence()
        return NativeSnapshot(
            stepsStatus = stepsStatus,
            pedalCadenceRpm = choice.rpm,
            cadenceSource = when (choice.source) {
                CadenceSource.PHONE -> NativeCadenceSource.PHONE
                CadenceSource.EXTERNAL -> NativeCadenceSource.EXTERNAL_SENSOR
                CadenceSource.NONE -> NativeCadenceSource.NONE
            },
            cadenceStatus = cadenceStatus,
            stepsFinal = state == NativeTrackingState.STOPPED && (restoredStepsFinal ?: true),
            journalPending = (sink?.pending ?: 0).toLong(),
            journalFailed = failure != null,
            restoreState = restoreState,
            engineChanged = engineChanged,
            elapsedTimeMs = synchronized(coreLock) { recorder?.elapsedTimeMs(monotonicMs()) } ?: 0,
            recoveredGapMs = synchronized(coreLock) { recorder?.recoveredGapMs } ?: 0,
            clockAnomaly = synchronized(coreLock) { recorder?.clockAnomaly } ?: false,
            collection = when {
                // A failure moves the state to ERROR/PERMISSION_DENIED, but the person still asked to record.
                collectorFailed && state != NativeTrackingState.STOPPED && state != NativeTrackingState.IDLE -> NativeCollection.BLOCKED
                state != NativeTrackingState.RUNNING -> NativeCollection.NOT_COLLECTING
                else -> NativeCollection.COLLECTING
            },
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
            "pedalCadence" to config.pedalCadence.toString(),
            // Kept in the journal so a recovery without Flutter (the service) knows the limit the app chose.
            "maxRecoverGapMs" to config.maxRecoverGapMs.toString(),
        )
        val rec = SessionRecorder.start(
            newSink, settingsOf(config.engine, config.adaptiveSampling, config.batterySaver), config.sessionId,
            now, wallClockMs(), config.startSegmentId.toInt(), config.initialSteps, newExtras,
        )
        // The open-session pointer: lets the process find this session again after it dies.
        withContext(Dispatchers.IO) { store.markActive(config.sessionId) }
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
            pedalCadence = config.pedalCadence
            externalCadence.reset()
            request = Request(config.intervalMs, config.minDistanceMeters.toFloat())
            state = NativeTrackingState.RUNNING
        }
        beginCollecting()
        beginSteps()
        beginCadence()
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
            // The pause is acknowledged only once it is DURABLE: a process killed right after `await pause()` must not
            // come back recording what the person had just paused.
            val durable = withContext(Dispatchers.IO) { recorder?.flush() == true }
            if (!durable) throw IOException("the pause could not be persisted")
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
            beginCadence()
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
            // Whether it is durable is read from the sink (`journalFailed`/`journalPending`), never assumed.
            withContext(Dispatchers.IO) {
                recorder?.flush()
                // The session is over: free the writer thread and the file handle (a later open reopens it).
                try {
                    store.clearActive(id)
                    store.release(id)
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "cannot release the journal writer", e)
                }
            }
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

    /**
     * The cadence to show: a healthy external sensor (fed by the host) else the phone estimate, only while recording.
     * Live only: never journaled, no effect on distance.
     */
    private fun resolveCadence(): CadenceChoice = synchronized(coreLock) {
        if (state != NativeTrackingState.RUNNING) return@synchronized CadenceChoice(PedalCadenceEstimator.UNKNOWN, CadenceSource.NONE)
        val now = monotonicMs()
        val phone = if (pedalCadence && cadenceStatus == NativeMotionStatus.ACTIVE) cadence.rpm(now) else PedalCadenceEstimator.UNKNOWN
        externalCadence.resolve(phone, now)
    }

    /** External cadence input (a connection state, a raw CSC notification, a ready rpm): only for the open session. */
    private fun withExternalCadence(id: String, block: (ExternalCadenceHub, Long) -> Unit) {
        synchronized(coreLock) {
            if (id != sessionId || (state != NativeTrackingState.RUNNING && state != NativeTrackingState.PAUSED)) return
            try {
                block(externalCadence, monotonicMs())
            } catch (e: Exception) { // bad input from outside must never touch the recording
                Log.e(LOG_TAG, "external cadence input refused", e)
            }
        }
    }

    fun pushCsc(id: String, sourceId: String, connectionId: Long, bytes: ByteArray) =
        withExternalCadence(id) { hub, now -> hub.onCsc(sourceId, connectionId, bytes, now) }

    fun pushExternalRpm(id: String, sourceId: String, connectionId: Long, rpm: Double) =
        withExternalCadence(id) { hub, now -> hub.onRpm(sourceId, connectionId, rpm, now) }

    fun setExternalConnected(id: String, sourceId: String, connectionId: Long, connected: Boolean) =
        withExternalCadence(id) { hub, _ -> hub.onConnection(sourceId, connectionId, connected) }

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
    suspend fun restore(config: StartConfig): NativeSnapshot =
        restoreSession(config.sessionId, config.intervalMs, config.minDistanceMeters, config.recoverRunning, config.maxRecoverGapMs)

    /** The id of the session that was recording or paused when it was last seen (the open-session pointer), or null. */
    @Throws(IOException::class)
    suspend fun activeSessionId(): String? = withContext(Dispatchers.IO) { store.activeId() }

    /**
     * Process-death recovery WITHOUT Flutter: finds the open session and continues it. Returns null when there is
     * none, the live snapshot when this process already holds it (nothing is replayed twice).
     */
    @Throws(IOException::class)
    suspend fun recoverActive(maxGapMs: Long = -1L): NativeSnapshot? = commands.withLock {
        // Reading the pointer and committing to it happen under the SAME lock as Start/Pause/Finish: a session the
        // person started meanwhile is never replaced by a recovery that was decided earlier.
        val id = withContext(Dispatchers.IO) { store.activeId() } ?: return@withLock null
        if (state != NativeTrackingState.IDLE && state != NativeTrackingState.STOPPED && id != sessionId) {
            return@withLock snapshot() // this process holds another session: it wins
        }
        restoreLocked(id, DEFAULT_INTERVAL_MS, 0.0, recoverRunning = true, maxRecoverGapMs = maxGapMs)
    }

    private suspend fun restoreSession(
        id: String, defaultIntervalMs: Long, defaultMinDistanceMeters: Double, recoverRunning: Boolean, maxRecoverGapMs: Long,
    ): NativeSnapshot = commands.withLock {
        restoreLocked(id, defaultIntervalMs, defaultMinDistanceMeters, recoverRunning, maxRecoverGapMs)
    }

    /** The body of a restore. The caller holds [commands]: choosing the session and committing to it are ONE step. */
    private suspend fun restoreLocked(
        id: String, defaultIntervalMs: Long, defaultMinDistanceMeters: Double, recoverRunning: Boolean, maxRecoverGapMs: Long,
    ): NativeSnapshot {
        val running = state == NativeTrackingState.RUNNING || state == NativeTrackingState.PAUSED
        if (id == sessionId && running) return snapshot()
        haltJobs()
        val lines = withContext(Dispatchers.IO) { store.read(id) }
        // The core decides what is trustworthy: intact (CRC) AND applicable. Nothing is appended before the repair.
        val plan = SessionRecorder.plan(lines)
        if (!plan.usable) throw IOException("no usable journal for this session (${plan.status})")
        if (plan.needsRewrite) {
            // Damage in the middle (or an old-format header) changes what the file holds: keep the original beside it.
            withContext(Dispatchers.IO) { store.rewrite(id, plan.lines, plan.keepBackup) }
        }
        val usable = plan.lines
        val newSink = store.open(id)
        val result = SessionRecorder.restore(newSink, usable, !recoverRunning)
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
            adaptive = settingsAdaptive(usable)
            stepsEnabled = result.extras["stepsEnabled"] == "true"
            stepsStatus = NativeStepsStatus.OFF
            pedalCadence = result.extras["pedalCadence"] == "true"
            cadenceStatus = NativeMotionStatus.OFF
            externalCadence.reset()
            val interval = result.extras["intervalMs"]?.toLongOrNull() ?: defaultIntervalMs
            val minDistance = result.extras["minDistanceMeters"]?.toFloatOrNull() ?: defaultMinDistanceMeters.toFloat()
            request = Request(interval, minDistance)
            lastError = if (result.engineChanged) {
                "${TrackingErrorCode.ENGINE_CHANGED}: the journal was recorded with engine ${result.engineVersion}; " +
                    "the replay may differ from what was shown live"
            } else null
            lastError = lastError ?: when (plan.status) {
                JournalStatus.OK -> null
                JournalStatus.TRUNCATED_TAIL -> "${TrackingErrorCode.JOURNAL_REPAIRED}: the last record was incomplete and was dropped"
                else -> "${TrackingErrorCode.JOURNAL_CORRUPT}: damage at line ${plan.damagedLine}; only the intact part was restored"
            }
            engineChanged = result.engineChanged
            restoreState = when (plan.status) {
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
                if (recoverRunning) {
                    // The process died: credit the calendar time that passed and continue in a NEW segment.
                    // A negative limit means "the one the app chose at start", read from the journal.
                    val limit = if (maxRecoverGapMs >= 0) maxRecoverGapMs else result.extras["maxRecoverGapMs"]?.toLongOrNull() ?: 0L
                    val recovery = rec.recoverAfterProcessDeath(monotonicMs(), wallClockMs(), limit)
                    segmentId = recovery.segmentId.toLong()
                    if (rec.snapshot().state == STATE_CODE_RUNNING) state = NativeTrackingState.RUNNING
                }
            }
        }
        if (state == NativeTrackingState.RUNNING) {
            beginCollecting()
            beginSteps()
            beginCadence()
        }
        publish()
        return snapshot()
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
        // Under the command lock the whole time: a start/restore of this session cannot interleave with the delete.
        commands.withLock {
            if (id == sessionId) {
                if (state == NativeTrackingState.RUNNING || state == NativeTrackingState.PAUSED ||
                    state == NativeTrackingState.FINISHING
                ) {
                    throw IOException("session $id is still open: finish it first")
                }
            }
            // Throws (keeping the file) when the writer did not stop; the engine's references are dropped only after.
            store.clear(id)
            if (id == sessionId) {
                recorder = null
                sink = null
            }
        }
    }

    @Throws(IOException::class)
    suspend fun listSessions(): List<String> = withContext(Dispatchers.IO) { store.list() }

    /** Marks the session as failed for a reason found outside the engine. */
    fun markPermissionDenied() {
        state = NativeTrackingState.PERMISSION_DENIED
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun settingsAdaptive(lines: List<String>): Boolean =
        (JournalCodec.decode(lines.first()) as JournalRecord.Header).settings.adaptiveSampling

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
        cadenceJob?.cancelAndJoin()
        collector = null
        ticker = null
        stepJob = null
        cadenceJob = null
        // `ACTIVE` means the sensor is collecting: it is not any more. A failure keeps its status (it says why).
        if (cadenceStatus == NativeMotionStatus.ACTIVE) cadenceStatus = NativeMotionStatus.OFF
        reconfiguring = false
    }

    private fun beginCollecting() {
        val req = request ?: return
        collectorFailed = false
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
                    guardedCore { it.onTick(monotonicMs(), wallClockMs()) }
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

    /**
     * Registers the accelerometer for the open segment when the pedal cadence was asked for. A missing or failing
     * sensor never stops the recording: it only sets [cadenceStatus]. The estimator starts empty on every segment, so
     * the paused interval is never read as a rhythm. Live only: nothing here is journaled.
     */
    private fun beginCadence() {
        synchronized(coreLock) { cadence.reset() }
        if (!pedalCadence) {
            cadenceStatus = NativeMotionStatus.OFF
            return
        }
        val status = accelerometerSource.status()
        cadenceStatus = status
        if (status != NativeMotionStatus.ACTIVE) return
        val gen = generation
        cadenceJob = scope.launch {
            // One try around the whole collect: `Flow.catch` does not see an exception thrown by the collector itself.
            try {
                accelerometerSource.samples().collect { sample ->
                    synchronized(coreLock) {
                        if (gen != generation || state != NativeTrackingState.RUNNING) return@synchronized
                        cadence.push(sample.elapsedMs, sample.x, sample.y, sample.z)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(LOG_TAG, "accelerometer failed", error)
                synchronized(coreLock) {
                    if (gen != generation) return@synchronized
                    cadence.reset()
                    cadenceStatus = NativeMotionStatus.FAILED
                    lastError = "${TrackingErrorCode.CADENCE_FAILED}: ${error.message ?: error::class.java.simpleName}"
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
            collectorFailed = true
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
            // The calendar time was fixed when the fix was accepted; never recomputed from "now" (a clock change or
            // a long pause would shift it).
            lastAcceptedFixAtMs = synchronized(coreLock) { recorder?.lastAcceptedFixWallMs() } ?: -1,
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
