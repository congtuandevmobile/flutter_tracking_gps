package tracker.journal

import tracker.bridge.TrackingCoreBridge

/**
 * Everything the engine was configured with, as primitives. Stored in the journal
 * header so a restore replays with the SAME configuration the session ran with,
 * whatever the app passes later.
 */
data class BridgeSettings(
    val maxAccuracyMeters: Double,
    val maxFixAgeMs: Long,
    val silenceAfterMs: Long,
    val maxSpeedMps: Double,
    val minStepMeters: Double,
    val accuracyNoiseFactor: Double,
    val stationarySpeedMps: Double,
    val maxBridgeMs: Long,
    val reanchorAfterRejects: Int,
    val rejectMock: Boolean,
    val pdrEnabled: Boolean,
    val pdrConfirmRatio: Double,
    val kalman: Boolean,
    val stopRadiusMeters: Double,
    val stopMinDwellMs: Long,
    val initialStrideMeters: Double,
    val pdrTrustedWhenCalibrated: Boolean,
    val recoveryStepEvidenceAfterMs: Long,
    val adaptiveSampling: Boolean,
    val batterySaver: Boolean,
) {
    fun toBridge(): TrackingCoreBridge = TrackingCoreBridge(
        maxAccuracyMeters = maxAccuracyMeters, maxFixAgeMs = maxFixAgeMs, silenceAfterMs = silenceAfterMs,
        maxSpeedMps = maxSpeedMps, minStepMeters = minStepMeters, accuracyNoiseFactor = accuracyNoiseFactor,
        stationarySpeedMps = stationarySpeedMps, maxBridgeMs = maxBridgeMs, reanchorAfterRejects = reanchorAfterRejects,
        rejectMock = rejectMock, pdrEnabled = pdrEnabled, pdrConfirmRatio = pdrConfirmRatio, kalman = kalman,
        stopRadiusMeters = stopRadiusMeters, stopMinDwellMs = stopMinDwellMs, initialStrideMeters = initialStrideMeters,
        pdrTrustedWhenCalibrated = pdrTrustedWhenCalibrated, recoveryStepEvidenceAfterMs = recoveryStepEvidenceAfterMs,
        adaptiveSampling = adaptiveSampling, batterySaver = batterySaver,
    )
}

/** One line of the journal. Times named `...AtMs` are on the engine's monotonic clock. */
sealed class JournalRecord {
    /** The first record: configuration and the session's identity. [extras] is for the host (notification text, intervals...). */
    data class Header(
        val version: Int,
        /** The rules that produced the recorded result (`JournalCodec.ENGINE_VERSION`). A replay with other rules may differ. */
        val engineVersion: Int,
        val sessionId: String,
        val atMs: Long,
        val wallAtMs: Long,
        val segmentId: Int,
        val initialSteps: Long,
        val settings: BridgeSettings,
        val extras: Map<String, String>,
    ) : JournalRecord()

    data class Pause(val atMs: Long) : JournalRecord()
    data class Resume(val atMs: Long, val segmentId: Int) : JournalRecord()
    data class Finish(val atMs: Long) : JournalRecord()

    /** A raw fix as the platform produced it, with the time it arrived ([nowMs]). Negative = unknown. */
    data class Fix(
        val sessionId: String,
        val segmentId: Int,
        val seq: Long,
        val timeMs: Long,
        val wallTimeMs: Long,
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracy: Double,
        val speed: Double,
        val speedAccuracy: Double,
        val course: Double,
        val altitude: Double,
        val verticalAccuracy: Double,
        val provider: String,
        val isMock: Boolean,
        val nowMs: Long,
    ) : JournalRecord()

    /** Written only when a tick changed the state (GPS silence): ticks are otherwise not inputs. */
    data class Tick(val atMs: Long) : JournalRecord()

    data class StepCounter(val segmentId: Int, val measuredAtMs: Long, val deliveredAtMs: Long, val raw: Long) : JournalRecord()
    data class SegmentSteps(val segmentId: Int, val measuredAtMs: Long, val deliveredAtMs: Long, val count: Long) : JournalRecord()
    data class StepsCorrection(val exactSteps: Long) : JournalRecord()
    data class StepSensor(val active: Boolean) : JournalRecord()
    data class Pdr(val segmentId: Int, val atMs: Long, val meters: Double) : JournalRecord()

    /**
     * Written once, after `Finish`, when the step total was settled: [stepsFinal] is true when the
     * platform's exact count was obtained (or there is no step sensor), false when the total is
     * only the live count. A journal that ends at `Finish` without it was cut before settling:
     * its steps are provisional.
     */
    data class Finalized(val stepsFinal: Boolean) : JournalRecord()

    /**
     * The platform's exact step count for the CLOSED segment [segmentId] was obtained ([exact] true) or
     * could not be ([exact] false). A session's steps are exact only when EVERY segment in which the
     * step sensor was active is settled exact; one failed or lost close keeps it provisional.
     */
    data class SegmentSettled(val segmentId: Int, val exact: Boolean) : JournalRecord()
}
