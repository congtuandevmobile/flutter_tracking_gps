package tracker

import tracker.algorithms.motion.StepSession
import tracker.algorithms.motion.StrideEstimator
import tracker.bridge.TrackingCoreBridge
import tracker.engine.GpsDistanceEngine
import tracker.model.GpsQuality
import tracker.model.RecordingState
import tracker.model.TrackingConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun near(expected: Double, actual: Double, tol: Double) =
    assertTrue(abs(expected - actual) <= tol, "expected $expected +/- $tol, was $actual")

class StrideEstimatorTest {
    @Test fun needsBothStepsAndMetresBeforeLearning() {
        val s = StrideEstimator()
        s.addSteps(50)
        assertNull(s.strideMeters)
        s.addMeters(10.0)
        assertNull(s.strideMeters) // 10 m is not enough yet
        s.addMeters(40.0)
        near(1.0, s.strideMeters!!, 1e-9)
    }

    @Test fun standingOrShakingNeverTeachesAStride() {
        val s = StrideEstimator()
        s.addSteps(500) // plenty of steps, no GPS distance: abandoned
        s.addMeters(30.0)
        s.addSteps(10)
        assertNull(s.strideMeters)
    }

    @Test fun implausibleStrideIsDiscarded() {
        val s = StrideEstimator()
        s.addSteps(100); s.addMeters(20.0) // 0.2 m per step: too short
        assertNull(s.strideMeters)
        s.addSteps(10); s.addMeters(100.0) // 10 m per step: too long
        assertNull(s.strideMeters)
    }

    @Test fun movingAverageMovesSlowlyAndBreakWindowKeepsIt() {
        val s = StrideEstimator()
        s.addSteps(100); s.addMeters(100.0) // 1.0
        s.addSteps(100); s.addMeters(150.0) // sample 1.5 -> 1.0 + 0.3 * 0.5
        near(1.15, s.strideMeters!!, 1e-9)
        s.addSteps(10)
        s.breakWindow()
        s.addMeters(100.0) // the 10 steps were dropped with the window
        near(1.15, s.strideMeters!!, 1e-9)
    }

    @Test fun initialStrideIsUsedAndRestoredOnReset() {
        val s = StrideEstimator(initialStrideMeters = 0.8)
        near(0.8, s.strideMeters!!, 1e-9)
        s.addSteps(100); s.addMeters(100.0)
        assertTrue(s.strideMeters!! > 0.8)
        s.reset()
        near(0.8, s.strideMeters!!, 1e-9)
    }
}

class StepSessionTest {
    @Test fun firstCounterReadingIsOnlyABaseline() {
        val s = StepSession().also { it.openSegment() }
        assertEquals(0, s.onCounter(10_000))
        assertEquals(7, s.onCounter(10_007))
        assertEquals(7, s.total)
    }

    @Test fun pauseKeepsTheTotalAndResumeStartsAFreshBaseline() {
        val s = StepSession().also { it.openSegment() }
        s.onCounter(1000); s.onCounter(1050)
        s.closeSegment()
        s.openSegment()
        // 400 steps were walked while paused: the first reading is a baseline.
        assertEquals(0, s.onCounter(1450))
        assertEquals(10, s.onCounter(1460))
        assertEquals(60, s.total)
    }

    @Test fun counterGoingBackwardsKeepsTheAcceptedTotal() {
        val s = StepSession().also { it.openSegment() }
        s.onCounter(5000); s.onCounter(5040)
        assertEquals(0, s.onCounter(12)) // device reboot: new baseline
        assertEquals(40, s.total)
        assertEquals(8, s.onCounter(20))
        assertEquals(48, s.total)
    }

    @Test fun segmentCountIsCumulativeAndALowerReadingIsIgnored() {
        val s = StepSession().also { it.openSegment() }
        assertEquals(30, s.onSegmentCount(30))
        assertEquals(0, s.onSegmentCount(25))
        assertEquals(30, s.total)
    }

    @Test fun exactClosedCountReplacesTheEstimateOnlyUntilTheNextSegmentCounts() {
        val s = StepSession().also { it.openSegment() }
        s.onSegmentCount(30)
        s.closeSegment()
        assertEquals(4, s.correctClosedSegment(34))
        assertEquals(34, s.total)
        s.openSegment()
        s.onSegmentCount(5)
        assertEquals(0, s.correctClosedSegment(99)) // too late: ignored
        assertEquals(39, s.total)
    }

