package tracker.cadence

import tracker.algorithms.cadence.CscCadenceParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CscCadenceParserTest {
    private val unknown = CscCadenceParser.UNKNOWN

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun crank(count: Int, ticks: Int): ByteArray = byteArrayOf(0x02) + u16(count) + u16(ticks)
    private fun wheel(revs: Long, ticks: Int): ByteArray =
        byteArrayOf(0x01, revs.toByte(), (revs shr 8).toByte(), (revs shr 16).toByte(), (revs shr 24).toByte()) + u16(ticks)
    private fun both(revs: Long, wheelTicks: Int, count: Int, ticks: Int): ByteArray =
        byteArrayOf(0x03) + wheel(revs, wheelTicks).copyOfRange(1, 7) + u16(count) + u16(ticks)

    @Test fun crankOnlyGivesTheRpm() {
        val p = CscCadenceParser()
        assertEquals(unknown, p.onMeasurement(crank(100, 1000), 0))
        assertEquals(80.0, p.onMeasurement(crank(101, 1768), 750), 0.001)
    }

    @Test fun wheelOnlyHasNoCadenceAndTouchesNothing() {
        val p = CscCadenceParser()
        assertNull(CscCadenceParser.decodeCrank(wheel(10, 100)))
        assertEquals(unknown, p.onMeasurement(wheel(10, 100), 0))
        assertEquals(unknown, p.rpm(0)) // no baseline was taken
    }

    @Test fun wheelPlusCrankReadsTheCrankAtTheRightOffset() {
        val decoded = assertNotNull(CscCadenceParser.decodeCrank(both(123456, 777, 4321, 2468)))
        assertEquals(4321, decoded[0])
        assertEquals(2468, decoded[1])
        val p = CscCadenceParser()
        p.onMeasurement(both(1, 10, 100, 1000), 0)
        assertEquals(80.0, p.onMeasurement(both(2, 20, 101, 1768), 750), 0.001)
    }

    @Test fun truncatedAndEmptyPacketsAreRejected() {
        assertNull(CscCadenceParser.decodeCrank(byteArrayOf()))
        assertNull(CscCadenceParser.decodeCrank(byteArrayOf(0x02, 10, 0)))
        assertNull(CscCadenceParser.decodeCrank(byteArrayOf(0x03, 1, 2, 3, 4, 5, 6, 7, 8)))
        assertEquals(unknown, CscCadenceParser().onMeasurement(byteArrayOf(0x02, 1), 0))
    }

    @Test fun counterRolloverDoesNotMakeASpike() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(65535, 1000), 0)
        assertEquals(80.0, p.onMeasurement(crank(0, 1768), 750), 0.001)
    }

    @Test fun eventTimeRolloverStillGivesTheRpm() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(100, 65500), 0)
        assertEquals(80.0, p.onMeasurement(crank(101, 732), 750), 0.001)
    }

    @Test fun twoRevolutionsBetweenNotificationsAreAveraged() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(10, 0), 0)
        assertEquals(80.0, p.onMeasurement(crank(12, 1536), 1500), 0.001)
    }

    @Test fun anUnchangedCounterKeepsTheValueThenBecomesZero() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(100, 1000), 0)
        assertEquals(80.0, p.onMeasurement(crank(101, 1768), 750), 0.001)
        assertEquals(80.0, p.onMeasurement(crank(101, 1768), 1750), 0.001) // between two revolutions: still pedalling
        assertEquals(80.0, p.onMeasurement(crank(101, 1768), 2750), 0.001)
        assertEquals(CscCadenceParser.ZERO, p.onMeasurement(crank(101, 1768), 3800), 0.0)
    }

    @Test fun aSlowCadenceIsNotZeroBetweenTwoRevolutions() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(0, 0), 0)
        // 20 rpm = 3 s per revolution; notifications every second repeat the counter in between.
        assertEquals(20.0, p.onMeasurement(crank(1, 3072), 3000), 0.001)
        assertEquals(20.0, p.onMeasurement(crank(1, 3072), 4000), 0.001)
        assertEquals(20.0, p.onMeasurement(crank(1, 3072), 5000), 0.001)
        assertEquals(20.0, p.onMeasurement(crank(2, 6144), 6000), 0.001)
    }

    @Test fun silenceIsUnknownNeverZero() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(100, 1000), 0)
        p.onMeasurement(crank(101, 1768), 750)
        assertEquals(80.0, p.rpm(2000), 0.001)
        assertEquals(unknown, p.rpm(6000)) // no notification for more than the stale time
        assertEquals(unknown, p.rpm(60_000))
    }

    @Test fun coastingForMoreThanTheEventClockSpanNeverInventsAnRpm() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(100, 1000), 0)
        p.onMeasurement(crank(101, 1768), 750)
        var t = 750L
        while (t < 66_750) { t += 1000; p.onMeasurement(crank(101, 1768), t) }
        // The true event gap is 66 s = 67584 ticks, which a 16-bit clock reads as 2048 ticks (a fake 30 rpm).
        assertEquals(unknown, p.onMeasurement(crank(102, (1768 + 67584) and 0xFFFF), t + 500))
    }

    @Test fun theFirstRevolutionAfterAShortCoastIsTooSlowToTrustThenItRecovers() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(100, 1000), 0)
        p.onMeasurement(crank(101, 1768), 750)
        var t = 750L
        while (t < 8_750) { t += 1000; p.onMeasurement(crank(101, 1768), t) }
        assertEquals(unknown, p.onMeasurement(crank(102, 1768 + 8800), t + 500)) // 6 rpm average: not a cadence
        assertEquals(80.0, p.onMeasurement(crank(103, 1768 + 8800 + 768), t + 1250), 0.001)
    }

    @Test fun aCounterThatJumpsBackwardsIsAResetNotAnRpm() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(500, 1000), 0)
        p.onMeasurement(crank(501, 1768), 750)
        assertEquals(unknown, p.onMeasurement(crank(3, 2536), 1500))
        assertEquals(80.0, p.onMeasurement(crank(4, 3304), 2250), 0.001) // a new baseline, then it works
    }

    @Test fun aNewConnectionNeverUsesTheLastSampleOfTheOldOne() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(65000, 45000), 0)
        p.onMeasurement(crank(65001, 45768), 750)
        p.reset() // the host reconnected
        assertEquals(unknown, p.onMeasurement(crank(5, 1200), 1000)) // baseline only
        assertEquals(unknown, p.rpm(1000))
    }

    @Test fun aLongSilenceStartsOver() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(100, 1000), 0)
        p.onMeasurement(crank(101, 1768), 750)
        assertEquals(unknown, p.onMeasurement(crank(150, 9000), 20_000)) // baseline after the silence
    }

    @Test fun aClockThatGoesBackwardsResets() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(100, 1000), 5_000)
        p.onMeasurement(crank(101, 1768), 5_750)
        assertEquals(unknown, p.onMeasurement(crank(102, 2536), 100))
        assertEquals(unknown, p.rpm(100)) // the notification at 100 ms was only the new baseline
    }

    @Test fun implausibleCadencesAreRejected() {
        val p = CscCadenceParser()
        p.onMeasurement(crank(0, 0), 0)
        assertEquals(unknown, p.onMeasurement(crank(1, 10), 700)) // 6000 rpm
        p.reset()
        p.onMeasurement(crank(0, 0), 0)
        assertEquals(unknown, p.onMeasurement(crank(0, 0), 100)) // still, but not yet zero
        assertEquals(unknown, p.onMeasurement(crank(1, 0), 700)) // no event time progress: no division by zero
    }

    @Test fun outOfRangeInputIsIgnored() {
        val p = CscCadenceParser()
        assertEquals(unknown, p.onCrank(70000, 0, 0))
        assertEquals(unknown, p.onCrank(0, -1, 0))
        assertEquals(unknown, p.onCrank(0, 0, -5))
    }

    @Test fun refusesAnImpossibleConfiguration() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { CscCadenceParser(minRpm = 100.0, maxRpm = 50.0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { CscCadenceParser(staleMs = 0) }
    }
}
