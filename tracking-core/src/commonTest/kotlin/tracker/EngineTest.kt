package tracker

import tracker.engine.GpsDistanceEngine
import tracker.model.DistanceSource
import tracker.model.FixVerdict
import tracker.model.GpsQuality
import tracker.model.PdrPolicy
import tracker.model.RecordingState
import tracker.model.TrackingConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun engine(config: TrackingConfig = TrackingConfig()) =
    GpsDistanceEngine(config).also { it.start("s", BASE_MS) }

private fun assertNear(expected: Double, actual: Double, tol: Double) =
    assertTrue(abs(expected - actual) <= tol, "expected $expected +/- $tol, was $actual")

class EngineTest {
    @Test fun firstFixAnchorsLaterFixesAdd() {
        val e = engine()
        assertEquals(FixVerdict.ANCHOR, e.onFix(fixAt(0, 0.0, 0.0), ms(0.0)).verdict)
        assertEquals(FixVerdict.ACCEPTED, e.onFix(fixAt(1, 1.0, 3.0, speed = 3.0), ms(1.0)).verdict)
        e.onFix(fixAt(2, 2.0, 7.0, speed = 4.0), ms(2.0))
        assertNear(7.0, e.totalMeters, 0.1)
        assertNear(7.0, e.ledger.meters(DistanceSource.GPS), 0.1)
    }

