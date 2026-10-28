package tracker.util

import kotlin.math.roundToLong

/** One decimal, without `String.format` (not available in common Kotlin). */
internal fun Double.f1(): String = ((this * 10.0).roundToLong() / 10.0).toString()
