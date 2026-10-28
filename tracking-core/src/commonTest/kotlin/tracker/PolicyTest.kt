package tracker

import tracker.engine.GpsDistanceEngine
import tracker.model.DistanceSource
import tracker.model.FixVerdict
import tracker.model.GpsQuality
import tracker.model.PdrPolicy
import tracker.model.TrackingConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun near(expected: Double, actual: Double, tol: Double) =
    assertTrue(abs(expected - actual) <= tol, "expected $expected +/- $tol, was $actual")

private fun engine(config: TrackingConfig = TrackingConfig()) = GpsDistanceEngine(config).also { it.start("s", BASE_MS) }

/** A steady 3 m/s run with 2 steps/s: teaches ~1.5 m per step. Returns the next seq. */
private fun GpsDistanceEngine.run(seconds: Int): Long {
    var seq = 0L
    for (t in 0..seconds) {
        onFix(fixAt(seq++, t.toDouble(), t * 3.0, speed = 3.0), ms(t.toDouble()))
        if (t > 0) onSteps(0, ms(t - 1.0), ms(t.toDouble()), ms(t.toDouble()), 2)
    }
    return seq
}

class TimeModelTest {
    @Test fun activeTimeExcludesPauses() {
        val e = engine()
        e.onTick(ms(100.0))
        e.pause(ms(100.0))
        e.resume(ms(160.0), 1)
        e.onTick(ms(200.0))
        assertEquals(140_000L, e.snapshot.activeTimeMs)
        e.finish(ms(200.0))
        assertEquals(140_000L, e.snapshot.activeTimeMs)
    }

    @Test fun aWeakStartThatNeverGetsAFixIsReportedNotHidden() {
        val e = engine()
        e.onTick(ms(30.0))
        e.finish(ms(60.0))
        assertEquals(60_000L, e.snapshot.uncreditedGapMs)
        assertEquals(1, e.snapshot.uncreditedGaps)
    }

    @Test fun aGapStillOpenAtFinishIsUncredited() {
        val e = engine()
        e.run(20)
        e.onTick(ms(40.0))
        e.finish(ms(80.0))
        assertEquals(60_000L, e.snapshot.uncreditedGapMs) // from the last healthy fix (t=20)
        assertEquals(1, e.snapshot.uncreditedGaps)
    }

    @Test fun aTooLongGapIsUncreditedAndItsTimeIsReported() {
        val e = engine()
        val seq = e.run(20) // good until t=20, at 60 m
        e.onTick(ms(40.0))
        e.onFix(fixAt(seq, 200.0, 200.0, speed = 3.0), ms(200.0)) // 180 s later: beyond maxBridge
        assertEquals(0.0, e.ledger.meters(DistanceSource.GAP_BRIDGE))
        assertEquals(180_000L, e.snapshot.uncreditedGapMs)
        assertEquals(1, e.snapshot.uncreditedGaps)
    }

    @Test fun aBridgedGapCountsAsMovingTime() {
        val e = engine()
        val seq = e.run(20)
        val movingBefore = e.snapshot.movingTimeMs
        e.onTick(ms(40.0))
        e.onFix(fixAt(seq, 60.0, 60.0 + 100.0, speed = 3.0), ms(60.0)) // 40 s, 100 m: moving
        assertEquals(40_000L, e.snapshot.bridgedGapMs)
        assertTrue(e.snapshot.movingTimeMs >= movingBefore + 40_000L)
    }
}

class StepEvidenceTest {
    private fun gapEngine(sensorActive: Boolean, config: TrackingConfig = TrackingConfig(initialStrideMeters = 1.0)): GpsDistanceEngine {
        val e = engine(config)
        e.setStepSensorActive(sensorActive)
        e.onFix(fixAt(0, 0.0, 0.0, speed = 3.0), ms(0.0))
        e.onTick(ms(15.0)) // GPS silent
        return e
    }

    @Test fun aLongGapWithNoStepsAtAllIsStandingStill() {
        val e = gapEngine(sensorActive = true)
        e.onFix(fixAt(1, 40.0, 60.0, speed = 3.0), ms(40.0))
        assertEquals(0.0, e.totalMeters)
        assertEquals(40_000L, e.snapshot.uncreditedGapMs)
    }

    @Test fun aLongGapWithStepsIsBridged() {
        val e = gapEngine(sensorActive = true)
        e.onSteps(0, ms(20.0), ms(35.0), ms(36.0), 30)
        e.onFix(fixAt(1, 40.0, 60.0, speed = 3.0), ms(40.0))
        near(50.0, e.totalMeters, 1.0)
    }

    @Test fun withoutAWorkingSensorSilenceIsNotEvidence() {
        val e = gapEngine(sensorActive = false)
        e.onFix(fixAt(1, 40.0, 60.0, speed = 3.0), ms(40.0))
        near(50.0, e.totalMeters, 1.0)
    }

    @Test fun aShortGapIsNotJudgedBecauseStepsMayArriveLate() {
        val e = gapEngine(sensorActive = true)
        e.onFix(fixAt(1, 20.0, 60.0, speed = 3.0), ms(20.0))
        near(50.0, e.totalMeters, 1.0)
    }

