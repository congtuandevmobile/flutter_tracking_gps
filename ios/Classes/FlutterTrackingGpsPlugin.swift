import Flutter
import UIKit

/// Flutter entry point. Thin on purpose: it forwards to the `TrackingEngine`,
/// which delivers every result on the main thread and turns every failure into
/// a `PigeonError` (code, message, details).
public class FlutterTrackingGpsPlugin: NSObject, FlutterPlugin, TrackingHostApi {
    /// Process-wide: a recording must not stop because the Flutter engine
    /// detached. Created on the main thread (CoreLocation needs a run loop).
    private static let engine: TrackingEngine = {
        let directory = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("flutter_tracking_gps/journal", isDirectory: true)
        return TrackingEngine(provider: CoreLocationProvider(), store: FileJournalStore(directory: directory))
    }()

    private var eventSink: PigeonEventSink<NativeFix>?
    private var snapshotSink: PigeonEventSink<NativeSnapshot>?
    private var messenger: FlutterBinaryMessenger?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FlutterTrackingGpsPlugin()
        let messenger = registrar.messenger()
        instance.messenger = messenger
        TrackingHostApiSetup.setUp(binaryMessenger: messenger, api: instance)
        StreamFixesStreamHandler.register(with: messenger, streamHandler: FixesHandler(plugin: instance))
        StreamSnapshotsStreamHandler.register(with: messenger, streamHandler: SnapshotsHandler(plugin: instance))
        engine.fixObserver = { [weak instance] fix in instance?.eventSink?.success(fix) }
        engine.snapshotObserver = { [weak instance] snapshot in instance?.snapshotSink?.success(snapshot) }
        registrar.publish(instance)
    }

    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        TrackingHostApiSetup.setUp(binaryMessenger: registrar.messenger(), api: nil)
        // The recording keeps going; only what this instance holds is released.
        FlutterTrackingGpsPlugin.engine.fixObserver = nil
        FlutterTrackingGpsPlugin.engine.snapshotObserver = nil
        eventSink = nil
        snapshotSink = nil
        messenger = nil
    }

    // MARK: - TrackingHostApi

    func permissionStatus() throws -> NativePermission {
        FlutterTrackingGpsPlugin.engine.permissionStatus()
    }

    func requestPermission(completion: @escaping (Result<NativePermission, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.requestPermission { completion(.success($0)) }
    }

    func stepsPermissionStatus() throws -> NativePermission {
        FlutterTrackingGpsPlugin.engine.stepsPermissionStatus()
    }

    func requestStepsPermission(completion: @escaping (Result<NativePermission, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.requestStepsPermission { completion(.success($0)) }
    }

    func start(config: StartConfig, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.start(config: config, completion: completion)
    }

    func pause(sessionId: String, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.pause(sessionId: sessionId, completion: completion)
    }

    func resume(sessionId: String, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.resume(sessionId: sessionId, completion: completion)
    }

    func stop(sessionId: String, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.stop(sessionId: sessionId, completion: completion)
    }

    func getSnapshot() throws -> NativeSnapshot {
        FlutterTrackingGpsPlugin.engine.snapshot()
    }

    func submitPdr(sessionId: String, segmentId: Int64, meters: Double) throws {
        FlutterTrackingGpsPlugin.engine.submitPdr(sessionId: sessionId, segmentId: segmentId, meters: meters)
    }

    func restoreSession(config: StartConfig, completion: @escaping (Result<NativeSnapshot, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.restore(config: config, completion: completion)
    }

    func updateNotification(title: String, text: String) throws {
        // Android only: iOS has no foreground notification to update.
    }

    func listSessions(completion: @escaping (Result<[String], Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.listSessions(completion: completion)
    }

    func getReport() throws -> String {
        FlutterTrackingGpsPlugin.engine.report()
    }

    func readPersistedFixes(
        sessionId: String, afterSeq: Int64, completion: @escaping (Result<[NativeFix], Error>) -> Void
    ) {
        FlutterTrackingGpsPlugin.engine.readPersisted(
            sessionId: sessionId, afterSeq: afterSeq, completion: completion)
    }

    func clearSession(sessionId: String, completion: @escaping (Result<Void, Error>) -> Void) {
        FlutterTrackingGpsPlugin.engine.clearSession(sessionId, completion: completion)
    }

    fileprivate func attach(_ sink: PigeonEventSink<NativeFix>?) { eventSink = sink }
    fileprivate func attachSnapshots(_ sink: PigeonEventSink<NativeSnapshot>?) { snapshotSink = sink }
}

/// Holds the event sink while Dart listens. Weak reference: the handler must
/// not keep the plugin alive.
private final class FixesHandler: StreamFixesStreamHandler {
    private weak var plugin: FlutterTrackingGpsPlugin?

    init(plugin: FlutterTrackingGpsPlugin) {
        self.plugin = plugin
        super.init()
    }

    override func onListen(withArguments arguments: Any?, sink: PigeonEventSink<NativeFix>) {
        plugin?.attach(sink)
    }

    override func onCancel(withArguments arguments: Any?) {
        plugin?.attach(nil)
    }
}

/// Same as `FixesHandler`, for the engine snapshots.
private final class SnapshotsHandler: StreamSnapshotsStreamHandler {
    private weak var plugin: FlutterTrackingGpsPlugin?

    init(plugin: FlutterTrackingGpsPlugin) {
        self.plugin = plugin
        super.init()
    }

    override func onListen(withArguments arguments: Any?, sink: PigeonEventSink<NativeSnapshot>) {
        plugin?.attachSnapshots(sink)
    }

    override func onCancel(withArguments arguments: Any?) {
        plugin?.attachSnapshots(nil)
    }
}
