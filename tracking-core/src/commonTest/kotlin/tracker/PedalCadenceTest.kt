package tracker

import tracker.algorithms.motion.PedalCadenceEstimator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PedalCadenceTest {
    /** A small deterministic generator, so the tests never flake. */
    private class Noise(seed: Long) {
        private var s = seed
        fun next(): Double { // uniform in [-1, 1)
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
    }

    /**
     * Feeds [seconds] of a leg-mounted phone at ~50 Hz: gravity plus a periodic motion at [rpm] on a tilted axis,
     * with an optional second harmonic and noise. Returns the next time to continue from.
     */
    private fun feed(
        e: PedalCadenceEstimator, fromMs: Long, seconds: Int, rpm: Double, amplitude: Double = 1.5,
        harmonic: Double = 0.0, noise: Double = 0.2, seed: Long = 7,
    ): Long {
        val rnd = Noise(seed + fromMs)
        val w = 2.0 * PI * rpm / 60.0
        var t = fromMs
        val end = fromMs + seconds * 1000L
        while (t < end) {
            val tt = t / 1000.0
            val s = amplitude * (sin(w * tt) + harmonic * sin(2.0 * w * tt + 0.7))
            // The motion is spread over the three axes (an unknown orientation); gravity is constant.
            e.push(t, 0.6 * s + noise * rnd.next(), -0.5 * s + noise * rnd.next(), 9.81 + 0.62 * s + noise * rnd.next())
            t += 20 + (rnd.next() * 2).toLong() // 20 ms with a little jitter
            if (t == fromMs) t++
        }
        return t
    }

    private fun readEverySecond(e: PedalCadenceEstimator, from: Long, to: Long): Double {
        var last = PedalCadenceEstimator.UNKNOWN
        var t = from
        while (t <= to) { last = e.rpm(t); t += 1000 }
        return last
    }

    @Test fun findsTheCadenceOfASteadyRhythm() {
        for (target in listOf(60.0, 75.0, 90.0, 110.0)) {
            val e = PedalCadenceEstimator()
            val end = feed(e, 0, 20, target)
            val rpm = readEverySecond(e, 9_000, end)
            assertTrue(abs(rpm - target) <= 3.0, "target $target got $rpm")
        }
    }

    @Test fun keepsTheFundamentalWhenTheSecondHarmonicIsStrong() {
        val e = PedalCadenceEstimator()
        val end = feed(e, 0, 20, 90.0, harmonic = 0.7)
        val rpm = readEverySecond(e, 9_000, end)
        assertTrue(abs(rpm - 90.0) <= 4.0, "got $rpm")
    }

    @Test fun doesNotGuessWhileWarmingUp() {
        val e = PedalCadenceEstimator()
        feed(e, 0, 4, 90.0)
        assertEquals(PedalCadenceEstimator.UNKNOWN, e.rpm(4_000))
    }

    @Test fun reportsUnknownWithoutPedallingMotion() {
        val e = PedalCadenceEstimator()
        val end = feed(e, 0, 20, 90.0, amplitude = 0.0, noise = 0.05)
        assertEquals(PedalCadenceEstimator.UNKNOWN, readEverySecond(e, 9_000, end))
    }

    @Test fun reportsUnknownForNoiseThatIsNotPeriodic() {
        val e = PedalCadenceEstimator()
        val end = feed(e, 0, 20, 90.0, amplitude = 0.0, noise = 3.0)
        assertEquals(PedalCadenceEstimator.UNKNOWN, readEverySecond(e, 9_000, end))
    }

    @Test fun holdsAValueBrieflyThenForgetsIt() {
        val e = PedalCadenceEstimator()
        val end = feed(e, 0, 20, 90.0)
        assertTrue(readEverySecond(e, 9_000, end) > 0)
        // The legs stop: no periodic signal. The last good estimate is held for 5 s, then it is unknown.
        val shortly = feed(e, end, 3, 90.0, amplitude = 0.0, noise = 0.05)
        assertTrue(e.rpm(shortly) > 0, "held shortly after the last estimate")
        val coasting = feed(e, shortly, 9, 90.0, amplitude = 0.0, noise = 0.05)
        assertEquals(PedalCadenceEstimator.UNKNOWN, readEverySecond(e, shortly, coasting))
    }

    @Test fun followsAChangeOfRhythm() {
        val e = PedalCadenceEstimator()
        var t = feed(e, 0, 15, 70.0)
        readEverySecond(e, 9_000, t)
        t = feed(e, t, 25, 100.0)
        val rpm = readEverySecond(e, 16_000, t)
        assertTrue(abs(rpm - 100.0) <= 5.0, "got $rpm")
    }

    @Test fun aStaleFeedIsUnknown() {
        val e = PedalCadenceEstimator()
        val end = feed(e, 0, 15, 90.0)
        assertTrue(readEverySecond(e, 9_000, end) > 0)
        assertEquals(PedalCadenceEstimator.UNKNOWN, e.rpm(end + 5_000))
    }

    @Test fun resetForgetsEverything() {
        val e = PedalCadenceEstimator()
        val end = feed(e, 0, 15, 90.0)
        assertTrue(readEverySecond(e, 9_000, end) > 0)
        e.reset()
        assertEquals(PedalCadenceEstimator.UNKNOWN, e.rpm(end))
    }

    @Test fun ignoresInvalidAndOutOfOrderSamples() {
        val e = PedalCadenceEstimator()
        e.push(1_000, Double.NaN, 0.0, 0.0)
        e.push(-5, 1.0, 1.0, 1.0)
        e.push(2_000, 1.0, 1.0, 1.0)
        e.push(1_500, 9.0, 9.0, 9.0) // older than the last: ignored
        e.push(2_000, 9.0, 9.0, 9.0) // not newer: ignored
        assertEquals(PedalCadenceEstimator.UNKNOWN, e.rpm(2_000))
    }

    @Test fun survivesMoreSamplesThanItsBuffer() {
        val e = PedalCadenceEstimator()
        // 100 Hz for 60 s is 6000 samples, more than the ring holds: the newest window must still decide.
        val rnd = Noise(3)
        val w = 2.0 * PI * 80.0 / 60.0
        var t = 0L
        while (t < 60_000) {
            val s = 1.5 * cos(w * t / 1000.0)
            e.push(t, 0.7 * s + 0.1 * rnd.next(), 0.7 * s + 0.1 * rnd.next(), 9.8 + 0.1 * rnd.next())
            t += 10
        }
        val rpm = readEverySecond(e, 50_000, 60_000)
        assertTrue(abs(rpm - 80.0) <= 3.0, "got $rpm")
    }

    /** Like [feed] but the motion direction (unit vector, tilted by [yaw] about the vertical) can change mid-recording. */
    private fun feedRotated(e: PedalCadenceEstimator, fromMs: Long, seconds: Int, rpm: Double, yaw: Double): Long {
        val rnd = Noise(11 + fromMs)
        val w = 2.0 * PI * rpm / 60.0
        var t = fromMs
        val end = fromMs + seconds * 1000L
        while (t < end) {
            val s = 1.5 * sin(w * t / 1000.0)
            e.push(t, s * cos(yaw) + 0.2 * rnd.next(), s * sin(yaw) + 0.2 * rnd.next(), 9.81 + 0.2 * rnd.next())
            t += 20
        }
        return t
    }

    @Test fun lowCadencesAreNotReadAsTheirDouble() {
        for (target in listOf(35.0, 40.0, 45.0, 50.0)) {
            val e = PedalCadenceEstimator()
            val end = feed(e, 0, 30, target, harmonic = 0.5)
            val rpm = readEverySecond(e, 9_000, end)
            // Either right or unknown: a confident double (or half) is the failure.
            assertTrue(rpm < 0 || abs(rpm - target) <= 4.0, "target $target got $rpm")
        }
    }

    @Test fun lowCadenceIsFoundWithEnoughData() {
        val e = PedalCadenceEstimator()
        val end = feed(e, 0, 30, 40.0)
        val rpm = readEverySecond(e, 9_000, end)
        assertTrue(abs(rpm - 40.0) <= 4.0, "got $rpm")
    }

    @Test fun coastingWithRoadVibrationStopsShowingTheOldRhythm() {
        val e = PedalCadenceEstimator()
        var t = feed(e, 0, 15, 80.0)
        assertTrue(readEverySecond(e, 9_000, t) > 0)
        // Legs stop; the phone still shakes (non periodic noise).
        val end = feed(e, t, 12, 80.0, amplitude = 0.0, noise = 1.0)
        val rpm = readEverySecond(e, t, end)
        assertEquals(PedalCadenceEstimator.UNKNOWN, rpm, "still showing $rpm after 12 s of coasting")
    }

    @Test fun resumesAtANewRhythmAfterCoasting() {
        val e = PedalCadenceEstimator()
        var t = feed(e, 0, 15, 80.0)
        readEverySecond(e, 9_000, t)
        t = feed(e, t, 10, 80.0, amplitude = 0.0, noise = 1.0)
        val end = feed(e, t, 20, 65.0)
        val rpm = readEverySecond(e, t, end)
        assertTrue(abs(rpm - 65.0) <= 4.0, "got $rpm")
    }

    @Test fun aRotationOfThePhoneNeverCreatesADoubledCadence() {
        for (yaw in listOf(PI / 6, PI / 4, PI / 2, PI)) {
            val e = PedalCadenceEstimator()
            var t = feedRotated(e, 0, 15, 80.0, 0.0)
            var worst = 0.0
            // The rotation happens between two samples (a pocket shift); read every second afterwards.
            val end = feedRotated(e, t, 20, 80.0, yaw)
            var n = t
            while (n <= end) { val r = e.rpm(n); if (r > 0) worst = maxOf(worst, abs(r - 80.0)); n += 1000 }
            assertTrue(worst <= 8.0, "yaw $yaw: worst error $worst")
            val last = e.rpm(end)
            assertTrue(abs(last - 80.0) <= 4.0, "yaw $yaw: last $last")
        }
    }

    @Test fun aGapInTheFeedIsUnknownThenRecovers() {
        val e = PedalCadenceEstimator()
        var t = feed(e, 0, 15, 80.0)
        assertTrue(readEverySecond(e, 9_000, t) > 0)
        assertEquals(PedalCadenceEstimator.UNKNOWN, e.rpm(t + 5_000))
        val end = feed(e, t + 5_000, 20, 80.0)
        assertTrue(abs(readEverySecond(e, t + 5_000, end) - 80.0) <= 4.0)
    }

    @Test fun refusesAnImpossibleConfiguration() {
        assertFailsWith<IllegalArgumentException> { PedalCadenceEstimator(windowMs = 500) }
        assertFailsWith<IllegalArgumentException> { PedalCadenceEstimator(minRpm = 100.0, maxRpm = 50.0) }
        assertFailsWith<IllegalArgumentException> { PedalCadenceEstimator(minCorrelation = 1.5) }
    }
}