    @Test fun cyclingNeverNeedsStepEvidence() {
        val e = gapEngine(sensorActive = true, TrackingConfig(pdrPolicy = PdrPolicy.OFF))
        e.onFix(fixAt(1, 40.0, 60.0, speed = 3.0), ms(40.0))
        near(50.0, e.totalMeters, 1.0)
    }
}

class StepIntervalTest {
    private fun GpsDistanceEngine.goodRun(until: Int) {
        for (t in 0..until) onFix(fixAt(t.toLong(), t.toDouble(), t * 3.0, speed = 3.0), ms(t.toDouble()))
    }

    @Test fun aBatchMeasuredWhollyInGoodGpsNeverBecomesPending() {
        val e = engine(TrackingConfig(initialStrideMeters = 1.0)).also { it.goodRun(10) }
        e.onSteps(0, ms(2.0), ms(8.0), ms(9.0), 12)
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
        assertEquals(0L, e.snapshot.ambiguousSteps)
    }

    @Test fun aBatchMeasuredWhollyInAWeakIntervalBecomesPending() {
        val e = engine(TrackingConfig(initialStrideMeters = 1.0)).also { it.goodRun(10) }
        e.onFix(fixAt(11, 12.0, 33.0, accuracy = 90.0), ms(12.0)) // degraded: GPS stopped measuring at t=10
        e.onSteps(0, ms(11.0), ms(14.0), ms(15.0), 6)
        near(6.0, e.snapshot.pendingPdrMeters, 1e-9)
    }

