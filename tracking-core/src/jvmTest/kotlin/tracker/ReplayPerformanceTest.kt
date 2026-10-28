package tracker

import tracker.journal.BridgeSettings
import tracker.journal.JournalSink
import tracker.journal.SessionRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Why there is no checkpoint yet: replaying a long recording is cheap. Measured, not assumed. */
class ReplayPerformanceTest {
    private class MemSink : JournalSink {
        val lines = ArrayList<String>()
        override fun append(line: String) { lines.add(line) }
        override fun flush(): Boolean = true
    }

    @Test fun replayingATenHourRecordingAtOneHertzTakesWellUnderTheBudget() {
        val sink = MemSink()
        val settings = BridgeSettings(40.0, 30_000, 10_000, 10.0, 2.0, 0.5, 0.4, 120_000, 3, true, true, 0.5, false, 15.0, 30_000, -1.0, false, 30_000, false, false)
        val r = SessionRecorder.start(sink, settings, "s", BASE_MS, 0, 0, 0, emptyMap())
        val seconds = 36_000
        for (t in 0..seconds) {
            r.onFix("s", 0, t.toLong(), BASE_MS + t * 1000L, 0, 10.0 + t * 3.0 / 111_195.0, 106.0, 5.0, 3.0, -1.0, -1.0, 0.0, -1.0, "gps", false, BASE_MS + t * 1000L)
        }
        r.pause(BASE_MS + seconds * 1000L)
        val live = r.snapshot().totalMeters
        val start = System.nanoTime()
        val restored = SessionRecorder.restore(MemSink(), sink.lines)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("replay of ${sink.lines.size} records took $ms ms")
        assertEquals(live, assertNotNull(restored.recorder).snapshot().totalMeters)
        assertTrue(ms < 5_000, "replay took $ms ms")
    }
}
