import CoreMotion
import Foundation

/// Where step counts come from. A protocol so the engine can be tested with a
/// fake.
protocol StepProviding: AnyObject {
    /// Whether the sensor can feed the engine (hardware and motion permission).
    var status: NativeStepsStatus { get }
    var permission: NativePermission { get }
    /// Asks for Motion & Fitness access. CoreMotion has no explicit request: the
    /// system prompt appears on the first query, so this runs a tiny one.
    func requestPermission(completion: @escaping (NativePermission) -> Void)
    /// Live count of steps since [start] (cumulative within the segment). The
    /// handler runs on an arbitrary thread.
    func startUpdates(from start: Date, handler: @escaping (_ steps: Int64) -> Void)
    func stopUpdates()
    /// The OS's exact count for `[from, to]`; nil when the query fails or times out.
    func query(from: Date, to: Date, completion: @escaping (_ steps: Int64?) -> Void)
}

/// `CMPedometer`. Unlike the device-lifetime counter on Android it can count from
/// a chosen date, so no baseline reading is needed, and at a boundary the OS can
/// give the exact count of the closed segment.
final class CoreMotionStepProvider: StepProviding {
    private let pedometer = CMPedometer()
    private let lock = NSLock()
    private var generation = 0

    var status: NativeStepsStatus {
        guard CMPedometer.isStepCountingAvailable() else { return .unavailable }
        switch CMPedometer.authorizationStatus() {
        case .denied, .restricted: return .permissionDenied
        case .notDetermined, .authorized: return .active  // notDetermined: starting prompts
        @unknown default: return .permissionDenied
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
        let now = Date()
        pedometer.queryPedometerData(from: now.addingTimeInterval(-1), to: now) { [weak self] _, _ in
            completion(self?.permission ?? .denied)
        }
    }

    func startUpdates(from start: Date, handler: @escaping (Int64) -> Void) {
        let gen = bumpGeneration()
        pedometer.startUpdates(from: start) { [weak self] data, _ in
            // A reading queued before stopUpdates() must not reach a later segment.
            guard let self = self, let data = data, self.isCurrent(gen) else { return }
            handler(data.numberOfSteps.int64Value)
        }
    }

    func stopUpdates() {
        _ = bumpGeneration()
        pedometer.stopUpdates()
    }

    func query(from: Date, to: Date, completion: @escaping (Int64?) -> Void) {
        guard to > from, CMPedometer.isStepCountingAvailable() else {
            completion(nil)
            return
        }
        let once = Once()
        pedometer.queryPedometerData(from: from, to: to) { data, _ in
            once.run { completion(data?.numberOfSteps.int64Value) }
        }
        // The OS may never answer: give up so the segment keeps its live count.
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + Self.queryTimeout) {
            once.run { completion(nil) }
        }
    }

    private static let queryTimeout: TimeInterval = 5

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
