# TrackingErrors.swift

**Path:** `ios/Classes/TrackingErrors.swift` · **Layer:** iOS native (error handling)

## Purpose
Implements the project rule "never swallow an error": every native failure becomes a `PigeonError` (the Swift counterpart of `FlutterError`) with a stable **code**, a short **message** and **details** (the underlying error and the call stack), and is also logged.

## Members
| Member | Description |
|---|---|
| `TrackingErrorCode` (enum of constants) | `missingBackgroundMode`, `permissionDenied`, `startFailed`, `pauseFailed`, `resumeFailed`, `stopFailed`, `restoreFailed`, `storeReadFailed`, `storeClearFailed`, `storeWriteFailed`, `locationFailed`. |
| `TrackingNativeError` | Failures the engine detects itself: `missingBackgroundMode` (`UIBackgroundModes` has no `location`, so asking CoreLocation for background updates would crash the app). |
| `trackingError(code:message:underlying:)` | Logs with `NSLog` and returns `PigeonError(code:message:details:)`; `details` holds `stack` (`Thread.callStackSymbols`) and `underlying` (the error description). |

## Used by
`TrackingEngine.swift`.
