import Foundation

/// Durable, append-only record of the fixes of a session, so the distance can
/// be rebuilt after the Flutter engine or the process died. The files hold
/// coordinates: they live in the app's private storage and are removed by
/// `clearSession`. Blocking I/O: never call it from the main thread.
protocol FixStoring {
    func append(_ fix: NativeFix) throws
    func read(sessionId: String, afterSeq: Int64) throws -> [NativeFix]
    func clearSession(_ sessionId: String) throws
}

enum FixStoreError: Error {
    case corruptLine(file: String)
    case cannotCreateDirectory(path: String)
}

final class FileFixStore: FixStoring {
    private let directory: URL
    private let fileManager: FileManager

    init(directory: URL, fileManager: FileManager = .default) {
        self.directory = directory
        self.fileManager = fileManager
    }

    func append(_ fix: NativeFix) throws {
        try ensureDirectory()
        let url = fileURL(for: fix.sessionId)
        var line = try JSONSerialization.data(withJSONObject: encode(fix), options: [])
        line.append(0x0A)
        if !fileManager.fileExists(atPath: url.path) {
            try line.write(to: url, options: .atomic)
            return
        }
        let handle = try FileHandle(forWritingTo: url)
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: line)
        } catch {
            closeAfterFailure(handle)
            throw error
        }
        try handle.close()
    }

    /// The original error is the one that matters; a failing close is logged,
    /// not hidden.
    private func closeAfterFailure(_ handle: FileHandle) {
        do {
            try handle.close()
        } catch {
            NSLog("[flutter_tracking_gps] closing the fix file failed: %@", String(describing: error))
        }
    }

    func read(sessionId: String, afterSeq: Int64) throws -> [NativeFix] {
        let url = fileURL(for: sessionId)
        guard fileManager.fileExists(atPath: url.path) else { return [] }
        let data = try Data(contentsOf: url)
        var fixes: [NativeFix] = []
        for line in data.split(separator: 0x0A) where !line.isEmpty {
            guard
                let object = try JSONSerialization.jsonObject(with: Data(line)) as? [String: Any],
                let fix = decode(object)
            else { throw FixStoreError.corruptLine(file: url.lastPathComponent) }
            if fix.seq > afterSeq { fixes.append(fix) }
        }
        return fixes
    }

    func clearSession(_ sessionId: String) throws {
        let url = fileURL(for: sessionId)
        if fileManager.fileExists(atPath: url.path) { try fileManager.removeItem(at: url) }
    }

    // MARK: - Private

    private func ensureDirectory() throws {
        if fileManager.fileExists(atPath: directory.path) { return }
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            throw FixStoreError.cannotCreateDirectory(path: directory.lastPathComponent)
        }
    }

    private func fileURL(for sessionId: String) -> URL {
        // The id comes from the caller: never let it name a path.
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let safe = String(sessionId.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
        return directory.appendingPathComponent("\(safe).jsonl")
    }

    private func encode(_ f: NativeFix) -> [String: Any] {
        [
            "s": f.sessionId, "g": f.segmentId, "q": f.seq, "t": f.timeMs, "e": f.elapsedMs,
            "la": f.latitude, "lo": f.longitude, "ha": f.horizontalAccuracy, "sp": f.speed,
            "sa": f.speedAccuracy, "co": f.course, "al": f.altitude, "va": f.verticalAccuracy,
            "pr": f.provider, "mk": f.isMock,
        ]
    }

    private func decode(_ o: [String: Any]) -> NativeFix? {
        guard
            let s = o["s"] as? String, let g = (o["g"] as? NSNumber)?.int64Value,
            let q = (o["q"] as? NSNumber)?.int64Value, let t = (o["t"] as? NSNumber)?.int64Value,
            let e = (o["e"] as? NSNumber)?.int64Value, let la = (o["la"] as? NSNumber)?.doubleValue,
            let lo = (o["lo"] as? NSNumber)?.doubleValue, let ha = (o["ha"] as? NSNumber)?.doubleValue,
            let sp = (o["sp"] as? NSNumber)?.doubleValue, let sa = (o["sa"] as? NSNumber)?.doubleValue,
            let co = (o["co"] as? NSNumber)?.doubleValue, let al = (o["al"] as? NSNumber)?.doubleValue,
            let va = (o["va"] as? NSNumber)?.doubleValue, let pr = o["pr"] as? String,
            let mk = o["mk"] as? Bool
        else { return nil }
        return NativeFix(
            sessionId: s, segmentId: g, seq: q, timeMs: t, elapsedMs: e, latitude: la, longitude: lo,
            horizontalAccuracy: ha, speed: sp, speedAccuracy: sa, course: co, altitude: al,
            verticalAccuracy: va, provider: pr, isMock: mk)
    }
}
