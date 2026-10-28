import CoreLocation
import XCTest

@testable import flutter_tracking_gps
@testable import TrackingCore

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
    func push(north: Double, seconds: Double = 0, accuracy: Double = 5, speed: Double = 3) {
        clock.seconds = seconds
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 10 + north / 111_195, longitude: 106),
            altitude: 0, horizontalAccuracy: accuracy, verticalAccuracy: 5, course: -1, speed: speed,
            timestamp: clock.wall)
        delegate?.locationProvider(self, didUpdate: [location])
    }
}

/// A fake step provider: tests push the segment's step count and decide how the exact query answers.
private final class FakeSteps: StepProviding {
    let clock: TestClock
    init(clock: TestClock) { self.clock = clock }
    var available: Result<Void, StepError> = .success(())
    var permission: NativePermission = .granted
    func availability() -> Result<Void, StepError> { available }
    func requestPermission(completion: @escaping (NativePermission) -> Void) { completion(permission) }

    private var handler: ((Result<StepReading, StepError>) -> Void)?
    private(set) var starts = 0
    private(set) var stops = 0
    /// How the exact query answers. `.some` answers at once; nil HOLDS the answer until `release`.
    var autoAnswer: Result<Int64, StepError>? = .success(0)
    private var held: [(Result<Int64, StepError>) -> Void] = []
    var heldCount: Int { held.count }

    func startUpdates(from start: Date, handler: @escaping (Result<StepReading, StepError>) -> Void) {
        starts += 1
        self.handler = handler
    }

    func stopUpdates() {
        stops += 1
        handler = nil
    }

    func query(from: Date, to: Date, timeout: TimeInterval, completion: @escaping (Result<Int64, StepError>) -> Void) {
        if let answer = autoAnswer { completion(answer) } else { held.append(completion) }
    }

    /// Answers the `index`-th held query (oldest first).
    func release(_ result: Result<Int64, StepError>, at index: Int = 0) {
        guard index < held.count else { return }
        held[index](result)
    }

    /// The segment's count so far, measured now.
    func push(_ steps: Int64) { handler?(.success(StepReading(count: steps, endDate: clock.wall))) }
    func fail(_ error: StepError) { handler?(.failure(error)) }
}

/// An in-memory journal store with the same contract as the file one.
private final class MemoryStore: JournalStoring {
    var files: [String: [String]] = [:]
    var rewrites = 0
    var backups = 0
    var flushes = 0
    /// When false, appended lines stay "queued" until flush (a slow disk).
    var autoWrite = true
    var onFlush: (() -> Void)?
    private var sinks: [String: MemorySink] = [:]

    final class MemorySink: NSObject, DurableSink {
        unowned let store: MemoryStore
        let id: String
        var failure: Error?
        private var queue: [String] = []
        var pending: Int { queue.count }
        init(store: MemoryStore, id: String) { self.store = store; self.id = id }
        func append(line: String) {
            if store.autoWrite { store.files[id, default: []].append(line) } else { queue.append(line) }
        }
        func flush() {
            store.flushes += 1
            store.files[id, default: []].append(contentsOf: queue)
            queue.removeAll()
            store.onFlush?()
        }
        func close() {}
    }

    func open(_ sessionId: String) -> DurableSink {
        if let s = sinks[sessionId] { return s }
        let s = MemorySink(store: self, id: sessionId)
        sinks[sessionId] = s
        return s
    }
    func read(_ sessionId: String) throws -> [String] { files[sessionId] ?? [] }
    func rewrite(_ sessionId: String, lines: [String], keepBackup: Bool) throws {
        rewrites += 1
        if keepBackup { backups += 1 }
        files[sessionId] = lines
    }
    func clear(_ sessionId: String) throws { files[sessionId] = nil; sinks[sessionId] = nil }
    func list() throws -> [String] {
        files.values.compactMap { ($0.first.flatMap { JournalCodec.shared.decode(line: $0) } as? JournalRecord.Header)?.sessionId }
    }
}

final class RunnerTests: XCTestCase {
    private let clock = TestClock()

    private func makeEngine(
        _ provider: FakeProvider, _ store: MemoryStore = MemoryStore(), steps: FakeSteps? = nil
    ) -> TrackingEngine {
        TrackingEngine(
            provider: provider, store: store, steps: steps ?? FakeSteps(clock: clock),
            monotonicMs: { self.clock.mono }, wallNow: { self.clock.wall })
    }

