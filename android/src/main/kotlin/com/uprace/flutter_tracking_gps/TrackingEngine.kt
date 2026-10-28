package com.uprace.flutter_tracking_gps

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
import tracker.bridge.TrackingCoreBridge
import java.io.IOException

/** Builds the shared Kotlin core from the channel's [EngineConfig]. */
internal fun createBridge(engine: EngineConfig, adaptiveSampling: Boolean, batterySaver: Boolean): TrackingCoreBridge =
    TrackingCoreBridge(
        maxAccuracyMeters = engine.maxAccuracyMeters,
        maxFixAgeMs = engine.maxFixAgeMs,
        silenceAfterMs = engine.silenceAfterMs,
        maxSpeedMps = engine.maxSpeedMps,
        minStepMeters = engine.minStepMeters,
        accuracyNoiseFactor = engine.accuracyNoiseFactor,
        stationarySpeedMps = engine.stationarySpeedMps,
        maxBridgeMs = engine.maxBridgeMs,
        reanchorAfterRejects = engine.reanchorAfterRejects.toInt(),
        rejectMock = engine.rejectMock,
        pdrEnabled = engine.pdrEnabled,
        pdrConfirmRatio = engine.pdrConfirmRatio,
        kalman = engine.kalman,
        stopRadiusMeters = engine.stopRadiusMeters,
        stopMinDwellMs = engine.stopMinDwellMs,
        adaptiveSampling = adaptiveSampling,
        batterySaver = batterySaver,
    )

/**
 * Collects raw fixes for one session at a time, persists them, and feeds the
 * shared Kotlin distance engine (`tracker.engine.GpsDistanceEngine` through
 * [TrackingCoreBridge]). It outlives the Flutter engine on purpose: if the UI
 * engine dies, native keeps recording AND keeps computing the distance.
 *
 *  - Commands ([start], [pause], [resume], [stop], [restore]) are serialized by a mutex.
 *  - Fixes are numbered in arrival order by the single collector coroutine.
 *  - The core runs on a MONOTONIC clock (`elapsedRealtime`, fix time from
 *    `elapsedRealtimeNanos`): a wall-clock jump can neither reorder nor age a fix.
 *    The wall clock is only used for display (`updatedAtMs`).
 *  - Calls into the core are guarded by [coreLock] (the core is not thread-safe):
 *    the collector and the 1 Hz ticker both use it.
 *  - Writing to disk happens on `Dispatchers.IO`, one fix at a time in order.
 *  - A failure is never swallowed: it is logged and surfaced in the snapshot.
 */
