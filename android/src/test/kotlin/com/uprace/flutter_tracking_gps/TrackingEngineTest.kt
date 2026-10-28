package com.uprace.flutter_tracking_gps

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.Collections
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val BASE = 1_700_000_000_000L

/** The monotonic clock (elapsedRealtime) the engine runs the core on. */
private const val MONO = 5_000_000L
private const val METERS_PER_DEGREE = 111_195.0

/** A location [north] metres north of the origin, [seconds] after BASE. */
private fun loc(seconds: Double, north: Double, speed: Double = -1.0, accuracy: Double = 5.0) = RawLocation(
    timeMs = BASE + (seconds * 1000).toLong(), elapsedMs = MONO + (seconds * 1000).toLong(),
    latitude = 10.0 + north / METERS_PER_DEGREE, longitude = 106.0,
    horizontalAccuracy = accuracy, speed = speed, speedAccuracy = -1.0, course = -1.0,
    altitude = 0.0, verticalAccuracy = -1.0, provider = "gps", isMock = false,
)

private class FakeSource : LocationSource {
    val requests = Collections.synchronizedList(mutableListOf<Pair<Long, Float>>())
    private val channel = MutableSharedFlow<RawLocation>(extraBufferCapacity = 256)
    var failure: Throwable? = null

    override fun locations(intervalMs: Long, minDistanceMeters: Float): Flow<RawLocation> = flow {
        requests.add(intervalMs to minDistanceMeters)
        failure?.let { throw it }
        emitAll(channel)
    }

    suspend fun push(l: RawLocation) = channel.emit(l)
}

private class MemoryStore : FixStore {
    val fixes = Collections.synchronizedList(mutableListOf<NativeFix>())
    var failOnAppend = false

    override fun append(fix: NativeFix) {
        if (failOnAppend) throw IOException("disk full")
        fixes.add(fix)
    }

    override fun read(sessionId: String, afterSeq: Long): List<NativeFix> =
        synchronized(fixes) { fixes.filter { it.sessionId == sessionId && it.seq > afterSeq } }

    override fun clearSession(sessionId: String) {
        fixes.removeAll { it.sessionId == sessionId }
    }
}

private fun engineConfig() = EngineConfig(
    maxAccuracyMeters = 40.0, maxFixAgeMs = 30_000, silenceAfterMs = 10_000, maxSpeedMps = 10.0,
    minStepMeters = 2.0, accuracyNoiseFactor = 0.5, stationarySpeedMps = 0.4, maxBridgeMs = 120_000,
    reanchorAfterRejects = 3, rejectMock = true, pdrEnabled = true, pdrConfirmRatio = 0.5,
    kalman = false, stopRadiusMeters = 15.0, stopMinDwellMs = 30_000,
)

