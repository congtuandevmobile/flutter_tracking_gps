package tracker

import tracker.algorithms.geometry.GeoMath
import tracker.engine.GpsDistanceEngine
import tracker.model.FixVerdict
import tracker.model.GpsQuality
import tracker.model.TrackingConfig
import tracker.model.TrackingFix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proofs of bugs found by the audit; each failed before the fix. */
class AuditProofTest {
    @Test fun standingStillThenMovingIsNotAGpsGap() {
        val e = GpsDistanceEngine().also { it.start("s", BASE_MS) }
        // 10 s of good fixes while standing (Doppler 0): the anchor is kept on purpose.
        for (i in 0..10) e.onFix(fixAt(i.toLong(), i.toDouble(), 0.0, speed = 0.0), ms(i.toDouble()))
        // Then the person walks 6 m: GPS never went away, so this is NOT a gap.
        val r = e.onFix(fixAt(11, 11.0, 6.0, speed = 3.0), ms(11.0))
        assertEquals(FixVerdict.ACCEPTED, r.verdict, "was ${r.verdict}")
        assertTrue(abs(e.totalMeters - 6.0) < 0.2, "total ${e.totalMeters}")
    }

    @Test fun aFixOfAnotherSessionIsRefused() {
        val e = GpsDistanceEngine().also { it.start("s", BASE_MS) }
        val other = fixAt(0, 0.0, 0.0).copy(sessionId = "other")
        assertEquals(FixVerdict.WRONG_SESSION, e.onFix(other, ms(0.0)).verdict)
    }

    @Test fun nonFiniteValuesAreInvalidNotUnknown() {
        val e = GpsDistanceEngine().also { it.start("s", BASE_MS) }
        val nan = TrackingFix("s", 0, 0, ms(0.0), 10.0, 106.0, horizontalAccuracy = Double.NaN)
        assertEquals(FixVerdict.INVALID, e.onFix(nan, ms(0.0)).verdict)
        val bad = TrackingFix("s", 0, 1, ms(1.0), Double.NaN, 106.0, horizontalAccuracy = 5.0)
        assertEquals(FixVerdict.INVALID, e.onFix(bad, ms(1.0)).verdict)
    }

    @Test fun distanceAcrossTheAntimeridianIsShort() {
        val d = GeoMath.haversineMeters(10.0, 179.9995, 10.0, -179.9995)
        assertTrue(d < 200.0, "was $d")
        val xy = GeoMath.toXY(10.0, 179.9995, 10.0, -179.9995)
        assertTrue(kotlin.math.abs(xy.x) < 200.0, "projection was ${xy.x}")
    }
}
