package tracker

import tracker.bridge.BridgeSnapshot
import tracker.journal.BridgeSettings
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import tracker.journal.JournalSink
import tracker.journal.JournalStatus
import tracker.journal.SessionRecorder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemSink : JournalSink {
    val lines = ArrayList<String>()
    var flushes = 0
    override fun append(line: String) { lines.add(line) }
    override fun flush() { flushes++ }
}

private fun settings(adaptive: Boolean = false) = BridgeSettings(
    maxAccuracyMeters = 40.0, maxFixAgeMs = 30_000, silenceAfterMs = 10_000, maxSpeedMps = 10.0, minStepMeters = 2.0,
    accuracyNoiseFactor = 0.5, stationarySpeedMps = 0.4, maxBridgeMs = 120_000, reanchorAfterRejects = 3,
    rejectMock = true, pdrEnabled = true, pdrConfirmRatio = 0.5, kalman = false, stopRadiusMeters = 15.0,
    stopMinDwellMs = 30_000, initialStrideMeters = -1.0, pdrTrustedWhenCalibrated = false,
    recoveryStepEvidenceAfterMs = 30_000, adaptiveSampling = adaptive, batterySaver = false,
)

private fun SessionRecorder.fix(seq: Long, seconds: Double, north: Double, segment: Int = 0, accuracy: Double = 5.0, speed: Double = 3.0, nowSeconds: Double = seconds): Int =
    onFix("s", segment, seq, ms(seconds), 1_700_000_000_000L + (seconds * 1000).toLong(), 10.0 + north / 111_195.0, 106.0,
        accuracy, speed, -1.0, -1.0, 0.0, -1.0, "gps", false, ms(nowSeconds))

/** A scenario that touches every input kind: fixes, steps, a GPS gap through a tick, pause, resume. */
private fun scenario(r: SessionRecorder): Long {
    r.setStepSensorActive(true)
    var seq = 0L
    var raw = 50_000L
    r.onStepCounter(0, ms(0.0), ms(0.0), raw)
    for (t in 0..30) {
        r.fix(seq++, t.toDouble(), t * 3.0)
        if (t > 0) { raw += 2; r.onStepCounter(0, ms(t.toDouble()), ms(t + 0.5), raw) }
        r.onTick(ms(t.toDouble()))
    }
    // GPS lost: a tick flips the state, steps are measured in the gap.
    r.onTick(ms(45.0))
    raw += 40; r.onStepCounter(0, ms(50.0), ms(51.0), raw)
    r.fix(seq++, 60.0, 90.0 + 100.0) // returns 100 m further
    for (t in 61..70) r.fix(seq++, t.toDouble(), 190.0 + (t - 60) * 3.0)
    r.pause(ms(71.0))
    r.resume(ms(100.0), 1)
    r.onStepCounter(1, ms(101.0), ms(101.0), raw + 500) // baseline of the new segment
    for (t in 101..120) { r.fix(seq++, t.toDouble(), 500.0 + (t - 100) * 3.0, segment = 1); raw += 2; r.onStepCounter(1, ms(t.toDouble()), ms(t.toDouble()), raw + 500) }
    return seq
}

private fun same(a: BridgeSnapshot, b: BridgeSnapshot) {
    assertEquals(a.totalMeters, b.totalMeters, "total")
    assertEquals(a.gpsMeters, b.gpsMeters, "gps")
    assertEquals(a.gapBridgeMeters, b.gapBridgeMeters, "gapBridge")
    assertEquals(a.pdrMeters, b.pdrMeters, "pdr")
    assertEquals(a.pendingPdrMeters, b.pendingPdrMeters, "pending")
    assertEquals(a.steps, b.steps, "steps")
    assertEquals(a.strideMeters, b.strideMeters, "stride")
    assertEquals(a.movingTimeMs, b.movingTimeMs, "moving")
    assertEquals(a.activeTimeMs, b.activeTimeMs, "active")
    assertEquals(a.bridgedGapMs, b.bridgedGapMs, "bridged")
    assertEquals(a.uncreditedGapMs, b.uncreditedGapMs, "uncredited")
    assertEquals(a.ambiguousSteps, b.ambiguousSteps, "ambiguous")
    assertEquals(a.segmentId, b.segmentId, "segment")
    assertEquals(a.fixCount, b.fixCount, "fixCount")
    assertEquals(a.state, b.state, "state")
}

