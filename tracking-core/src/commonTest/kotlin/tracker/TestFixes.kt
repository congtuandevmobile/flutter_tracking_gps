package tracker

import tracker.model.TrackingFix

internal const val BASE_MS = 1_700_000_000_000L
private const val METERS_PER_DEGREE = 111_195.0

internal fun ms(seconds: Double): Long = BASE_MS + (seconds * 1000).toLong()

/** A fix [north] metres north of the origin, [seconds] after the base time. */
internal fun fixAt(
    seq: Long,
    seconds: Double,
    north: Double,
    segment: Int = 0,
    accuracy: Double = 5.0,
    speed: Double? = null,
    mock: Boolean = false,
) = TrackingFix(
    sessionId = "s", segmentId = segment, seq = seq, timeMs = ms(seconds),
    latitude = 10.0 + north / METERS_PER_DEGREE, longitude = 106.0,
    horizontalAccuracy = accuracy, speed = speed, isMock = mock,
)
