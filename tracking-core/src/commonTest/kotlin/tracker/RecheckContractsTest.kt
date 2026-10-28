package tracker

import tracker.engine.GpsDistanceEngine
import tracker.journal.BridgeSettings
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import tracker.journal.JournalSink
import tracker.journal.JournalStatus
import tracker.journal.RecoveryResult
import tracker.journal.SessionRecorder
import tracker.model.FixVerdict
import tracker.model.TrackingConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RSink : JournalSink {
    val lines = ArrayList<String>()
    override fun append(line: String) { lines.add(line) }
    override fun flush(): Boolean = true
}

private fun rSettings() = BridgeSettings(40.0, 30_000, 10_000, 10.0, 2.0, 0.5, 0.4, 120_000, 3, true, true, 0.5, false, 15.0, 30_000, 1.0, false, 30_000, false, false)

private fun RecorderFix(r: SessionRecorder, seq: Long, t: Double, north: Double, segment: Int = 0, wall: Long = 1_700_000_000_000L): Int =
    r.onFix("s", segment, seq, ms(t), wall + (t * 1000).toLong(), 10.0 + north / 111_195.0, 106.0, 5.0, 3.0, -1.0, -1.0, 0.0, -1.0, "gps", false, ms(t))

class JournalEncodingContractTest {
    private val awkward = listOf(
        "", " ", "a,b", "a|b", "a=b", "100%", "%2C", "line1\nline2", "cr\rlf", "tab\t", "Chạy bộ, nhấn để quay lại ứng dụng…", "🏃‍♂️ 5 km, 🔋", ",,,", "|=,%",
    )

    @Test fun everySeparatorAndUnicodeSurvivesInEveryTextField() {
        for (text in awkward) {
            val h = JournalRecord.Header(JournalCodec.VERSION, JournalCodec.ENGINE_VERSION, text, 1, 2, 0, 0, rSettings(), mapOf(text to text, "k" to text))
            assertEquals(h, JournalCodec.decode(JournalCodec.encode(h)), "header with <$text>")
            val f = JournalRecord.Fix(text, 0, 1, 2, 3, 1.0, 2.0, 3.0, -1.0, -1.0, -1.0, 0.0, -1.0, text, false, 4)
            assertEquals(f, JournalCodec.decode(JournalCodec.encode(f)), "fix with <$text>")
        }
    }

    @Test fun aSessionWhoseNotificationTextHasACommaStillRestores() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, mapOf("title" to "Running, now", "text" to "Tap, to return"))
        for (t in 0..5) RecorderFix(r, t.toLong(), t.toDouble(), t * 3.0)
        val restored = SessionRecorder.restore(RSink(), sink.lines)
        assertNotNull(restored.recorder)
        assertEquals("Tap, to return", restored.extras["text"])
        assertEquals(JournalStatus.OK, restored.status)
    }
}

class JournalVersionContractTest {
    @Test fun thisCodeWritesFormatVersionThreeAndEngineVersionTwo() {
        val sink = RSink()
        SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        val h = assertNotNull(JournalCodec.decode(sink.lines.first()) as? JournalRecord.Header)
        assertEquals(3, h.version)
        assertEquals(JournalCodec.ENGINE_VERSION, h.engineVersion)
    }

    @Test fun aVersionOneJournalWithoutTheNewRecordsStillReads() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        for (t in 0..5) RecorderFix(r, t.toLong(), t.toDouble(), t * 3.0)
        val lines = sink.lines.toMutableList()
        lines[0] = JournalCodec.encode((JournalCodec.decode(lines[0]) as JournalRecord.Header).copy(version = 1))
        assertEquals(JournalStatus.OK, JournalCodec.read(lines).status)
        assertNotNull(SessionRecorder.restore(RSink(), lines).recorder)
    }

    @Test fun aNewerFormatThanThisCodeIsRefusedNotMisreadAsDamage() {
        val sink = RSink()
        SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        val lines = sink.lines.toMutableList()
        lines[0] = JournalCodec.encode((JournalCodec.decode(lines[0]) as JournalRecord.Header).copy(version = JournalCodec.VERSION + 1))
        lines.add(JournalCodec.encode(JournalRecord.Pause(1)))
        assertEquals(JournalStatus.UNSUPPORTED_VERSION, JournalCodec.read(lines).status)
    }
}