class JournalCodecTest {
    private val header = JournalRecord.Header(1, 1, "ses|sion=1\n%", 5L, 6L, 0, 12L, settings(), mapOf("title" to "Re|cord=ing", "text" to "Tap\nto return"))

    @Test fun everyRecordTypeRoundTrips() {
        val records = listOf(
            header, JournalRecord.Pause(1), JournalRecord.Resume(2, 3), JournalRecord.Finish(4),
            JournalRecord.Fix("s|1", 2, 3, 4, 5, -10.123456789, 106.0, 5.5, -1.0, 1e-7, 359.9, -4.5, 3.0, "gp=s", true, 9),
            JournalRecord.Tick(7), JournalRecord.StepCounter(1, 2, 3, 4), JournalRecord.SegmentSteps(1, 2, 3, 4),
            JournalRecord.StepsCorrection(8), JournalRecord.StepSensor(true), JournalRecord.Pdr(1, 2, 3.5),
        )
        for (r in records) assertEquals(r, JournalCodec.decode(JournalCodec.encode(r)), r.toString())
    }

    @Test fun aFlippedCharacterIsDetected() {
        val line = JournalCodec.encode(JournalRecord.Pause(123456))
        val damaged = line.substring(0, line.length - 1) + (if (line.last() == '7') '8' else '7')
        assertNull(JournalCodec.decode(damaged))
    }

    @Test fun aHalfWrittenLastLineIsATruncatedTailAndThePrefixIsTrusted() {
        val lines = listOf(JournalCodec.encode(header), JournalCodec.encode(JournalRecord.Pause(1)), JournalCodec.encode(JournalRecord.Resume(2, 1)).dropLast(6))
        val c = JournalCodec.read(lines)
        assertEquals(JournalStatus.TRUNCATED_TAIL, c.status)
        assertEquals(2, c.records.size)
        assertEquals(2, c.damagedLine)
    }

    @Test fun aDamagedMiddleLineIsCorruptionNotARepair() {
        val bad = JournalCodec.encode(JournalRecord.Pause(1)).replaceRange(10, 11, "#")
        val c = JournalCodec.read(listOf(JournalCodec.encode(header), bad, JournalCodec.encode(JournalRecord.Finish(2))))
        assertEquals(JournalStatus.CORRUPT, c.status)
        assertEquals(1, c.records.size)
        assertEquals(1, c.damagedLine)
    }

    @Test fun aNewerFormatIsRefusedAndAnEmptyJournalIsReported() {
        val newer = JournalCodec.encode(header.copy(version = 99))
        assertEquals(JournalStatus.UNSUPPORTED_VERSION, JournalCodec.read(listOf(newer)).status)
        assertEquals(JournalStatus.EMPTY, JournalCodec.read(listOf("", "  ")).status)
        assertEquals(JournalStatus.CORRUPT, JournalCodec.read(listOf(JournalCodec.encode(JournalRecord.Pause(1)))).status)
    }
}

class JournalReplayTest {
    private fun live(adaptive: Boolean = false): Pair<SessionRecorder, MemSink> {
        val sink = MemSink()
        val r = SessionRecorder.start(sink, settings(adaptive), "s", BASE_MS, 1_700_000_000_000L, 0, 0, mapOf("title" to "Recording"))
        return r to sink
    }

    @Test fun replayingTheJournalReproducesTheLiveResultExactly() {
        val (r, sink) = live()
        scenario(r)
        r.pause(ms(130.0))
        val result = SessionRecorder.restore(MemSink(), sink.lines)
        assertEquals(JournalStatus.OK, result.status)
        same(r.snapshot(), assertNotNull(result.recorder).snapshot())
        assertTrue(r.snapshot().pdrMeters >= 0.0 && r.snapshot().bridgedGapMs > 0, "the scenario must exercise a bridged gap")
        assertEquals("Recording", result.extras["title"])
    }

