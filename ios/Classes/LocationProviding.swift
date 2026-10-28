import CoreLocation
import Foundation

/// Receives what a location provider produces. Called on an arbitrary thread.
protocol LocationProviderDelegate: AnyObject {
    func locationProvider(_ provider: LocationProviding, didUpdate locations: [CLLocation])
    func locationProvider(_ provider: LocationProviding, didFailWith error: Error)
    func locationProviderDidChangeAuthorization(_ provider: LocationProviding)
}

/// Where raw locations come from. A protocol so the engine can be tested with a
/// fake.
protocol LocationProviding: AnyObject {
    var delegate: LocationProviderDelegate? { get set }
    var permission: NativePermission { get }
    func requestWhenInUseAuthorization()
    /// Throws [TrackingNativeError.missingBackgroundMode] instead of crashing.
    func startUpdates(minDistanceMeters: Double) throws
    func stopUpdates()
    /// Changes the minimum displacement of the running updates (adaptive
    /// sampling). CoreLocation has no interval request, only `distanceFilter`.
    func updateRequest(minDistanceMeters: Double)
}

/// CoreLocation, tuned for a workout. The manager lives on the main thread
/// (its delegate callbacks need a run loop); every call to it is hopped there.
final class CoreLocationProvider: NSObject, LocationProviding, CLLocationManagerDelegate {
    weak var delegate: LocationProviderDelegate?
    private let manager: CLLocationManager

    /// Must be created on the main thread.
    override init() {
        manager = CLLocationManager()
        super.init()
        manager.delegate = self
    }

    var permission: NativePermission {
        // Not on the main thread: `locationServicesEnabled` can block.
        guard CLLocationManager.locationServicesEnabled() else { return .servicesDisabled }
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = manager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        switch status {
        case .notDetermined: return .notDetermined
        case .restricted: return .restricted
        case .denied: return .denied
        case .authorizedAlways, .authorizedWhenInUse: return .granted
        @unknown default: return .denied
        }
    }

    func requestWhenInUseAuthorization() {
        DispatchQueue.main.async { [weak self] in
            self?.manager.requestWhenInUseAuthorization()
        }
    }

    func startUpdates(minDistanceMeters: Double) throws {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        guard modes.contains("location") else { throw TrackingNativeError.missingBackgroundMode }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.manager.desiredAccuracy = kCLLocationAccuracyBest
            self.manager.activityType = .fitness
            self.manager.distanceFilter = minDistanceMeters > 0 ? minDistanceMeters : kCLDistanceFilterNone
            self.manager.pausesLocationUpdatesAutomatically = false
            self.manager.allowsBackgroundLocationUpdates = true
            if #available(iOS 11.0, *) { self.manager.showsBackgroundLocationIndicator = true }
            self.manager.startUpdatingLocation()
        }
    }

    func stopUpdates() {
        DispatchQueue.main.async { [weak self] in
            self?.manager.stopUpdatingLocation()
        }
    }

    func updateRequest(minDistanceMeters: Double) {
        DispatchQueue.main.async { [weak self] in
            self?.manager.distanceFilter = minDistanceMeters > 0 ? minDistanceMeters : kCLDistanceFilterNone
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        delegate?.locationProvider(self, didUpdate: locations)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        delegate?.locationProvider(self, didFailWith: error)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        delegate?.locationProviderDidChangeAuthorization(self)
    }
}
