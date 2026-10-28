package tracker

import tracker.engine.GpsDistanceEngine
import tracker.journal.BridgeSettings
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import tracker.journal.JournalSink
import tracker.journal.JournalStatus
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
    @Test fun thisCodeWritesFormatVersionTwoAndEngineVersionTwo() {
        val sink = RSink()
        SessionRecorder.start(sink, rSettings(), "s", BASE_MS, 0, 0, 0, emptyMap())
        val h = assertNotNull(JournalCodec.decode(sink.lines.first()) as? JournalRecord.Header)
        assertEquals(2, h.version)
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
