package tracker
import tracker.algorithms.motion.StepSession
import tracker.bridge.TrackingCoreBridge
import tracker.engine.GpsDistanceEngine
import tracker.model.*
import kotlin.test.*

class ReviewRegressionTest {
 private fun e()=GpsDistanceEngine(TrackingConfig(initialStrideMeters=1.0)).also{it.start("s",BASE_MS)}
 private fun b()=TrackingCoreBridge(40.0,30000,10000,10.0,2.0,0.5,0.4,120000,3,true,true,0.5,false,15.0,30000,1.0,false,30000,false,false).also{it.start("s",BASE_MS,0)}
 @Test fun closedCounterDoesNotAddSteps(){val s=StepSession();s.openSegment();s.onCounter(100);s.onCounter(130);s.closeSegment();s.onCounter(200);s.onCounter(250);assertEquals(30L,s.total)}
 @Test fun closedSegmentCountDoesNotAddSteps(){val s=StepSession();s.openSegment();s.onSegmentCount(30);s.closeSegment();s.onSegmentCount(50);assertEquals(30L,s.total)}
 @Test fun correctionBeforeAnyCloseIsIgnored(){val s=StepSession();s.reset(500);s.correctClosedSegment(34);assertEquals(500L,s.total)}
 @Test fun wrongSegmentDoesNotMutateBridgeTotal(){val b=b();b.onSegmentSteps(0,ms(1.0),ms(1.0),30);b.pause(ms(2.0));b.resume(ms(3.0),1);b.onSegmentSteps(0,ms(4.0),ms(4.0),100);assertEquals(30L,b.snapshot().steps);b.onSegmentSteps(1,ms(5.0),ms(5.0),5);assertEquals(35L,b.snapshot().steps)}
 @Test fun wrongCounterDoesNotSetBaselineOfNewSegment(){val b=b();b.pause(ms(1.0));b.resume(ms(2.0),1);b.onStepCounter(0,ms(3.0),ms(3.0),1000);b.onStepCounter(1,ms(4.0),ms(4.0),1500);b.onStepCounter(1,ms(5.0),ms(5.0),1510);assertEquals(10L,b.snapshot().steps)}
 @Test fun pausedBridgeDoesNotMutateTotal(){val b=b();b.onSegmentSteps(0,ms(1.0),ms(1.0),30);b.pause(ms(2.0));b.onSegmentSteps(0,ms(3.0),ms(3.0),50);assertEquals(30L,b.snapshot().steps)}
 @Test fun stepBeforeLegDoesNotMutateBridge(){val b=b();b.onSegmentSteps(0,BASE_MS-1,BASE_MS-1,30);assertEquals(0L,b.snapshot().steps)}
 @Test fun directStepsBeforeLegDoNotCreatePending(){val e=e();e.onSteps(0,BASE_MS-1,30);assertEquals(0.0,e.snapshot.pendingPdrMeters)}
 @Test fun shortWeakPeriodDoesNotLeakPendingIntoNextGap(){val e=e();e.onFix(fixAt(0,0.0,0.0),ms(0.0));e.onFix(fixAt(1,1.0,3.0,accuracy=90.0),ms(1.0));e.onSteps(0,ms(1.5),30);e.onFix(fixAt(2,2.0,6.0,speed=3.0),ms(2.0));assertEquals(0.0,e.snapshot.pendingPdrMeters);e.onTick(ms(13.0));e.onFix(fixAt(3,20.0,26.0,speed=3.0),ms(20.0));assertEquals(0.0,e.snapshot.pdrMeters);assertTrue(e.totalMeters in 15.9..16.1)}
 @Test fun jumpUsesMostRecentHealthyObservation(){val e=e();for(i in 0..60)e.onFix(fixAt(i.toLong(),i.toDouble(),0.0,speed=0.0),ms(i.toDouble()));val r=e.onFix(fixAt(61,61.0,100.0,speed=3.0),ms(61.0));assertEquals(FixVerdict.JUMP,r.verdict);assertEquals(0.0,e.totalMeters)}
 @Test fun shortRealOutageAfterLongStandstillCanBridge(){val e=e();for(i in 0..100)e.onFix(fixAt(i.toLong(),i.toDouble(),0.0,speed=0.0),ms(i.toDouble()));e.onTick(ms(115.0));val r=e.onFix(fixAt(101,125.0,50.0,speed=3.0),ms(125.0));assertEquals(FixVerdict.RECOVERED,r.verdict);assertTrue(r.addedMeters in 39.9..40.1)}
 @Test fun exactCorrectionStillWorksAfterDoubleClose(){val s=StepSession();s.openSegment();s.onSegmentCount(30);s.closeSegment();s.closeSegment();s.correctClosedSegment(34);assertEquals(34L,s.total)}
}