    @Test fun aBatchStraddlingAQualityChangeIsAmbiguousAndUsedForNothing() {
        val e = engine(TrackingConfig(initialStrideMeters = 1.0)).also { it.goodRun(10) }
        e.onFix(fixAt(11, 12.0, 33.0, accuracy = 90.0), ms(12.0))
        e.onSteps(0, ms(8.0), ms(13.0), ms(14.0), 10) // 2 s good + 3 s weak
        assertEquals(10L, e.snapshot.ambiguousSteps)
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun aWeakBatchDeliveredAfterGpsCameBackIsTooLateToAttach() {
        val e = engine(TrackingConfig(initialStrideMeters = 1.0)).also { it.goodRun(10) }
        e.onFix(fixAt(11, 12.0, 33.0, accuracy = 90.0), ms(12.0))
        e.onFix(fixAt(12, 16.0, 48.0, speed = 3.0), ms(16.0)) // back in the normal path
        assertEquals(GpsQuality.GOOD, e.quality)
        e.onSteps(0, ms(11.0), ms(14.0), ms(20.0), 6) // measured in the weak interval, delivered late
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun aBatchOfAnEarlierLegIsRefused() {
        val e = engine(TrackingConfig(initialStrideMeters = 1.0))
        e.pause(ms(10.0))
        e.resume(ms(30.0), 1)
        e.onTick(ms(45.0))
        e.onSteps(1, ms(5.0), ms(9.0), ms(46.0), 50) // measured before the new leg
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }
}

class StationaryPolicyTest {
    @Test fun jitterAfterAStopIsNotDistanceAndRealMovementIsCreditedInFull() {
        val e = engine()
        e.onFix(fixAt(0, 0.0, 0.0, accuracy = 5.0), ms(0.0))
        for (t in 1..40) e.onFix(fixAt(t.toLong(), t.toDouble(), if (t % 2 == 0) 0.5 else -0.5, accuracy = 5.0), ms(t.toDouble()))
        assertEquals(0.0, e.totalMeters)
        // Unknown speed, 3 m of drift: beyond the noise radius but inside the stay radius.
        assertEquals(FixVerdict.STATIONARY, e.onFix(fixAt(41, 41.0, 3.0, accuracy = 5.0), ms(41.0)).verdict)
        assertEquals(0.0, e.totalMeters)
        // The person walks away: once outside the radius the whole displacement counts.
        e.onFix(fixAt(42, 43.0, 20.0, accuracy = 5.0), ms(43.0))
        near(20.0, e.totalMeters, 0.5)
    }

    @Test fun unknownSpeedFallsBackToTheDisplacementSpeed() {
        val e = engine()
        e.onFix(fixAt(0, 0.0, 0.0, accuracy = 5.0), ms(0.0))
        // 3 m in 8 s = 0.375 m/s (< 0.4): standing, even though no Doppler speed was given.
        assertEquals(FixVerdict.STATIONARY, e.onFix(fixAt(1, 8.0, 3.0, accuracy = 5.0), ms(8.0)).verdict)
    }
}

class TrustedPdrPolicyTest {
    private fun trusted() = TrackingConfig(pdrPolicy = PdrPolicy.TRUSTED_WHEN_CALIBRATED)

    @Test fun aCalibratedStrideCreditsALongGapAsEstimatedDistance() {
        val e = engine(trusted())
        val seq = e.run(40)
        e.setStepSensorActive(true)
        e.onTick(ms(55.0))
        e.onSteps(0, ms(60.0), ms(190.0), ms(191.0), 150) // ~225 m of steps in a 150 s gap
        e.onFix(fixAt(seq, 200.0, 140.0, speed = 3.0), ms(200.0)) // far beyond maxBridge, 20 m from the last fix
        assertTrue(e.ledger.meters(DistanceSource.PDR) > 150.0, "pdr ${e.ledger.meters(DistanceSource.PDR)}")
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun theConservativePolicyCreditsNothingForTheSameGap() {
        val e = engine(TrackingConfig(pdrPolicy = PdrPolicy.CONFIRMED_BY_GPS))
        val seq = e.run(40)
        e.setStepSensorActive(true)
        e.onTick(ms(55.0))
        e.onSteps(0, ms(60.0), ms(190.0), ms(191.0), 150)
        val before = e.totalMeters
        e.onFix(fixAt(seq, 200.0, 140.0, speed = 3.0), ms(200.0))
        assertEquals(before, e.totalMeters)
    }

    @Test fun anUncalibratedStrideIsNeverTrusted() {
        val e = engine(TrackingConfig(pdrPolicy = PdrPolicy.TRUSTED_WHEN_CALIBRATED, initialStrideMeters = 1.0))
        e.onFix(fixAt(0, 0.0, 0.0, speed = 3.0), ms(0.0))
        e.onTick(ms(15.0))
        e.onSteps(0, ms(20.0), ms(150.0), ms(151.0), 150)
        val before = e.totalMeters
        e.onFix(fixAt(1, 200.0, 20.0, speed = 3.0), ms(200.0))
        assertEquals(before, e.totalMeters, "a configured stride alone is not calibration")
    }
}

class DriftAndStepAccountingTest {
    @Test fun slowGpsDriftWithUnknownSpeedAndGoodLookingAccuracyIsNeverDistance() {
        val e = engine()
        e.onFix(fixAt(0, 0.0, 0.0, accuracy = 5.0), ms(0.0))
        // A phone on a table: the position walks away at 0.2 m/s (24 m in 2 minutes), no Doppler, accuracy "good".
        for (t in 1..120) e.onFix(fixAt(t.toLong(), t.toDouble(), t * 0.2, accuracy = 5.0), ms(t.toDouble()))
        assertEquals(0.0, e.totalMeters, "drift below the stationary speed must not accumulate")
    }

    @Test fun driftFasterThanTheStationarySpeedCannotBeToldFromSlowWalkingWithoutDoppler() {
        // The documented limit, pinned so nobody believes the gate does more than it does.
        val e = engine()
        e.onFix(fixAt(0, 0.0, 0.0, accuracy = 5.0), ms(0.0))
        for (t in 1..120) e.onFix(fixAt(t.toLong(), t.toDouble(), t * 0.6, accuracy = 5.0), ms(t.toDouble()))
        assertTrue(e.totalMeters > 50.0, "0.6 m/s with no Doppler is credited: was ${e.totalMeters}")
    }

    @Test fun theDisplacementSpeedOnlyEverBlocksItNeverConfirmsMovement() {
        val e = engine()
        e.onFix(fixAt(0, 0.0, 0.0, accuracy = 5.0), ms(0.0))
        // 2 m in 4 s = 0.5 m/s is above the limit: not blocked by the speed gate, but still has to clear the noise radius.
        val v = e.onFix(fixAt(1, 4.0, 2.0, accuracy = 5.0), ms(4.0)).verdict
        assertEquals(FixVerdict.NOISE, v)
    }

    @Test fun stepsAreSplitIntoReportedAttributedAndEligible() {
        val e = engine(TrackingConfig(initialStrideMeters = 1.0))
        for (t in 0..10) e.onFix(fixAt(t.toLong(), t.toDouble(), t * 3.0, speed = 3.0), ms(t.toDouble()))
        e.onSteps(0, ms(2.0), ms(8.0), ms(9.0), 12) // wholly good: teaches the stride
        e.onFix(fixAt(11, 12.0, 33.0, accuracy = 90.0), ms(12.0))
        e.onSteps(0, ms(11.0), ms(14.0), ms(15.0), 6) // wholly weak with a stride: a pending estimate
        e.onSteps(0, ms(8.0), ms(13.0), ms(15.5), 10) // straddles the change: ambiguous
        val s = e.snapshot
        assertEquals(10L, s.ambiguousSteps)
        assertEquals(18L, s.distanceEligibleSteps, "12 taught the stride + 6 became pending; the ambiguous 10 did not")
    }

    @Test fun aWeakBatchThatArrivesAfterGpsReturnedIsNotEligible() {
        val e = engine(TrackingConfig(initialStrideMeters = 1.0))
        for (t in 0..10) e.onFix(fixAt(t.toLong(), t.toDouble(), t * 3.0, speed = 3.0), ms(t.toDouble()))
        e.onFix(fixAt(11, 12.0, 33.0, accuracy = 90.0), ms(12.0))
        e.onFix(fixAt(12, 16.0, 48.0, speed = 3.0), ms(16.0))
        e.onSteps(0, ms(11.0), ms(14.0), ms(20.0), 6)
        assertEquals(0L, e.snapshot.distanceEligibleSteps)
    }
}
