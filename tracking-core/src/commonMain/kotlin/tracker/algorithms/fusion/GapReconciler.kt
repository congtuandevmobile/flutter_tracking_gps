package tracker.algorithms.fusion

import tracker.model.DistanceSource
import tracker.model.PdrPolicy
import tracker.model.TrackingConfig
import kotlin.math.max
import kotlin.math.min

/** What a GPS gap is worth. [note] explains it in the log (no coordinates). */
class GapResult(val creditMeters: Double, val source: DistanceSource, val note: String)

/**
 * Credits ONE measurement interval (a GPS gap) once: the larger of the straight
 * line across it, shrunk by its own uncertainty, and the step estimate that GPS
 * corroborates. It never adds the two. A pending estimate is accepted only up
 * to `lowerChord / pdrConfirmRatio`, so a large estimate cannot be taken just
 * because GPS moved a little, and a phone shaken without GPS (chord about 0)
 * adds nothing.
 */
object GapReconciler {
    fun reconcile(
        chordMeters: Double,
        uncertaintyMeters: Double,
        gapMs: Long,
        pendingPdrMeters: Double,
        config: TrackingConfig,
        strideCalibrated: Boolean = false,
    ): GapResult {
        if (!chordMeters.isFinite() || chordMeters < 0.0 ||
            !uncertaintyMeters.isFinite() || uncertaintyMeters < 0.0 ||
            !pendingPdrMeters.isFinite() || pendingPdrMeters < 0.0 || gapMs <= 0L
        ) return GapResult(0.0, DistanceSource.GAP_BRIDGE, "invalid interval: not bridged")
        val seconds = gapMs / 1000.0
        val ceiling = config.maxSpeedMps * seconds
        // Opt-in: a calibrated stride makes the estimate usable even for a long or
        // curved gap. It is ESTIMATED distance (source PDR), bounded by the speed
        // ceiling, and never summed with the straight line.
        if (config.pdrPolicy == PdrPolicy.TRUSTED_WHEN_CALIBRATED && strideCalibrated && pendingPdrMeters > 0.0) {
            val estimate = min(pendingPdrMeters, ceiling)
            val line = if (gapMs <= config.maxBridgeMs) min(max(0.0, chordMeters - uncertaintyMeters), ceiling) else 0.0
            return if (estimate > line) {
                GapResult(estimate, DistanceSource.PDR, "pdr trusted (calibrated stride)")
            } else {
                GapResult(line, DistanceSource.GAP_BRIDGE, "straight line")
            }
        }
        if (gapMs > config.maxBridgeMs) {
            return GapResult(0.0, DistanceSource.GAP_BRIDGE, "gap ${gapMs / 1000}s too long: not bridged")
        }
        val lowerChord = max(0.0, chordMeters - uncertaintyMeters)
        // Physically possible even after allowing for the GPS error of both ends.
        if (lowerChord > ceiling) {
            return GapResult(0.0, DistanceSource.GAP_BRIDGE, "gap implied speed too high: not bridged")
        }
        var credit = min(lowerChord, ceiling)
        var source = DistanceSource.GAP_BRIDGE
        var note = "straight line"
        if (config.pdrPolicy != PdrPolicy.OFF && pendingPdrMeters > 0.0 && lowerChord > 0.0) {
            val cap = min(ceiling, lowerChord / config.pdrConfirmRatio)
            val corroborated = min(pendingPdrMeters, cap)
            note = if (pendingPdrMeters <= cap) "pdr corroborated" else "pdr capped by the straight line"
            if (corroborated > credit) {
                credit = corroborated
                source = DistanceSource.PDR
            }
        } else if (pendingPdrMeters > 0.0) {
            note = "pdr NOT corroborated"
        }
        return GapResult(credit, source, note)
    }
}
