package tracker

import tracker.algorithms.geometry.GeoMath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GeoMathTest {
    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        val d = GeoMath.haversineMeters(10.0, 106.0, 11.0, 106.0)
        assertTrue(abs(d - 111_195.0) < 50.0, "was $d")
    }

    @Test
    fun samePointIsZero() {
        assertTrue(GeoMath.haversineMeters(10.0, 106.0, 10.0, 106.0) == 0.0)
    }
}