private fun startConfig(id: String = "s", adaptive: Boolean = false) = StartConfig(
    sessionId = id, startSeq = 0, startSegmentId = 0, intervalMs = 1000, minDistanceMeters = 0.0,
    notificationTitle = "t", notificationText = "t", engine = engineConfig(),
    adaptiveSampling = adaptive, batterySaver = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingEngineTest {
    private var clock = MONO

    private fun engine(source: FakeSource, store: MemoryStore): TrackingEngine = TrackingEngine(
        source = source, store = store,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        wallClockMs = { BASE + (clock - MONO) },
        monotonicMs = { clock },
    )

    @Test
    fun fixesBecomeDistanceInTheSnapshot() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryStore())
        engine.start(startConfig())
        for (i in 0..10) {
            clock = MONO + i * 1000L
            source.push(loc(i.toDouble(), i * 3.0, speed = 3.0))
        }
        val s = engine.snapshot()
        assertEquals(NativeTrackingState.RUNNING, s.state)
        assertTrue(abs(s.distance.totalMeters - 30.0) < 0.5, "was ${s.distance.totalMeters}")
        assertEquals(NativeGpsQuality.GOOD, s.distance.quality)
        assertEquals(11L, s.fixCount)
    }

    @Test
    fun fixesArePersistedInOrderWithSequenceNumbers() = runTest {
        val source = FakeSource()
        val store = MemoryStore()
        val engine = engine(source, store)
        engine.start(startConfig())
        for (i in 0..2) {
            clock = MONO + i * 1000L
            source.push(loc(i.toDouble(), i * 3.0, speed = 3.0))
        }
        // The writer runs on Dispatchers.IO: wait for it.
        val deadline = System.currentTimeMillis() + 2000
        while (store.fixes.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf(0L, 1L, 2L), store.fixes.map { it.seq })
    }

    @Test
    fun pauseKeepsTheTotalAndResumeOpensANewSegmentWithoutBridging() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryStore())
        engine.start(startConfig())
        source.push(loc(0.0, 0.0))
        clock = MONO + 1000
        source.push(loc(1.0, 5.0, speed = 4.0))
        val before = engine.snapshot().distance.totalMeters

        val paused = engine.pause("s")
        assertEquals(NativeTrackingState.PAUSED, paused.state)
        assertEquals(before, paused.distance.totalMeters)

        clock = MONO + 600_000
        val resumed = engine.resume("s")
        assertEquals(NativeTrackingState.RUNNING, resumed.state)
        assertEquals(1L, resumed.segmentId)
        source.push(loc(601.0, 5000.0)) // far away: the first fix of a segment only anchors
        assertEquals(before, engine.snapshot().distance.totalMeters)
    }

    @Test
    fun stopFreezesTheResult() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryStore())
        engine.start(startConfig())
        source.push(loc(0.0, 0.0))
        clock = MONO + 1000
        source.push(loc(1.0, 5.0, speed = 4.0))
        val stopped = engine.stop("s")
        assertEquals(NativeTrackingState.STOPPED, stopped.state)
        val total = stopped.distance.totalMeters
        clock = MONO + 2000
        source.push(loc(2.0, 50.0, speed = 4.0))
        assertEquals(total, engine.snapshot().distance.totalMeters)
    }

    @Test
    fun aStepEstimateIsIgnoredWhileGpsIsStillGood() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryStore())
        engine.start(startConfig())
        source.push(loc(0.0, 0.0))
        clock = MONO + 15_000
        engine.submitPdr("s", 0, 130.0)
        val s = engine.snapshot().distance
        // GPS is still GOOD (no tick yet): the estimate is ignored, not counted
        assertEquals(0.0, s.totalMeters)
        assertEquals(0.0, s.pendingPdrMeters)
    }

    @Test
    fun restoreRebuildsTheDistanceFromPersistedFixesAndComesBackPaused() = runTest {
        val store = MemoryStore()
        fun fix(seq: Long, segment: Long, seconds: Double, north: Double) = NativeFix(
            sessionId = "s", segmentId = segment, seq = seq, timeMs = BASE + (seconds * 1000).toLong(),
            elapsedMs = MONO + (seconds * 1000).toLong(), latitude = 10.0 + north / METERS_PER_DEGREE, longitude = 106.0,
            horizontalAccuracy = 5.0, speed = 4.0, speedAccuracy = -1.0, course = -1.0, altitude = 0.0,
            verticalAccuracy = -1.0, provider = "gps", isMock = false,
        )
        store.fixes.addAll(
            listOf(fix(0, 0, 0.0, 0.0), fix(1, 0, 1.0, 5.0), fix(2, 0, 2.0, 10.0), fix(3, 1, 500.0, 9000.0)),
        )
        val engine = engine(FakeSource(), store)
        val s = engine.restore(startConfig())
        assertEquals(NativeTrackingState.PAUSED, s.state)
        assertTrue(abs(s.distance.totalMeters - 10.0) < 0.3, "segments must not be joined: ${s.distance.totalMeters}")
        assertEquals(1L, s.segmentId)
        assertEquals(4L, s.fixCount)

        // a restore of a session that already runs replays nothing
        val again = engine.restore(startConfig())
        assertEquals(s.distance.totalMeters, again.distance.totalMeters)
    }

    @Test
    fun aMissingPermissionBecomesAStateAndAnError() = runTest {
        val source = FakeSource().also { it.failure = SecurityException("no permission") }
        val engine = engine(source, MemoryStore())
        engine.start(startConfig())
        val s = engine.snapshot()
        assertEquals(NativeTrackingState.PERMISSION_DENIED, s.state)
        assertNotNull(s.error)
        assertTrue(s.error!!.startsWith(TrackingErrorCode.LOCATION_UPDATES_FAILED))
    }

    @Test
    fun aFailedWriteIsReportedButRecordingContinues() = runTest {
        val source = FakeSource()
        val store = MemoryStore().also { it.failOnAppend = true }
        val engine = engine(source, store)
        engine.start(startConfig())
        source.push(loc(0.0, 0.0))
        val deadline = System.currentTimeMillis() + 2000
        while (engine.snapshot().error == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val s = engine.snapshot()
        assertEquals(NativeTrackingState.RUNNING, s.state)
        assertTrue(s.error!!.startsWith(TrackingErrorCode.STORE_WRITE_FAILED))
        assertEquals(1L, s.fixCount)
    }

    @Test
    fun adaptiveSamplingReRequestsLocationsWhenTheUserStandsStill() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryStore())
        engine.start(startConfig(adaptive = true))
        assertEquals(1, source.requests.size)
        for (i in 0..60) {
            clock = MONO + i * 1000L
            source.push(loc(i.toDouble(), 0.0, speed = 0.0))
        }
        // After a long standstill the request became sparser (3 s) at least once.
        val deadline = System.currentTimeMillis() + 2000
        while (source.requests.none { it.first >= 3000 } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(source.requests.any { it.first >= 3000 }, "requests were ${source.requests}")
    }

    @Test
    fun aWallClockJumpDoesNotAgeOrReorderFixes() = runTest {
        val source = FakeSource()
        // The wall clock is hours off and jumps backwards; the monotonic clock is steady.
        var wall = BASE + 10 * 3_600_000L
        val engine = TrackingEngine(
            source = source, store = MemoryStore(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            wallClockMs = { wall }, monotonicMs = { clock },
        )
        engine.start(startConfig())
        for (i in 0..10) {
            clock = MONO + i * 1000L
            wall -= 7_200_000L // jumps back two hours on every fix
            source.push(loc(i.toDouble(), i * 3.0, speed = 3.0))
        }
        val s = engine.snapshot()
        assertTrue(abs(s.distance.totalMeters - 30.0) < 0.5, "was ${s.distance.totalMeters}")
        assertEquals(11L, s.fixCount)
    }
}