    @Test fun closingAnAlreadyClosedSegmentKeepsTheExactCorrectionValid() {
        // iOS: pause, then finish while the OS query is still pending, then the exact count arrives.
        val s = StepSession().also { it.openSegment() }
        s.onSegmentCount(30)
        s.closeSegment() // pause
        s.closeSegment() // finish from paused: must not forget the 30
        assertEquals(4, s.correctClosedSegment(34))
        assertEquals(34, s.total)
    }

    @Test fun restoredTotalIsNotAddedToTheDeviceCounter() {
        val s = StepSession().also { it.reset(initialSteps = 500); it.openSegment() }
        assertEquals(0, s.onCounter(88_000))
        assertEquals(500, s.total)
    }
}

class EngineStepsTest {
    private fun engine() = GpsDistanceEngine(TrackingConfig()).also { it.start("s", BASE_MS) }

    /** A steady 3 m/s, 2 steps/s run for [seconds], teaching the engine ~1.5 m per step. */
    private fun GpsDistanceEngine.run(from: Int, seconds: Int, seq0: Long = from.toLong()): Long {
        var seq = seq0
        for (t in from..from + seconds) {
            onFix(fixAt(seq++, t.toDouble(), t * 3.0, speed = 3.0), ms(t.toDouble()))
            if (t > from) onSteps(0, ms(t.toDouble()), 2)
        }
        return seq
    }

    @Test fun strideIsLearnedFromGoodGpsAndStepsAreNotDistanceThen() {
        val e = engine()
        e.run(0, 30)
        near(1.5, e.snapshot.strideMeters!!, 0.2)
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
        near(90.0, e.totalMeters, 1.0) // all of it from GPS
        assertEquals(0.0, e.ledger.meters(tracker.model.DistanceSource.PDR))
    }