class TickDurabilityContractTest {
    @Test fun everyTickIsJournaledSoTheActiveTimeThatWasShownSurvives() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        for (t in 1..30) r.onTick(ms(t.toDouble()))
        assertEquals(30, sink.lines.count { JournalCodec.decode(it) is JournalRecord.Tick })
        val restored = assertNotNull(SessionRecorder.restore(RSink(), sink.lines).recorder).snapshot()
        assertEquals(30_000L, restored.activeTimeMs)
    }
}

class StepMeasurementContractTest {
    private fun bridge() = rSettings().toBridge().also { it.start("s", BASE_MS, 0) }

    @Test fun aMeasurementAfterItsDeliveryIsABrokenClockAndChangesNothing() {
        val b = bridge()
        b.onSegmentSteps(0, ms(20.0), ms(10.0), 30)
        assertEquals(0L, b.snapshot().steps)
    }

    @Test fun aReadingOlderThanThePreviousOneChangesNothing() {
        val b = bridge()
        b.onStepCounter(0, ms(1.0), ms(1.0), 100) // baseline
        b.onStepCounter(0, ms(5.0), ms(5.0), 110)
        b.onStepCounter(0, ms(3.0), ms(6.0), 140) // measured before the previous reading: out of order
        assertEquals(10L, b.snapshot().steps)
    }

    @Test fun aCounterBaselineMeasuredBeforeTheLegIsStillABaselineSoNoStepsAreLost() {
        val b = bridge()
        b.pause(ms(1.0)); b.resume(ms(10.0), 1)
        // Android stamps a reading with the time of its LAST step, which can precede the registration.
        b.onStepCounter(1, ms(4.0), ms(11.0), 5_000) // baseline: accepted, adds nothing
        b.onStepCounter(1, ms(12.0), ms(12.0), 5_007)
        assertEquals(7L, b.snapshot().steps)
    }

    @Test fun aBatchThatSpansThePauseIsNotAttributedToTheNewLeg() {
        val b = bridge()
        b.onSegmentSteps(0, ms(1.0), ms(1.0), 10)
        b.pause(ms(2.0)); b.resume(ms(10.0), 1)
        b.onSegmentSteps(1, ms(6.0), ms(11.0), 25) // measured while paused
        assertEquals(10L, b.snapshot().steps)
    }
}

class WeakStartContractTest {
    private fun engine() = GpsDistanceEngine().also { it.start("s", BASE_MS) }

    @Test fun startupLatencyAtOrBelowTheSilenceLimitIsNotAGap() {
        val e = engine()
        e.onFix(fixAt(0, 10.0, 0.0, speed = 3.0), ms(10.0)) // exactly the 10 s silence limit
        assertEquals(0L, e.snapshot.uncreditedGapMs)
    }

    @Test fun anUnmeasuredStartBeyondTheLimitIsCountedOnceAndNotAgainAtFinish() {
        val e = engine()
        e.onFix(fixAt(0, 10.001, 0.0, speed = 3.0), ms(10.001))
        assertEquals(10_001L, e.snapshot.uncreditedGapMs)
        assertEquals(1, e.snapshot.uncreditedGaps)
        e.finish(ms(20.0))
        assertEquals(10_001L, e.snapshot.uncreditedGapMs, "not counted again at finish")
        assertEquals(1, e.snapshot.uncreditedGaps)
    }
}

class MovingTimeContractTest {
    @Test fun aNoiseVerdictStillCountsAsMovingForAWalkerWhoseFixesAreCloserThanTheNoiseRadius() {
        val e = GpsDistanceEngine().also { it.start("s", BASE_MS) }
        e.onFix(fixAt(0, 0.0, 0.0, speed = 1.2), ms(0.0))
        assertEquals(FixVerdict.NOISE, e.onFix(fixAt(1, 1.0, 1.2, speed = 1.2), ms(1.0)).verdict)
        assertEquals(1_000L, e.snapshot.movingTimeMs)
    }

