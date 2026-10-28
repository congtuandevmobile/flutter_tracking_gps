# LocationSource.kt

**Path:** `android/src/main/kotlin/io/github/congtuandevmobile/flutter_tracking_gps/LocationSource.kt` · **Layer:** Android native (location input)

## Purpose
Where raw locations come from, behind an interface so the engine can be unit tested with a fake and without any Android type.

## `data class RawLocation`
One reading free of Android types: `timeMs, elapsedMs, latitude, longitude, horizontalAccuracy, speed, speedAccuracy, course, altitude, verticalAccuracy, provider, isMock`. A negative number means "unknown".

## `interface LocationSource`
`locations(intervalMs: Long, minDistanceMeters: Float): Flow<RawLocation>`: a cold flow. Collecting registers the updates; cancelling unregisters them. It fails with the underlying exception (for example `SecurityException`).

## `class FusedLocationSource(context)`
Google Fused Location Provider, high accuracy.
- Builds a `LocationRequest` with `Priority.PRIORITY_HIGH_ACCURACY`, the requested interval, `setMinUpdateIntervalMillis(intervalMs)`, `setMinUpdateDistanceMeters(minDistance)` and `setWaitForAccurateLocation(false)`.
- Implemented with `callbackFlow`: locations are sent oldest first; a registration failure closes the flow with the error; `awaitClose` removes the updates.
- `.buffer(Channel.UNLIMITED)`: a fix is never dropped by a full buffer.
- A private `Location.toRaw()` maps the Android `Location` (unknown accuracy, speed, bearing and vertical accuracy become `-1.0`; mock flag from `isMock` on API 31+ or `isFromMockProvider`).

## Notes
`@SuppressLint("MissingPermission")`: the caller checks the permission before starting.

## Used by
`TrackingEngine.kt`, `TrackingRuntime.kt`. Faked in `TrackingEngineTest.kt`.
