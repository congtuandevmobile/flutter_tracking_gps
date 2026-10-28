package tracker

import tracker.algorithms.filtering.Kalman2D
import tracker.algorithms.filtering.LowPassFilter
import tracker.algorithms.geometry.DouglasPeucker
import tracker.algorithms.geometry.GeoMath
import tracker.algorithms.geometry.LatLon
import tracker.algorithms.motion.StopDetector
import tracker.algorithms.power.AdaptiveSamplingPolicy
import tracker.model.Smoothing
import tracker.model.TrackingConfig
import tracker.engine.GpsDistanceEngine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlgorithmsTest {
    @Test fun lowPassSmoothsAndRejectsBadInput() {
        val f = LowPassFilter(0.5)
        assertEquals(10.0, f.push(10.0))
        assertEquals(15.0, f.push(20.0))
        assertFailsWith<IllegalArgumentException> { f.push(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { LowPassFilter(1.5) }
    }

    @Test fun kalmanTracksAConstantVelocityBetterThanRawNoise() {
        val k = Kalman2D()
        val metersPerDegree = GeoMath.metersPerDegreeLat()
        var rawLength = 0.0
        var filtered = 0.0
        var prevRaw: Double? = null
        var prevFiltered: Double? = null
        for (i in 0..60) {
            val noise = if (i % 2 == 0) 4.0 else -4.0
            val truth = i * 3.0
            val rawNorth = truth + noise
            val out = k.push(10.0 + rawNorth / metersPerDegree, 106.0, 8.0, BASE_MS + i * 1000L)
            val fNorth = (out.latitude - 10.0) * metersPerDegree
            prevRaw?.let { rawLength += abs(rawNorth - it) }
            prevFiltered?.let { filtered += abs(fNorth - it) }
            prevRaw = rawNorth
            prevFiltered = fNorth
        }
        assertTrue(filtered < rawLength, "filtered $filtered should be shorter than raw $rawLength")
        assertTrue(abs(filtered - 180.0) < 15.0, "filtered path was $filtered, truth 180")
    }

    @Test fun kalmanGatesOutliersThenReLocalizes() {
        val k = Kalman2D(resetAfterRejects = 3)
        for (i in 0..10) k.push(10.0, 106.0, 5.0, BASE_MS + i * 1000L)
        assertTrue(!k.push(10.01, 106.0, 5.0, BASE_MS + 11_000L).accepted)
        k.push(10.01, 106.0, 5.0, BASE_MS + 12_000L)
        val third = k.push(10.01, 106.0, 5.0, BASE_MS + 13_000L)
        assertTrue(third.discontinuity)
    }

    @Test fun kalmanIsAGateOnlyAndNeverSuppliesTheCoordinates() {
        val raw = GpsDistanceEngine(TrackingConfig())
        val gated = GpsDistanceEngine(TrackingConfig(smoothing = Smoothing.KALMAN))
        for (e in listOf(raw, gated)) e.start("s", BASE_MS)
        for (i in 0..30) {
            val fix = fixAt(i.toLong(), i.toDouble(), i * 3.0, speed = 3.0)
            raw.onFix(fix, ms(i.toDouble()))
            gated.onFix(fix, ms(i.toDouble()))
        }
        // Same raw fixes, same distance: the filter must not cut the path.
        assertEquals(raw.totalMeters, gated.totalMeters, "gate changed the distance")
    }

    @Test fun kalmanReportsAGapResetAsADiscontinuityAndValidatesInput() {
        val k = Kalman2D(maxGapMs = 3_000)
        k.push(10.0, 106.0, 3.0, 1_000)
        val gap = k.push(10.0, 106.1, 3.0, 5_001)
        assertTrue(gap.discontinuity)
        assertEquals(Kalman2D.Status.GAP_RESET, gap.status)
        assertEquals(Kalman2D.Status.OUT_OF_ORDER, k.push(10.0, 106.1, 3.0, 5_001).status)
        assertFailsWith<IllegalArgumentException> { k.push(10.0, 106.0, Double.NaN, 6_000) }
        assertFailsWith<IllegalArgumentException> { k.push(91.0, 106.0, 3.0, 6_000) }
        assertFailsWith<IllegalArgumentException> { Kalman2D(maxGapMs = 0) }
    }

    @Test fun geoMathWrapsTheAntimeridianAndRefusesThePoles() {
        val xy = tracker.algorithms.geometry.GeoMath.toXY(10.0, 179.9995, 10.0, -179.9995)
        assertTrue(abs(xy.x) < 200.0, "was ${xy.x}")
        val back = tracker.algorithms.geometry.GeoMath.fromXY(10.0, 179.9995, xy)
        assertTrue(abs(back.second - (-179.9995)) < 1e-6, "was ${back.second}")
        assertFailsWith<IllegalArgumentException> { tracker.algorithms.geometry.GeoMath.toXY(90.0, 0.0, 89.9, 0.0) }
        assertFailsWith<IllegalArgumentException> { tracker.algorithms.geometry.GeoMath.haversineMeters(Double.NaN, 0.0, 0.0, 0.0) }
    }

    @Test fun outlierRejectorTreatsInvalidMeasurementsAsJumps() {
        assertTrue(tracker.algorithms.filtering.OutlierRejector.isJump(Double.NaN, 1.0, 10.0))
        assertTrue(tracker.algorithms.filtering.OutlierRejector.isJump(5.0, 0.0, 10.0))
        assertTrue(!tracker.algorithms.filtering.OutlierRejector.isJump(5.0, 1.0, 10.0))
    }

    @Test fun gapReconcilerNeverAcceptsAHugeEstimateOnATinyChord() {
        val cfg = TrackingConfig()
        // 200 m of "steps" but GPS moved only 12 m (minus 10 uncertainty): capped, not accepted.
        val r = tracker.algorithms.fusion.GapReconciler.reconcile(12.0, 10.0, 20_000, 200.0, cfg)
        assertTrue(r.creditMeters <= 2.0 / cfg.pdrConfirmRatio + 1e-9, "was ${r.creditMeters}")
        val invalid = tracker.algorithms.fusion.GapReconciler.reconcile(Double.NaN, 1.0, 5_000, 0.0, cfg)
        assertEquals(0.0, invalid.creditMeters)
    }

    @Test fun stopDetectorNeedsBothDwellAndNoMotion() {
        val d = StopDetector(radiusMeters = 15.0, minDwellMs = 30_000)
        for (i in 0..40) d.push(BASE_MS + i * 1000L, 10.0, 106.0, 0.0)
        assertTrue(d.isStopped)
        // inside the radius but Doppler says 2 m/s: that is motion, not a stop
        d.push(BASE_MS + 41_000L, 10.0, 106.0, 2.0)
        assertTrue(!d.isStopped)
    }

    @Test fun adaptiveSamplingTreatsAnInvalidSpeedAndABackwardsClockAsMoving() {
        val p = AdaptiveSamplingPolicy(batterySaver = false, stillAfterMs = 20_000)
        p.plan(0.0, 0)
        assertEquals(1_000, p.plan(Double.NaN, 30_000).intervalMs)
        p.plan(0.0, 40_000)
        assertEquals(1_000, p.plan(0.0, 10_000).intervalMs, "a clock that went back resets the timer")
    }

    @Test fun invalidConfigurationIsRefused() {
        assertFailsWith<IllegalArgumentException> { TrackingConfig(pdrConfirmRatio = 0.0) }
        assertFailsWith<IllegalArgumentException> { TrackingConfig(maxAccuracyMeters = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { TrackingConfig(silenceAfterMs = 0) }
        assertEquals(
            tracker.model.PdrPolicy.OFF,
            TrackingConfig.forSport(tracker.model.Sport.CYCLING).pdrPolicy,
        )
        assertFailsWith<IllegalArgumentException> { tracker.engine.TrackingLog(0) }
        assertFailsWith<IllegalArgumentException> { tracker.engine.DistanceLedger().add(0, tracker.model.DistanceSource.GPS, -1.0, 0) }
    }

    @Test fun douglasPeuckerDropsCollinearPointsAndKeepsCorners() {
        val line = List(11) { LatLon(10.0 + it * 0.0001, 106.0) }
        assertEquals(2, DouglasPeucker.simplify(line, 1.0).size)
        val corner = listOf(LatLon(10.0, 106.0), LatLon(10.001, 106.0), LatLon(10.001, 106.001))
        assertEquals(3, DouglasPeucker.simplify(corner, 1.0).size)
        val zigzag = List(7) { LatLon(10.0 + it * 0.0001, 106.0 + (if (it % 2 == 0) 0.0 else 0.0001)) }
        assertEquals(7, DouglasPeucker.simplify(zigzag, 0.0).size)
        assertFailsWith<IllegalArgumentException> { DouglasPeucker.simplify(line, -1.0) }
    }

    @Test fun stopDetectorNeedsAContinuousStayAndEndsOnLeaving() {
        val d = StopDetector(radiusMeters = 15.0, minDwellMs = 30_000)
        for (i in 0..40) d.push(BASE_MS + i * 1000L, 10.0, 106.0, 0.0)
        assertTrue(d.isStopped)
        d.push(BASE_MS + 41_000L, 10.001, 106.0, 3.0)
        assertTrue(!d.isStopped)
    }

    @Test fun stopDetectorIgnoresAPassBySamePlaceTwice() {
        val d = StopDetector(radiusMeters = 15.0, minDwellMs = 30_000)
        d.push(BASE_MS, 10.0, 106.0, 3.0)
        d.push(BASE_MS + 10_000L, 10.01, 106.0, 3.0) // far away
        d.push(BASE_MS + 600_000L, 10.0, 106.0, 3.0) // back, ten minutes later
        assertTrue(!d.isStopped)
    }

    @Test fun adaptiveSamplingNeverMakesSparseFixesLookLikeAnOutage() {
        val p = AdaptiveSamplingPolicy(batterySaver = true, stillAfterMs = 20_000)
        assertEquals(1_000, p.plan(3.0, 0).intervalMs)
        assertEquals(1_000, p.plan(0.0, 1_000).intervalMs) // hysteresis: not still yet
        val sparse = p.plan(0.0, 30_000)
        assertEquals(5_000, sparse.intervalMs)
        assertTrue(sparse.silenceAfterMs() >= sparse.intervalMs * 4)
        assertEquals(1_000, p.plan(3.0, 31_000).intervalMs) // moves again
    }
}
