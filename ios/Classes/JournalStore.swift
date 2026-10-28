import CryptoKit
import Foundation
import TrackingCore

/// A journal writer that can report a failed write and be closed.
protocol DurableSink: JournalSink {
    /// The FIRST write or sync failure, or nil. Sticky: never forgotten. A failure never stops the recording;
    /// the engine shows it in the snapshot (`journalFailed`).
    var failure: Error? { get }
    /// Lines accepted by `append` that are not DURABLE: queued, written but not yet synchronized, or LOST (a
    /// write or sync failed). A lost line is never subtracted: it stays pending forever, so a failure can
    /// never look like "everything is saved". Zero means everything appended survives even a power loss.
    var pending: Int { get }
    /// Drains the queue and releases the file. True when the writer is stopped.
    @discardableResult func close() -> Bool
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
    /// Atomically replaces the journal with `lines`: the new content is written and synchronized next to it and
    /// then swapped in (`replaceItemAt`), so a crash at any step leaves the old file or the new one, never
    /// neither. With `keepBackup` the original is first copied to `<name>.corrupt`: damage in the MIDDLE drops
    /// everything after it, and that must never destroy the only copy. Throws, original untouched, on failure.
    func rewrite(_ sessionId: String, lines: [String], keepBackup: Bool) throws
    /// Closes the writer, waits for it to drain, then deletes the journal.
    func clear(_ sessionId: String) throws
    /// Closes the writer of a session that is over (a later `open` reopens it).
    func release(_ sessionId: String)
    /// The ids of the sessions on this device, read from the journal headers.
    func list() throws -> [String]
    /// The open-session pointer: the id of the session that is recording or paused, so a process that died can find
    /// it again without Flutter. Written at start, removed when the session finishes or is cleared. Nil when none.
    func activeId() -> String?
    func markActive(_ sessionId: String) throws
    /// Removes the pointer only when it still names `sessionId` (a newer session's pointer is never removed).
    func clearActive(_ sessionId: String)
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
        _ = writer?.flush()
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
        if FileManager.default.fileExists(atPath: temp.path) { try FileManager.default.removeItem(at: temp) }
        FileManager.default.createFile(atPath: temp.path, contents: nil)
        let handle = try FileHandle(forWritingTo: temp)
        do {
            try handle.write(contentsOf: Data(lines.map { $0 + "\n" }.joined().utf8))
            try handle.synchronize()  // the new content is on disk BEFORE it replaces the old one
            try handle.close()
        } catch {
            try? handle.close()
            try? FileManager.default.removeItem(at: temp)
            throw error
        }
        if FileManager.default.fileExists(atPath: url.path) {
            // Atomic swap: no delete-then-move window in which neither file exists.
            _ = try FileManager.default.replaceItemAt(url, withItemAt: temp)
        } else {
            try FileManager.default.moveItem(at: temp, to: url)
        }
        syncDirectory()
    }

    func release(_ sessionId: String) {
        lock.lock()
        let writer = writers.removeValue(forKey: sessionId)
        lock.unlock()
        writer?.close()
    }

    /// Best effort: commits the directory entry itself to disk.
    private func syncDirectory() {
        let fd = Darwin.open(directory.path, O_RDONLY)
        guard fd >= 0 else { return }
        _ = Darwin.fsync(fd)
        Darwin.close(fd)
    }

    func activeId() -> String? {
        guard let text = try? String(contentsOf: activeURL, encoding: .utf8) else { return nil }
        let id = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return id.isEmpty ? nil : id
    }

    func markActive(_ sessionId: String) throws {
        try ensureDirectory()
        let temp = directory.appendingPathComponent("active.session.tmp")
        if FileManager.default.fileExists(atPath: temp.path) { try FileManager.default.removeItem(at: temp) }
        FileManager.default.createFile(atPath: temp.path, contents: nil)
        let handle = try FileHandle(forWritingTo: temp)
        do {
            try handle.write(contentsOf: Data(sessionId.utf8))
            try handle.synchronize()
            try handle.close()
        } catch {
            try? handle.close()
            throw error
        }
        if FileManager.default.fileExists(atPath: activeURL.path) {
            _ = try FileManager.default.replaceItemAt(activeURL, withItemAt: temp)
        } else {
            try FileManager.default.moveItem(at: temp, to: activeURL)
        }
    }

    func clearActive(_ sessionId: String) {
        if activeId() == sessionId { try? FileManager.default.removeItem(at: activeURL) }
    }

    private var activeURL: URL { directory.appendingPathComponent("active.session") }

    func clear(_ sessionId: String) throws {
        clearActive(sessionId)
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
    private let queue = DispatchQueue(label: "io.github.congtuandevmobile.flutter_tracking_gps.journal", qos: .utility)
    private let stateLock = NSLock()
    private var handle: FileHandle?
    private var closed = false
    private var firstFailure: Error?
    /// Accepted and not yet synchronized to disk (queued + written-but-unsynced). Guarded by `stateLock`.
    private var queued = 0
    // Only touched on `queue`.
    private var unsynced = 0
    private var syncScheduled = false
    private static let syncInterval: TimeInterval = 1

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
            // A line that cannot be written is pending (lost), never "saved".
            record(JournalError.closed)
            stateLock.lock()
            queued += 1
            stateLock.unlock()
            return
        }
        stateLock.lock()
        queued += 1
        stateLock.unlock()
        queue.async { [weak self] in self?.write(line) }
    }

    /// The barrier: true only when everything queued is on disk AND nothing ever failed.
    func flush() -> Bool {
        queue.sync { [weak self] in self?.sync() }
        stateLock.lock()
        defer { stateLock.unlock() }
        return firstFailure == nil && queued == 0
    }

    @discardableResult
    func close() -> Bool {
        stateLock.lock()
        let already = closed
        closed = true
        stateLock.unlock()
        if !already {
            queue.sync { [weak self] in
                guard let self = self else { return }
                self.sync()
                try? self.handle?.close()
                self.handle = nil
            }
        }
        return true  // the serial queue drained: the writer is stopped
    }

    // MARK: - Private (queue)

    /// Synchronizes what was written to disk; only then is it durable and no longer "pending".
    private func sync() {
        do {
            try handle?.synchronize()
            stateLock.lock()
            queued -= unsynced
            stateLock.unlock()
        } catch {
            // Not durable: those lines stay pending and the failure is sticky.
            record(error)
        }
        unsynced = 0
    }

    private func scheduleSync() {
        guard !syncScheduled else { return }
        syncScheduled = true
        queue.asyncAfter(deadline: .now() + Self.syncInterval) { [weak self] in
            guard let self = self else { return }
            self.syncScheduled = false
            if self.unsynced > 0 { self.sync() }
        }
    }

    private func write(_ line: String) {
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
            unsynced += 1
            scheduleSync()
        } catch {
            // Recording goes on; the loss is visible in the snapshot.
            NSLog("[flutter_tracking_gps] journal write failed: %@", String(describing: error))
            record(error)  // the line stays PENDING forever: lost is never durable
        }
    }

    private func record(_ error: Error) {
        stateLock.lock()
        if firstFailure == nil { firstFailure = error }
        stateLock.unlock()
    }
}

enum JournalError: Error { case closed }
