package io.github.congtuandevmobile.flutter_tracking_gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * One location reading, free of Android types so the engine can be unit tested
 * without a device. A negative number means "unknown".
 */
internal data class RawLocation(
    val timeMs: Long,
    val elapsedMs: Long,
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
)

/** Where raw locations come from. An interface so the engine can be tested with a fake. */
internal interface LocationSource {
    /**
     * Cold flow: collecting registers the updates, cancelling unregisters them.
     * Fails with the underlying exception (for example [SecurityException]).
     */
    fun locations(intervalMs: Long, minDistanceMeters: Float): Flow<RawLocation>
}

/** Google Fused Location Provider, high accuracy. */
internal class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission") // checked by the caller before starting
    override fun locations(intervalMs: Long, minDistanceMeters: Float): Flow<RawLocation> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Oldest first: the engine numbers fixes in arrival order.
                for (location in result.locations) trySend(location.toRaw())
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { error -> close(error) }
        awaitClose { client.removeLocationUpdates(callback) }
        // Never drop a fix: a full buffer would lose one without any error.
    }.buffer(Channel.UNLIMITED)
}

private fun Location.toRaw(): RawLocation {
    val hasSpeedAccuracy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()
    val hasVerticalAccuracy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasVerticalAccuracy()
    val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider
    return RawLocation(
        timeMs = time,
        elapsedMs = elapsedRealtimeNanos / 1_000_000L,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracy = if (hasAccuracy()) accuracy.toDouble() else -1.0,
        speed = if (hasSpeed()) speed.toDouble() else -1.0,
        speedAccuracy = if (hasSpeedAccuracy) speedAccuracyMetersPerSecond.toDouble() else -1.0,
        course = if (hasBearing()) bearing.toDouble() else -1.0,
        altitude = if (hasAltitude()) altitude else 0.0,
        verticalAccuracy = if (hasVerticalAccuracy) verticalAccuracyMeters.toDouble() else -1.0,
        provider = provider ?: "unknown",
        isMock = mock,
    )
}
