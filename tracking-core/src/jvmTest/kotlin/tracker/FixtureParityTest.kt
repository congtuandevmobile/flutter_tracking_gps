package tracker

import tracker.engine.GpsDistanceEngine
import tracker.model.TrackingConfig
import tracker.model.TrackingFix
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs every CSV file in `test/fixtures` through the engine. The Dart tests run the
 * SAME files against the Dart engine: both must land on the same total.
 *
 * Lines: fix,seq,segment,tSec,northMeters,accuracy,speed(empty=unknown)
 *        tick,tSec | pdr,tSec,meters | pause,tSec | resume,tSec,segment |
 *        finish,tSec | expect,totalMeters,toleranceMeters
 */
class FixtureParityTest {
    private val baseMs = 1_700_000_000_000L

    @Test
    fun everyFixtureHasTheExpectedTotal() {
        val dir = File("../test/fixtures")
        val files = dir.listFiles { f -> f.extension == "csv" }?.sortedBy { it.name }.orEmpty()
        assertTrue(files.isNotEmpty(), "no fixtures in ${dir.absolutePath}")
        for (file in files) {
            val engine = GpsDistanceEngine(TrackingConfig())
            engine.start("s", baseMs)
            var expectTotal: Double? = null
            var tolerance = 0.5
            for (raw in file.readLines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val p = line.split(",")
                fun t(i: Int) = baseMs + (p[i].toDouble() * 1000).toLong()
                when (p[0]) {
                    "fix" -> {
                        val fix = TrackingFix(
                            sessionId = "s", segmentId = p[2].toInt(), seq = p[1].toLong(), timeMs = t(3),
                            latitude = 10.0 + p[4].toDouble() / 111_195.0, longitude = 106.0,
                            horizontalAccuracy = p[5].toDouble(),
                            speed = p.getOrNull(6)?.takeIf { it.isNotEmpty() }?.toDouble(),
                        )
                        engine.onFix(fix, t(3))
                    }
                    "tick" -> engine.onTick(t(1))
                    "pdr" -> engine.submitPdr(engine.segmentId, t(1), p[2].toDouble())
                    "pause" -> engine.pause(t(1))
                    "resume" -> engine.resume(t(1), p[2].toInt())
                    "finish" -> engine.finish(t(1))
                    "expect" -> {
                        expectTotal = p[1].toDouble()
                        tolerance = p[2].toDouble()
                    }
                    else -> error("${file.name}: unknown line '$line'")
                }
            }
            val expected = checkNotNull(expectTotal) { "${file.name} has no expect line" }
            assertTrue(
                abs(engine.totalMeters - expected) <= tolerance,
                "${file.name}: expected $expected +/- $tolerance, was ${engine.totalMeters}\n${engine.log.report()}",
            )
        }
    }
}
