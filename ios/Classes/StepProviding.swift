import CoreMotion
import Foundation

/// Why a step operation did not give a number. Never collapsed into "0 steps".
enum StepError: Error, Equatable {
    case permissionDenied
    case unavailable
    /// `NSMotionUsageDescription` is missing from Info.plist: asking would crash the app.
    case missingUsageDescription
    /// The OS did not answer in time (not the same as "no steps").
    case timeout
    case failed(String)

    var code: String {
        switch self {
        case .permissionDenied: return "STEPS_PERMISSION_DENIED"
        case .unavailable: return "STEPS_UNAVAILABLE"
        case .missingUsageDescription: return "MISSING_MOTION_USAGE_DESCRIPTION"
        case .timeout: return "STEPS_QUERY_TIMEOUT"
        case .failed: return "STEPS_FAILED"
        }
    }

    var message: String {
        switch self {
        case .permissionDenied: return "Motion & Fitness access was denied"
        case .unavailable: return "this device has no step counting"
        case .missingUsageDescription: return "Info.plist has no NSMotionUsageDescription"
        case .timeout: return "the pedometer did not answer in time"
        case .failed(let message): return message
        }
    }
}

/// One live reading: the steps counted since the segment started, up to `endDate`.
struct StepReading {
    let count: Int64
    let endDate: Date
}

/// Where step counts come from, as a protocol so `TrackingEngine` can be tested with a fake.
protocol StepProviding: AnyObject {
    /// Whether the sensor can feed the engine now; the error says why not.
    func availability() -> Result<Void, StepError>
    var permission: NativePermission { get }
    /// Asks for Motion & Fitness access. CoreMotion has no explicit request: the system prompt
    /// appears on the first query, so a tiny one is run.
    func requestPermission(completion: @escaping (NativePermission) -> Void)
    /// Live steps since `start`. The handler runs on an arbitrary thread; an error is a runtime
    /// failure (for example access revoked), never swallowed.
    func startUpdates(from start: Date, handler: @escaping (Result<StepReading, StepError>) -> Void)
    func stopUpdates()
    /// The OS's exact count for `[from, to]`; `.timeout` after `timeout` seconds.
    func query(from: Date, to: Date, timeout: TimeInterval, completion: @escaping (Result<Int64, StepError>) -> Void)
}

/// `CMPedometer`. It can count from a chosen date, so no baseline reading is needed, and at a
/// boundary the OS gives the exact count of the closed segment.
final class CoreMotionStepProvider: StepProviding {
    private let pedometer = CMPedometer()
    private let lock = NSLock()
    private var generation = 0

    func availability() -> Result<Void, StepError> {
        guard CMPedometer.isStepCountingAvailable() else { return .failure(.unavailable) }
        guard Bundle.main.object(forInfoDictionaryKey: "NSMotionUsageDescription") != nil else {
            return .failure(.missingUsageDescription)
        }
        switch CMPedometer.authorizationStatus() {
        case .denied, .restricted: return .failure(.permissionDenied)
        case .notDetermined, .authorized: return .success(())  // notDetermined: starting prompts
        @unknown default: return .failure(.permissionDenied)
        }
    }

    var permission: NativePermission {
        guard CMPedometer.isStepCountingAvailable() else { return .denied }
        switch CMPedometer.authorizationStatus() {
        case .notDetermined: return .notDetermined
        case .restricted: return .restricted
        case .denied: return .denied
        case .authorized: return .granted
        @unknown default: return .denied
        }
    }

    func requestPermission(completion: @escaping (NativePermission) -> Void) {
        guard permission == .notDetermined else {
            completion(permission)
            return
        }
        guard Bundle.main.object(forInfoDictionaryKey: "NSMotionUsageDescription") != nil else {
            completion(.denied)  // asking without the key would crash the app
            return
        }
        let now = Date()
        pedometer.queryPedometerData(from: now.addingTimeInterval(-1), to: now) { [weak self] _, _ in
            completion(self?.permission ?? .denied)
        }
    }

    func startUpdates(from start: Date, handler: @escaping (Result<StepReading, StepError>) -> Void) {
        let gen = bumpGeneration()
        pedometer.startUpdates(from: start) { [weak self] data, error in
            // A reading queued before stopUpdates() must not reach a later segment.
            guard let self = self, self.isCurrent(gen) else { return }
            if let error = error {
                handler(.failure(Self.map(error)))
            } else if let data = data {
                handler(.success(StepReading(count: data.numberOfSteps.int64Value, endDate: data.endDate)))
            }
        }
    }

    func stopUpdates() {
        _ = bumpGeneration()
        pedometer.stopUpdates()
    }

    func query(
        from: Date, to: Date, timeout: TimeInterval, completion: @escaping (Result<Int64, StepError>) -> Void
    ) {
        if case .failure(let error) = availability() {
            completion(.failure(error))
            return
        }
        guard to > from else {
            completion(.success(0))  // a zero-length range really holds no steps
            return
        }
        let once = Once()
        pedometer.queryPedometerData(from: from, to: to) { data, error in
            once.run {
                if let error = error {
                    completion(.failure(Self.map(error)))
                } else if let data = data {
                    completion(.success(data.numberOfSteps.int64Value))
                } else {
                    completion(.failure(.failed("CMPedometer returned neither data nor an error")))
                }
            }
        }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout) {
            once.run { completion(.failure(.timeout)) }
        }
    }

    private static func map(_ error: Error) -> StepError {
        let ns = error as NSError
        if ns.domain == CMErrorDomain {
            if ns.code == Int(CMErrorMotionActivityNotAuthorized.rawValue)
                || ns.code == Int(CMErrorNotAuthorized.rawValue)
            {
                return .permissionDenied
            }
            if ns.code == Int(CMErrorNotAvailable.rawValue) { return .unavailable }
        }
        return .failed(error.localizedDescription)
    }

    private func bumpGeneration() -> Int {
        lock.lock()
        defer { lock.unlock() }
        generation += 1
        return generation
    }

    private func isCurrent(_ gen: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return gen == generation
    }
}

/// Runs a block at most once (the OS answer and the timeout race).
private final class Once {
    private let lock = NSLock()
    private var done = false

    func run(_ block: () -> Void) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { block() }
    }
}
