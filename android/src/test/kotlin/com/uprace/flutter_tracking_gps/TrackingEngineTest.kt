package com.uprace.flutter_tracking_gps

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import java.io.IOException
import java.util.Collections
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

private class FakeSteps(var status: NativeStepsStatus = NativeStepsStatus.ACTIVE) : StepSource {
    private val channel = MutableSharedFlow<StepSample>(extraBufferCapacity = 256)
    @Volatile var collectors = 0

    override fun status(): NativeStepsStatus = status

    override fun counts(): Flow<StepSample> = flow {
        collectors += 1
        try {
            emitAll(channel)
        } finally {
            collectors -= 1
        }
    }

    suspend fun push(raw: Long, atMs: Long = MONO) = channel.emit(StepSample(raw, atMs))
}

/** An in-memory journal store: the lines a real one would hold, plus counters to assert on. */
private class MemoryJournalStore : JournalStore {
    val files = HashMap<String, MutableList<String>>()
    val sinks = HashMap<String, MemorySink>()
    var flushes = 0
    var rewrites = 0
    var backups = 0
    var failOnAppend = false
    /** When false, appended lines stay "queued" until flush (a slow disk). */
    var autoWrite = true
    var onFlush: (() -> Unit)? = null

    inner class MemorySink(private val id: String) : DurableSink {
        override var failure: Throwable? = null
        private val queue = ArrayList<String>()
        private var lost = 0
        /** Queued AND lost lines: a lost line is never durable, so it stays pending (as the real writer does). */
        override val pending: Int get() = queue.size + lost
        override fun append(line: String) {
            if (failOnAppend) {
                failure = failure ?: IOException("disk full")
                lost++
                return
            }
            if (autoWrite) files.getOrPut(id) { ArrayList() }.add(line) else queue.add(line)
        }
        override fun flush(): Boolean {
            flushes++
            files.getOrPut(id) { ArrayList() }.addAll(queue)
            queue.clear()
            onFlush?.invoke()
            return failure == null && lost == 0
        }
        override fun close(): Boolean = true
    }

    override fun open(sessionId: String): DurableSink = sinks.getOrPut(sessionId) { MemorySink(sessionId) }
    override fun read(sessionId: String): List<String> = files[sessionId]?.toList() ?: emptyList()
    override fun rewrite(sessionId: String, lines: List<String>, keepBackup: Boolean) {
        rewrites++
        if (keepBackup) backups++
        files[sessionId] = lines.toMutableList()
    }
    override fun clear(sessionId: String) { files.remove(sessionId); sinks.remove(sessionId) }
    override fun release(sessionId: String) {}
    override fun list(): List<String> = files.values.mapNotNull { (it.firstOrNull()?.let(JournalCodec::decode) as? JournalRecord.Header)?.sessionId }
}

private fun engineConfig(stride: Double = -1.0, maxSpeed: Double = 10.0) = EngineConfig(
    maxAccuracyMeters = 40.0, maxFixAgeMs = 30_000, silenceAfterMs = 10_000, maxSpeedMps = maxSpeed,
    minStepMeters = 2.0, accuracyNoiseFactor = 0.5, stationarySpeedMps = 0.4, maxBridgeMs = 120_000,
    reanchorAfterRejects = 3, rejectMock = true, pdrEnabled = true, pdrConfirmRatio = 0.5,
    kalman = false, stopRadiusMeters = 15.0, stopMinDwellMs = 30_000, initialStrideMeters = stride,
    pdrTrustedWhenCalibrated = false, recoveryStepEvidenceAfterMs = 30_000,
)

