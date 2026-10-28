import CryptoKit
import Foundation
import TrackingCore

/// A journal writer that can report a failed write and be closed.
protocol DurableSink: JournalSink {
    /// The first write failure, or nil. A failure never stops the recording; the engine shows it in the snapshot.
    var failure: Error? { get }
    /// Lines accepted by `append` that are not on disk yet. Zero means everything appended is durable.
    var pending: Int { get }
    /// Drains the queue and releases the file.
    func close()
}

/// Where the session journals live. One file per session, named from a hash of the
/// session id (two ids never collide; the real id is in the header). The files hold
/// coordinates: they live in app-private storage and are deleted by `clear`.
/// `read`, `rewrite`, `clear` and `list` block on I/O: call them off the main thread.
protocol JournalStoring: AnyObject {
    /// The append-only writer of `sessionId`: ONE writer per session, shared by every caller.
    func open(_ sessionId: String) -> DurableSink
    /// Every line of the journal, after the writer (if any) reached the disk.
    func read(_ sessionId: String) throws -> [String]
    /// Atomically replaces the journal with `lines` (drops a damaged tail before appending again). With
    /// `keepBackup` the original is first copied to `<name>.corrupt`: damage in the MIDDLE drops everything
    /// after it, and that must never destroy the only copy.
    func rewrite(_ sessionId: String, lines: [String], keepBackup: Bool) throws
    /// Closes the writer, waits for it to drain, then deletes the journal.
    func clear(_ sessionId: String) throws
    /// The ids of the sessions on this device, read from the journal headers.
    func list() throws -> [String]
}

final class FileJournalStore: JournalStoring {
    private let directory: URL
    private let lock = NSLock()
    private var writers: [String: FileJournalSink] = [:]

    init(directory: URL) { self.directory = directory }

    func open(_ sessionId: String) -> DurableSink {
        lock.lock()
        defer { lock.unlock() }
        if let existing = writers[sessionId] { return existing }
        let sink = FileJournalSink(file: fileURL(sessionId))
        writers[sessionId] = sink
        return sink
    }

    func read(_ sessionId: String) throws -> [String] {
        lock.lock()
        let writer = writers[sessionId]
        lock.unlock()
        writer?.flush()
        let url = fileURL(sessionId)
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let text = try String(contentsOf: url, encoding: .utf8)
        return text.components(separatedBy: "\n").filter { !$0.isEmpty }
    }

    func rewrite(_ sessionId: String, lines: [String], keepBackup: Bool) throws {
        lock.lock()
        let writer = writers.removeValue(forKey: sessionId)
        lock.unlock()
        writer?.close()
        try ensureDirectory()
        let url = fileURL(sessionId)
        if keepBackup, FileManager.default.fileExists(atPath: url.path) {
            let backup = directory.appendingPathComponent(url.lastPathComponent + ".corrupt")
            if FileManager.default.fileExists(atPath: backup.path) { try FileManager.default.removeItem(at: backup) }
            try FileManager.default.copyItem(at: url, to: backup)
        }
        let temp = directory.appendingPathComponent(url.lastPathComponent + ".tmp")
        try lines.map { $0 + "\n" }.joined().write(to: temp, atomically: false, encoding: .utf8)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        try FileManager.default.moveItem(at: temp, to: url)
    }

    func clear(_ sessionId: String) throws {
        lock.lock()
        let writer = writers.removeValue(forKey: sessionId)
        lock.unlock()
        writer?.close()
        let url = fileURL(sessionId)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }

    func list() throws -> [String] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "jnl" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        var ids: [String] = []
        for file in files {
            guard let text = try? String(contentsOf: file, encoding: .utf8),
                let first = text.components(separatedBy: "\n").first(where: { !$0.isEmpty }),
                let header = JournalCodec.shared.decode(line: first) as? JournalRecord.Header
            else { continue }
            ids.append(header.sessionId)
        }
        return ids
    }

    private func ensureDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func fileURL(_ sessionId: String) -> URL {
        let digest = SHA256.hash(data: Data(sessionId.utf8))
        let name = digest.map { String(format: "%02x", $0) }.joined().prefix(40)
        return directory.appendingPathComponent(String(name) + ".jnl")
    }
}

/// The single writer of one journal: `append` only queues (never blocks the caller),
/// one serial queue writes in order, and `flush` is the barrier that returns once
/// everything queued is on disk. Nothing else touches the file.
final class FileJournalSink: DurableSink {
    private let file: URL
    private let queue = DispatchQueue(label: "com.uprace.flutter_tracking_gps.journal", qos: .utility)
    private let stateLock = NSLock()
    private var handle: FileHandle?
    private var closed = false
    private var firstFailure: Error?
    private var queued = 0

    init(file: URL) { self.file = file }

    var pending: Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        return queued
    }

    var failure: Error? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return firstFailure
    }

    func append(line: String) {
        stateLock.lock()
        let isClosed = closed
        stateLock.unlock()
        if isClosed {
            record(JournalError.closed)
            return
        }
        stateLock.lock()
        queued += 1
        stateLock.unlock()
        queue.async { [weak self] in self?.write(line) }
    }

    func flush() {
        queue.sync { [weak self] in
            guard let self = self else { return }
            do { try self.handle?.synchronize() } catch { self.record(error) }
        }
    }

    func close() {
        stateLock.lock()
        if closed {
            stateLock.unlock()
            return
        }
        closed = true
        stateLock.unlock()
        queue.sync { [weak self] in
            guard let self = self else { return }
            do { try self.handle?.synchronize() } catch { self.record(error) }
            try? self.handle?.close()
            self.handle = nil
        }
    }

    // MARK: - Private (queue)

    private func write(_ line: String) {
        defer {
            stateLock.lock()
            queued -= 1
            stateLock.unlock()
        }
        do {
            if handle == nil {
                try FileManager.default.createDirectory(
                    at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                if !FileManager.default.fileExists(atPath: file.path) {
                    FileManager.default.createFile(atPath: file.path, contents: nil)
                }
                let h = try FileHandle(forWritingTo: file)
                try h.seekToEnd()
                handle = h
            }
            try handle?.write(contentsOf: Data((line + "\n").utf8))
        } catch {
            // Recording goes on; the loss is visible in the snapshot.
            NSLog("[flutter_tracking_gps] journal write failed: %@", String(describing: error))
            record(error)
        }
    }

    private func record(_ error: Error) {
        stateLock.lock()
        if firstFailure == nil { firstFailure = error }
        stateLock.unlock()
    }
}

enum JournalError: Error { case closed }
