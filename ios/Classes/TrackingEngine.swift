import CoreLocation
import Darwin
import Foundation
import TrackingCore

/// The engine configuration the app sent, as the primitives the core stores in the journal header.
func makeSettings(_ e: EngineConfig, adaptiveSampling: Bool, batterySaver: Bool) -> BridgeSettings {
    BridgeSettings(
        maxAccuracyMeters: e.maxAccuracyMeters, maxFixAgeMs: e.maxFixAgeMs, silenceAfterMs: e.silenceAfterMs,
        maxSpeedMps: e.maxSpeedMps, minStepMeters: e.minStepMeters, accuracyNoiseFactor: e.accuracyNoiseFactor,
        stationarySpeedMps: e.stationarySpeedMps, maxBridgeMs: e.maxBridgeMs,
        reanchorAfterRejects: Int32(e.reanchorAfterRejects), rejectMock: e.rejectMock, pdrEnabled: e.pdrEnabled,
        pdrConfirmRatio: e.pdrConfirmRatio, kalman: e.kalman, stopRadiusMeters: e.stopRadiusMeters,
        stopMinDwellMs: e.stopMinDwellMs, initialStrideMeters: e.initialStrideMeters,
        pdrTrustedWhenCalibrated: e.pdrTrustedWhenCalibrated,
        recoveryStepEvidenceAfterMs: e.recoveryStepEvidenceAfterMs, adaptiveSampling: adaptiveSampling,
        batterySaver: batterySaver)
}

/// The monotonic clock the core runs on, in ms. `CLOCK_MONOTONIC` also counts
/// the time the device sleeps (unlike `systemUptime`), so a fix delivered after a
/// sleep is not squeezed into a shorter interval, and a wall-clock jump cannot
/// reorder or age a fix.
func monotonicNowMs() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000) }

/// Collects raw fixes and steps for one session at a time and feeds them to the shared Kotlin core
/// through a `SessionRecorder`, which writes every input to a journal BEFORE applying it.
///
///  - All state, including the core (which is not thread-safe), lives on `queue`; every public call hops there.
///  - Results, fixes and snapshots are delivered on the MAIN thread.
///  - The journal has ONE writer (`FileJournalSink`); `stop`, `readPersisted` and `clearSession` first
///    wait for it to reach the disk (flush barrier).
///  - Every asynchronous callback is checked against the session it belongs to: step readings by a
///    generation, the exact-steps correction by the (session token, segment) captured when the segment
///    was closed, so a late answer can never touch a newer session.
///  - `stop` answers only after the exact step count was obtained (or failed or timed out, which is
///    reported with a stable code and `stepsFinal == false`) and the journal is durable: its result is final.
///  - A failure is never swallowed: it is logged and surfaced in the snapshot.
///
/// It outlives the Flutter engine on purpose: if the engine detaches, native keeps recording AND keeps
/// computing the distance.
final class TrackingEngine: LocationProviderDelegate {
    private let provider: LocationProviding
    private let store: JournalStoring
    private let steps: StepProviding
    private let monotonicMs: () -> Int64
    private let wallNow: () -> Date
    private let queue = DispatchQueue(label: "com.uprace.flutter_tracking_gps.engine", qos: .userInitiated)
    private let storeQueue = DispatchQueue(label: "com.uprace.flutter_tracking_gps.store", qos: .utility)

    /// Receives each fix on the main thread. Set by the plugin while attached.
    var fixObserver: ((NativeFix) -> Void)?
    /// Receives the engine state on the main thread, about once per second.
    var snapshotObserver: ((NativeSnapshot) -> Void)?

    // State, only touched on `queue`.
    private var recorder: SessionRecorder?
    private var sink: DurableSink?
    private var tickTimer: DispatchSourceTimer?
    private var sessionId: String?
    private var state: NativeTrackingState = .idle
    private var segmentId: Int64 = 0
    private var seq: Int64 = 0
    private var fixCount: Int64 = 0
    private var lastFixTimeMs: Int64?
    private var lastError: String?
    private var restoreState: NativeRestoreState = .notRestored
    private var engineChanged = false
    /// Set by a restore of a FINISHED session: whether its step total was settled. nil for a live session.
    private var restoredStepsFinal: Bool?
    private var minDistanceMeters: Double = 0
    private var adaptive = false
    private var extras: [String: String] = [:]
    private var legStartMono: Int64 = 0
    private var permissionWaiters: [(NativePermission) -> Void] = []

