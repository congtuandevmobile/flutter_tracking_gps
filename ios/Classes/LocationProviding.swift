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
    private let monitorSignificantChanges: Bool

    /// Must be created on the main thread.
    init(monitorSignificantChanges: Bool = false) {
        self.monitorSignificantChanges = monitorSignificantChanges
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
            // A wake source: lets the system relaunch the app into the background after it was terminated. It needs
            // "Always" authorization; without it this does nothing.
            if self.monitorSignificantChanges, CLLocationManager.significantLocationChangeMonitoringAvailable() {
                self.manager.startMonitoringSignificantLocationChanges()
            }
        }
    }

    func stopUpdates() {
        DispatchQueue.main.async { [weak self] in
            self?.manager.stopUpdatingLocation()
            if self?.monitorSignificantChanges == true { self?.manager.stopMonitoringSignificantLocationChanges() }
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


/// The modern Core Location sequence (iOS 17+). Authorization still goes through a `CLLocationManager` (its permission
/// prompts and status are what the app already handles); only the location stream changes.
///
/// `CLBackgroundActivitySession` keeps the app collecting in the background (and, per Apple, lets the system continue
/// the sequence across a system termination); on iOS 18 a `CLServiceSession` is held for the recording's lifetime. If
/// the app is terminated and relaunched, the session must be recreated IMMEDIATELY at launch: the engine's recovery
/// (`FlutterTrackingGpsRuntime.bootstrapAtLaunch`) does that by calling `startUpdates` again.
///
/// Not verified on a physical device: whether a relaunch happens after a user force-quit is up to the system.
@available(iOS 17.0, *)
final class LiveUpdatesLocationProvider: LocationProviding {
    weak var delegate: LocationProviderDelegate? {
        get { authorization.delegate }
        set { authorization.delegate = newValue; lock.lock(); storedDelegate = newValue; lock.unlock() }
    }
    private weak var storedDelegate: LocationProviderDelegate?
    private let authorization: CoreLocationProvider

    /// Guards [generation], [task], [background] and [serviceSession]: the engine queue, the main queue and the Swift
    /// task all touch them.
    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var background: CLBackgroundActivitySession?
    private var serviceSession: AnyObject?
    private var generation = 0

    init() { authorization = CoreLocationProvider() }

    var permission: NativePermission { authorization.permission }
    func requestWhenInUseAuthorization() { authorization.requestWhenInUseAuthorization() }

    func startUpdates(minDistanceMeters: Double) throws {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        guard modes.contains("location") else { throw TrackingNativeError.missingBackgroundMode }
        stopUpdates()
        lock.lock()
        generation += 1
        let token = generation
        let session = CLBackgroundActivitySession()
        background = session
        if #available(iOS 18.0, *) { serviceSession = CLServiceSession(authorization: .whenInUse) }
        task = Task { [weak self] in
            do {
                for try await update in CLLocationUpdate.liveUpdates(.fitness) {
                    guard !Task.isCancelled, let self = self, self.isCurrent(token) else { return }
                    self.deliver(update)
                }
            } catch is CancellationError {
                // Stopped on purpose (pause / finish).
            } catch {
                guard let self = self, self.isCurrent(token) else { return }
                self.delegateNow()?.locationProvider(self, didFailWith: error)
            }
        }
        lock.unlock()
    }

    func stopUpdates() {
        lock.lock()
        generation += 1
        task?.cancel()
        task = nil
        background?.invalidate()
        background = nil
        if #available(iOS 18.0, *) { (serviceSession as? CLServiceSession)?.invalidate() }
        serviceSession = nil
        lock.unlock()
    }

    /// The modern sequence has no displacement filter: the engine's own filters decide what counts.
    func updateRequest(minDistanceMeters: Double) {}

    // MARK: - Private

    private func isCurrent(_ token: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return generation == token
    }

    private func delegateNow() -> LocationProviderDelegate? {
        lock.lock()
        defer { lock.unlock() }
        return storedDelegate
    }

    /// A location goes to the engine. An update WITHOUT one carries a diagnostic: the reasons that make recording
    /// impossible are reported as failures, so a recording that receives nothing never looks like it is collecting.
    /// `locationUnavailable` is a GPS-quality matter and `isStationary` is NOT a pause: neither is a failure.
    private func deliver(_ update: CLLocationUpdate) {
        guard let delegate = delegateNow() else { return }
        if let location = update.location {
            delegate.locationProvider(self, didUpdate: [location])
            return
        }
        // The diagnostics exist from iOS 18. On iOS 17 a lost authorization still reaches the engine through the
        // authorization callback of the manager this provider holds.
        if #available(iOS 18.0, *) {
            if update.authorizationDenied || update.authorizationDeniedGlobally || update.authorizationRestricted {
                delegate.locationProvider(self, didFailWith: CLError(.denied))
                return
            }
            if update.insufficientlyInUse || update.serviceSessionRequired {
                // The background session / service session is not in effect: location will not arrive.
                delegate.locationProvider(self, didFailWith: CLError(.denied))
            }
        }
    }
}
