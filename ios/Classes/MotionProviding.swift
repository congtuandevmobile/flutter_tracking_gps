import CoreMotion
import Foundation

/// One accelerometer reading, in m/s² (gravity included, device axes). `timestamp` is `CMLogItem.timestamp`: seconds
/// since the device booted (the `systemUptime` clock), NOT the engine's monotonic clock; the engine converts it.
struct AccelReading {
    let timestamp: TimeInterval
    let x: Double
    let y: Double
    let z: Double
}

enum MotionError: Error {
    case unavailable
    case failed(String)

    /// A stable code for `NativeSnapshot.error`.
    var code: String { TrackingErrorCode.cadenceFailed }

    var message: String {
        switch self {
        case .unavailable: return "no accelerometer on this device"
        case .failed(let text): return text
        }
    }
}

/// Where accelerometer readings come from, as a protocol so `TrackingEngine` can be tested with a fake. It only reads
/// the sensor; the rhythm is found by the shared core (`PedalCadenceEstimator`).
protocol MotionProviding: AnyObject {
    /// Whether the device has an accelerometer.
    var isAvailable: Bool { get }

    /// Starts about 50 readings a second. The handler may be called on any queue. A runtime failure is delivered as a
    /// `.failure`, never swallowed.
    func startUpdates(handler: @escaping (Result<AccelReading, MotionError>) -> Void)

    /// Stops the readings. A reading already queued may still arrive: the caller drops it by generation.
    func stopUpdates()
}

/// `CMMotionManager` accelerometer at 50 Hz (`accelerometerUpdateInterval = 0.02`), delivered on a private serial queue.
/// CoreMotion reports in g: it is converted to m/s² here. Not measured on devices: the rate the OS really delivers and
/// the battery cost of holding the sensor for the whole recording.
final class CoreMotionAccelerometerProvider: MotionProviding {
    private let manager = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue()
        q.name = "io.github.congtuandevmobile.flutter_tracking_gps.accelerometer"
        q.maxConcurrentOperationCount = 1
        q.qualityOfService = .userInitiated
        return q
    }()
    private static let standardGravity = 9.80665

    var isAvailable: Bool { manager.isAccelerometerAvailable }

    func startUpdates(handler: @escaping (Result<AccelReading, MotionError>) -> Void) {
        guard manager.isAccelerometerAvailable else {
            handler(.failure(.unavailable))
            return
        }
        manager.stopAccelerometerUpdates()
        manager.accelerometerUpdateInterval = 0.02
        manager.startAccelerometerUpdates(to: queue) { data, error in
            if let error = error {
                handler(.failure(.failed(error.localizedDescription)))
                return
            }
            guard let data = data else { return }
            let g = Self.standardGravity
            handler(.success(AccelReading(
                timestamp: data.timestamp,
                x: data.acceleration.x * g, y: data.acceleration.y * g, z: data.acceleration.z * g)))
        }
    }

    func stopUpdates() {
        manager.stopAccelerometerUpdates()
    }
}