    // Steps.
    private var stepsEnabled = false
    private var stepsStatus: NativeStepsStatus = .off
    private var stepsEverActive = false
    private var stepSegmentStart: Date?
    /// Bumped whenever the live step subscription ends, so a reading queued before it cannot reach a later segment.
    private var stepGeneration = 0
    /// Identity of the session: bumped at every start and restore. A late correction carries it.
    private var sessionToken = 0
    /// Step closes (a pause or the stop) of the CURRENT session that have not answered yet. A late answer of
    /// an older session carries an older token and never touches this count.
    private var openStepCloses = 0
    private var stepCloseWaiters: [() -> Void] = []
    /// Whether the finished session's step total is settled (every stepped segment got the exact count).
    private var liveStepsFinal = false
    /// The one finish in progress: a second `stop` joins it instead of starting another.
    private var finishOp: FinishOperation?

    /// A finish in progress, bound to the session it started for. Every asynchronous boundary re-checks it.
    private final class FinishOperation {
        let token: Int
        var completions: [(Result<NativeSnapshot, Error>) -> Void] = []
        init(token: Int) { self.token = token }
    }

    static let exactStepsTimeout: TimeInterval = 3
    static let permissionTimeout: TimeInterval = 120

    init(
        provider: LocationProviding, store: JournalStoring,
        steps: StepProviding = CoreMotionStepProvider(),
        monotonicMs: @escaping () -> Int64 = monotonicNowMs,
        wallNow: @escaping () -> Date = Date.init
    ) {
        self.provider = provider
        self.store = store
        self.steps = steps
        self.monotonicMs = monotonicMs
        self.wallNow = wallNow
        provider.delegate = self
    }

    // MARK: - Permission

    func permissionStatus() -> NativePermission {
        queue.sync { provider.permission }
    }

