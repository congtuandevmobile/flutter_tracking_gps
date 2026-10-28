import CoreLocation
import XCTest

@testable import flutter_tracking_gps

/// One controllable time for the engine: `mono` is its monotonic clock and `wall`
/// the wall clock the fix ages are measured against.
private final class TestClock {
    var seconds: Double = 0
    let base = Date()
    var mono: Int64 { 5_000_000 + Int64(seconds * 1000) }
    var wall: Date { base.addingTimeInterval(seconds) }
}

/// A fake location provider: tests push locations by hand.
private final class FakeProvider: LocationProviding {
    weak var delegate: LocationProviderDelegate?
    let clock: TestClock
    init(clock: TestClock) { self.clock = clock }
    var permission: NativePermission = .granted
    var startError: Error?
    private(set) var starts = 0
    private(set) var stops = 0

    func requestWhenInUseAuthorization() {}

    func startUpdates(minDistanceMeters: Double) throws {
        if let startError = startError { throw startError }
        starts += 1
    }

    func stopUpdates() { stops += 1 }

    private(set) var requestedMinDistances: [Double] = []
    func updateRequest(minDistanceMeters: Double) { requestedMinDistances.append(minDistanceMeters) }

    /// A location [north] metres north of the origin, [seconds] into the test. The
    /// test clock moves to that moment first: the fix is measured and arrives then.
    func push(
        north: Double, seconds: Double = 0, accuracy: Double = 5, speed: Double = 3
    ) {
        clock.seconds = seconds
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 10 + north / 111_195, longitude: 106),
            altitude: 0, horizontalAccuracy: accuracy, verticalAccuracy: 5, course: -1, speed: speed,
            timestamp: clock.wall)
        delegate?.locationProvider(self, didUpdate: [location])
    }
}

/// A fake step provider: tests push the segment's step count by hand.
private final class FakeSteps: StepProviding {
    var status: NativeStepsStatus = .active
    var permission: NativePermission = .granted
    func requestPermission(completion: @escaping (NativePermission) -> Void) { completion(permission) }

    private var handler: ((Int64) -> Void)?
    private(set) var starts = 0
    private(set) var stops = 0
    /// What the OS answers when the segment is closed; nil = the query failed.
    var exact: Int64?

    func startUpdates(from start: Date, handler: @escaping (Int64) -> Void) {
        starts += 1
        self.handler = handler
    }

    func stopUpdates() {
        stops += 1
        handler = nil
    }

    func query(from: Date, to: Date, completion: @escaping (Int64?) -> Void) { completion(exact) }

    func push(_ steps: Int64) { handler?(steps) }
}

/// An in-memory store.
private final class MemoryStore: FixStoring {
    var fixes: [NativeFix] = []
    func append(_ fix: NativeFix) throws { fixes.append(fix) }
    func read(sessionId: String, afterSeq: Int64) throws -> [NativeFix] {
        fixes.filter { $0.sessionId == sessionId && $0.seq > afterSeq }
    }
    func clearSession(_ sessionId: String) throws { fixes.removeAll { $0.sessionId == sessionId } }
}

final class RunnerTests: XCTestCase {
    private let clock = TestClock()

    private func makeEngine(
        _ provider: FakeProvider, _ store: MemoryStore = MemoryStore(), steps: FakeSteps = FakeSteps()
    ) -> TrackingEngine {
        TrackingEngine(
            provider: provider, store: store, steps: steps, monotonicMs: { self.clock.mono },
            wallNow: { self.clock.wall })
    }

    private func config(
        _ id: String = "s", adaptive: Bool = false, steps: Bool = false, stride: Double = -1
    ) -> StartConfig {
        StartConfig(
            stepsEnabled: steps, initialSteps: 0, foregroundMode: .builtIn,
            engine: EngineConfig(
                maxAccuracyMeters: 40, maxFixAgeMs: 30_000, silenceAfterMs: 10_000, maxSpeedMps: 10,
                minStepMeters: 2, accuracyNoiseFactor: 0.5, stationarySpeedMps: 0.4, maxBridgeMs: 120_000,
                reanchorAfterRejects: 3, rejectMock: true, pdrEnabled: true, pdrConfirmRatio: 0.5,
                kalman: false, stopRadiusMeters: 15, stopMinDwellMs: 30_000, initialStrideMeters: stride),
            adaptiveSampling: adaptive, batterySaver: false,
            sessionId: id, startSeq: 0, startSegmentId: 0, intervalMs: 1000, minDistanceMeters: 0,
            notificationTitle: "t", notificationText: "t")
    }