    @Test fun aStationaryVerdictDoesNotCountAndTheNextRealMovementDoes() {
        val e = GpsDistanceEngine().also { it.start("s", BASE_MS) }
        e.onFix(fixAt(0, 0.0, 0.0), ms(0.0))
        assertEquals(FixVerdict.STATIONARY, e.onFix(fixAt(1, 8.0, 0.0), ms(8.0)).verdict)
        assertEquals(0L, e.snapshot.movingTimeMs)
        val v = e.onFix(fixAt(2, 10.0, 6.0, speed = 3.0), ms(10.0)) // real movement, 2 s later
        assertEquals(FixVerdict.ACCEPTED, v.verdict)
        assertEquals(2_000L, e.snapshot.movingTimeMs)
    }
}

class ValidationBoundaryContractTest {
    @Test fun aRefusedLifecycleInputIsNeverJournaledAndNeverThrows() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        val before = sink.lines.size
        r.resume(ms(1.0), 1) // not paused
        assertEquals(before, sink.lines.size)
        assertNotNull(r.lastFailure)
        r.pause(ms(2.0)); r.pause(ms(3.0)) // the second pause is refused
        r.resume(ms(4.0), 0) // does not open a NEW segment
        assertEquals(before + 1, sink.lines.size, "only the first pause was journaled")
        assertNotNull(SessionRecorder.restore(RSink(), sink.lines).recorder, "the journal stays replayable")
    }

    @Test fun invalidSettingsAreReportedAndNothingIsWritten() {
        val sink = RSink()
        val result = SessionRecorder.tryStart(sink, rSettings().copy(maxAccuracyMeters = -1.0), "s", BASE_MS, 0, 0, 0, emptyMap())
        assertNull(result.recorder)
        assertNotNull(result.error)
        assertEquals(0, sink.lines.size)
        assertNull(SessionRecorder.tryStart(RSink(), rSettings(), " ", BASE_MS, 0, 0, 0, emptyMap()).recorder, "a blank session id is invalid")
    }

    @Test fun aValidCrcButImpossibleRecordStopsTheReplayAndSaysSo() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        for (t in 0..4) RecorderFix(r, t.toLong(), t.toDouble(), t * 3.0)
        val lines = sink.lines.toMutableList()
        lines.add(JournalCodec.encode(JournalRecord.Pause(ms(5.0))))
        lines.add(JournalCodec.encode(JournalRecord.Resume(ms(6.0), 0))) // a resume that does not open a new segment: valid CRC, impossible content
        lines.add(JournalCodec.encode(JournalRecord.Pause(ms(7.0))))
        val restored = SessionRecorder.restore(RSink(), lines)
        assertNotNull(restored.recorder)
        assertEquals(JournalStatus.CORRUPT, restored.status)
        assertEquals(7, restored.damagedLine, "header + 5 fixes + pause, then the bad resume at index 7")
    }
}

class SegmentFinalityContractTest {
    @Test fun stepsAreExactOnlyWhenEveryActiveSegmentIsSettledExact() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        assertTrue(r.allStepsExact(), "vacuously, with no step sensor")
        r.setStepSensorActive(true) // segment 0
        assertFalse(r.allStepsExact())
        r.pause(ms(2.0)); r.markSegmentSettled(0, false) // its exact query failed
        r.resume(ms(3.0), 1); r.setStepSensorActive(true) // segment 1
        r.pause(ms(5.0)); r.markSegmentSettled(1, true)
        assertFalse(r.allStepsExact(), "segment 0 is still provisional: segment 1 being exact must not hide it")
        r.markSegmentSettled(0, true)
        assertTrue(r.allStepsExact())
    }

    @Test fun finalityPerSegmentSurvivesARestore() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        r.setStepSensorActive(true)
        r.pause(ms(2.0)) // the close never got an exact answer before the process died
        val restored = assertNotNull(SessionRecorder.restore(RSink(), sink.lines).recorder)
        assertFalse(restored.allStepsExact(), "a lost flag must not turn a provisional total into an exact one")
    }
}

class CalendarTimeContractTest {
    @Test fun theAcceptedFixKeepsItsOwnCalendarTimeNoMatterWhatTheClockDoesLater() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        RecorderFix(r, 0, 0.0, 0.0, wall = 1_700_000_000_000L)
        RecorderFix(r, 1, 1.0, 3.0, wall = 1_700_000_000_000L)
        assertEquals(1_700_000_001_000L, r.lastAcceptedFixWallMs())
        val restored = assertNotNull(SessionRecorder.restore(RSink(), sink.lines).recorder)
        assertEquals(-1L, restored.lastAcceptedFixWallMs(), "the restored session is paused: no current fix")
        r.pause(ms(2.0))
        assertEquals(-1L, r.lastAcceptedFixWallMs())
    }
}