    @Test fun dopplerBelowTheLimitMeansNoDistance() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0, speed = 0.0), ms(0.0)) }
        assertEquals(FixVerdict.STATIONARY, e.onFix(fixAt(1, 1.0, 6.0, speed = 0.1), ms(1.0)).verdict)
        assertEquals(0.0, e.totalMeters)
    }

    @Test fun driftInsideTheNoiseRadiusCannotAccumulate() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0, accuracy = 10.0), ms(0.0)) }
        for (i in 1..3) e.onFix(fixAt(i.toLong(), i.toDouble(), i * 1.5, accuracy = 10.0), ms(i.toDouble()))
        assertEquals(0.0, e.totalMeters)
    }

    @Test fun anImpossibleJumpIsRejectedAndTheAnchorKept() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        assertEquals(FixVerdict.JUMP, e.onFix(fixAt(1, 1.0, 500.0, speed = 3.0), ms(1.0)).verdict)
        e.onFix(fixAt(2, 2.0, 6.0, speed = 3.0), ms(2.0))
        assertNear(6.0, e.totalMeters, 0.1)
    }

    @Test fun repeatedJumpsReAnchorWithoutDistance() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        for (i in 1..3) e.onFix(fixAt(i.toLong(), i.toDouble(), 500.0 + i * 3, speed = 3.0), ms(i.toDouble()))
        assertEquals(0.0, e.totalMeters)
        e.onFix(fixAt(4, 4.0, 515.0, speed = 3.0), ms(4.0))
        assertTrue(e.totalMeters > 0.0)
    }

    @Test fun duplicateStaleMockAndWrongSegmentAreDropped() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        assertEquals(FixVerdict.DUPLICATE, e.onFix(fixAt(0, 1.0, 5.0), ms(1.0)).verdict)
        assertEquals(FixVerdict.MOCK, e.onFix(fixAt(1, 1.0, 5.0, mock = true), ms(1.0)).verdict)
        assertEquals(FixVerdict.WRONG_SEGMENT, e.onFix(fixAt(2, 2.0, 5.0, segment = 3), ms(2.0)).verdict)
        assertEquals(FixVerdict.STALE, e.onFix(fixAt(3, 3.0, 5.0), ms(100.0)).verdict)
    }

    @Test fun aPoorFixTellsTheQualityNeverTheDistance() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        assertEquals(FixVerdict.LOW_ACCURACY, e.onFix(fixAt(1, 1.0, 50.0, accuracy = 80.0), ms(1.0)).verdict)
        assertEquals(GpsQuality.DEGRADED, e.quality)
        assertEquals(0.0, e.totalMeters)
    }

    @Test fun silenceMarksGpsUnavailable() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        assertEquals(GpsQuality.GOOD, e.onTick(ms(5.0)))
        assertEquals(GpsQuality.UNAVAILABLE, e.onTick(ms(11.0)))
    }

    @Test fun gapIsCreditedOnceAsStraightLineMinusUncertainty() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onTick(ms(15.0)) }
        assertEquals(FixVerdict.RECOVERED, e.onFix(fixAt(1, 20.0, 100.0), ms(20.0)).verdict)
        assertNear(90.0, e.totalMeters, 0.5)
        assertEquals(GpsQuality.RECOVERING, e.quality)
    }

    @Test fun implausibleOrTooLongGapsAreNotBridged() {
        val a = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onTick(ms(15.0)) }
        a.onFix(fixAt(1, 20.0, 5000.0), ms(20.0))
        assertEquals(0.0, a.totalMeters)
        val b = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onTick(ms(15.0)) }
        b.onFix(fixAt(1, 300.0, 400.0), ms(300.0))
        assertEquals(0.0, b.totalMeters)
    }

    @Test fun shakenPhoneWithoutGpsAddsNothing() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onTick(ms(15.0)) }
        e.submitPdr(0, ms(30.0), 130.0)
        assertEquals(130.0, e.snapshot.pendingPdrMeters)
        assertEquals(0.0, e.totalMeters)
        e.onFix(fixAt(1, 100.0, 1.0), ms(100.0))
        assertEquals(0.0, e.totalMeters)
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun realTunnelRunConfirmsTheEstimateAndIsNotDoubleCounted() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onTick(ms(15.0)) }
        e.submitPdr(0, ms(40.0), 100.0)
        val r = e.onFix(fixAt(1, 60.0, 90.0), ms(60.0))
        assertEquals(DistanceSource.PDR, r.source)
        assertNear(100.0, e.totalMeters, 0.5)
    }

    @Test fun estimateIgnoredWhileGpsIsGoodOrPolicyOff() {
        val good = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        good.submitPdr(0, ms(1.0), 50.0)
        assertEquals(0.0, good.snapshot.pendingPdrMeters)
        val off = engine(TrackingConfig(pdrPolicy = PdrPolicy.OFF))
            .also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onTick(ms(15.0)) }
        off.submitPdr(0, ms(30.0), 50.0)
        assertEquals(0.0, off.snapshot.pendingPdrMeters)
    }

    @Test fun nothingIsBridgedAcrossAPause() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onFix(fixAt(1, 1.0, 5.0, speed = 4.0), ms(1.0)) }
        val before = e.totalMeters
        e.pause(ms(2.0))
        e.resume(ms(600.0), 1)
        assertEquals(FixVerdict.ANCHOR, e.onFix(fixAt(0, 601.0, 5000.0, segment = 1), ms(601.0)).verdict)
        assertEquals(before, e.totalMeters)
    }

    @Test fun finishFreezesTheResult() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onFix(fixAt(1, 1.0, 5.0, speed = 4.0), ms(1.0)) }
        e.finish(ms(2.0))
        val total = e.totalMeters
        assertEquals(RecordingState.FINISHED, e.state)
        assertEquals(FixVerdict.NOT_RUNNING, e.onFix(fixAt(2, 3.0, 50.0, speed = 4.0), ms(3.0)).verdict)
        assertEquals(total, e.totalMeters)
    }

    @Test fun theTotalNeverDecreases() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        var last = 0.0
        for (i in 1 until 40) {
            val north = (i * 4 + if (i % 2 == 0) 30 else -30).toDouble()
            e.onFix(fixAt(i.toLong(), i.toDouble(), north, speed = if (i % 5 == 0) 0.0 else 3.0), ms(i.toDouble()))
            assertTrue(e.totalMeters >= last)
            last = e.totalMeters
        }
    }

    @Test fun movingTimeStopsWhileStanding() {
        val e = engine()
        for (i in 0..40) e.onFix(fixAt(i.toLong(), i.toDouble(), 0.0, speed = 0.0), ms(i.toDouble()))
        assertTrue(e.snapshot.isStopped)
        assertTrue(e.snapshot.movingTimeMs < 31_000, "was ${e.snapshot.movingTimeMs}")
    }

    @Test fun aLateEstimateOfAnotherSegmentIsRefused() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)); it.onTick(ms(15.0)) }
        e.submitPdr(1, ms(20.0), 80.0) // segment 1 does not exist yet
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
        e.pause(ms(21.0))
        e.resume(ms(30.0), 1)
        e.onFix(fixAt(0, 31.0, 0.0, segment = 1), ms(31.0))
        e.onTick(ms(50.0))
        e.submitPdr(0, ms(51.0), 80.0) // an estimate of the OLD segment must not leak in
        assertEquals(0.0, e.snapshot.pendingPdrMeters)
    }

    @Test fun invalidNumbersGetAVerdictAndNeverThrow() {
        val e = engine()
        val nan = fixAt(0, 0.0, 0.0).copy(horizontalAccuracy = Double.NaN)
        assertEquals(FixVerdict.INVALID, e.onFix(nan, ms(0.0)).verdict)
        val negative = fixAt(1, 1.0, 0.0).copy(speed = -3.0)
        assertEquals(FixVerdict.INVALID, e.onFix(negative, ms(1.0)).verdict)
        val bad = fixAt(2, 2.0, 0.0).copy(latitude = 123.0)
        assertEquals(FixVerdict.INVALID, e.onFix(bad, ms(2.0)).verdict)
    }

    @Test fun aFixMeasuredInTheFutureIsRefused() {
        val e = engine()
        assertEquals(FixVerdict.FUTURE, e.onFix(fixAt(0, 100.0, 0.0), ms(0.0)).verdict)
    }

    @Test fun aWorseSpeedAccuracyMeansDopplerIsNotBelieved() {
        val e = engine().also { it.onFix(fixAt(0, 0.0, 0.0), ms(0.0)) }
        // Doppler says 0.1 m/s but is +-3 m/s: not trusted, so the movement counts.
        val doubtful = fixAt(1, 1.0, 6.0, speed = 0.1).copy(speedAccuracy = 3.0)
        assertEquals(FixVerdict.ACCEPTED, e.onFix(doubtful, ms(1.0)).verdict)
    }

    @Test fun movingTimeDoesNotCountFixesTheDopplerCallsStill() {
        val e = engine()
        for (i in 0..20) e.onFix(fixAt(i.toLong(), i.toDouble(), 0.0, speed = 0.0), ms(i.toDouble()))
        assertEquals(0L, e.snapshot.movingTimeMs)
    }
}
