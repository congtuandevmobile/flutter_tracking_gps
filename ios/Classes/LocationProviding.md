# LocationProviding.swift

**Path:** `ios/Classes/LocationProviding.swift` · **Layer:** iOS native (location input)

## Purpose
Where raw locations come from, behind protocols so the engine can be tested with a fake provider. Implements the protocol-oriented rule.

## `protocol LocationProviderDelegate: AnyObject`
Called on an arbitrary thread.
- `locationProvider(_:didUpdate:)`: new `[CLLocation]`.
- `locationProvider(_:didFailWith:)`: a location error.
- `locationProviderDidChangeAuthorization(_:)`: the permission changed.

## `protocol LocationProviding: AnyObject`
| Member | Description |
|---|---|
| `delegate` | The receiver (held weakly by the implementation). |
| `permission: NativePermission` | Current permission, including `servicesDisabled`. |
| `requestWhenInUseAuthorization()` | Asks for "when in use" location permission. |
| `startUpdates(minDistanceMeters:) throws` | Starts updates. Throws `TrackingNativeError.missingBackgroundMode` instead of crashing. |
| `stopUpdates()` | Stops updates. |
| `updateRequest(minDistanceMeters:)` | Changes the minimum displacement of the running updates (adaptive sampling). CoreLocation has no interval request, only `distanceFilter`. |

## `final class CoreLocationProvider`
CoreLocation tuned for a workout. The `CLLocationManager` lives on the main thread (its delegate callbacks need a run loop), so every call to it is hopped there, with `[weak self]`. **Create it on the main thread.**
- `permission`: `locationServicesEnabled()` (not on the main thread) then the authorization status (`authorizationStatus` on iOS 14+).
- `startUpdates`: checks `UIBackgroundModes` contains `location`; then sets `desiredAccuracy = best`, `activityType = .fitness`, `distanceFilter` (0 = none), `pausesLocationUpdatesAutomatically = false`, `allowsBackgroundLocationUpdates = true`, `showsBackgroundLocationIndicator = true` and starts.
- `updateRequest` hops to the main thread and sets `distanceFilter` (0 = none).
- Delegate methods forward `didUpdateLocations`, `didFailWithError` and `locationManagerDidChangeAuthorization`.

## Used by
`TrackingEngine.swift`, `FlutterTrackingGpsPlugin.swift`.
