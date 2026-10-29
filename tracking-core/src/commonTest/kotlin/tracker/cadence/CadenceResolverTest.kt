package tracker.cadence

import tracker.algorithms.cadence.CadenceResolver
import tracker.algorithms.cadence.CadenceResolverConfig
import tracker.algorithms.cadence.CadenceSource
import tracker.algorithms.cadence.CscCadenceParser
import tracker.algorithms.cadence.ExternalCadenceHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CadenceResolverTest {
    private val unknown = CscCadenceParser.UNKNOWN

    private fun CadenceResolver.healthyExternal(rpm: Double, from: Long = 0) {
        onExternalConnection(true)
        onExternal(rpm, from)
        onExternal(rpm, from + 1000)
    }

    @Test fun externalWinsOverPhone() {
        val r = CadenceResolver()
        r.onPhone(80.0, 2000)
        r.healthyExternal(85.0)
        val c = r.result(2000)
        assertEquals(CadenceSource.EXTERNAL, c.source)
        assertEquals(85.0, c.rpm, 0.0)
    }

    @Test fun externalZeroIsAMeasurementAndNeverFallsBackToThePhone() {
        val r = CadenceResolver()
        r.onPhone(78.0, 2000)
        r.healthyExternal(0.0)
        val c = r.result(2000)
        assertEquals(CadenceSource.EXTERNAL, c.source)
        assertEquals(0.0, c.rpm, 0.0)
    }

    @Test fun disconnectFallsBackToThePhoneAtOnce() {
        val r = CadenceResolver()
        r.healthyExternal(85.0)
        r.onPhone(78.0, 1500)
        r.onExternalConnection(false)
        val c = r.result(1600)
        assertEquals(CadenceSource.PHONE, c.source)
        assertEquals(78.0, c.rpm, 0.0)
    }

    @Test fun aStaleExternalFallsBackToThePhoneOrNothing() {
        val r = CadenceResolver()
        r.healthyExternal(85.0)
        r.onPhone(70.0, 6500)
        assertEquals(CadenceSource.PHONE, r.result(6500).source) // external last seen at 1000: 5.5 s ago
        r.onPhone(-1.0, 7000)
        val c = r.result(7000)
        assertEquals(CadenceSource.NONE, c.source)
        assertTrue(c.rpm < 0)
    }

    @Test fun aStalePhoneIsNotUsed() {
        val r = CadenceResolver()
        r.onPhone(80.0, 0)
        assertEquals(CadenceSource.PHONE, r.result(2900).source)
        assertEquals(CadenceSource.NONE, r.result(3100).source)
    }

    @Test fun aFreshReconnectNeedsMoreThanOneValueBeforeItIsPreferred() {
        val r = CadenceResolver()
        r.healthyExternal(85.0)
        r.onExternalConnection(false)
        r.onExternalConnection(true)
        r.onExternal(85.0, 10_000) // one value: only a baseline-grade sample
        r.onPhone(78.0, 10_000)
        assertEquals(CadenceSource.PHONE, r.result(10_000).source)
        r.onExternal(86.0, 10_400) // two values but not yet 1 s apart
        r.onPhone(78.0, 10_400)
        assertEquals(CadenceSource.PHONE, r.result(10_400).source)
        r.onExternal(86.0, 11_100)
        r.onPhone(78.0, 11_100)
        assertEquals(CadenceSource.EXTERNAL, r.result(11_100).source)
    }

    @Test fun aSpellWithoutDataMakesItEarnItsPlaceAgain() {
        val r = CadenceResolver()
        r.healthyExternal(85.0)
        r.onPhone(78.0, 9000)
        r.result(9000) // stale: dropped
        r.onExternal(85.0, 9100)
        r.onPhone(78.0, 9100)
        assertEquals(CadenceSource.PHONE, r.result(9100).source) // a single value after the gap
    }

    @Test fun anIntermittentSensorDoesNotFlapEveryNotification() {
        val r = CadenceResolver()
        r.healthyExternal(85.0)
        var switches = 0
        var last = CadenceSource.EXTERNAL
        var t = 2000L
        // A notification only every 3 s: shorter than the stale time, so the source must stay put.
        while (t < 30_000) {
            r.onExternal(85.0, t)
            r.onPhone(78.0, t)
            val s = r.result(t + 1000).source
            if (s != last) switches++
            last = s
            t += 3000
        }
        assertEquals(0, switches)
    }

    @Test fun dataIsIgnoredWhileDisconnected() {
        val r = CadenceResolver()
        r.onExternal(90.0, 0)
        r.onExternal(90.0, 1000)
        r.onPhone(70.0, 1000)
        assertEquals(CadenceSource.PHONE, r.result(1000).source)
    }

    @Test fun invalidValuesNeverBecomeACadence() {
        val r = CadenceResolver()
        r.onExternalConnection(true)
        r.onExternal(Double.NaN, 0)
        r.onExternal(-5.0, 1000)
        r.onPhone(Double.POSITIVE_INFINITY, 1000)
        assertEquals(CadenceSource.NONE, r.result(1000).source)
    }

    @Test fun resetForgetsEverything() {
        val r = CadenceResolver()
        r.healthyExternal(85.0)
        r.onPhone(78.0, 1000)
        r.reset()
        assertEquals(CadenceSource.NONE, r.result(1000).source)
    }

    @Test fun refusesAnImpossibleConfiguration() {
        assertFailsWith<IllegalArgumentException> { CadenceResolverConfig(externalRecoveryMinSamples = 0) }
        assertFailsWith<IllegalArgumentException> { CadenceResolverConfig(phoneStaleAfterMs = 0) }
    }

    // ---- the hub: identity, generation, raw CSC ----

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun crank(count: Int, ticks: Int): ByteArray = byteArrayOf(0x02) + u16(count) + u16(ticks)

    /** One revolution every 750 ms (80 rpm), a notification every [stepMs] ms. */
    private fun ExternalCadenceHub.ride(src: String, conn: Long, fromMs: Long, toMs: Long, stepMs: Long = 750) {
        var t = fromMs
        var count = 100
        var ticks = 1000
        while (t <= toMs) {
            onCsc(src, conn, crank(count, ticks), t)
            count += 1; ticks += 768; t += stepMs
        }
    }

    @Test fun hubChoosesTheCrankSensorOverThePhone() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 1, true)
        h.ride("sensor", 1, 0, 3000)
        val c = h.resolve(70.0, 3000)
        assertEquals(CadenceSource.EXTERNAL, c.source)
        assertEquals(80.0, c.rpm, 0.5)
    }

    @Test fun hubFallsBackToThePhoneWhenTheHostDisconnects() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 1, true)
        h.ride("sensor", 1, 0, 3000)
        h.onConnection("sensor", 1, false)
        assertEquals(CadenceSource.PHONE, h.resolve(70.0, 3100).source)
    }

    @Test fun hubDropsALateNotificationOfAnOldConnection() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 2, true)
        h.onCsc("sensor", 1, crank(1, 1), 0) // a late packet of connection 1
        h.onCsc("sensor", 1, crank(2, 769), 750)
        assertEquals(CadenceSource.NONE, h.resolve(-1.0, 800).source)
    }

    @Test fun hubIgnoresDataOfAClosedConnectionUntilANewOneOpens() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 1, true)
        h.ride("sensor", 1, 0, 3000)
        h.onConnection("sensor", 1, false)
        h.ride("sensor", 1, 3100, 6000)
        assertEquals(CadenceSource.NONE, h.resolve(-1.0, 6000).source)
        h.onConnection("sensor", 2, true)
        h.ride("sensor", 2, 7000, 10_000)
        assertEquals(CadenceSource.EXTERNAL, h.resolve(-1.0, 10_000).source)
    }

    @Test fun hubNeverComparesTheCountersOfTwoConnections() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 1, true)
        h.onCsc("sensor", 1, crank(65000, 45000), 0)
        h.onCsc("sensor", 1, crank(65001, 45768), 750)
        h.onConnection("sensor", 2, true)
        h.onCsc("sensor", 2, crank(5, 1200), 1500) // a device reset: only a baseline
        assertEquals(CadenceSource.NONE, h.resolve(-1.0, 1500).source)
    }

    @Test fun hubKeepsTheZeroOfAStillCrank() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 1, true)
        h.ride("sensor", 1, 0, 3000)
        var t = 3000L
        while (t < 8000) { t += 1000; h.onCsc("sensor", 1, crank(104, 1000 + 4 * 768), t) }
        val c = h.resolve(75.0, t)
        assertEquals(CadenceSource.EXTERNAL, c.source)
        assertEquals(0.0, c.rpm, 0.0)
    }

    @Test fun hubDoesNotReportZeroWhenTheNotificationsStop() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 1, true)
        h.ride("sensor", 1, 0, 3000)
        val c = h.resolve(-1.0, 10_000) // the sensor went quiet, still "connected"
        assertEquals(CadenceSource.NONE, c.source)
        assertTrue(c.rpm < 0)
    }

    @Test fun hubTakesARpmFromAnotherSourceIncludingZero() {
        val h = ExternalCadenceHub()
        h.onConnection("power", 1, true)
        h.onRpm("power", 1, 90.0, 0)
        h.onRpm("power", 1, 0.0, 1000)
        val c = h.resolve(70.0, 1000)
        assertEquals(CadenceSource.EXTERNAL, c.source)
        assertEquals(0.0, c.rpm, 0.0)
        h.onRpm("power", 1, 999.0, 1500) // not a cadence
        h.onRpm("power", 1, Double.NaN, 1600)
        assertEquals(0.0, h.resolve(70.0, 1600).rpm, 0.0)
    }

    @Test fun hubResetForgetsTheSource() {
        val h = ExternalCadenceHub()
        h.onConnection("sensor", 1, true)
        h.ride("sensor", 1, 0, 3000)
        assertTrue(h.hasExternalSource())
        h.reset()
        assertTrue(!h.hasExternalSource())
        assertEquals(CadenceSource.NONE, h.resolve(-1.0, 3100).source)
        assertEquals(unknown, h.resolve(-1.0, 3100).rpm, 0.0)
    }
}