private fun startConfig(id: String = "s", adaptive: Boolean = false, steps: Boolean = false, engine: EngineConfig = engineConfig()) = StartConfig(
    stepsEnabled = steps, initialSteps = 0,
    sessionId = id, startSeq = 0, startSegmentId = 0, intervalMs = 1000, minDistanceMeters = 0.0,
    notificationTitle = "t", notificationText = "x", engine = engine,
    adaptiveSampling = adaptive, batterySaver = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingEngineTest {
    private var clock = MONO

    private fun engine(source: FakeSource, store: MemoryJournalStore, steps: StepSource = NoStepSource): TrackingEngine = TrackingEngine(
        source = source, store = store, stepSource = steps,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        wallClockMs = { BASE + (clock - MONO) },
        monotonicMs = { clock },
    )

    private suspend fun FakeSource.run(seconds: Int, from: Int = 0, segmentNorth: Double = 0.0) {
        for (i in from..from + seconds) {
            clock = MONO + i * 1000L
            push(loc(i.toDouble(), segmentNorth + (i - from) * 3.0, speed = 3.0))
        }
    }

    @Test
    fun fixesBecomeDistanceInTheSnapshot() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig())
        source.run(10)
        val s = engine.snapshot()
        assertEquals(NativeTrackingState.RUNNING, s.state)
        assertTrue(abs(s.distance.totalMeters - 30.0) < 0.5, "was ${s.distance.totalMeters}")
        assertEquals(NativeGpsQuality.GOOD, s.distance.quality)
        assertEquals(11L, s.fixCount)
    }

    @Test
    fun theJournalHoldsAHeaderAndEveryFixInOrder() = runTest {
        val source = FakeSource()
        val store = MemoryJournalStore()
        val engine = engine(source, store)
        engine.start(startConfig())
        source.run(2)
        val records = store.read("s").map { JournalCodec.decode(it) }
        assertTrue(records.first() is JournalRecord.Header)
        assertEquals(listOf(0L, 1L, 2L), records.filterIsInstance<JournalRecord.Fix>().map { it.seq })
        assertEquals("t", (records.first() as JournalRecord.Header).extras["title"])
    }

    @Test
    fun lastAcceptedFixIsACalendarTimeNotAMonotonicOne() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig())
        source.run(3)
        val at = engine.snapshot().distance.lastAcceptedFixAtMs
        assertTrue(abs(at - (BASE + 3000)) < 5, "was $at")
    }

    @Test
    fun pauseKeepsTheTotalAndResumeOpensANewSegmentWithoutBridging() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig())
        source.run(1)
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
    fun aLocationThatArrivesAfterPauseIsDroppedByTheGenerationGate() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig())
        source.run(2)
        engine.pause("s")
        val count = engine.snapshot().fixCount
        source.push(loc(3.0, 9.0, speed = 3.0))
        assertEquals(count, engine.snapshot().fixCount)
    }

    @Test
    fun stopWaitsForTheJournalAndReturnsAFinalResult() = runTest {
        val source = FakeSource()
        val store = MemoryJournalStore()
        val engine = engine(source, store)
        engine.start(startConfig())
        source.run(3)
        val flushesBefore = store.flushes
        val result = engine.stop("s")
        assertEquals(NativeTrackingState.STOPPED, result.state)
        assertTrue(result.stepsFinal)
        assertTrue(store.flushes > flushesBefore, "stop must flush the writer")
        assertTrue(store.read("s").any { JournalCodec.decode(it) is JournalRecord.Finish })
    }

    @Test
    fun restoreReplaysTheJournalAndComesBackPausedWithTheSameResult() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig())
        source.run(10)
        val live = first.pause("s")

        val second = engine(FakeSource(), store) // a new process
        val restored = second.restore(startConfig())
        assertEquals(NativeTrackingState.PAUSED, restored.state)
        assertEquals(live.distance.totalMeters, restored.distance.totalMeters)
        assertEquals(live.fixCount, restored.fixCount)
        assertEquals(live.distance.activeTimeMs, restored.distance.activeTimeMs)
    }

    @Test
    fun restoreUsesTheConfigurationStoredInTheJournalNotTheOneSentNow() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig(engine = engineConfig(maxSpeed = 10.0)))
        source.run(5) // 3 m/s
        val live = first.pause("s")
        val second = engine(FakeSource(), store)
        val restored = second.restore(startConfig(engine = engineConfig(maxSpeed = 0.5))) // would reject every fix
        assertEquals(live.distance.totalMeters, restored.distance.totalMeters)
        assertTrue(restored.distance.totalMeters > 10.0)
    }

    @Test
    fun aHalfWrittenLastRecordIsDroppedTheJournalRepairedAndRecordingCanContinue() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig())
        source.run(5)
        val lines = store.files.getValue("s")
        lines[lines.lastIndex] = lines.last().dropLast(9) // the process was killed mid-write

        val second = engine(FakeSource(), store)
        val restored = second.restore(startConfig())
        assertEquals(1, store.rewrites)
        assertTrue(restored.error?.startsWith(TrackingErrorCode.JOURNAL_REPAIRED) == true, restored.error)
        assertEquals(5L, restored.fixCount) // the sixth fix was the damaged one
        // New records land after an intact line, so the journal stays readable.
        second.resume("s")
        assertTrue(store.read("s").all { JournalCodec.decode(it) != null })
    }

    @Test
    fun nothingToRestoreIsAnErrorNotAnEmptySession() = runTest {
        val engine = engine(FakeSource(), MemoryJournalStore())
        assertFailsWith<IOException> { engine.restore(startConfig()) }
    }

    @Test
    fun aSessionCannotBeClearedWhileOpenAndClearingAfterStopDeletesTheJournal() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val engine = engine(source, store)
        engine.start(startConfig())
        source.run(2)
        assertFailsWith<IOException> { engine.clearSession("s") }
        engine.stop("s")
        engine.clearSession("s")
        assertEquals(emptyList(), store.read("s"))
        assertEquals(emptyList(), engine.listSessions())
    }

    @Test
    fun sessionsOnTheDeviceCanBeListed() = runTest {
        val store = MemoryJournalStore()
        val engine = engine(FakeSource(), store)
        engine.start(startConfig("a"))
        engine.stop("a")
        engine.start(startConfig("b"))
        assertEquals(setOf("a", "b"), engine.listSessions().toSet())
    }

    @Test
    fun persistedFixesAreReadBackWithTheirOriginalTimes() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig())
        source.run(3)
        val fixes = engine.readPersisted("s", afterSeq = 0)
        assertEquals(listOf(1L, 2L, 3L), fixes.map { it.seq })
        assertEquals(BASE + 1000, fixes.first().timeMs)
        assertEquals(MONO + 1000, fixes.first().elapsedMs)
    }

    @Test
    fun aWallClockJumpDoesNotAgeOrReorderFixes() = runTest {
        val source = FakeSource()
        val engine = TrackingEngine(
            source = source, store = MemoryJournalStore(), scope = CoroutineScope(UnconfinedTestDispatcher()),
            wallClockMs = { BASE + 10_000_000_000L }, // the wall clock jumped a long way forward
            monotonicMs = { clock },
        )
        engine.start(startConfig())
        source.run(5)
        assertEquals(6L, engine.snapshot().fixCount)
        assertTrue(engine.snapshot().distance.totalMeters > 10.0)
    }

    @Test
    fun aFailedWriteIsReportedButRecordingContinues() = runTest {
        val source = FakeSource()
        val store = MemoryJournalStore()
        val engine = engine(source, store)
        engine.start(startConfig())
        store.failOnAppend = true
        source.run(4)
        val s = engine.snapshot()
        assertTrue(s.error?.startsWith(TrackingErrorCode.STORE_WRITE_FAILED) == true, s.error)
        assertEquals(NativeTrackingState.RUNNING, s.state)
        assertTrue(s.distance.totalMeters > 5.0)
    }

    @Test
    fun aMissingPermissionBecomesAStateAndAnError() = runTest {
        val source = FakeSource().also { it.failure = SecurityException("no permission") }
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig())
        val s = engine.snapshot()
        assertEquals(NativeTrackingState.PERMISSION_DENIED, s.state)
        assertTrue(s.error?.contains(TrackingErrorCode.LOCATION_UPDATES_FAILED) == true)
    }

    @Test
    fun adaptiveSamplingReRequestsLocationsWhenTheUserStandsStill() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig(adaptive = true))
        for (i in 0..40) {
            clock = MONO + i * 1000L
            source.push(loc(i.toDouble(), 0.0, speed = 0.0))
        }
        assertTrue(source.requests.size >= 2, "requests ${source.requests}")
        assertTrue(source.requests.last().first > source.requests.first().first)
    }

    @Test
    fun aStepEstimateIsIgnoredWhileGpsIsStillGood() = runTest {
        val source = FakeSource()
        val engine = engine(source, MemoryJournalStore())
        engine.start(startConfig())
        source.run(3)
        engine.submitPdr("s", 0, 50.0)
        assertEquals(0.0, engine.snapshot().distance.pendingPdrMeters)
    }

    // ── Steps ───────────────────────────────────────────────────────────────

    @Test
    fun stepsWhileGpsIsDegradedBecomeAPendingEstimateNotDistance() = runTest {
        val source = FakeSource()
        val steps = FakeSteps()
        val engine = engine(source, MemoryJournalStore(), steps)
        engine.start(startConfig(steps = true, engine = engineConfig(stride = 1.0)))
        assertEquals(NativeStepsStatus.ACTIVE, engine.snapshot().stepsStatus)
        source.run(10)
        clock = MONO + 12_000
        source.push(loc(12.0, 33.0, speed = 3.0, accuracy = 90.0)) // degraded: GPS stopped measuring at t=10
        clock = MONO + 14_000
        steps.push(10_000, MONO + 11_000) // baseline
        steps.push(10_006, MONO + 14_000) // 6 steps measured in the weak interval
        val s = engine.snapshot()
        assertEquals(6L, s.distance.steps)
        assertTrue(abs(s.distance.pendingPdrMeters - 6.0) < 1e-6, "pending ${s.distance.pendingPdrMeters}")
    }

    @Test
    fun pauseUnregistersTheStepSensorAndResumeRegistersItAgain() = runTest {
        val steps = FakeSteps()
        val engine = engine(FakeSource(), MemoryJournalStore(), steps)
        engine.start(startConfig(steps = true))
        assertEquals(1, steps.collectors)
        engine.pause("s")
        assertEquals(0, steps.collectors)
        engine.resume("s")
        assertEquals(1, steps.collectors)
        engine.stop("s")
        assertEquals(0, steps.collectors)
    }

    @Test
    fun aMissingPermissionOnlyDisablesStepsTheRecordingGoesOn() = runTest {
        val source = FakeSource()
        val steps = FakeSteps(NativeStepsStatus.PERMISSION_DENIED)
        val engine = engine(source, MemoryJournalStore(), steps)
        engine.start(startConfig(steps = true))
        source.run(2)
        val s = engine.snapshot()
        assertEquals(NativeStepsStatus.PERMISSION_DENIED, s.stepsStatus)
        assertEquals(NativeTrackingState.RUNNING, s.state)
        assertEquals(0, steps.collectors)
    }

    @Test
    fun stepsOffByConfigNeverTouchTheSensor() = runTest {
        val steps = FakeSteps()
        val engine = engine(FakeSource(), MemoryJournalStore(), steps)
        engine.start(startConfig())
        assertEquals(NativeStepsStatus.OFF, engine.snapshot().stepsStatus)
        assertEquals(0, steps.collectors)
    }

    @Test
    fun walkedWhilePausedIsNotCountedAfterResume() = runTest {
        val steps = FakeSteps()
        val engine = engine(FakeSource(), MemoryJournalStore(), steps)
        engine.start(startConfig(steps = true))
        steps.push(1000); steps.push(1040)
        engine.pause("s")
        clock = MONO + 60_000
        engine.resume("s")
        steps.push(1500, clock); steps.push(1510, clock) // 460 steps were walked while paused: a new baseline
        assertEquals(50L, engine.snapshot().distance.steps)
    }

    @Test
    fun restoredStepsAndStrideComeBackFromTheJournal() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val steps = FakeSteps()
        val first = engine(source, store, steps)
        first.start(startConfig(steps = true))
        for (i in 0..30) {
            clock = MONO + i * 1000L
            source.push(loc(i.toDouble(), i * 3.0, speed = 3.0))
            steps.push(1000L + i * 2, clock)
        }
        val live = first.pause("s")
        assertTrue(live.distance.steps > 40 && live.distance.strideMeters > 0, "steps ${live.distance.steps}, stride ${live.distance.strideMeters}")
        val restored = engine(FakeSource(), store).restore(startConfig(steps = true))
        assertEquals(live.distance.steps, restored.distance.steps)
        assertEquals(live.distance.strideMeters, restored.distance.strideMeters)
    }

    @Test
    fun stopIsFinishingUntilTheJournalIsDurableAndOnlyThenStopped() = runTest {
        val source = FakeSource()
        val store = MemoryJournalStore()
        val engine = engine(source, store)
        engine.start(startConfig())
        source.run(3)
        var stateAtFlush: NativeTrackingState? = null
        var stepsFinalAtFlush: Boolean? = null
        store.onFlush = { stateAtFlush = engine.snapshot().state; stepsFinalAtFlush = engine.snapshot().stepsFinal }
        val result = engine.stop("s")
        assertEquals(NativeTrackingState.FINISHING, stateAtFlush, "while the journal is being flushed the result is NOT final")
        assertEquals(false, stepsFinalAtFlush)
        assertEquals(NativeTrackingState.STOPPED, result.state)
        assertTrue(result.stepsFinal)
    }

    @Test
    fun theSnapshotSaysHowManyEventsAreNotOnDiskYet() = runTest {
        val source = FakeSource()
        val store = MemoryJournalStore().also { it.autoWrite = false }
        val engine = engine(source, store)
        engine.start(startConfig())
        source.run(2)
        assertTrue(engine.snapshot().journalPending >= 3, "pending ${engine.snapshot().journalPending}")
        val result = engine.stop("s") // flushes
        assertEquals(0L, result.journalPending)
    }

    @Test
    fun aDamagedMiddleLineKeepsABackupOfTheOriginalBeforeRepairing() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig())
        source.run(6)
        val lines = store.files.getValue("s")
        lines[3] = lines[3].replaceRange(10, 11, "#") // damage in the MIDDLE
        val restored = engine(FakeSource(), store).restore(startConfig())
        assertEquals(1, store.backups, "the only copy must survive the repair")
        assertTrue(restored.error?.startsWith(TrackingErrorCode.JOURNAL_CORRUPT) == true, restored.error)
    }

    @Test
    fun aTruncatedTailNeedsNoBackup() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig())
        source.run(4)
        val lines = store.files.getValue("s")
        lines[lines.lastIndex] = lines.last().dropLast(5)
        engine(FakeSource(), store).restore(startConfig())
        assertEquals(0, store.backups)
    }

    @Test
    fun aJournalOfAnotherEngineVersionIsRestoredButTheSnapshotSaysSo() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig())
        source.run(4)
        val lines = store.files.getValue("s")
        val header = JournalCodec.decode(lines[0]) as JournalRecord.Header
        lines[0] = JournalCodec.encode(header.copy(engineVersion = JournalCodec.ENGINE_VERSION + 1))
        val restored = engine(FakeSource(), store).restore(startConfig())
        assertEquals(NativeTrackingState.PAUSED, restored.state)
        assertTrue(restored.error?.startsWith(TrackingErrorCode.ENGINE_CHANGED) == true, restored.error)
    }

    @Test
    fun aFinishedSessionRestoresAsStoppedNotPausedWithItsFinality() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig())
        source.run(5)
        val live = first.stop("s")
        val restored = engine(FakeSource(), store).restore(startConfig())
        assertEquals(NativeTrackingState.STOPPED, restored.state, "a finished session is read-only, never paused")
        assertTrue(restored.stepsFinal)
        assertEquals(live.distance.totalMeters, restored.distance.totalMeters)
        assertEquals(live.distance.activeTimeMs, restored.distance.activeTimeMs)
        // And resume is refused: nothing can reopen a finished session.
        val again = engine(FakeSource(), store)
        again.restore(startConfig())
        assertEquals(NativeTrackingState.STOPPED, again.resume("s").state)
    }

    @Test
    fun theRestoreOutcomeIsAStructuredFieldNotJustAString() = runTest {
        val store = MemoryJournalStore()
        val source = FakeSource()
        val first = engine(source, store)
        first.start(startConfig())
        source.run(6)
        assertEquals(NativeRestoreState.NOT_RESTORED, first.snapshot().restoreState)
        assertEquals(NativeRestoreState.COMPLETE, engine(FakeSource(), store).restore(startConfig()).restoreState)

        val lines = store.files.getValue("s")
        lines[lines.lastIndex] = lines.last().dropLast(5)
        val tail = engine(FakeSource(), store).restore(startConfig())
        assertEquals(NativeRestoreState.TAIL_REPAIRED, tail.restoreState)

        store.files.getValue("s")[2] = store.files.getValue("s")[2].replaceRange(10, 11, "#")
        val partial = engine(FakeSource(), store).restore(startConfig())
        assertEquals(NativeRestoreState.PARTIAL, partial.restoreState)
        assertTrue(partial.fixCount < 6, "everything after the damage was NOT applied")

        val header = JournalCodec.decode(store.files.getValue("s")[0]) as JournalRecord.Header
        store.files.getValue("s")[0] = JournalCodec.encode(header.copy(engineVersion = JournalCodec.ENGINE_VERSION + 1))
        assertTrue(engine(FakeSource(), store).restore(startConfig()).engineChanged)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuditNativeProbeTest {
    @Test fun wallClockChangeMustNotMoveTheTimestampOfAnExistingFix() = runTest {
        val source = FakeSource()
        var wall = BASE
        val engine = TrackingEngine(source, MemoryJournalStore(),
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            wallClockMs = { wall }, monotonicMs = { MONO })
        engine.start(startConfig())
        source.push(loc(0.0,0.0))
        assertEquals(BASE,engine.snapshot().distance.lastAcceptedFixAtMs)
        wall += 3600000L
        assertEquals(BASE,engine.snapshot().distance.lastAcceptedFixAtMs,"same measurement must retain its epoch")
    }
    @Test fun failedJournalMustNotBeReportedAsStoppedWithNoPendingData() = runTest {
        val source=FakeSource();val store=MemoryJournalStore();store.failOnAppend=true
        val engine=TrackingEngine(source,store,
            CoroutineScope(backgroundScope.coroutineContext+UnconfinedTestDispatcher(testScheduler)),
            wallClockMs={BASE},monotonicMs={MONO})
        engine.start(startConfig())
        val s=engine.stop("s")
        assertNotNull(s.error)
        assertTrue(s.state!=NativeTrackingState.STOPPED || s.journalPending>0,"lost journal must not advertise a durable final snapshot")
    }
    @Test fun failedRealFileWriterCannotClaimZeroUndurableLines() {
        val parent=java.io.File.createTempFile("gps-audit","not-dir")
        val sink=FileJournalSink(java.io.File(parent,"s.jnl"))
        try {
            sink.append("record");sink.flush()
            assertNotNull(sink.failure)
            assertTrue(sink.pending>0,"a failed write is still not durable")
        } finally {sink.close();parent.delete()}
    }
}