    @Test fun aSessionThatWasRunningComesBackPausedAndAJournaledPauseIsAppended() {
        val (r, sink) = live()
        scenario(r)
        val whileRunning = sink.lines.toList() // the process dies here: no Pause was written
        // The last journaled event is at t=120 s: THAT is when the restored session is paused, not "now".
        val liveAfterPause = run { r.pause(ms(120.0)); r.snapshot() }
        val restoreSink = MemSink()
        val result = SessionRecorder.restore(restoreSink, whileRunning)
        val restored = assertNotNull(result.recorder).snapshot()
        same(liveAfterPause, restored)
        assertEquals(2, restored.state)
        assertTrue(restoreSink.lines.any { JournalCodec.decode(it) is JournalRecord.Pause })
    }

    @Test fun aFixRejectedLiveIsRejectedInReplayBecauseItsArrivalTimeIsJournaled() {
        val (r, sink) = live()
        r.fix(0, 0.0, 0.0)
        r.fix(1, 1.0, 3.0)
        // Measured at t=2 but only delivered 60 s later: stale live, so it must be stale in replay too.
        val verdict = r.fix(2, 2.0, 9.0, nowSeconds = 62.0)
        assertEquals(8, verdict) // STALE
        val total = r.snapshot().totalMeters
        val restored = assertNotNull(SessionRecorder.restore(MemSink(), sink.lines).recorder).snapshot()
        assertEquals(total, restored.totalMeters)
        assertEquals(r.snapshot().fixCount, restored.fixCount)
    }

    @Test fun theConfigurationOfTheSessionIsTheOneInTheHeader() {
        val sink = MemSink()
        val custom = settings().copy(maxSpeedMps = 1.0) // a walker's ceiling
        val r = SessionRecorder.start(sink, custom, "s", BASE_MS, 0, 0, 0, emptyMap())
        r.fix(0, 0.0, 0.0)
        r.fix(1, 1.0, 30.0) // 30 m in 1 s: a jump under THIS config (allowing for the GPS error)
        val live = r.snapshot().totalMeters
        assertEquals(0.0, live)
        val restored = assertNotNull(SessionRecorder.restore(MemSink(), sink.lines).recorder).snapshot()
        assertEquals(0.0, restored.totalMeters)
    }

    @Test fun adaptiveSamplingReplaysTheSameSilenceLimit() {
        val (r, sink) = live(adaptive = true)
        scenario(r)
        r.pause(ms(130.0))
        same(r.snapshot(), assertNotNull(SessionRecorder.restore(MemSink(), sink.lines).recorder).snapshot())
    }

    @Test fun aCrashMidWriteLosesOnlyTheLastRecord() {
        val (r, sink) = live()
        r.fix(0, 0.0, 0.0); r.fix(1, 1.0, 3.0); r.fix(2, 2.0, 6.0)
        val lines = sink.lines.dropLast(1) + sink.lines.last().dropLast(8)
        val result = SessionRecorder.restore(MemSink(), lines)
        assertEquals(JournalStatus.TRUNCATED_TAIL, result.status)
        assertEquals(2, assertNotNull(result.recorder).snapshot().fixCount)
        assertEquals(2L, result.nextSeq)
    }

    @Test fun nothingUsableMeansNoRecorder() {
        assertNull(SessionRecorder.restore(MemSink(), emptyList()).recorder)
        assertNull(SessionRecorder.restore(MemSink(), listOf("garbage")).recorder)
    }

    @Test fun theRouteCanBeReadBackFromTheJournal() {
        val (r, sink) = live()
        r.fix(0, 0.0, 0.0); r.fix(1, 1.0, 3.0)
        val fixes = SessionRecorder.fixes(sink.lines)
        assertEquals(listOf(0L, 1L), fixes.map { it.seq })
        assertTrue(abs(fixes[1].latitude - (10.0 + 3.0 / 111_195.0)) < 1e-12)
    }

    @Test fun flushIsABarrierTheHostImplements() {
        val (r, sink) = live()
        r.flush()
        assertEquals(1, sink.flushes)
    }