class RestorePlanTest {
    private fun recorded(): List<String> {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        for (t in 0..5) RecorderFix(r, t.toLong(), t.toDouble(), t * 3.0)
        r.pause(ms(6.0))
        return sink.lines
    }

    @Test fun aCrcValidButImpossibleRecordIsCorruptAndDroppedBeforeAnythingIsAppended() {
        val lines = recorded() + JournalCodec.encode(JournalRecord.Resume(ms(7.0), 0)) // reuses segment 0
        val plan = SessionRecorder.plan(lines)
        assertEquals(JournalStatus.CORRUPT, plan.status)
        assertEquals(lines.size - 1, plan.damagedLine)
        assertEquals(lines.size - 1, plan.lines.size, "the impossible record is not kept")
        assertTrue(plan.needsRewrite)
        assertTrue(plan.keepBackup, "damage that drops data keeps the original")
        // What is kept restores cleanly: nothing impossible remains.
        assertEquals(JournalStatus.OK, SessionRecorder.restore(RSink(), plan.lines).status)
    }

    @Test fun anIntactJournalNeedsNoRewrite() {
        val plan = SessionRecorder.plan(recorded())
        assertEquals(JournalStatus.OK, plan.status)
        assertFalse(plan.needsRewrite)
        assertFalse(plan.migrated)
    }

    @Test fun aVersionOneHeaderIsMigratedBeforeVersionTwoRecordsFollow() {
        val lines = recorded().toMutableList()
        val h = JournalCodec.decode(lines[0]) as JournalRecord.Header
        lines[0] = JournalCodec.encode(h.copy(version = 1, engineVersion = 1))
        val plan = SessionRecorder.plan(lines)
        assertTrue(plan.migrated)
        assertTrue(plan.needsRewrite)
        assertTrue(plan.keepBackup)
        val header = JournalCodec.decode(plan.lines[0]) as JournalRecord.Header
        assertEquals(JournalCodec.VERSION, header.version)
        assertEquals(1, header.engineVersion, "the engine that recorded the old part is still reported")
        assertTrue(SessionRecorder.restore(RSink(), plan.lines).engineChanged)
    }

    @Test fun aFinishedVersionOneJournalStaysReadOnlyAndIsNotMigrated() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        for (t in 0..3) RecorderFix(r, t.toLong(), t.toDouble(), t * 3.0)
        r.finish(ms(4.0))
        val lines = sink.lines.toMutableList()
        lines[0] = JournalCodec.encode((JournalCodec.decode(lines[0]) as JournalRecord.Header).copy(version = 1))
        val plan = SessionRecorder.plan(lines)
        assertFalse(plan.migrated)
        assertFalse(plan.needsRewrite)
    }
}


class ProcessDeathRecoveryTest {
    private val wall0 = 1_700_000_000_000L

