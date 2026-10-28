package tracker

import tracker.engine.GpsDistanceEngine
import tracker.journal.*
import tracker.model.*
import kotlin.test.*

private class AuditSink : JournalSink {
    val lines = ArrayList<String>()
    override fun append(line: String) { lines.add(line) }
    override fun flush(): Boolean = true
}
private fun auditSettings() = BridgeSettings(40.0,30000,10000,10.0,2.0,0.5,0.4,120000,3,true,true,0.5,false,15.0,30000,1.0,false,30000,false,false)

class AuditRecheckTest {
    @Test fun commaInNotificationTextMustRoundTrip() {
        val h = JournalRecord.Header(1,1,"s",BASE_MS,0,0,0,auditSettings(),mapOf("text" to "Running, tap to return"))
        assertEquals(h, JournalCodec.decode(JournalCodec.encode(h)))
    }
    @Test fun ordinaryTicksMustSurviveReplayAsActiveTime() {
        val sink = AuditSink()
        val r = SessionRecorder.start(sink,auditSettings(),"s",BASE_MS,0,0,0,emptyMap())
        r.onTick(ms(1.0)); r.onTick(ms(2.0))
        val replay = assertNotNull(SessionRecorder.reprocess(sink.lines))
        assertEquals(r.snapshot().activeTimeMs,replay.activeTimeMs,"live active time vs replay")
    }
    @Test fun continuedOutageTicksMustSurviveCrashRestore() {
        val sink = AuditSink()
        val r = SessionRecorder.start(sink,auditSettings(),"s",BASE_MS,0,0,0,emptyMap())
        for (t in 1..60) r.onTick(ms(t.toDouble()))
        val live = r.snapshot().activeTimeMs
        val restored = assertNotNull(SessionRecorder.restore(AuditSink(),sink.lines).recorder).snapshot()
        assertEquals(live,restored.activeTimeMs,"60 seconds known alive must survive")
    }
    @Test fun weakStartThatEventuallyGetsGoodGpsMustRemainVisible() {
        val e = GpsDistanceEngine().also { it.start("s",BASE_MS) }
        e.onTick(ms(15.0))
        e.onFix(fixAt(0,60.0,90.0,speed=3.0),ms(60.0))
        e.finish(ms(61.0))
        assertEquals(60000L,e.snapshot.uncreditedGapMs)
    }
    @Test fun weakResumeThatEventuallyGetsGoodGpsMustRemainVisible() {
        val e = GpsDistanceEngine().also { it.start("s",BASE_MS) }
        e.onFix(fixAt(0,0.0,0.0,speed=3.0),ms(0.0));e.pause(ms(1.0));e.resume(ms(10.0),1)
        e.onTick(ms(25.0))
        e.onFix(fixAt(1,70.0,90.0,segment=1,speed=3.0),ms(70.0))
        e.finish(ms(71.0))
        assertEquals(60000L,e.snapshot.uncreditedGapMs)
    }
    @Test fun preLegMeasurementDeliveredInNewLegMustNotMutateSteps() {
        val b=auditSettings().toBridge();b.start("s",BASE_MS,0)
        b.pause(ms(1.0));b.resume(ms(10.0),1)
        b.onSegmentSteps(1,ms(5.0),ms(11.0),30)
        assertEquals(0L,b.snapshot().steps)
    }
    @Test fun lateBatchFromOldWeakIntervalMustNotAttachToNewGap() {
        val e=GpsDistanceEngine(TrackingConfig(initialStrideMeters=1.0)).also { it.start("s",BASE_MS) }
        e.onFix(fixAt(0,0.0,0.0,speed=3.0),ms(0.0))
        e.onFix(fixAt(1,10.0,30.0,speed=3.0),ms(10.0))
        e.onFix(fixAt(2,12.0,33.0,accuracy=90.0),ms(12.0))
        e.onFix(fixAt(3,16.0,48.0,speed=3.0),ms(16.0))
        e.onTick(ms(27.0))
        e.onSteps(0,ms(11.0),ms(14.0),ms(28.0),30)
        assertEquals(0.0,e.snapshot.pendingPdrMeters,"old gap steps cannot fund new gap")
    }
    @Test fun stationaryVerdictWithUnknownSpeedMustNotAddMovingTime() {
        val e=GpsDistanceEngine().also { it.start("s",BASE_MS) }
        e.onFix(fixAt(0,0.0,0.0),ms(0.0))
        val v=e.onFix(fixAt(1,8.0,0.0),ms(8.0)).verdict
        assertEquals(FixVerdict.STATIONARY,v)
        assertEquals(0L,e.snapshot.movingTimeMs)
    }
    @Test fun oldGapEvidenceMustNotDefeatTheNextGapsNoStepPolicy() {
        val e=GpsDistanceEngine(TrackingConfig(initialStrideMeters=1.0)).also { it.start("s",BASE_MS); it.setStepSensorActive(true) }
        e.onFix(fixAt(0,0.0,0.0,speed=3.0),ms(0.0))
        e.onFix(fixAt(1,10.0,30.0,speed=3.0),ms(10.0))
        e.onFix(fixAt(2,12.0,33.0,accuracy=90.0),ms(12.0))
        e.onSteps(0,ms(11.0),ms(14.0),ms(15.0),6)
        e.onFix(fixAt(3,16.0,48.0,speed=3.0),ms(16.0))
        val before=e.totalMeters
        e.onTick(ms(27.0))
        e.onFix(fixAt(4,60.0,98.0,speed=3.0),ms(60.0))
        assertEquals(before,e.totalMeters,"no NEW step events in this long gap")
    }

}