    @Test fun theTimeTheProcessWasDeadIsNeverCountedAsWorkoutTime() {
        val (r, sink) = live()
        scenario(r)
        val atLastEvent = run { r.pause(ms(120.0)); r.snapshot().activeTimeMs }
        // Restored "an hour later": the restore must not know or care.
        val restored = assertNotNull(SessionRecorder.restore(MemSink(), sink.lines.dropLast(1)).recorder).snapshot()
        assertEquals(atLastEvent, restored.activeTimeMs)
    }

    @Test fun aRebootThatRestartsTheMonotonicClockNeitherLosesNorInventsTime() {
        val sink = MemSink()
        val boot1 = 9_000_000L
        val r = SessionRecorder.start(sink, settings(), "s", boot1, 1_700_000_000_000L, 0, 0, emptyMap())
        for (t in 0..20) r.onFix("s", 0, t.toLong(), boot1 + t * 1000L, 0, 10.0 + t * 3.0 / 111_195.0, 106.0, 5.0, 3.0, -1.0, -1.0, 0.0, -1.0, "gps", false, boot1 + t * 1000L)
        val restored = SessionRecorder.restore(MemSink(), sink.lines)
        val rec = assertNotNull(restored.recorder)
        assertEquals(20_000L, rec.snapshot().activeTimeMs) // the first leg's real duration survives
        // The device rebooted: the new clock starts near zero, far below the old one.
        val boot2 = 1_000L
        rec.resume(boot2, restored.segmentId + 1)
        for (t in 0..10) rec.onFix("s", restored.segmentId + 1, restored.nextSeq + t, boot2 + t * 1000L, 0, 10.5 + t * 3.0 / 111_195.0, 106.0, 5.0, 3.0, -1.0, -1.0, 0.0, -1.0, "gps", false, boot2 + t * 1000L)
        rec.pause(boot2 + 10_000L)
        assertEquals(30_000L, rec.snapshot().activeTimeMs)
        assertTrue(rec.snapshot().totalMeters > 40.0)
    }

    @Test fun aJournalOfAnotherEngineVersionIsReplayedButFlagged() {
        val (r, sink) = live()
        scenario(r)
        val lines = sink.lines.toMutableList()
        val header = JournalCodec.decode(lines[0]) as JournalRecord.Header
        lines[0] = JournalCodec.encode(header.copy(engineVersion = JournalCodec.ENGINE_VERSION + 7))
        val result = SessionRecorder.restore(MemSink(), lines)
        assertNotNull(result.recorder)
        assertTrue(result.engineChanged)
        assertEquals(JournalCodec.ENGINE_VERSION + 7, result.engineVersion)
        assertTrue(!SessionRecorder.restore(MemSink(), sink.lines).engineChanged)
    }

    @Test fun reprocessMayDifferFromRestoreAndTouchesNothing() {
        val (r, sink) = live()
        scenario(r)
        val before = sink.lines.size
        val restored = assertNotNull(SessionRecorder.restore(MemSink(), sink.lines).recorder).snapshot()
        val again = assertNotNull(SessionRecorder.reprocess(sink.lines)).totalMeters
        assertEquals(restored.totalMeters, again, "same settings: same result")
        val slower = assertNotNull(SessionRecorder.reprocess(sink.lines, settings().copy(maxSpeedMps = 1.0))).totalMeters
        assertTrue(slower < restored.totalMeters, "a 1 m/s ceiling rejects the run: $slower vs ${restored.totalMeters}")
        assertEquals(before, sink.lines.size, "reprocess writes nothing")
    }

    @Test fun movingAndUncreditedTimeAreBothPartsOfActiveTime() {
        val (r, _) = live()
        scenario(r)
        r.pause(ms(130.0))
        val s = r.snapshot()
        assertTrue(s.movingTimeMs <= s.activeTimeMs, "moving ${s.movingTimeMs} > active ${s.activeTimeMs}")
        assertTrue(s.uncreditedGapMs <= s.activeTimeMs)
        assertTrue(s.bridgedGapMs + s.uncreditedGapMs <= s.activeTimeMs, "gaps are inside the active time, not added to it")
    }
}