    func requestPermission(completion: @escaping (NativePermission) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let current = self.provider.permission
            guard current == .notDetermined else {
                DispatchQueue.main.async { completion(current) }
                return
            }
            self.permissionWaiters.append { status in
                DispatchQueue.main.async { completion(status) }
            }
            self.provider.requestWhenInUseAuthorization()
            // Bounded: if the system never answers, waiters get the current status instead of waiting forever.
            self.queue.asyncAfter(deadline: .now() + Self.permissionTimeout) { [weak self] in
                guard let self = self, !self.permissionWaiters.isEmpty else { return }
                let waiters = self.permissionWaiters
                self.permissionWaiters.removeAll()
                let status = self.provider.permission
                waiters.forEach { $0(status) }
            }
        }
    }

    /// Motion & Fitness permission for the step sensor.
    func stepsPermissionStatus() -> NativePermission { steps.permission }

    func requestStepsPermission(completion: @escaping (NativePermission) -> Void) {
        steps.requestPermission { status in DispatchQueue.main.async { completion(status) } }
    }

    // MARK: - Session

    func snapshot() -> NativeSnapshot {
        queue.sync { makeSnapshot() }
    }

    /// Coordinate-free timeline of what the core decided.
    func report() -> String {
        queue.sync { recorder?.report() ?? "" }
    }

    func start(config: StartConfig, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if self.state == .running { self.provider.stopUpdates() }
            self.stopTicker()
            self.endSteps()
            self.beginNewSession()
            // A fresh journal: a new start must never be mixed with an old one of the same id.
            do {
                try self.store.clear(config.sessionId)
            } catch {
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.startFailed, message: "could not prepare the journal",
                        underlying: error)),
                    to: completion)
                return
            }
            let sink = self.store.open(config.sessionId)
            let newExtras = [
                "intervalMs": String(config.intervalMs), "minDistanceMeters": String(config.minDistanceMeters),
                "stepsEnabled": String(config.stepsEnabled),
            ]
            let now = self.monotonicMs()
            let started = SessionRecorder.Companion.shared.tryStart(
                sink: sink,
                settings: makeSettings(config.engine, adaptiveSampling: config.adaptiveSampling, batterySaver: config.batterySaver),
                sessionId: config.sessionId, atMs: now, wallAtMs: self.wallMs(self.wallNow()),
                segmentId: Int32(config.startSegmentId), initialSteps: config.initialSteps, extras: newExtras)
            guard let recorder = started.recorder else {
                self.store.release(config.sessionId)
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.startFailed,
                        message: "the engine refused the configuration (\(started.error ?? "invalid"))")),
                    to: completion)
                return
            }
            self.recorder = recorder
            self.sink = sink
            self.sessionId = config.sessionId
            self.seq = config.startSeq
            self.segmentId = config.startSegmentId
            self.fixCount = 0
            self.lastFixTimeMs = nil
            self.lastError = nil
            self.restoreState = .notRestored
            self.engineChanged = false
            self.restoredStepsFinal = nil
            self.extras = newExtras
            self.minDistanceMeters = config.minDistanceMeters
            self.adaptive = config.adaptiveSampling
            self.stepsEnabled = config.stepsEnabled
            self.stepsStatus = .off
            self.stepsEverActive = false
            self.liveStepsFinal = false
            self.legStartMono = now
            guard self.provider.permission == .granted else {
                self.state = .permissionDenied
                self.publish()
                self.deliver(.success(self.makeSnapshot()), to: completion)
                return
            }
            do {
                try self.provider.startUpdates(minDistanceMeters: self.minDistanceMeters)
                self.state = .running
                self.startTicker()
                self.beginSteps()
                self.publish()
                self.deliver(.success(self.makeSnapshot()), to: completion)
            } catch TrackingNativeError.missingBackgroundMode {
                self.state = .error
                self.lastError = TrackingErrorCode.missingBackgroundMode
                self.publish()
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.missingBackgroundMode,
                        message: "Info.plist UIBackgroundModes must contain \"location\"",
                        underlying: TrackingNativeError.missingBackgroundMode)),
                    to: completion)
            } catch {
                self.state = .error
                self.lastError = "\(TrackingErrorCode.startFailed): \(error.localizedDescription)"
                self.publish()
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.startFailed, message: "could not start tracking",
                        underlying: error)),
                    to: completion)
            }
        }
    }

    func pause(sessionId id: String, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if id == self.sessionId, self.state == .running {
                self.provider.stopUpdates()
                self.stopTicker()
                self.state = .paused
                self.recorder?.pause(atMs: self.monotonicMs())
                self.closeStepSegment(completion: nil)
                self.publish()
            }
            self.deliver(.success(self.makeSnapshot()), to: completion)
        }
    }

    func resume(sessionId id: String, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard id == self.sessionId, self.state == .paused else {
                self.deliver(.success(self.makeSnapshot()), to: completion)
                return
            }
            do {
                self.segmentId += 1
                self.recorder?.resume(atMs: self.monotonicMs(), segmentId: Int32(self.segmentId))
                self.legStartMono = self.monotonicMs()
                try self.provider.startUpdates(minDistanceMeters: self.minDistanceMeters)
                self.state = .running
                self.startTicker()
                self.beginSteps()
                self.publish()
                self.deliver(.success(self.makeSnapshot()), to: completion)
            } catch {
                self.state = .error
                self.lastError = "\(TrackingErrorCode.resumeFailed): \(error.localizedDescription)"
                self.publish()
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.resumeFailed, message: "could not resume tracking",
                        underlying: error)),
                    to: completion)
            }
        }
    }

    /// Finishes the session. Answers only after the exact step count is in (or failed, or timed out) and
    /// the journal is durable, so the returned snapshot is final. A second `stop` while one is in progress
    /// JOINS it (same result, one finalization); a finish overtaken by a newer start/restore is answered with
    /// an explicit `SUPERSEDED` failure, never with the newer session's snapshot.
    func stop(sessionId id: String, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if id == self.sessionId, self.state == .finishing, let op = self.finishOp, op.token == self.sessionToken {
                op.completions.append(completion)
                return
            }
            guard id == self.sessionId, self.state != .idle, self.state != .stopped, self.state != .finishing else {
                self.deliver(.success(self.makeSnapshot()), to: completion)
                return
            }
            let wasRunning = self.state == .running
            self.provider.stopUpdates()
            self.stopTicker()
            // FINISHING until the exact step count is in and the journal is durable: nothing published
            // meanwhile may pass for a final result.
            self.state = .finishing
            self.recorder?.finish(atMs: self.monotonicMs())
            let op = FinishOperation(token: self.sessionToken)
            op.completions.append(completion)
            self.finishOp = op
            let finalize: () -> Void = { [weak self] in
                guard let self = self, self.finishOp === op, op.token == self.sessionToken else { return }
                // Settle the step total IN the journal before the flush: a restore of this session then
                // reports the same finality as the live result. Per segment: one provisional segment keeps
                // the whole total provisional.
                let settled = self.recorder?.allStepsExact() ?? true
                self.recorder?.markFinalized(stepsFinal: settled)
                let recorder = self.recorder
                let finishedId = id
                self.storeQueue.async { [weak self] in
                    _ = recorder?.flush()
                    self?.queue.async { [weak self] in
                        guard let self = self else { return }
                        // The session may have been replaced while the journal was flushing.
                        guard self.finishOp === op, op.token == self.sessionToken else {
                            self.finish(op, with: .failure(trackingError(
                                code: TrackingErrorCode.superseded,
                                message: "the session was replaced by a newer one before it finished")))
                            return
                        }
                        self.liveStepsFinal = settled
                        self.state = .stopped
                        self.finishOp = nil
                        self.storeQueue.async { [weak self] in self?.store.release(finishedId) }
                        self.publish()
                        self.finish(op, with: .success(self.makeSnapshot()))
                    }
                }
            }
            if wasRunning {
                self.closeStepSegment(completion: finalize)
            } else {
                self.afterStepClose(finalize)  // paused: its close may still be in flight
            }
        }
    }

    /// Answers every caller waiting on `op`, once.
    private func finish(_ op: FinishOperation, with result: Result<NativeSnapshot, Error>) {
        let waiting = op.completions
        op.completions.removeAll()
        waiting.forEach { deliver(result, to: $0) }
    }

    /// A start or restore takes over: a finish still in progress is answered with an explicit failure and
    /// forgotten, and the old session's step-close bookkeeping is dropped (its late answers carry an older
    /// token and are ignored).
    private func beginNewSession() {
        if let op = finishOp {
            finishOp = nil
            finish(op, with: .failure(trackingError(
                code: TrackingErrorCode.superseded,
                message: "the session was replaced by a newer one before it finished")))
        }
        sessionToken += 1
        openStepCloses = 0
        stepCloseWaiters.removeAll()
    }

    /// A step estimate for `segmentId` from the app's own source: held as pending until the GPS that
    /// returns confirms it. One that belongs to another segment is refused by the core.
    func submitPdr(sessionId id: String, segmentId segment: Int64, meters: Double) {
        queue.async { [weak self] in
            guard let self = self, id == self.sessionId, self.state == .running else { return }
            self.recorder?.submitPdr(segmentId: Int32(segment), atMs: self.monotonicMs(), meters: meters)
            self.publish()
        }
    }

    /// After a process death: replays the session's journal (with the configuration stored in ITS header,
    /// not the one the app passes now) and leaves the session PAUSED. A damaged last record is dropped and
    /// the journal repaired so new records can be appended; damage in the middle keeps only the intact part
    /// and says so in the snapshot. If this session already runs, nothing is replayed.
    func restore(config: StartConfig, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        storeQueue.async { [weak self] in
            guard let self = self else { return }
            let lines: [String]
            do {
                lines = try self.store.read(config.sessionId)
            } catch {
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.restoreFailed, message: "could not read the journal",
                        underlying: error)),
                    to: completion)
                return
            }
            self.queue.async { [weak self] in
                guard let self = self else { return }
                let running = self.state == .running || self.state == .paused || self.state == .finishing
                if config.sessionId == self.sessionId, running {
                    self.deliver(.success(self.makeSnapshot()), to: completion)
                    return
                }
                self.stopTicker()
                self.endSteps()
                if self.state == .running { self.provider.stopUpdates() }
                let contents = JournalCodec.shared.read(lines: lines)
                guard let header = contents.records.first as? JournalRecord.Header else {
                    self.deliver(
                        .failure(trackingError(
                            code: TrackingErrorCode.restoreFailed,
                            message: "no usable journal for this session (\(contents.status))")),
                        to: completion)
                    return
                }
                let usable = Array(lines.filter { !$0.isEmpty }.prefix(contents.records.count))
                if contents.status != .ok {
                    // Only a damaged LAST record is a normal crash artefact; damage in the middle drops
                    // everything after it, so the original is kept next to the repaired file.
                    do { try self.store.rewrite(config.sessionId, lines: usable, keepBackup: contents.status != .truncatedTail) } catch {
                        self.deliver(
                            .failure(trackingError(
                                code: TrackingErrorCode.restoreFailed, message: "could not repair the journal",
                                underlying: error)),
                            to: completion)
                        return
                    }
                }
                let sink = self.store.open(config.sessionId)
                let result = SessionRecorder.Companion.shared.restore(sink: sink, lines: usable)
                guard let recorder = result.recorder else {
                    self.deliver(
                        .failure(trackingError(
                            code: TrackingErrorCode.restoreFailed, message: "journal replay failed (\(result.status))")),
                        to: completion)
                    return
                }
                self.beginNewSession()
                self.recorder = recorder
                self.sink = sink
                self.sessionId = result.sessionId
                self.seq = result.nextSeq
                self.segmentId = Int64(result.segmentId)
                self.fixCount = Int64(result.fixCount)
                self.lastFixTimeMs = result.lastFixWallMs >= 0 ? result.lastFixWallMs : nil
                self.extras = result.extras
                self.adaptive = header.settings.adaptiveSampling
                self.stepsEnabled = result.extras["stepsEnabled"] == "true"
                self.stepsStatus = .off
                self.stepsEverActive = false
                self.liveStepsFinal = false
                self.minDistanceMeters = Double(result.extras["minDistanceMeters"] ?? "") ?? config.minDistanceMeters
                if result.engineChanged {
                    self.lastError = "\(TrackingErrorCode.engineChanged): the journal was recorded with engine \(result.engineVersion); the replay may differ from what was shown live"
                }
                switch contents.status {
                case .ok: if !result.engineChanged { self.lastError = nil }
                case .truncatedTail:
                    self.lastError = "\(TrackingErrorCode.journalRepaired): the last record was incomplete and was dropped"
                default:
                    self.lastError = "\(TrackingErrorCode.journalCorrupt): damage at line \(contents.damagedLine); only the intact part was restored"
                }
                self.engineChanged = result.engineChanged
                switch contents.status {
                case .ok: self.restoreState = .complete
                case .truncatedTail: self.restoreState = .tailRepaired
                default: self.restoreState = .partial
                }
                // A journal that ended at Finish is a finished session: read-only, never "paused".
                if result.finished {
                    self.restoredStepsFinal = result.stepsFinal
                    self.state = .stopped
                } else {
                    self.restoredStepsFinal = nil
                    self.state = .paused
                }
                self.publish()
                self.deliver(.success(self.makeSnapshot()), to: completion)
            }
        }
    }

    // MARK: - Persistence

    /// The persisted fixes (after the writer reached the disk), oldest first.
    func readPersisted(
        sessionId: String, afterSeq: Int64, completion: @escaping (Result<[NativeFix], Error>) -> Void
    ) {
        storeQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                let lines = try self.store.read(sessionId)
                let fixes = SessionRecorder.Companion.shared.fixes(lines: lines)
                    .filter { $0.seq > afterSeq }
                    .map { Self.nativeFix($0) }
                self.deliver(.success(fixes), to: completion)
            } catch {
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.storeReadFailed,
                        message: "could not read the persisted fixes", underlying: error)),
                    to: completion)
            }
        }
    }

    /// Deletes the journal. The writer is closed and drained first, so nothing re-creates the file. A
    /// session that is still open cannot be cleared.
    func clearSession(_ sessionId: String, completion: @escaping (Result<Void, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if sessionId == self.sessionId, self.state == .running || self.state == .paused || self.state == .finishing {
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.sessionOpen, message: "finish the session before clearing it")),
                    to: completion)
                return
            }
            if sessionId == self.sessionId {
                self.recorder = nil
                self.sink = nil
            }
            self.storeQueue.async { [weak self] in
                guard let self = self else { return }
                do {
                    try self.store.clear(sessionId)
                    self.deliver(.success(()), to: completion)
                } catch {
                    self.deliver(
                        .failure(trackingError(
                            code: TrackingErrorCode.storeClearFailed,
                            message: "could not clear the session", underlying: error)),
                        to: completion)
                }
            }
        }
    }

    func listSessions(completion: @escaping (Result<[String], Error>) -> Void) {
        storeQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                self.deliver(.success(try self.store.list()), to: completion)
            } catch {
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.storeListFailed, message: "could not list the sessions",
                        underlying: error)),
                    to: completion)
            }
        }
    }

    // MARK: - LocationProviderDelegate

    func locationProvider(_ provider: LocationProviding, didUpdate locations: [CLLocation]) {
        // The arrival clocks are read HERE, in the callback, not later on the queue: that is the
        // moment the fix reached us.
        let arrivalMono = monotonicMs()
        let arrivalWall = wallNow()
        queue.async { [weak self] in
            guard let self = self, self.state == .running, let id = self.sessionId, let recorder = self.recorder
            else { return }
            for location in locations {
                // A negative horizontal accuracy means CoreLocation says the coordinate itself is invalid.
                guard location.horizontalAccuracy >= 0 else { continue }
                let ageMs = Int64((arrivalWall.timeIntervalSince(location.timestamp) * 1000).rounded())
                let elapsedMs = arrivalMono - ageMs
                // Measured before this leg started: it belongs to an earlier segment (a callback queued
                // before a pause), never to this one.
                guard elapsedMs >= self.legStartMono else { continue }
                let n = self.seq
                self.seq += 1
                self.fixCount += 1
                self.lastFixTimeMs = Int64(location.timestamp.timeIntervalSince1970 * 1000)
                var mock = false
                if #available(iOS 15.0, *) { mock = location.sourceInformation?.isSimulatedBySoftware ?? false }
                _ = recorder.onFix(
                    sessionId: id, segmentId: Int32(self.segmentId), seq: n, timeMs: elapsedMs,
                    wallTimeMs: Int64(location.timestamp.timeIntervalSince1970 * 1000),
                    latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                    horizontalAccuracy: location.horizontalAccuracy,
                    speed: location.speed < 0 ? -1 : location.speed,
                    speedAccuracy: location.speedAccuracy < 0 ? -1 : location.speedAccuracy,
                    course: location.course < 0 ? -1 : location.course,
                    altitude: location.verticalAccuracy < 0 ? 0 : location.altitude,
                    verticalAccuracy: location.verticalAccuracy < 0 ? -1 : location.verticalAccuracy,
                    provider: "gps", isMock: mock, nowMs: arrivalMono)
                let fix = Self.nativeFix(
                    id: id, segment: self.segmentId, seq: n, location: location, elapsedMs: elapsedMs, mock: mock)
                DispatchQueue.main.async { [weak self] in self?.fixObserver?(fix) }
                if self.adaptive, let plan = recorder.lastPlan {
                    // iOS has no interval request: only the displacement filter moves.
                    if plan.minDistanceMeters != self.minDistanceMeters {
                        self.minDistanceMeters = plan.minDistanceMeters
                        self.provider.updateRequest(minDistanceMeters: plan.minDistanceMeters)
                    }
                }
            }
            self.publish()
        }
    }

    func locationProvider(_ provider: LocationProviding, didFailWith error: Error) {
        queue.async { [weak self] in
            // Only a recording session reacts: an error that arrives after pause/stop belongs to nothing.
            guard let self = self, self.state == .running else { return }
            // kCLErrorLocationUnknown is transient: CoreLocation keeps trying.
            let code = (error as NSError).code
            if (error as NSError).domain == kCLErrorDomain, code == CLError.locationUnknown.rawValue {
                self.lastError = "\(TrackingErrorCode.locationFailed): location unknown (transient)"
                NSLog("[flutter_tracking_gps] location unknown (transient)")
                return
            }
            NSLog("[flutter_tracking_gps] location updates failed: %@", String(describing: error))
            self.lastError = "\(TrackingErrorCode.locationFailed): \(error.localizedDescription)"
            if (error as NSError).domain == kCLErrorDomain, code == CLError.denied.rawValue {
                self.state = .permissionDenied
            }
            self.publish()
        }
    }

    func locationProviderDidChangeAuthorization(_ provider: LocationProviding) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let status = self.provider.permission
            guard status != .notDetermined else { return }
            let waiters = self.permissionWaiters
            self.permissionWaiters.removeAll()
            waiters.forEach { $0(status) }
            if status != .granted, self.state == .running { self.state = .permissionDenied }
        }
    }

    // MARK: - Steps (queue)

    /// Starts counting steps for the segment that just opened. A missing sensor, permission or setup
    /// only sets `stepsStatus` and `lastError` with a stable code: the recording goes on.
    private func beginSteps() {
        guard stepsEnabled else {
            stepsStatus = .off
            return
        }
        if case .failure(let error) = steps.availability() {
            stepsStatus = Self.status(for: error)
            lastError = "\(error.code): \(error.message)"
            recorder?.setStepSensorActive(active: false)
            return
        }
        stepsStatus = .active
        stepsEverActive = true
        recorder?.setStepSensorActive(active: true)
        stepGeneration += 1
        let generation = stepGeneration
        let segment = Int32(segmentId)
        let start = wallNow()
        stepSegmentStart = start
        let monotonic = monotonicMs
        let wall = wallNow
        steps.startUpdates(from: start) { [weak self] result in
            // Both (injected) clocks are read in the callback: that is when the reading reached us.
            let arrivalMono = monotonic()
            let arrivalWall = wall()
            self?.queue.async { [weak self] in
                guard let self = self, generation == self.stepGeneration, self.state == .running else { return }
                switch result {
                case .success(let reading):
                    // The data's own end date is when the steps were measured.
                    let ageMs = Int64((arrivalWall.timeIntervalSince(reading.endDate) * 1000).rounded())
                    let measured = arrivalMono - max(0, ageMs)
                    self.recorder?.onSegmentSteps(
                        segmentId: segment, measuredAtMs: measured, deliveredAtMs: arrivalMono, count: reading.count)
                case .failure(let error):
                    // A runtime failure (for example access revoked) is reported, never swallowed.
                    self.stepsStatus = Self.status(for: error)
                    self.lastError = "\(error.code): \(error.message)"
                    self.recorder?.setStepSensorActive(active: false)
                    self.steps.stopUpdates()
                }
                self.publish()
            }
        }
    }

    /// Ends the live subscription (a new session replaces an open one).
    private func endSteps() {
        stepGeneration += 1
        steps.stopUpdates()
        stepSegmentStart = nil
    }

    /// Runs `block` once no step close of the current session is in flight (immediately when none is).
    private func afterStepClose(_ block: @escaping () -> Void) {
        if openStepCloses > 0 {
            stepCloseWaiters.append(block)
        } else {
            block()
        }
    }

    /// Ends the live subscription at a pause or stop and replaces the segment's live count with the OS's
    /// exact count up to the boundary. The answer carries the identity captured NOW (session token and
    /// segment): it is dropped when another session is current. It only corrects the displayed total, never
    /// distance. The outcome is journaled PER SEGMENT (`markSegmentSettled`): a segment whose query failed
    /// stays provisional for good and is never upgraded by another segment's success. `completion` runs on
    /// `queue` once the close is settled (exact, failed or timed out); nil for a pause, which does not wait.
    private func closeStepSegment(completion: (() -> Void)?) {
        let start = stepSegmentStart
        let token = sessionToken
        let closedSegment = segmentId
        let boundary = wallNow()
        endSteps()
        guard stepsEnabled, stepsStatus == .active, let start = start else {
            completion?()
            return
        }
        openStepCloses += 1
        var settled = false  // touched only on `queue`: a SECOND answer (a late one after a timeout) is ignored
        steps.query(from: start, to: boundary, timeout: Self.exactStepsTimeout) { [weak self] result in
            self?.queue.async { [weak self] in
                guard let self = self, !settled else { return }
                settled = true
                // Another session is current: nothing of this answer applies, and the counters are not ours.
                guard token == self.sessionToken else { return }
                switch result {
                case .success(let exact):
                    // The correction applies to the segment that was closed, which may no longer be the latest.
                    if self.segmentId == closedSegment { self.recorder?.correctClosedSegment(exactSteps: exact) }
                    self.recorder?.markSegmentSettled(segmentId: Int32(closedSegment), exact: self.segmentId == closedSegment)
                case .failure(let error):
                    // Not "0 steps": the total stays the live count and says so.
                    self.lastError = "\(error.code): \(error.message)"
                    self.recorder?.markSegmentSettled(segmentId: Int32(closedSegment), exact: false)
                }
                self.publish()
                self.openStepCloses -= 1
                completion?()
                if self.openStepCloses == 0 {
                    let waiters = self.stepCloseWaiters
                    self.stepCloseWaiters.removeAll()
                    waiters.forEach { $0() }
                }
            }
        }
    }

    private static func status(for error: StepError) -> NativeStepsStatus {
        switch error {
        case .permissionDenied: return .permissionDenied
        case .unavailable: return .unavailable
        case .missingUsageDescription, .timeout, .failed: return .failed
        }
    }

    // MARK: - Ticker (queue)

    /// A 1 Hz tick: lets the core notice GPS silence and refreshes the snapshot.
    private func startTicker() {
        stopTicker()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self = self, self.state == .running else { return }
            _ = self.recorder?.onTick(nowMs: self.monotonicMs())
            self.publish()
        }
        timer.resume()
        tickTimer = timer
    }

    private func stopTicker() {
        tickTimer?.cancel()
        tickTimer = nil
    }

    // MARK: - Snapshot (queue)

    private func makeSnapshot() -> NativeSnapshot {
        var error = lastError
        if error == nil, let failure = sink?.failure {
            error = "\(TrackingErrorCode.storeWriteFailed): \(failure.localizedDescription)"
        }
        let finalSteps = state == .stopped && (restoredStepsFinal ?? liveStepsFinal)
        return NativeSnapshot(
            stepsStatus: stepsStatus, restoreState: restoreState, engineChanged: engineChanged,
            journalPending: Int64(sink?.pending ?? 0), journalFailed: sink?.failure != nil, stepsFinal: finalSteps,
            sessionId: sessionId, state: state, segmentId: segmentId, distance: makeDistance(),
            fixCount: fixCount, lastFixTimeMs: lastFixTimeMs,
            updatedAtMs: wallMs(wallNow()), error: error)
    }

    private func makeDistance() -> NativeDistance {
        guard let s = recorder?.snapshot() else { return Self.emptyDistance }
        let quality: NativeGpsQuality
        switch s.quality {
        case 1: quality = .good
        case 2: quality = .degraded
        case 3: quality = .unavailable
        case 4: quality = .recovering
        default: quality = .unknown
        }
        // The core measures on the monotonic clock; the app wants a calendar time.
        let lastFixEpoch: Int64 = s.lastFixAtMs >= 0 ? wallMs(wallNow()) - (monotonicMs() - s.lastFixAtMs) : -1
        return NativeDistance(
            quality: quality, totalMeters: s.totalMeters, gpsMeters: s.gpsMeters,
            gapBridgeMeters: s.gapBridgeMeters, pdrMeters: s.pdrMeters,
            pendingPdrMeters: s.pendingPdrMeters, movingTimeMs: s.movingTimeMs, isStopped: s.isStopped,
            speedMps: s.speedMps, lastAcceptedFixAtMs: lastFixEpoch, steps: s.steps,
            strideMeters: s.strideMeters, activeTimeMs: s.activeTimeMs, bridgedGapMs: s.bridgedGapMs,
            uncreditedGapMs: s.uncreditedGapMs, uncreditedGaps: Int64(s.uncreditedGaps),
            ambiguousSteps: s.ambiguousSteps, distanceEligibleSteps: s.distanceEligibleSteps)
    }

    static let emptyDistance = NativeDistance(
        quality: .unknown, totalMeters: 0, gpsMeters: 0, gapBridgeMeters: 0, pdrMeters: 0,
        pendingPdrMeters: 0, movingTimeMs: 0, isStopped: false, speedMps: -1, lastAcceptedFixAtMs: -1,
        steps: 0, strideMeters: -1, activeTimeMs: 0, bridgedGapMs: 0, uncreditedGapMs: 0, uncreditedGaps: 0,
        ambiguousSteps: 0, distanceEligibleSteps: 0)

    private func publish() {
        let snapshot = makeSnapshot()
        DispatchQueue.main.async { [weak self] in self?.snapshotObserver?(snapshot) }
    }

    private func wallMs(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    private static func nativeFix(
        id: String, segment: Int64, seq: Int64, location l: CLLocation, elapsedMs: Int64, mock: Bool
    ) -> NativeFix {
        NativeFix(
            sessionId: id, segmentId: segment, seq: seq,
            timeMs: Int64(l.timestamp.timeIntervalSince1970 * 1000), elapsedMs: elapsedMs,
            latitude: l.coordinate.latitude, longitude: l.coordinate.longitude,
            horizontalAccuracy: l.horizontalAccuracy, speed: l.speed < 0 ? -1 : l.speed,
            speedAccuracy: l.speedAccuracy < 0 ? -1 : l.speedAccuracy,
            course: l.course < 0 ? -1 : l.course,
            altitude: l.verticalAccuracy < 0 ? 0 : l.altitude,
            verticalAccuracy: l.verticalAccuracy < 0 ? -1 : l.verticalAccuracy,
            provider: "gps", isMock: mock)
    }

    private static func nativeFix(_ f: JournalRecord.Fix) -> NativeFix {
        NativeFix(
            sessionId: f.sessionId, segmentId: Int64(f.segmentId), seq: f.seq, timeMs: f.wallTimeMs,
            elapsedMs: f.timeMs, latitude: f.latitude, longitude: f.longitude,
            horizontalAccuracy: f.horizontalAccuracy, speed: f.speed, speedAccuracy: f.speedAccuracy,
            course: f.course, altitude: f.altitude, verticalAccuracy: f.verticalAccuracy,
            provider: f.provider, isMock: f.isMock)
    }

    /// Delivers a result on the main thread, as Flutter requires.
    private func deliver<T>(_ result: Result<T, Error>, to completion: @escaping (Result<T, Error>) -> Void) {
        DispatchQueue.main.async { completion(result) }
    }
}