    private func engineConfig(stride: Double = -1, maxSpeed: Double = 10) -> EngineConfig {
        EngineConfig(
            maxAccuracyMeters: 40, maxFixAgeMs: 30_000, silenceAfterMs: 10_000, maxSpeedMps: maxSpeed,
            minStepMeters: 2, accuracyNoiseFactor: 0.5, stationarySpeedMps: 0.4, maxBridgeMs: 120_000,
            reanchorAfterRejects: 3, rejectMock: true, pdrEnabled: true, pdrConfirmRatio: 0.5,
            kalman: false, stopRadiusMeters: 15, stopMinDwellMs: 30_000, initialStrideMeters: stride,
            pdrTrustedWhenCalibrated: false, recoveryStepEvidenceAfterMs: 30_000)
    }

    private func config(
        _ id: String = "s", adaptive: Bool = false, steps: Bool = false, stride: Double = -1, maxSpeed: Double = 10
    ) -> StartConfig {
        StartConfig(
            stepsEnabled: steps, initialSteps: 0,
            engine: engineConfig(stride: stride, maxSpeed: maxSpeed),
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
        wait(for: [done], timeout: 5)
        return try XCTUnwrap(out).get()
    }

    private func runFixes(_ provider: FakeProvider, seconds: Int, from: Int = 0, north0: Double = 0) {
        for i in from...(from + seconds) {
            provider.push(north: north0 + Double(i - from) * 3, seconds: Double(i))
        }
    }

    // MARK: - Lifecycle

    func testStartPauseResumeStop() throws {
        let engine = makeEngine(FakeProvider(clock: clock))
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
        runFixes(provider, seconds: 10)
        drain(engine)
        let s = engine.snapshot()
        XCTAssertEqual(s.distance.totalMeters, 30, accuracy: 0.6)
        XCTAssertEqual(s.distance.quality, .good)
        XCTAssertEqual(s.fixCount, 11)
        XCTAssertEqual(s.distance.activeTimeMs, 10_000, accuracy: 1_100)
    }

    func testFixesAreNumberedJournaledAndDelivered() throws {
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
        XCTAssertTrue(store.files["s"]?.first.flatMap { JournalCodec.shared.decode(line: $0) } is JournalRecord.Header)
    }

    func testAnInvalidCoordinateIsNotForwarded() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        var count = 0
        engine.fixObserver = { _ in count += 1 }
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        provider.push(north: 0, accuracy: -1)
        XCTAssertEqual(engine.snapshot().fixCount, 0)
        XCTAssertEqual(count, 0)
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
        clock.seconds = 599
        let resumed: NativeSnapshot = try run { engine.resume(sessionId: "s", completion: $0) }
        XCTAssertEqual(resumed.segmentId, 1)
        provider.push(north: 9000, seconds: 600)
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.totalMeters, before, accuracy: 0.001)
    }

    func testALocationMeasuredBeforeTheNewLegIsIgnored() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        provider.push(north: 0, seconds: 0)
        let _: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }
        clock.seconds = 100
        let _: NativeSnapshot = try run { engine.resume(sessionId: "s", completion: $0) }
        let count = engine.snapshot().fixCount
        // A callback queued before the pause: measured at t=50, delivered at t=101.
        let old = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 10.001, longitude: 106), altitude: 0, horizontalAccuracy: 5,
            verticalAccuracy: 5, course: -1, speed: 3, timestamp: clock.base.addingTimeInterval(50))
        clock.seconds = 101
        provider.delegate?.locationProvider(provider, didUpdate: [old])
        XCTAssertEqual(engine.snapshot().fixCount, count)
    }

    func testAShakenPhoneWithoutGpsAddsNothingAndTheReportExplainsWhy() throws {
        let provider = FakeProvider(clock: clock)
        let engine = makeEngine(provider)
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        provider.push(north: 0, seconds: 0)
        drain(engine)
        engine.submitPdr(sessionId: "s", segmentId: 0, meters: 130)
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.totalMeters, 0) // GPS still good: the estimate is ignored
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

    // MARK: - Restore

    func testRestoreReplaysTheJournalAndComesBackPausedWithTheSameResult() throws {
        let store = MemoryStore()
        let provider = FakeProvider(clock: clock)
        let first = makeEngine(provider, store)
        let _: NativeSnapshot = try run { first.start(config: config(), completion: $0) }
        runFixes(provider, seconds: 10)
        let live: NativeSnapshot = try run { first.pause(sessionId: "s", completion: $0) }

        let second = makeEngine(FakeProvider(clock: clock), store)  // a new process
        let restored: NativeSnapshot = try run { second.restore(config: config(), completion: $0) }
        XCTAssertEqual(restored.state, .paused)
        XCTAssertEqual(restored.distance.totalMeters, live.distance.totalMeters)
        XCTAssertEqual(restored.fixCount, live.fixCount)
        XCTAssertEqual(restored.distance.activeTimeMs, live.distance.activeTimeMs)
    }

    func testRestoreUsesTheConfigurationStoredInTheJournal() throws {
        let store = MemoryStore()
        let provider = FakeProvider(clock: clock)
        let first = makeEngine(provider, store)
        let _: NativeSnapshot = try run { first.start(config: config(maxSpeed: 10), completion: $0) }
        runFixes(provider, seconds: 5)
        let live: NativeSnapshot = try run { first.pause(sessionId: "s", completion: $0) }
        let second = makeEngine(FakeProvider(clock: clock), store)
        // A speed ceiling of 0.5 m/s would reject every fix: the header's configuration must win.
        let restored: NativeSnapshot = try run { second.restore(config: config(maxSpeed: 0.5), completion: $0) }
        XCTAssertEqual(restored.distance.totalMeters, live.distance.totalMeters)
        XCTAssertGreaterThan(restored.distance.totalMeters, 10)
    }

    func testAHalfWrittenLastRecordIsDroppedAndTheJournalRepaired() throws {
        let store = MemoryStore()
        let provider = FakeProvider(clock: clock)
        let first = makeEngine(provider, store)
        let _: NativeSnapshot = try run { first.start(config: config(), completion: $0) }
        runFixes(provider, seconds: 5)
        drain(first)
        var lines = store.files["s"] ?? []
        lines[lines.count - 1] = String(lines[lines.count - 1].dropLast(9))
        store.files["s"] = lines

        let second = makeEngine(FakeProvider(clock: clock), store)
        let restored: NativeSnapshot = try run { second.restore(config: config(), completion: $0) }
        XCTAssertEqual(store.rewrites, 1)
        XCTAssertTrue(restored.error?.hasPrefix(TrackingErrorCode.journalRepaired) == true, restored.error ?? "nil")
        XCTAssertEqual(restored.fixCount, 5)
        XCTAssertTrue((store.files["s"] ?? []).allSatisfy { JournalCodec.shared.decode(line: $0) != nil })
    }

    func testNothingToRestoreIsAnErrorNotAnEmptySession() throws {
        let engine = makeEngine(FakeProvider(clock: clock))
        XCTAssertThrowsError(
            try { let _: NativeSnapshot = try run { engine.restore(config: config(), completion: $0) } }())
    }

    func testAnOpenSessionCannotBeClearedAndSessionsCanBeListed() throws {
        let store = MemoryStore()
        let engine = makeEngine(FakeProvider(clock: clock), store)
        let _: NativeSnapshot = try run { engine.start(config: config("a"), completion: $0) }
        XCTAssertThrowsError(try { let _: Void = try run { engine.clearSession("a", completion: $0) } }())
        let _: NativeSnapshot = try run { engine.stop(sessionId: "a", completion: $0) }
        let ids: [String] = try run { engine.listSessions(completion: $0) }
        XCTAssertEqual(ids, ["a"])
        let _: Void = try run { engine.clearSession("a", completion: $0) }
        XCTAssertEqual(store.files["a"] ?? [], [])
    }

    // MARK: - Steps

    func testWeakGpsStepsBecomeAPendingEstimateNotDistance() throws {
        let provider = FakeProvider(clock: clock)
        let steps = FakeSteps(clock: clock)
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
        let steps = FakeSteps(clock: clock)
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let started: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        XCTAssertEqual(started.stepsStatus, .off)
        XCTAssertEqual(steps.starts, 0)
    }

    func testAMissingMotionPermissionOnlyDisablesStepsAndSaysWhy() throws {
        let steps = FakeSteps(clock: clock)
        steps.available = .failure(.permissionDenied)
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let started: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        XCTAssertEqual(started.state, .running)
        XCTAssertEqual(started.stepsStatus, .permissionDenied)
        XCTAssertTrue(started.error?.hasPrefix("STEPS_PERMISSION_DENIED") == true, started.error ?? "nil")
        XCTAssertEqual(steps.starts, 0)
    }

    func testAMissingUsageDescriptionIsASetupErrorNotACrash() throws {
        let steps = FakeSteps(clock: clock)
        steps.available = .failure(.missingUsageDescription)
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let started: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        XCTAssertEqual(started.stepsStatus, .failed)
        XCTAssertTrue(started.error?.hasPrefix("MISSING_MOTION_USAGE_DESCRIPTION") == true, started.error ?? "nil")
    }

    func testARuntimeCoreMotionErrorIsReportedNotSwallowed() throws {
        let steps = FakeSteps(clock: clock)
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(10)
        steps.fail(.permissionDenied)  // access revoked while recording
        drain(engine)
        let s = engine.snapshot()
        XCTAssertEqual(s.stepsStatus, .permissionDenied)
        XCTAssertTrue(s.error?.hasPrefix("STEPS_PERMISSION_DENIED") == true, s.error ?? "nil")
        XCTAssertEqual(s.state, .running, "the recording goes on")
    }

    func testPauseEndsTheSubscriptionAndTheExactCountCorrectsTheTotal() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = .success(34)
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let _: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.steps, 34)
        steps.push(500)  // a reading after the pause is ignored
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.steps, 34)
    }

    func testResumeStartsAFreshStepCountAndKeepsTheTotal() throws {
        let steps = FakeSteps(clock: clock)
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        steps.autoAnswer = .success(30)
        let _: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }
        clock.seconds = 60
        let _: NativeSnapshot = try run { engine.resume(sessionId: "s", completion: $0) }
        XCTAssertEqual(steps.starts, 2)
        steps.push(5)
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.steps, 35)
    }

    func testStopWaitsForTheExactCountAndReturnsAFinalSnapshot() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = nil  // hold the OS answer
        let store = MemoryStore()
        let engine = makeEngine(FakeProvider(clock: clock), store, steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let done = expectation(description: "stop answered")
        var result: NativeSnapshot?
        engine.stop(sessionId: "s") { r in
            result = try? r.get()
            done.fulfill()
        }
        drain(engine)  // the engine asked the OS for the exact count
        XCTAssertEqual(steps.heldCount, 1)
        // The answer is still pending: stop must NOT have returned a "final" snapshot yet.
        XCTAssertNil(result)
        steps.release(.success(36))
        wait(for: [done], timeout: 5)
        let s = try XCTUnwrap(result)
        XCTAssertEqual(s.state, .stopped)
        XCTAssertEqual(s.distance.steps, 36)
        XCTAssertTrue(s.stepsFinal)
        XCTAssertGreaterThan(store.flushes, 0, "the journal is durable before stop answers")
    }

    func testStopWithAFailedExactQueryIsNotFinalAndSaysWhy() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = .failure(.timeout)
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let s: NativeSnapshot = try run { engine.stop(sessionId: "s", completion: $0) }
        XCTAssertEqual(s.distance.steps, 30, "the live count stays: a failed query is not 0 steps")
        XCTAssertFalse(s.stepsFinal)
        XCTAssertTrue(s.error?.hasPrefix("STEPS_QUERY_TIMEOUT") == true, s.error ?? "nil")
    }

    func testFinishingWhilePausedStillWaitsForThePausesExactCount() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = nil
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let _: NativeSnapshot = try run { engine.pause(sessionId: "s", completion: $0) }  // query held
        let done = expectation(description: "stop answered")
        var result: NativeSnapshot?
        engine.stop(sessionId: "s") { r in
            result = try? r.get()
            done.fulfill()
        }
        drain(engine)
        XCTAssertNil(result)
        steps.release(.success(33))
        wait(for: [done], timeout: 5)
        XCTAssertEqual(try XCTUnwrap(result).distance.steps, 33, "pause then finish must not double count or lose the exact answer")
        XCTAssertTrue(try XCTUnwrap(result).stepsFinal)
    }

    func testALateExactAnswerOfAnOlderSessionNeverTouchesTheNewOne() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = nil
        let store = MemoryStore()
        let engine = makeEngine(FakeProvider(clock: clock), store, steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config("a", steps: true), completion: $0) }
        steps.push(30)
        let _: NativeSnapshot = try run { engine.pause(sessionId: "a", completion: $0) }  // query for "a" held
        // A NEW session starts (same segment id 0, and paused again) before the old answer arrives.
        let _: NativeSnapshot = try run { engine.start(config: config("b", steps: true), completion: $0) }
        steps.push(7)
        let _: NativeSnapshot = try run { engine.pause(sessionId: "b", completion: $0) }
        XCTAssertEqual(steps.heldCount, 2)
        steps.release(.success(999), at: 0)  // the answer meant for session "a"
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.steps, 7, "an answer for another session is dropped by identity")
        steps.release(.success(8), at: 1)  // session "b"'s own answer is applied
        drain(engine)
        XCTAssertEqual(engine.snapshot().distance.steps, 8)
    }

    // MARK: - Finalization and durability

    func testStopIsFinishingUntilTheJournalIsDurableAndOnlyThenStopped() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = nil
        let store = MemoryStore()
        let engine = makeEngine(FakeProvider(clock: clock), store, steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let done = expectation(description: "stop answered")
        var result: NativeSnapshot?
        engine.stop(sessionId: "s") { r in
            result = try? r.get()
            done.fulfill()
        }
        drain(engine)
        // The exact answer is pending: the engine is FINISHING, not stopped, and says its steps are not final.
        let mid = engine.snapshot()
        XCTAssertEqual(mid.state, .finishing)
        XCTAssertFalse(mid.stepsFinal)
        steps.release(.success(33))
        wait(for: [done], timeout: 5)
        XCTAssertEqual(try XCTUnwrap(result).state, .stopped)
        XCTAssertTrue(try XCTUnwrap(result).stepsFinal)
    }

    func testTheSnapshotSaysHowManyEventsAreNotOnDiskYet() throws {
        let provider = FakeProvider(clock: clock)
        let store = MemoryStore()
        store.autoWrite = false
        let engine = makeEngine(provider, store)
        let _: NativeSnapshot = try run { engine.start(config: config(), completion: $0) }
        provider.push(north: 0, seconds: 0)
        provider.push(north: 3, seconds: 1)
        drain(engine)
        XCTAssertGreaterThanOrEqual(engine.snapshot().journalPending, 3)
        let stopped: NativeSnapshot = try run { engine.stop(sessionId: "s", completion: $0) }
        XCTAssertEqual(stopped.journalPending, 0)
    }

    func testADamagedMiddleLineKeepsABackupAndATruncatedTailDoesNot() throws {
        let store = MemoryStore()
        let provider = FakeProvider(clock: clock)
        let first = makeEngine(provider, store)
        let _: NativeSnapshot = try run { first.start(config: config(), completion: $0) }
        runFixes(provider, seconds: 6)
        drain(first)
        var lines = store.files["s"] ?? []
        lines[3] = String(lines[3].prefix(10)) + "#" + String(lines[3].dropFirst(11))
        store.files["s"] = lines
        let second = makeEngine(FakeProvider(clock: clock), store)  // kept alive: the engine's closures hold it weakly
        let restored: NativeSnapshot = try run { second.restore(config: config(), completion: $0) }
        XCTAssertEqual(store.backups, 1, "the only copy must survive the repair")
        XCTAssertTrue(restored.error?.hasPrefix(TrackingErrorCode.journalCorrupt) == true, restored.error ?? "nil")
    }

    func testAJournalOfAnotherEngineVersionIsRestoredButSaysSo() throws {
        let store = MemoryStore()
        let provider = FakeProvider(clock: clock)
        let first = makeEngine(provider, store)
        let _: NativeSnapshot = try run { first.start(config: config(), completion: $0) }
        runFixes(provider, seconds: 4)
        drain(first)
        var lines = store.files["s"] ?? []
        let h = try XCTUnwrap(JournalCodec.shared.decode(line: lines[0]) as? JournalRecord.Header)
        let changed = JournalRecord.Header(
            version: h.version, engineVersion: h.engineVersion + 1, sessionId: h.sessionId, atMs: h.atMs,
            wallAtMs: h.wallAtMs, segmentId: h.segmentId, initialSteps: h.initialSteps, settings: h.settings,
            extras: h.extras)
        lines[0] = JournalCodec.shared.encode(r: changed)
        store.files["s"] = lines
        let second = makeEngine(FakeProvider(clock: clock), store)
        let restored: NativeSnapshot = try run { second.restore(config: config(), completion: $0) }
        XCTAssertEqual(restored.state, .paused)
        XCTAssertTrue(restored.error?.hasPrefix(TrackingErrorCode.engineChanged) == true, restored.error ?? "nil")
    }

    func testAFinishedSessionRestoresAsStoppedNotPausedWithItsFinality() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = .success(33)
        let store = MemoryStore()
        let provider = FakeProvider(clock: clock)
        let first = makeEngine(provider, store, steps: steps)
        let _: NativeSnapshot = try run { first.start(config: config(steps: true), completion: $0) }
        runFixes(provider, seconds: 5)
        steps.push(30)
        let live: NativeSnapshot = try run { first.stop(sessionId: "s", completion: $0) }
        XCTAssertTrue(live.stepsFinal)
        let second = makeEngine(FakeProvider(clock: clock), store)
        let restored: NativeSnapshot = try run { second.restore(config: config(steps: true), completion: $0) }
        XCTAssertEqual(restored.state, .stopped, "a finished session is read-only, never paused")
        XCTAssertTrue(restored.stepsFinal)
        XCTAssertEqual(restored.distance.steps, live.distance.steps)
        XCTAssertEqual(restored.distance.totalMeters, live.distance.totalMeters)
    }

    func testAFinishWhoseExactQueryFailedRestoresWithProvisionalSteps() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = .failure(.timeout)
        let store = MemoryStore()
        let first = makeEngine(FakeProvider(clock: clock), store, steps: steps)
        let _: NativeSnapshot = try run { first.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let live: NativeSnapshot = try run { first.stop(sessionId: "s", completion: $0) }
        XCTAssertFalse(live.stepsFinal)
        let second = makeEngine(FakeProvider(clock: clock), store)
        let restored: NativeSnapshot = try run { second.restore(config: config(steps: true), completion: $0) }
        XCTAssertEqual(restored.state, .stopped)
        XCTAssertFalse(restored.stepsFinal, "the live count was never confirmed: it must not become exact by being restored")
    }

    func testALateSecondExactAnswerNeverChangesAFinalResult() throws {
        let steps = FakeSteps(clock: clock)
        steps.autoAnswer = nil
        let engine = makeEngine(FakeProvider(clock: clock), steps: steps)
        let _: NativeSnapshot = try run { engine.start(config: config(steps: true), completion: $0) }
        steps.push(30)
        let done = expectation(description: "stop answered")
        var result: NativeSnapshot?
        engine.stop(sessionId: "s") { r in
            result = try? r.get()
            done.fulfill()
        }
        drain(engine)
        steps.release(.success(36), at: 0)  // the real answer
        wait(for: [done], timeout: 5)
        steps.release(.success(999), at: 0)  // a provider answering AGAIN, long after the result was published
        drain(engine)
        XCTAssertEqual(try XCTUnwrap(result).distance.steps, 36)
        XCTAssertEqual(engine.snapshot().distance.steps, 36, "a published final result is never silently rewritten")
    }

    func testTheRestoreOutcomeIsAStructuredField() throws {
        let store = MemoryStore()
        let provider = FakeProvider(clock: clock)
        let first = makeEngine(provider, store)
        let _: NativeSnapshot = try run { first.start(config: config(), completion: $0) }
        runFixes(provider, seconds: 6)
        drain(first)
        XCTAssertEqual(first.snapshot().restoreState, .notRestored)
        let a = makeEngine(FakeProvider(clock: clock), store)
        XCTAssertEqual(try run { (c: @escaping (Result<NativeSnapshot, Error>) -> Void) in a.restore(config: config(), completion: c) }.restoreState, .complete)
        var lines = store.files["s"] ?? []
        lines[lines.count - 1] = String(lines[lines.count - 1].dropLast(5))
        store.files["s"] = lines
        let b = makeEngine(FakeProvider(clock: clock), store)
        XCTAssertEqual(try run { (c: @escaping (Result<NativeSnapshot, Error>) -> Void) in b.restore(config: config(), completion: c) }.restoreState, .tailRepaired)
        var damaged = store.files["s"] ?? []
        damaged[2] = String(damaged[2].prefix(10)) + "#" + String(damaged[2].dropFirst(11))
        store.files["s"] = damaged
        let c = makeEngine(FakeProvider(clock: clock), store)
        let partial: NativeSnapshot = try run { c.restore(config: config(), completion: $0) }
        XCTAssertEqual(partial.restoreState, .partial)
        XCTAssertLessThan(partial.fixCount, 6, "everything after the damage was NOT applied")
    }
}