internal class TrackingEngine(
    private val source: LocationSource,
    private val store: FixStore,
    private val scope: CoroutineScope,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val coreFactory: (EngineConfig, Boolean, Boolean) -> TrackingCoreBridge = ::createBridge,
) {
    private val commands = Mutex()
    private val coreLock = Any()
    private val writes = Channel<NativeFix>(Channel.UNLIMITED)
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

    @Volatile private var sessionId: String? = null
    @Volatile private var state = NativeTrackingState.IDLE
    @Volatile private var segmentId = 0L
    @Volatile private var fixCount = 0L
    @Volatile private var lastFixTimeMs: Long? = null
    @Volatile private var lastError: String? = null
    @Volatile private var core: TrackingCoreBridge? = null
    @Volatile private var request: Request? = null
    private var seq = 0L
    private var collector: Job? = null
    private var ticker: Job? = null
    private var adaptive = false
    private var reconfiguring = false

    private data class Request(val intervalMs: Long, val minDistanceMeters: Float)

    init {
        scope.launch(Dispatchers.IO) { drainWrites() }
    }

    fun snapshot(): NativeSnapshot = NativeSnapshot(
        state = state,
        segmentId = segmentId,
        fixCount = fixCount,
        updatedAtMs = wallClockMs(),
        distance = distance(),
        sessionId = sessionId,
        lastFixTimeMs = lastFixTimeMs,
        error = lastError,
    )

    suspend fun start(config: StartConfig): NativeSnapshot = commands.withLock {
        stopJobs()
        sessionId = config.sessionId
        seq = config.startSeq
        segmentId = config.startSegmentId
        fixCount = 0
        lastFixTimeMs = null
        lastError = null
        adaptive = config.adaptiveSampling
        request = Request(config.intervalMs, config.minDistanceMeters.toFloat())
        val bridge = coreFactory(config.engine, config.adaptiveSampling, config.batterySaver)
        synchronized(coreLock) {
            bridge.start(config.sessionId, monotonicMs(), config.startSegmentId.toInt())
            core = bridge
        }
        beginCollecting(config.sessionId)
        publish()
        snapshot()
    }

    suspend fun pause(id: String): NativeSnapshot = commands.withLock {
        if (id == sessionId && state == NativeTrackingState.RUNNING) {
            stopJobs()
            state = NativeTrackingState.PAUSED
            coreStrict { it.pause(monotonicMs()) }
            publish()
        }
        snapshot()
    }

    suspend fun resume(id: String): NativeSnapshot = commands.withLock {
        if (id == sessionId && state == NativeTrackingState.PAUSED) {
            segmentId += 1
            coreStrict { it.resume(monotonicMs(), segmentId.toInt()) }
            beginCollecting(id)
            publish()
        }
        snapshot()
    }

    suspend fun stop(id: String): NativeSnapshot = commands.withLock {
        if (id == sessionId && state != NativeTrackingState.IDLE && state != NativeTrackingState.STOPPED) {
            stopJobs()
            state = NativeTrackingState.STOPPED
            coreStrict { it.finish(monotonicMs()) }
            publish()
        }
        snapshot()
    }

    /**
     * A step estimate for [segment]: held as pending until the GPS that returns
     * confirms it. One that belongs to another segment (a late estimate after a
     * resume) is refused by the core.
     */
    fun submitPdr(id: String, segment: Int, meters: Double) {
        if (id != sessionId) return
        withCore { it.submitPdr(segment, monotonicMs(), meters) }
        publish()
    }

    /** Coordinate-free timeline of the core's decisions. */
    fun report(): String = withCore { it.report() } ?: ""

    /**
     * After a process death: rebuilds the core by replaying the persisted fixes
     * (segments are never joined) and leaves the session PAUSED. If this session
     * already runs, nothing is replayed.
     */
    @Throws(IOException::class)
    suspend fun restore(config: StartConfig): NativeSnapshot = commands.withLock {
        val running = state == NativeTrackingState.RUNNING || state == NativeTrackingState.PAUSED
        if (config.sessionId == sessionId && running) return@withLock snapshot()

        stopJobs()
        val fixesOnDisk = withContext(Dispatchers.IO) { store.read(config.sessionId, -1) }
        val bridge = coreFactory(config.engine, config.adaptiveSampling, config.batterySaver)
        var segment = fixesOnDisk.firstOrNull()?.segmentId ?: config.startSegmentId
        var lastSeq = -1L
        synchronized(coreLock) {
            bridge.start(config.sessionId, fixesOnDisk.firstOrNull()?.elapsedMs ?: monotonicMs(), segment.toInt())
            for (fix in fixesOnDisk) {
                if (fix.segmentId != segment) {
                    bridge.pause(fix.elapsedMs)
                    bridge.resume(fix.elapsedMs, fix.segmentId.toInt())
                    segment = fix.segmentId
                }
                bridge.onFix(
                    fix.sessionId, fix.segmentId.toInt(), fix.seq, fix.elapsedMs, fix.latitude, fix.longitude,
                    fix.horizontalAccuracy, fix.speed, fix.speedAccuracy, fix.isMock, fix.elapsedMs, true,
                )
                if (fix.seq > lastSeq) lastSeq = fix.seq
            }
            bridge.pause(monotonicMs())
            core = bridge
        }
        sessionId = config.sessionId
        adaptive = config.adaptiveSampling
        request = Request(config.intervalMs, config.minDistanceMeters.toFloat())
        segmentId = segment
        seq = lastSeq + 1
        fixCount = fixesOnDisk.size.toLong()
        lastFixTimeMs = fixesOnDisk.lastOrNull()?.timeMs
        lastError = null
        state = NativeTrackingState.PAUSED
        publish()
        snapshot()
    }

    /** Reads the persisted fixes; blocking I/O runs on `Dispatchers.IO`. */
    @Throws(IOException::class)
    suspend fun readPersisted(id: String, afterSeq: Long): List<NativeFix> =
        withContext(Dispatchers.IO) { store.read(id, afterSeq) }

    @Throws(IOException::class)
    suspend fun clearSession(id: String) = withContext(Dispatchers.IO) { store.clearSession(id) }

    /** Marks the session as failed for a reason found outside the engine. */
    fun markPermissionDenied() {
        state = NativeTrackingState.PERMISSION_DENIED
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun stopJobs() {
        collector?.cancel()
        collector = null
        ticker?.cancel()
        ticker = null
    }

    private fun beginCollecting(id: String) {
        val req = request ?: return
        state = NativeTrackingState.RUNNING
        reconfiguring = false
        collector = scope.launch {
            source.locations(req.intervalMs, req.minDistanceMeters)
                .catch { error -> onSourceFailure(error) }
                .collect { location -> onLocation(id, location) }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                withCore { it.onTick(monotonicMs()) }
                publish()
            }
        }
    }

    private fun onLocation(id: String, location: RawLocation) {
        val fix = toFix(id, location)
        seq += 1
        fixCount += 1
        lastFixTimeMs = fix.timeMs
        val plan = withCore { bridge ->
            val now = monotonicMs()
            bridge.onFix(
                fix.sessionId, fix.segmentId.toInt(), fix.seq, fix.elapsedMs, fix.latitude, fix.longitude,
                fix.horizontalAccuracy, fix.speed, fix.speedAccuracy, fix.isMock, now, false,
            )
            if (adaptive) bridge.samplingPlan(now) else null
        }
        writes.trySend(fix)
        _fixes.tryEmit(fix)
        publish()
        if (plan != null) applyPlan(id, plan)
    }

    /** Changes the location request when the adaptive plan changed. */
    private fun applyPlan(id: String, plan: BridgePlan) {
        val wanted = Request(plan.intervalMs, plan.minDistanceMeters.toFloat())
        if (wanted == request || reconfiguring) return
        reconfiguring = true
        scope.launch {
            commands.withLock {
                if (state == NativeTrackingState.RUNNING && sessionId == id) {
                    collector?.cancel()
                    ticker?.cancel()
                    request = wanted
                    beginCollecting(id)
                } else {
                    reconfiguring = false
                }
            }
        }
    }

    private fun onSourceFailure(error: Throwable) {
        if (error is CancellationException) throw error
        Log.e(LOG_TAG, "location updates failed", error)
        lastError = "${TrackingErrorCode.LOCATION_UPDATES_FAILED}: ${error.message ?: error::class.java.simpleName}"
        state = if (error is SecurityException) {
            NativeTrackingState.PERMISSION_DENIED
        } else {
            NativeTrackingState.ERROR
        }
        publish()
    }

    private suspend fun drainWrites() {
        for (fix in writes) {
            try {
                store.append(fix)
            } catch (e: IOException) {
                // Recording goes on; the loss is visible in the snapshot.
                Log.e(LOG_TAG, "persisting a fix failed", e)
                lastError = "${TrackingErrorCode.STORE_WRITE_FAILED}: ${e.message ?: "I/O error"}"
            }
        }
    }

    private fun publish() {
        _snapshots.tryEmit(snapshot())
    }

    /**
     * Runs [block] on the core under the lock. For data coming from the OS (a
     * fix, a tick, an estimate): a failure of the core must never crash the
     * collector, so it is logged and shown in `lastError`, and null is returned.
     */
    private fun <T> withCore(block: (TrackingCoreBridge) -> T): T? {
        val bridge = core ?: return null
        return try {
            synchronized(coreLock) { block(bridge) }
        } catch (e: IllegalArgumentException) {
            reportCoreFailure(e)
        } catch (e: IllegalStateException) {
            reportCoreFailure(e)
        }
    }

    /** For commands: a failure propagates to the caller (and becomes a FlutterError). */
    private fun <T> coreStrict(block: (TrackingCoreBridge) -> T): T? {
        val bridge = core ?: return null
        return synchronized(coreLock) { block(bridge) }
    }

    private fun <T> reportCoreFailure(e: Exception): T? {
        Log.e(LOG_TAG, "the distance core refused an input", e)
        lastError = "${TrackingErrorCode.CORE_REJECTED}: ${e.message ?: e::class.java.simpleName}"
        return null
    }

    private fun distance(): NativeDistance {
        val s: BridgeSnapshot = withCore { it.snapshot() } ?: return emptyDistance()
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
            lastAcceptedFixAtMs = s.lastFixAtMs,
        )
    }

    private fun qualityOf(ordinal: Int): NativeGpsQuality = when (ordinal) {
        1 -> NativeGpsQuality.GOOD
        2 -> NativeGpsQuality.DEGRADED
        3 -> NativeGpsQuality.UNAVAILABLE
        4 -> NativeGpsQuality.RECOVERING
        else -> NativeGpsQuality.UNKNOWN
    }

    private fun toFix(id: String, l: RawLocation): NativeFix = NativeFix(
        sessionId = id,
        segmentId = segmentId,
        seq = seq,
        timeMs = l.timeMs,
        elapsedMs = l.elapsedMs,
        latitude = l.latitude,
        longitude = l.longitude,
        horizontalAccuracy = l.horizontalAccuracy,
        speed = l.speed,
        speedAccuracy = l.speedAccuracy,
        course = l.course,
        altitude = l.altitude,
        verticalAccuracy = l.verticalAccuracy,
        provider = l.provider,
        isMock = l.isMock,
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
)