    /// Lets the engine queue run what the fake pushed (it is async).
    private func drain(_ engine: TrackingEngine) { _ = engine.snapshot() }

    private func run<T>(_ body: (@escaping (Result<T, Error>) -> Void) -> Void) throws -> T {
        let done = expectation(description: "completion")
        var out: Result<T, Error>?
        body { result in
            out = result
            done.fulfill()
        }
        wait(for: [done], timeout: 2)
        return try XCTUnwrap(out).get()
    }

    func testStartPauseResumeStop() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)

        let started: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        XCTAssertEqual(started.state, .running)

        let paused: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }
        XCTAssertEqual(paused.state, .paused)

        let resumed: NativeSnapshot = try run { engine.resume(sessionId: "s", completion: $0) }
        XCTAssertEqual(resumed.state, .running)
        XCTAssertEqual(resumed.segmentId, 1, "a resume opens a new segment")

        let stopped: NativeSnapshot = try run { engine.stop(sessionId: "s", completion: $0) }
        XCTAssertEqual(stopped.state, .stopped)
    }

    func testMissingBackgroundModeIsAnErrorWithCodeMessageDetails() throws {
        let provider = FakeProvider(clock: clock)
        provider.startError = TrackingNativeError.missingBackgroundMode
        let engine = makeEngine(provider)
        XCTAssertThrowsError(
            try { let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) } }()
        ) { error in
            let pigeon = error as? PigeonError
            XCTAssertEqual(pigeon?.code, TrackingErrorCode.missingBackgroundMode)
            XCTAssertNotNil(pigeon?.message)
            XCTAssertNotNil(pigeon?.details)
        }
    }

    func testFixesAreNumberedPersistedAndDelivered() throws {
        let provider = FakeProvider(clock: clock)
        let store = MemoryStore()
        let engine = makeEngine(provider, store)
        let delivered = expectation(description: "two fixes delivered")
        delivered.expectedFulfillmentCount = 2
        var seqs: [Int64] = []
        engine.fixObserver = { fix in
            seqs.append(fix.seq)
            delivered.fulfill()
        }
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }

        provider.push(north: 0, seconds: 0)
        provider.push(north: 3, seconds: 1)
        wait(for: [delivered], timeout: 2)

        XCTAssertEqual(seqs, [0, 1])
        let persisted: [NativeFix] = try run { engine.readPersisted(sessionId: "s", afterSeq: -1, completion: $0) }
        XCTAssertEqual(persisted.map { $0.seq }, [0, 1])
    }

    func testAnInvalidCoordinateIsNotForwarded() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        var count = 0
        engine.fixObserver = { _ in count += 1 }
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        provider.push(north: 0, accuracy: -1)
        let snapshot = engine.snapshot()
        XCTAssertEqual(snapshot.fixCount, 0)
        XCTAssertEqual(count, 0)
    }

    func testPermissionDeniedStartsNothing() throws {
        let provider = FakeProvider(clock: clock)
        provider.permission = .denied
        let engine = makeEngine(provider)
        let snapshot: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        XCTAssertEqual(snapshot.state, .permissionDenied)
        XCTAssertEqual(provider.starts, 0)
    }

    func testFixesBecomeDistanceInTheSnapshotThroughTheKotlinCore() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        for i in 0...10 { provider.push(north: Double(i) * 3, seconds: Double(i)) }
        drain(engine)
        let s = engine.snapshot()
        XCTAssertEqual(s.distance.totalMeters, 30, accuracy: 0.6)
        XCTAssertEqual(s.distance.quality, .good)
        XCTAssertEqual(s.fixCount, 11)
    }

    func testPauseKeepsTheTotalAndResumeNeverBridges() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        provider.push(north: 0, seconds: 0)
        provider.push(north: 5, seconds: 1, speed: 4)
        drain(engine)
        let before = engine.snapshot().distance.totalMeters
        let _: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }
        let resumed: NativeSnapshot = try run { engine.resume(sessionId: "s", completion: $0) }
        XCTAssertEqual(resumed.segmentId, 1)
        provider.push(north: 9000, seconds: 600)
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.totalMeters, before, accuracy: 0.001)
    }

    func testRestoreRebuildsTheDistanceFromPersistedFixesAndComesBackPaused() throws {
        let store = MemoryStore()
        let base = Int64(Date().timeIntervalSince1970 * 1000)
        func fix(_ seq: Int64, _ segment: Int64, _ seconds: Int64, _ north: Double) -> NativeFix {
            NativeFix(
                sessionId: "s", segmentId: segment, seq: seq, timeMs: base + seconds * 1000,
                elapsedMs: 5_000_000 + seconds * 1000,
                latitude: 10 + north / 111_195, longitude: 106, horizontalAccuracy: 5, speed: 4,
                speedAccuracy: -1, course: -1, altitude: 0, verticalAccuracy: -1, provider: "gps", isMock: false)
        }
        store.fixes = [fix(0, 0, 0, 0), fix(1, 0, 1, 5), fix(2, 0, 2, 10), fix(3, 1, 500, 9000)]
        let engine = makeEngine(FakeProvider(clock: clock), store)
        let s: NativeSnapshot = try run { engine.restore(config: config(), completion: $0) }
        XCTAssertEqual(s.state, .paused)
        XCTAssertEqual(s.distance.totalMeters, 10, accuracy: 0.3, "segments must not be joined")
        XCTAssertEqual(s.segmentId, 1)
        XCTAssertEqual(s.fixCount, 4)
    }

    func testAShakenPhoneWithoutGpsAddsNothingAndTheReportExplainsWhy() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        provider.push(north: 0, seconds: 0)
        drain(engine)
        engine.submitPdr(sessionId: "s", segmentId: 0, meters: 130)
        drain(engine)
        // GPS is still good (no silence yet): the estimate is ignored, not counted.
        XCTAssertEqual(engine.snapshot().distance.totalMeters, 0)
        XCTAssertTrue(engine.report().contains("TIMELINE"))
    }

    func testAdaptiveSamplingChangesTheDisplacementFilterWhenStandingStill() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        let _: NativeSnapshot = try run { engine.start(config: config(adaptive: true), completion: $0) }
        for i in 0...60 { provider.push(north: 0, seconds: Double(i), speed: 0) }
        drain(engine)
        XCTAssertFalse(provider.requestedMinDistances.isEmpty, "the policy should have asked for a sparser request")
    }

    // MARK: - Steps

    func testWeakGpsStepsBecomeAPendingEstimateNotDistance() throws {
        let provider = FakeProvider(clock: clock)
        let steps = FakeSteps()
        let engine = makeEngine(provider, steps: steps)
        let started: NativeSnapshot = try run { engine.start(config: config(steps: true, stride: 1.0), completion: $0) }
        XCTAssertEqual(started.stepsStatus, .active)

        provider.push(north: 0, seconds: 0)
        provider.push(north: 3, seconds: 1, accuracy: 90)  // degraded
        steps.push(30)
        drain(engine)
        let s = engine.snapshot()
        XCTAssertEqual(s.distance.steps, 30)
        XCTAssertEqual(s.distance.pendingPdrMeters, 30, accuracy: 1e-6)
        XCTAssertEqual(s.distance.totalMeters, 0)
    }

    func testStepsOffByConfigNeverStartTheSensor() throws {
        let provider = FakeProvider(clock: clock)
        let steps = FakeSteps()
        let engine = makeEngine(provider, steps: steps)
        let started: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        XCTAssertEqual(started.stepsStatus, .off)
        XCTAssertEqual(steps.starts, 0)
    }

    func testAMissingMotionPermissionOnlyDisablesSteps() throws {
        let provider = FakeProvider(clock: clock)
        let steps = FakeSteps()
        steps.status = .permissionDenied
        let engine = makeEngine(provider, steps: steps)
        let started: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        XCTAssertEqual(started.state, .running)
        XCTAssertEqual(started.stepsStatus, .permissionDenied)
        XCTAssertEqual(steps.starts, 0)
    }

    func testPauseEndsTheSubscriptionAndTheExactCountCorrectsTheTotal() throws {
        let provider = FakeProvider(clock: clock)
        let steps = FakeSteps()
        let engine = makeEngine(provider, steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        steps.exact = 34
        let paused: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }
        XCTAssertEqual(steps.stops, 2, "one when the session opened, one at the pause")
        drain(engine)
        XCTAssertEqual(paused.state, .paused)
        XCTAssertEqual(engine.snapshot().distance.steps, 34)
        steps.push(500)  // a reading after the pause is ignored
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.steps, 34)
    }

    func testResumeStartsAFreshStepCountAndKeepsTheTotal() throws {
        let provider = FakeProvider(clock: clock)
        let steps = FakeSteps()
        let engine = makeEngine(provider, steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let _: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }
        let _: NativeSnapshot = try run { engine.resume(sessionId: "s", completion: $0) }
        XCTAssertEqual(steps.starts, 2)
        steps.push(5)  // counted from the new segment's start
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.steps, 35)
    }
}