    /** A session that ran [seconds] at 2 m/s, with calendar time on every tick. */
    private fun ran(seconds: Int): List<String> {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), wall0, 0, 0, emptyMap())
        for (t in 1..seconds) {
            RecorderFix(r, t.toLong(), t.toDouble(), t * 2.0)
            r.onTick(ms(t.toDouble()), wall0 + t * 1000L)
        }
        return sink.lines
    }

    private fun recoverAt(lines: List<String>, nowMono: Long, nowWall: Long, maxGap: Long = 0): Pair<SessionRecorder, RecoveryResult> {
        val restored = SessionRecorder.restore(RSink(), lines, false)
        val rec = assertNotNull(restored.recorder)
        assertTrue(restored.wasRunning || rec.snapshot().state != 1)
        return rec to rec.recoverAfterProcessDeath(nowMono, nowWall, maxGap)
    }

    @Test fun theDeadIntervalIsAddedToBothClocksAndTheRecordingContinuesInANewSegment() {
        val lines = ran(10)
        val (rec, result) = recoverAt(lines, ms(13.0), wall0 + 13_000)
        assertTrue(result.recovered)
        assertEquals(3_000, result.gapMs)
        val s = rec.snapshot()
        assertEquals(13_000, s.activeTimeMs)
        assertEquals(13_000, rec.elapsedTimeMs(ms(13.0)))
        assertEquals(1, s.segmentId, "a NEW segment: nothing is bridged across the dead interval")
        assertEquals(1, s.state)
        // Live inputs continue on the same time line.
        rec.onTick(ms(14.0), wall0 + 14_000)
        assertEquals(14_000, rec.snapshot().activeTimeMs)
    }

    @Test fun noDistanceIsInventedForTheDeadInterval() {
        val lines = ran(10)
        val before = SessionRecorder.restore(RSink(), lines).recorder!!.snapshot().totalMeters
        val (rec, _) = recoverAt(lines, ms(40.0), wall0 + 40_000)
        assertEquals(before, rec.snapshot().totalMeters, 1e-9)
        assertTrue(rec.snapshot().uncreditedGapMs >= 30_000, "the silence is REPORTED, not hidden: ${rec.snapshot().uncreditedGapMs}")
    }

    @Test fun aRebootedMonotonicClockDoesNotChangeTheResult() {
        val lines = ran(10)
        val (rec, result) = recoverAt(lines, 50L, wall0 + 13_000) // monotonic restarted from ~0
        assertTrue(result.recovered)
        assertEquals(13_000, rec.snapshot().activeTimeMs)
        rec.onTick(1_050L, wall0 + 14_000)
        assertEquals(14_000, rec.snapshot().activeTimeMs)
    }

    @Test fun aPausedSessionKeepsItsActiveTimeButItsElapsedTimeKeepsGrowing() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), wall0, 0, 0, emptyMap())
        for (t in 1..10) { RecorderFix(r, t.toLong(), t.toDouble(), t * 2.0); r.onTick(ms(t.toDouble()), wall0 + t * 1000L) }
        r.pause(ms(10.0))
        val (rec, result) = recoverAt(sink.lines, ms(70.0), wall0 + 70_000)
        assertTrue(result.recovered)
        assertEquals(10_000, rec.snapshot().activeTimeMs, "pauses are not workout time")
        assertEquals(70_000, rec.elapsedTimeMs(ms(70.0)), "elapsed includes the pause and the dead interval")
        assertEquals(2, rec.snapshot().state, "it stays paused")
    }

    @Test fun aFinishedSessionIsNotChanged() {
        val lines = ran(10) + JournalCodec.encode(JournalRecord.Finish(ms(10.0)))
        val restored = SessionRecorder.restore(RSink(), lines, false)
        val result = restored.recorder!!.recoverAfterProcessDeath(ms(500.0), wall0 + 500_000, 0)
        assertFalse(result.recovered)
        assertEquals(10_000, restored.recorder!!.snapshot().activeTimeMs)
        assertEquals(10_000, restored.recorder!!.elapsedTimeMs(ms(500.0)), "frozen at the finish")
    }

    @Test fun aCalendarClockThatWentBackwardsCountsZeroAndIsFlagged() {
        val (rec, result) = recoverAt(ran(10), ms(11.0), wall0 + 5_000)
        assertTrue(result.recovered)
        assertEquals(0, result.gapMs)
        assertTrue(result.clockAnomaly)
        assertTrue(rec.clockAnomaly)
        assertEquals(10_000, rec.snapshot().activeTimeMs)
    }

    @Test fun aGapAboveTheLimitIsNotCreditedAndTheSessionIsParkedPaused() {
        val (rec, result) = recoverAt(ran(10), ms(10.0 + 3_600), wall0 + 10_000 + 3_600_000, maxGap = 600_000)
        assertFalse(result.recovered)
        assertTrue(result.gapTooLong)
        assertEquals(10_000, rec.snapshot().activeTimeMs)
        assertEquals(2, rec.snapshot().state)
    }

    @Test fun aSessionWithoutCalendarTimeFallsBackToThePlainPause() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), -1L, 0, 0, emptyMap())
        for (t in 1..5) { RecorderFix(r, t.toLong(), t.toDouble(), t * 2.0, wall = -1_000_000L); r.onTick(ms(t.toDouble())) }
        val (rec, result) = recoverAt(sink.lines, ms(100.0), wall0)
        assertFalse(result.recovered)
        assertEquals(2, rec.snapshot().state)
    }

    @Test fun restoringARecoveredJournalReproducesTheSameClocksAndRecoveringAgainAddsNothing() {
        val lines = ran(10)
        val firstSink = RSink()
        val restored = SessionRecorder.restore(firstSink, lines, false)
        val rec = restored.recorder!!
        rec.recoverAfterProcessDeath(ms(13.0), wall0 + 13_000, 0)
        val all = lines + firstSink.lines
        // Replay: same numbers, deterministically.
        val replay = SessionRecorder.restore(RSink(), all).recorder!!
        assertEquals(13_000, replay.snapshot().activeTimeMs)
        assertEquals(3_000, replay.recoveredGapMs)
        // A second process death at the SAME calendar moment adds nothing.
        val again = SessionRecorder.restore(RSink(), all, false).recorder!!
        val second = again.recoverAfterProcessDeath(ms(13.0), wall0 + 13_000, 0)
        assertEquals(13_000, again.snapshot().activeTimeMs)
        assertEquals(0, second.gapMs)
    }

    @Test fun version2JournalsStillRestoreAndTicksWithoutCalendarTimeDecode() {
        assertEquals(JournalRecord.Tick(5), JournalCodec.decode(JournalCodec.encode(JournalRecord.Tick(5))))
        assertEquals(JournalRecord.Tick(5, 99), JournalCodec.decode(JournalCodec.encode(JournalRecord.Tick(5, 99))))
        val q = JournalRecord.Recovered(100, -40, 3_000, true, 1, true, 123)
        assertEquals(q, JournalCodec.decode(JournalCodec.encode(q)))
    }

    // ── The independent reviewer's probes (each failed before the clock fix) ─────────────────────────────

    @Test fun aFixMeasuredEarlierButReceivedLaterDoesNotMakeTheGapCountTwice() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), wall0, 0, 0, emptyMap())
        // measured at t=10 (wall0+10 s), RECEIVED at t=15
        r.onFix("s", 0, 1, ms(10.0), wall0 + 10_000, 10.0, 106.0, 5.0, 3.0, -1.0, -1.0, 0.0, -1.0, "gps", false, ms(15.0))
        val (rec, _) = recoverAt(sink.lines, ms(18.0), wall0 + 18_000)
        assertEquals(18_000, rec.elapsedTimeMs(ms(18.0)), "not 23 s: the receive time must not be paired with the measure wall time")
    }

    @Test fun aPauseAfterTheLastTickDoesNotAddItsOwnOffsetToTheGap() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), wall0, 0, 0, emptyMap())
        r.onTick(ms(10.0), wall0 + 10_000)
        r.pause(ms(10.5))
        val (rec, _) = recoverAt(sink.lines, ms(13.5), wall0 + 13_500)
        assertEquals(13_500, rec.elapsedTimeMs(ms(13.5)))
    }

    @Test fun aPausedSessionRecoveredTwiceNeverGoesBackwards() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), wall0, 0, 0, emptyMap())
        for (t in 1..10) r.onTick(ms(t.toDouble()), wall0 + t * 1000L)
        r.pause(ms(10.0))
        val firstSink = RSink()
        val first = SessionRecorder.restore(firstSink, sink.lines, false).recorder!!
        first.recoverAfterProcessDeath(ms(20.0), wall0 + 20_000, 0)
        assertEquals(20_000, first.elapsedTimeMs(ms(20.0)))
        val all = sink.lines + firstSink.lines
        val second = SessionRecorder.restore(RSink(), all, false).recorder!!
        second.recoverAfterProcessDeath(ms(25.0), wall0 + 25_000, 0)
        assertEquals(25_000, second.elapsedTimeMs(ms(25.0)), "not 15 s")
    }

    @Test fun aCrashRightAfterTheRecoveredRecordWasWrittenLosesNothing() {
        val lines = ran(10)
        val firstSink = RSink()
        val first = SessionRecorder.restore(firstSink, lines, false).recorder!!
        first.recoverAfterProcessDeath(ms(20.0), wall0 + 20_000, 0)
        // Keep ONLY the recovered record (the process died before anything that followed it was written).
        val onlyQ = firstSink.lines.filter { (JournalCodec.decode(it) is JournalRecord.Recovered) }
        assertEquals(1, onlyQ.size)
        val second = SessionRecorder.restore(RSink(), lines + onlyQ, false).recorder!!
        second.recoverAfterProcessDeath(ms(25.0), wall0 + 25_000, 0)
        assertEquals(25_000, second.snapshot().activeTimeMs, "not 15 s")
    }

    @Test fun aShortDeadIntervalWhileGpsWasGoodIsStillReportedAsUncredited() {
        val (rec, result) = recoverAt(ran(10), ms(13.0), wall0 + 13_000)
        assertEquals(3_000, result.gapMs)
        assertEquals(3_000, rec.snapshot().uncreditedGapMs, "the interruption is explicit, not only a GPS-silence threshold")
        assertEquals(1, rec.snapshot().uncreditedGaps)
    }

    @Test fun aRecoveredRecordWithANegativeGapIsRefused() {
        val lines = ran(10) + JournalCodec.encode(JournalRecord.Recovered(15_000, 5_000, -1_000, true, 1, false, wall0 + 15_000))
        val restored = SessionRecorder.restore(RSink(), lines)
        assertEquals(JournalStatus.CORRUPT, restored.status)
    }

    // ── Second round of the reviewer's probes ────────────────────────────────────────────────────────────

    @Test fun aFixTheCoreRefusedForItsTimeIsNoEvidenceOfWhatTimeItIs() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), wall0, 0, 0, emptyMap())
        r.onTick(ms(10.0), wall0 + 10_000)
        // measured at t=100 but RECEIVED at t=10: dated in the future
        r.onFix("s", 0, 1, ms(100.0), wall0 + 100_000, 10.0, 106.0, 5.0, 3.0, -1.0, -1.0, 0.0, -1.0, "gps", false, ms(10.0))
        val (rec, _) = recoverAt(sink.lines, ms(13.0), wall0 + 13_000)
        assertEquals(13_000, rec.elapsedTimeMs(ms(13.0)), "not 100 s")
    }

    @Test fun aFixOfAnotherSegmentIsNoEvidenceEither() {
        val sink = RSink()
        val r = SessionRecorder.start(sink, rSettings(), "s", ms(0.0), wall0, 0, 0, emptyMap())
        r.onTick(ms(10.0), wall0 + 10_000)
        r.onFix("s", 7, 1, ms(9.0), wall0 + 99_000, 10.0, 106.0, 5.0, 3.0, -1.0, -1.0, 0.0, -1.0, "gps", false, ms(10.0))
        val (rec, _) = recoverAt(sink.lines, ms(13.0), wall0 + 13_000)
        assertEquals(13_000, rec.elapsedTimeMs(ms(13.0)))
    }

    @Test fun theRestoredSegmentIsTheCoresAfterARecoveredJournal() {
        val lines = ran(10)
        val firstSink = RSink()
        val first = SessionRecorder.restore(firstSink, lines, false).recorder!!
        first.recoverAfterProcessDeath(ms(13.0), wall0 + 13_000, 0)
        val all = lines + firstSink.lines
        val restored = SessionRecorder.restore(RSink(), all) // a plain restore
        assertEquals(restored.recorder!!.snapshot().segmentId, restored.segmentId, "the result must match the core")
        assertEquals(1, restored.segmentId)
        // And a resume with the NEXT segment works.
        restored.recorder!!.resume(ms(20.0), restored.segmentId + 1)
        assertEquals(1, restored.recorder!!.snapshot().state, "recording again")
    }

    @Test fun aRefusedRecoveredRecordLeavesTheValidPrefixUntouched() {
        val lines = ran(10)
        val bad = JournalCodec.encode(JournalRecord.Recovered(15_000, 5_000, 5_000, true, 0, false, wall0 + 15_000)) // reuses segment 0
        val clean = SessionRecorder.restore(RSink(), lines).recorder!!.snapshot().activeTimeMs
        val restored = SessionRecorder.restore(RSink(), lines + bad)
        assertEquals(JournalStatus.CORRUPT, restored.status)
        assertEquals(clean, restored.recorder!!.snapshot().activeTimeMs, "the prefix's result, not 15 s")
    }
}
