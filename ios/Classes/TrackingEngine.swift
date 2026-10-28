import CoreLocation
import Darwin
import Foundation
import TrackingCore

/// Builds the shared Kotlin core from the channel's `EngineConfig`.
func makeCoreBridge(_ e: EngineConfig, adaptiveSampling: Bool, batterySaver: Bool) -> TrackingCoreBridge {
    TrackingCoreBridge(
        maxAccuracyMeters: e.maxAccuracyMeters, maxFixAgeMs: e.maxFixAgeMs,
        silenceAfterMs: e.silenceAfterMs, maxSpeedMps: e.maxSpeedMps, minStepMeters: e.minStepMeters,
        accuracyNoiseFactor: e.accuracyNoiseFactor, stationarySpeedMps: e.stationarySpeedMps,
        maxBridgeMs: e.maxBridgeMs, reanchorAfterRejects: Int32(e.reanchorAfterRejects),
        rejectMock: e.rejectMock, pdrEnabled: e.pdrEnabled, pdrConfirmRatio: e.pdrConfirmRatio,
        kalman: e.kalman, stopRadiusMeters: e.stopRadiusMeters, stopMinDwellMs: e.stopMinDwellMs,
        adaptiveSampling: adaptiveSampling, batterySaver: batterySaver)
}

/// The monotonic clock the core runs on, in ms. `CLOCK_MONOTONIC` also counts
/// the time the device sleeps (unlike `systemUptime`), so a fix delivered after a
/// sleep is not squeezed into a shorter interval, and a wall-clock jump cannot
/// reorder or age a fix.
func monotonicNowMs() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000) }

/// Collects raw fixes for one session at a time, persists them, and feeds the
/// shared Kotlin distance engine (`GpsDistanceEngine` through
/// `TrackingCoreBridge`).
///
///  - All state, including the core (which is not thread-safe), lives on `queue`;
///    every public call hops there.
///  - Results, fixes and snapshots are delivered on the MAIN thread.
///  - Disk writes run on `storeQueue`, one fix at a time, in order.
///  - A failure is never swallowed: it is logged and surfaced in the snapshot.
///
/// It outlives the Flutter engine on purpose: if the engine detaches, native
/// keeps recording AND keeps computing the distance.
final class TrackingEngine: LocationProviderDelegate {
    private let provider: LocationProviding
    private let store: FixStoring
    private let coreFactory: (EngineConfig, Bool, Bool) -> TrackingCoreBridge
    private let monotonicMs: () -> Int64
    private let wallNow: () -> Date
    private let queue = DispatchQueue(label: "com.uprace.flutter_tracking_gps.engine", qos: .userInitiated)
    private let storeQueue = DispatchQueue(label: "com.uprace.flutter_tracking_gps.store", qos: .utility)

    /// Receives each fix on the main thread. Set by the plugin while attached.
    var fixObserver: ((NativeFix) -> Void)?
    /// Receives the engine state on the main thread, about once per second.
    var snapshotObserver: ((NativeSnapshot) -> Void)?

    // State, only touched on `queue`.
    private var core: TrackingCoreBridge?
    private var tickTimer: DispatchSourceTimer?
    private var sessionId: String?
    private var state: NativeTrackingState = .idle
    private var segmentId: Int64 = 0
    private var seq: Int64 = 0
    private var fixCount: Int64 = 0
    private var lastFixTimeMs: Int64?
    private var lastError: String?
    private var minDistanceMeters: Double = 0
    private var adaptive = false
    private var permissionWaiters: [(NativePermission) -> Void] = []

