import Foundation

/// Error codes the Dart side can switch on.
enum TrackingErrorCode {
    static let missingBackgroundMode = "MISSING_BACKGROUND_MODE"
    static let permissionDenied = "PERMISSION_DENIED"
    static let startFailed = "START_FAILED"
    static let pauseFailed = "PAUSE_FAILED"
    static let resumeFailed = "RESUME_FAILED"
    static let stopFailed = "STOP_FAILED"
    static let restoreFailed = "RESTORE_FAILED"
    static let storeReadFailed = "STORE_READ_FAILED"
    static let storeClearFailed = "STORE_CLEAR_FAILED"
    static let storeWriteFailed = "STORE_WRITE_FAILED"
    static let locationFailed = "LOCATION_UPDATES_FAILED"
    static let journalRepaired = "JOURNAL_REPAIRED"
    static let journalCorrupt = "JOURNAL_CORRUPT"
    static let engineChanged = "ENGINE_CHANGED"
    static let storeListFailed = "STORE_LIST_FAILED"
    static let sessionOpen = "SESSION_OPEN"
}

/// Failures the engine itself detects.
enum TrackingNativeError: Error {
    /// `UIBackgroundModes` has no `location`: asking CoreLocation for background
    /// updates anyway would crash the app.
    case missingBackgroundMode
}

/// Turns any failure into the three-part error the channel carries: a stable
/// code, a short message, and details (the underlying error and the call
/// stack). Nothing is swallowed: the failure is also logged.
func trackingError(code: String, message: String, underlying: Error? = nil) -> PigeonError {
    var details: [String: Any] = ["stack": Thread.callStackSymbols]
    if let underlying = underlying {
        details["underlying"] = String(describing: underlying)
    }
    NSLog("[flutter_tracking_gps] %@: %@ %@", code, message, underlying.map { String(describing: $0) } ?? "")
    let full = underlying.map { "\(message) (\($0.localizedDescription))" } ?? message
    return PigeonError(code: code, message: full, details: details)
}