    @Test fun withoutAStrideWeakGpsStepsEstimateNothing() {
        val e = engine()
        e.onFix(fixAt(0, 0.0, 0.0, speed = 3.0), ms(0.0))
        e.onTick(ms(15.0)) // GPS silent
        e.onSteps(0, ms(20.0), 200)
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun stepsDuringAGpsGapBecomePendingAndAreConfirmedOnce() {
        val e = engine()
        val seq = e.run(0, 30) // good GPS, ends at 90 m, stride ~1.5
        e.onTick(ms(45.0)) // GPS lost
        assertEquals(GpsQuality.UNAVAILABLE, e.quality)
        // The runner covers 60 m in the tunnel: 40 steps.
        e.onSteps(0, ms(50.0), 40)
        near(60.0, e.snapshot.pendingPdrMeters, 12.0)
        val before = e.totalMeters
        e.onFix(fixAt(seq, 50.0, 150.0, speed = 3.0), ms(50.0))
        val credited = e.totalMeters - before
        // One source for the gap, never a sum: the straight line is 60 m.
        assertTrue(credited in 45.0..60.5, "credited $credited")
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun shakingWithoutGpsAfterLearningStillAddsNothing() {
        val e = engine()
        val seq = e.run(0, 30)
        e.onTick(ms(45.0))
        e.onSteps(0, ms(60.0), 300) // shaken in the hand: steps, no movement
        val before = e.totalMeters
        e.onFix(fixAt(seq, 70.0, 91.0, speed = 0.1), ms(70.0)) // GPS back where it was
        assertTrue(e.totalMeters - before < 1.0, "was ${e.totalMeters - before}")
    }

    @Test fun anEstimateIsCappedByTheCorroboratedStraightLine() {
        val e = engine()
        val seq = e.run(0, 30)
        e.onTick(ms(45.0))
        e.onSteps(0, ms(55.0), 133) // ~200 m of steps in a loop
        near(200.0, e.snapshot.pendingPdrMeters, 40.0)
        val before = e.totalMeters
        e.onFix(fixAt(seq, 56.0, 130.0, speed = 3.0), ms(56.0)) // only 40 m away in a line
        // lower bound of the line = 40 - (5 + 5) = 30 m, so the estimate is capped at 30 / 0.5 = 60 m,
        // far below the ~200 m of steps: a loop is only credited as far as GPS can corroborate.
        val credited = e.totalMeters - before
        assertTrue(credited in 55.0..60.5, "was $credited")
    }

    @Test fun stepsOfAnotherSegmentOrWhilePausedAreRefused() {
        val e = engine()
        e.run(0, 30)
        val stride = e.snapshot.strideMeters
        e.pause(ms(31.0))
        e.onSteps(0, ms(32.0), 50)
        e.resume(ms(40.0), 1)
        e.onTick(ms(60.0)) // silent in the new segment
        e.onSteps(0, ms(61.0), 50) // late steps of the old segment
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
        assertNotNull(stride)
        assertEquals(RecordingState.RUNNING, e.state)
    }

    @Test fun pauseAndResumeDropTheLearningWindowButKeepTheStride() {
        val e = engine()
        e.run(0, 30)
        val stride = e.snapshot.strideMeters
        e.onSteps(0, ms(31.0), 15) // an open window of 15 steps
        e.pause(ms(32.0))
        e.resume(ms(40.0), 1)
        assertEquals(stride, e.snapshot.strideMeters)
    }

    @Test fun cyclingConfigNeverTurnsStepsIntoPending() {
        val e = GpsDistanceEngine(TrackingConfig.forSport(tracker.model.Sport.CYCLING)).also { it.start("s", BASE_MS) }
        e.run(0, 30)
        e.onTick(ms(45.0))
        e.onSteps(0, ms(50.0), 100)
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun initialStrideMakesAWeakStartEstimateImmediately() {
        val e = GpsDistanceEngine(TrackingConfig(initialStrideMeters = 1.0)).also { it.start("s", BASE_MS) }
        e.onFix(fixAt(0, 0.0, 0.0, speed = 3.0), ms(0.0))
        e.onTick(ms(15.0))
        e.onSteps(0, ms(20.0), 30)
        near(30.0, e.snapshot.pendingPdrMeters, 1e-9)
    }
}

class BridgeStepsTest {
    private fun bridge(stride: Double = -1.0) = TrackingCoreBridge(
        maxAccuracyMeters = 40.0, maxFixAgeMs = 30_000, silenceAfterMs = 10_000, maxSpeedMps = 10.0,
        minStepMeters = 2.0, accuracyNoiseFactor = 0.5, stationarySpeedMps = 0.4, maxBridgeMs = 120_000,
        reanchorAfterRejects = 3, rejectMock = true, pdrEnabled = true, pdrConfirmRatio = 0.5,
        kalman = false, stopRadiusMeters = 15.0, stopMinDwellMs = 30_000, initialStrideMeters = stride,
        pdrTrustedWhenCalibrated = false, recoveryStepEvidenceAfterMs = 30_000,
        adaptiveSampling = false, batterySaver = false,
    )

    @Test fun deviceCounterBaselineThenDeltasReachTheEngine() {
        val b = bridge(stride = 1.0).also { it.start("s", BASE_MS, 0) }
        b.onFix("s", 0, 0, ms(0.0), 10.0, 106.0, 5.0, 3.0, -1.0, false, ms(0.0), false)
        assertEquals(3, b.onTick(ms(15.0)))
        b.onStepCounter(0, ms(16.0), ms(16.0), 70_000) // baseline
        b.onStepCounter(0, ms(20.0), ms(20.0), 70_025)
        val s = b.snapshot()
        assertEquals(25, s.steps)
        near(25.0, s.pendingPdrMeters, 1e-9)
        near(1.0, s.strideMeters, 1e-9)
    }

    @Test fun strideIsUnknownAsNegative() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        assertTrue(b.snapshot().strideMeters < 0.0)
    }

    @Test fun restoredStepsSurviveAndPauseResumeKeepTheTotal() {
        val b = bridge().also { it.start("s", BASE_MS, 0, initialSteps = 500) }
        b.onStepCounter(0, ms(1.0), ms(1.0), 100); b.onStepCounter(0, ms(2.0), ms(2.0), 130)
        b.pause(ms(3.0))
        b.resume(ms(60.0), 1)
        b.onStepCounter(1, ms(61.0), ms(61.0), 900) // walked while paused: baseline only
        b.onStepCounter(1, ms(62.0), ms(62.0), 910)
        assertEquals(540, b.snapshot().steps)
    }

    @Test fun iosStyleExactCloseCorrectsTheTotalOnly() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        b.onSegmentSteps(0, ms(1.0), ms(1.0), 30)
        b.pause(ms(2.0))
        b.correctClosedSegment(34)
        assertEquals(34, b.snapshot().steps)
        assertEquals(0.0, b.snapshot().totalMeters)
    }
}