    init(
        provider: LocationProviding, store: FixStoring,
        coreFactory: @escaping (EngineConfig, Bool, Bool) -> TrackingCoreBridge = {
            makeCoreBridge($0, adaptiveSampling: $1, batterySaver: $2)
        },
        monotonicMs: @escaping () -> Int64 = monotonicNowMs,
        wallNow: @escaping () -> Date = Date.init
    ) {
        self.provider = provider
        self.store = store
        self.coreFactory = coreFactory
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
        }
    }

    // MARK: - Session

    func snapshot() -> NativeSnapshot {
        queue.sync { makeSnapshot() }
    }

    /// Coordinate-free timeline of what the core decided.
    func report() -> String {
        queue.sync { core?.report() ?? "" }
    }

    func start(config: StartConfig, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if self.state == .running { self.provider.stopUpdates() }
            self.stopTicker()
            self.sessionId = config.sessionId
            self.seq = config.startSeq
            self.segmentId = config.startSegmentId
            self.fixCount = 0
            self.lastFixTimeMs = nil
            self.lastError = nil
            self.minDistanceMeters = config.minDistanceMeters
            self.adaptive = config.adaptiveSampling
            let bridge = self.coreFactory(config.engine, config.adaptiveSampling, config.batterySaver)
            bridge.start(sessionId: config.sessionId, atMs: self.monotonicMs(), segmentId: Int32(config.startSegmentId))
            self.core = bridge
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
                self.core?.pause(atMs: self.monotonicMs())
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
                self.core?.resume(atMs: self.monotonicMs(), segmentId: Int32(self.segmentId))
                try self.provider.startUpdates(minDistanceMeters: self.minDistanceMeters)
                self.state = .running
                self.startTicker()
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

    func stop(sessionId id: String, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if id == self.sessionId, self.state != .idle, self.state != .stopped {
                self.provider.stopUpdates()
                self.stopTicker()
                self.state = .stopped
                self.core?.finish(atMs: self.monotonicMs())
                self.publish()
            }
            self.deliver(.success(self.makeSnapshot()), to: completion)
        }
    }

    /// A step estimate for `segmentId`: held as pending until the GPS that returns
    /// confirms it. One that belongs to another segment (a late estimate after a
    /// resume) is refused by the core.
    func submitPdr(sessionId id: String, segmentId segment: Int64, meters: Double) {
        queue.async { [weak self] in
            guard let self = self, id == self.sessionId else { return }
            self.core?.submitPdr(segmentId: Int32(segment), atMs: self.monotonicMs(), meters: meters)
            self.publish()
        }
    }

    /// After a process death: rebuilds the core by replaying the persisted fixes
    /// (segments are never joined) and leaves the session PAUSED. If this session
    /// already runs, nothing is replayed.
    func restore(config: StartConfig, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        storeQueue.async { [weak self] in
            guard let self = self else { return }
            let persisted: [NativeFix]
            do {
                persisted = try self.store.read(sessionId: config.sessionId, afterSeq: -1)
            } catch {
                self.deliver(
                    .failure(trackingError(
                        code: TrackingErrorCode.restoreFailed,
                        message: "could not restore the session", underlying: error)),
                    to: completion)
                return
            }
            self.queue.async {
                let running = self.state == .running || self.state == .paused
                if config.sessionId == self.sessionId, running {
                    self.deliver(.success(self.makeSnapshot()), to: completion)
                    return
                }
                self.stopTicker()
                let bridge = self.coreFactory(config.engine, config.adaptiveSampling, config.batterySaver)
                var segment = persisted.first?.segmentId ?? config.startSegmentId
                var lastSeq: Int64 = -1
                bridge.start(
                    sessionId: config.sessionId, atMs: persisted.first?.elapsedMs ?? self.monotonicMs(),
                    segmentId: Int32(segment))
                for fix in persisted {
                    if fix.segmentId != segment {
                        bridge.pause(atMs: fix.elapsedMs)
                        bridge.resume(atMs: fix.elapsedMs, segmentId: Int32(fix.segmentId))
                        segment = fix.segmentId
                    }
                    _ = bridge.onFix(
                        sessionId: fix.sessionId, segmentId: Int32(fix.segmentId), seq: fix.seq,
                        timeMs: fix.elapsedMs, latitude: fix.latitude, longitude: fix.longitude,
                        horizontalAccuracy: fix.horizontalAccuracy, speed: fix.speed,
                        speedAccuracy: fix.speedAccuracy, isMock: fix.isMock,
                        nowMs: fix.elapsedMs, replay: true)
                    lastSeq = max(lastSeq, fix.seq)
                }
                bridge.pause(atMs: self.monotonicMs())
                self.core = bridge
                self.sessionId = config.sessionId
                self.adaptive = config.adaptiveSampling
                self.minDistanceMeters = config.minDistanceMeters
                self.segmentId = segment
                self.seq = lastSeq + 1
                self.fixCount = Int64(persisted.count)
                self.lastFixTimeMs = persisted.last?.timeMs
                self.lastError = nil
                self.state = .paused
                self.publish()
                self.deliver(.success(self.makeSnapshot()), to: completion)
            }
        }
    }

    // MARK: - Persistence

    func readPersisted(
        sessionId: String, afterSeq: Int64, completion: @escaping (Result<[NativeFix], Error>) -> Void
    ) {
        storeQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                let fixes = try self.store.read(sessionId: sessionId, afterSeq: afterSeq)
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

    func clearSession(_ sessionId: String, completion: @escaping (Result<Void, Error>) -> Void) {
        storeQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                try self.store.clearSession(sessionId)
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

    // MARK: - LocationProviderDelegate

    func locationProvider(_ provider: LocationProviding, didUpdate locations: [CLLocation]) {
        // The arrival clocks are read HERE, in the callback, not later on the
        // queue: that is the moment the fix reached us.
        let arrivalMono = monotonicMs()
        let arrivalWall = wallNow()
        queue.async { [weak self] in
            guard let self = self, self.state == .running, let id = self.sessionId else { return }
            for location in locations {
                // A negative horizontal accuracy means CoreLocation says the
                // coordinate itself is invalid.
                guard location.horizontalAccuracy >= 0 else { continue }
                let fix = self.makeFix(
                    sessionId: id, location: location, arrivalMono: arrivalMono, arrivalWall: arrivalWall)
                self.seq += 1
                self.fixCount += 1
                self.lastFixTimeMs = fix.timeMs
                _ = self.core?.onFix(
                    sessionId: fix.sessionId, segmentId: Int32(fix.segmentId), seq: fix.seq,
                    timeMs: fix.elapsedMs, latitude: fix.latitude, longitude: fix.longitude,
                    horizontalAccuracy: fix.horizontalAccuracy, speed: fix.speed,
                    speedAccuracy: fix.speedAccuracy, isMock: fix.isMock,
                    nowMs: arrivalMono, replay: false)
                self.persist(fix)
                DispatchQueue.main.async { [weak self] in self?.fixObserver?(fix) }
                if self.adaptive, let plan = self.core?.samplingPlan(nowMs: arrivalMono) {
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
            guard let self = self else { return }
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

    // MARK: - Private (queue)

    /// A 1 Hz tick: lets the core notice GPS silence and refreshes the snapshot.
    private func startTicker() {
        stopTicker()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            _ = self.core?.onTick(nowMs: self.monotonicMs())
            self.publish()
        }
        timer.resume()
        tickTimer = timer
    }

    private func stopTicker() {
        tickTimer?.cancel()
        tickTimer = nil
    }

    private func makeSnapshot() -> NativeSnapshot {
        NativeSnapshot(
            sessionId: sessionId, state: state, segmentId: segmentId, distance: makeDistance(),
            fixCount: fixCount, lastFixTimeMs: lastFixTimeMs,
            updatedAtMs: Int64((wallNow().timeIntervalSince1970 * 1000).rounded()), error: lastError)
    }

    private func makeDistance() -> NativeDistance {
        guard let s = core?.snapshot() else { return Self.emptyDistance }
        let quality: NativeGpsQuality
        switch s.quality {
        case 1: quality = .good
        case 2: quality = .degraded
        case 3: quality = .unavailable
        case 4: quality = .recovering
        default: quality = .unknown
        }
        return NativeDistance(
            quality: quality, totalMeters: s.totalMeters, gpsMeters: s.gpsMeters,
            gapBridgeMeters: s.gapBridgeMeters, pdrMeters: s.pdrMeters,
            pendingPdrMeters: s.pendingPdrMeters, movingTimeMs: s.movingTimeMs, isStopped: s.isStopped,
            speedMps: s.speedMps, lastAcceptedFixAtMs: s.lastFixAtMs)
    }

    static let emptyDistance = NativeDistance(
        quality: .unknown, totalMeters: 0, gpsMeters: 0, gapBridgeMeters: 0, pdrMeters: 0,
        pendingPdrMeters: 0, movingTimeMs: 0, isStopped: false, speedMps: -1, lastAcceptedFixAtMs: -1)

    private func publish() {
        let snapshot = makeSnapshot()
        DispatchQueue.main.async { [weak self] in self?.snapshotObserver?(snapshot) }
    }

    /// `elapsedMs` is the moment the position was measured on the monotonic clock:
    /// the arrival time minus the fix age (the age is negative only for a clock
    /// that is slightly off, and that must not reorder fixes either).
    private func makeFix(
        sessionId: String, location l: CLLocation, arrivalMono: Int64, arrivalWall: Date
    ) -> NativeFix {
        let ageMs = Int64((arrivalWall.timeIntervalSince(l.timestamp) * 1000).rounded())
        let elapsedMs = arrivalMono - ageMs
        var mock = false
        if #available(iOS 15.0, *) { mock = l.sourceInformation?.isSimulatedBySoftware ?? false }
        return NativeFix(
            sessionId: sessionId, segmentId: segmentId, seq: seq,
            timeMs: Int64(l.timestamp.timeIntervalSince1970 * 1000), elapsedMs: elapsedMs,
            latitude: l.coordinate.latitude, longitude: l.coordinate.longitude,
            horizontalAccuracy: l.horizontalAccuracy, speed: l.speed < 0 ? -1 : l.speed,
            speedAccuracy: l.speedAccuracy < 0 ? -1 : l.speedAccuracy,
            course: l.course < 0 ? -1 : l.course,
            altitude: l.verticalAccuracy < 0 ? 0 : l.altitude,
            verticalAccuracy: l.verticalAccuracy < 0 ? -1 : l.verticalAccuracy,
            provider: "gps", isMock: mock)
    }

    private func persist(_ fix: NativeFix) {
        storeQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                try self.store.append(fix)
            } catch {
                // Recording goes on; the loss is visible in the snapshot.
                NSLog("[flutter_tracking_gps] persisting a fix failed: %@", String(describing: error))
                self.queue.async { [weak self] in
                    self?.lastError = "\(TrackingErrorCode.storeWriteFailed): \(error.localizedDescription)"
                }
            }
        }
    }

    /// Delivers a result on the main thread, as Flutter requires.
    private func deliver<T>(_ result: Result<T, Error>, to completion: @escaping (Result<T, Error>) -> Void) {
        DispatchQueue.main.async { completion(result) }
    }
}
