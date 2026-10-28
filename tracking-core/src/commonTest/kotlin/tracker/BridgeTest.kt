package tracker

import tracker.bridge.TrackingCoreBridge
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun bridge(adaptive: Boolean = false, pdr: Boolean = true) = TrackingCoreBridge(
    maxAccuracyMeters = 40.0, maxFixAgeMs = 30_000, silenceAfterMs = 10_000, maxSpeedMps = 10.0,
    minStepMeters = 2.0, accuracyNoiseFactor = 0.5, stationarySpeedMps = 0.4, maxBridgeMs = 120_000,
    reanchorAfterRejects = 3, rejectMock = true, pdrEnabled = pdr, pdrConfirmRatio = 0.5,
    kalman = false, stopRadiusMeters = 15.0, stopMinDwellMs = 30_000, initialStrideMeters = -1.0,
    adaptiveSampling = adaptive, batterySaver = false,
)

private fun TrackingCoreBridge.fix(seq: Long, seconds: Double, north: Double, segment: Int = 0, speed: Double = -1.0) =
    onFix("s", segment, seq, ms(seconds), 10.0 + north / 111_195.0, 106.0, 5.0, speed, -1.0, false, ms(seconds), false)

class BridgeTest {
    @Test fun aRunThroughTheBridgeMatchesTheEngine() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        for (i in 0..10) b.fix(i.toLong(), i.toDouble(), i * 3.0, speed = 3.0)
        val s = b.snapshot()
        assertTrue(abs(s.totalMeters - 30.0) < 0.5, "was ${s.totalMeters}")
        assertEquals(1, s.state)
        assertEquals(1, s.quality)
        assertEquals(11, s.fixCount)
    }

    @Test fun shakingWithoutGpsAddsNothing() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        b.fix(0, 0.0, 0.0)
        assertEquals(3, b.onTick(ms(15.0)))
        b.submitPdr(0, ms(30.0), 130.0)
        assertEquals(130.0, b.snapshot().pendingPdrMeters)
        b.fix(1, 100.0, 1.0)
        assertEquals(0.0, b.snapshot().totalMeters)
    }

    @Test fun unknownSpeedIsNegative() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        b.fix(0, 0.0, 0.0)
        assertTrue(b.snapshot().speedMps < 0.0)
        assertTrue(b.snapshot().lastFixAtMs >= 0L)
    }

    @Test fun pauseAndResumeKeepTheTotalAndOpenANewSegment() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        b.fix(0, 0.0, 0.0)
        b.fix(1, 1.0, 5.0, speed = 4.0)
        val before = b.snapshot().totalMeters
        b.pause(ms(2.0))
        assertEquals(2, b.snapshot().state)
        b.resume(ms(60.0), 1)
        b.fix(0, 61.0, 9000.0, segment = 1)
        val s = b.snapshot()
        assertEquals(before, s.totalMeters)
        assertEquals(1, s.segmentId)
    }

    @Test fun replayAcceptsOldFixes() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        b.onFix("s", 0, 0, ms(0.0), 10.0, 106.0, 5.0, -1.0, -1.0, false, ms(500.0), true)
        b.onFix("s", 0, 1, ms(1.0), 10.0 + 6.0 / 111_195.0, 106.0, 5.0, 3.0, -1.0, false, ms(500.0), true)
        assertTrue(b.snapshot().totalMeters > 0.0)
    }

    @Test fun adaptivePlanRaisesTheSilenceLimitWhenSamplingIsSparse() {
        val b = bridge(adaptive = true).also { it.start("s", BASE_MS, 0) }
        assertEquals(1_000, b.samplingPlan(ms(0.0)).intervalMs)
        for (i in 0..40) b.fix(i.toLong(), i.toDouble(), 0.0, speed = 0.0)
        b.samplingPlan(ms(30.0)) // the policy must SEE the standstill begin
        val sparse = b.samplingPlan(ms(60.0))
        assertEquals(3_000, sparse.intervalMs)
        assertTrue(sparse.silenceAfterMs >= 12_000)
        val fixed = bridge(adaptive = false).also { it.start("s", BASE_MS, 0) }
        assertEquals(1_000, fixed.samplingPlan(ms(60.0)).intervalMs)
    }

    @Test fun exactlyMinusOneIsUnknownButNaNIsInvalid() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        // unknown accuracy (-1) is a LOW_ACCURACY fix, NaN is INVALID: different codes
        val unknown = b.onFix("s", 0, 0, ms(0.0), 10.0, 106.0, -1.0, -1.0, -1.0, false, ms(0.0), false)
        val nan = b.onFix("s", 0, 1, ms(1.0), 10.0, 106.0, Double.NaN, -1.0, -1.0, false, ms(1.0), false)
        assertEquals(7, unknown)
        assertEquals(14, nan)
    }

    @Test fun theStableCodesDoNotDependOnEnumOrder() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        assertEquals(0, b.fix(0, 0.0, 0.0)) // ANCHOR
        assertEquals(1, b.fix(1, 1.0, 5.0, speed = 4.0)) // ACCEPTED
        assertEquals(9, b.fix(1, 2.0, 9.0)) // DUPLICATE (same seq)
        assertEquals(10, b.fix(2, 3.0, 9.0, segment = 5)) // WRONG_SEGMENT
        assertEquals(13, b.onFix("x", 0, 9, ms(4.0), 10.0, 106.0, 5.0, -1.0, -1.0, false, ms(4.0), false)) // WRONG_SESSION
    }

    @Test fun anEstimateOfAnotherSegmentIsRefusedThroughTheBridge() {
        val b = bridge().also { it.start("s", BASE_MS, 0) }
        b.fix(0, 0.0, 0.0)
        b.onTick(ms(15.0))
        b.submitPdr(3, ms(20.0), 50.0)
        assertEquals(0.0, b.snapshot().pendingPdrMeters)
        b.submitPdr(0, ms(20.0), 50.0)
        assertEquals(50.0, b.snapshot().pendingPdrMeters)
    }
}
