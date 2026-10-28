package com.uprace.flutter_tracking_gps

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import tracker.journal.JournalSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** A [JournalSink] that can report a failed write, count what is not durable, and be closed. */
internal interface DurableSink : JournalSink {
    /** The FIRST write, sync or barrier failure, or null. Sticky: a failure is never forgotten. */
    val failure: Throwable?

    /**
     * Lines accepted by [append] that are not DURABLE: queued, written but not yet `fsync`ed, or LOST (a write or
     * sync failed). A lost line is never subtracted: it stays pending forever, so a failure can never look like
     * "everything is saved". Zero means everything appended survives even a power loss.
     */
    val pending: Int

    /** Drains the queue and releases the file. True only when the writer really stopped within the time limit. */
    fun close(): Boolean
}

/**
 * Where the session journals live (`doc/JOURNAL.md`). One file per session in app-private storage, named from a
 * SHA-256 hash of the session id (two ids never collide; the real id is in the header). The files hold
 * coordinates and are deleted by [clear].
 *
 * [read], [rewrite], [clear], [release] and [list] block on I/O: call them from `Dispatchers.IO`.
 */
internal interface JournalStore {
    /** The append-only writer of [sessionId]: ONE shared writer per session. */
    fun open(sessionId: String): DurableSink

    /** Every line of the journal, after the writer (if any) reached the disk. Empty when there is none. */
    @Throws(IOException::class)
    fun read(sessionId: String): List<String>

    /**
     * Atomically replaces the journal with [lines]: the new content is written and synced next to it and then
     * renamed over it, so a crash at any step leaves either the old file or the new one, never neither. With
     * [keepBackup] the original is also copied to `<name>.corrupt` first (damage in the MIDDLE of a file drops
     * everything after it, and that must never destroy the only copy). Throws, leaving the original untouched,
     * when the writer cannot be stopped or the replace fails.
     */
    @Throws(IOException::class)
    fun rewrite(sessionId: String, lines: List<String>, keepBackup: Boolean = false)

    /** Closes the writer, WAITS for it to stop, then deletes the journal. Throws without deleting when it did not stop. */
    @Throws(IOException::class)
    fun clear(sessionId: String)

    /** Closes the writer of a session that is over (it holds a thread and a file handle). A later `open` reopens it. */
    @Throws(IOException::class)
    fun release(sessionId: String)

    /** The ids of the sessions on this device, read from the journal headers. */
    @Throws(IOException::class)
    fun list(): List<String>
}

internal class FileJournalStore(private val directory: File) : JournalStore {
    private val writers = HashMap<String, FileJournalSink>()

    @Synchronized
    override fun open(sessionId: String): DurableSink =
        writers.getOrPut(sessionId) { FileJournalSink(fileFor(sessionId)) }

    @Synchronized
    @Throws(IOException::class)
    override fun read(sessionId: String): List<String> {
        writers[sessionId]?.flush()
        val file = fileFor(sessionId)
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun rewrite(sessionId: String, lines: List<String>, keepBackup: Boolean) {
        stopWriter(sessionId)
        val file = fileFor(sessionId)
        ensureDirectory()
        if (keepBackup && file.exists()) file.copyTo(File(directory, file.name + ".corrupt"), overwrite = true)
        val temp = File(directory, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(lines.joinToString("") { it + "\n" }.toByteArray(StandardCharsets.UTF_8))
            out.fd.sync() // the new content is on disk BEFORE it replaces the old one
        }
        // rename(2) over an existing file is atomic on POSIX: no delete-then-rename window.
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("cannot replace ${file.name}; the original is untouched")
        }
        syncDirectory()
    }

    @Synchronized
    @Throws(IOException::class)
    override fun clear(sessionId: String) {
        stopWriter(sessionId)
        val file = fileFor(sessionId)
        if (file.exists() && !file.delete()) throw IOException("cannot delete ${file.name}")
    }

    @Synchronized
    @Throws(IOException::class)
    override fun release(sessionId: String) {
        stopWriter(sessionId)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun list(): List<String> {
        val files = directory.listFiles { f -> f.name.endsWith(EXTENSION) } ?: return emptyList()
        val ids = ArrayList<String>()
        for (f in files.sortedBy { it.name }) {
            val first = f.useLines(Charsets.UTF_8) { lines -> lines.firstOrNull { it.isNotBlank() } } ?: continue
            val header = JournalCodec.decode(first) as? JournalRecord.Header ?: continue
            ids.add(header.sessionId)
        }
        return ids
    }

    /** Stops the writer and CONFIRMS it stopped; a writer that did not stop must not be raced by a delete or a replace. */
    private fun stopWriter(sessionId: String) {
        val writer = writers[sessionId] ?: return
        if (!writer.close()) throw IOException("the journal writer of this session did not stop in time; nothing was changed")
        writers.remove(sessionId)
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) throw IOException("cannot create ${directory.path}")
    }

    /** Best effort: commits the rename itself to disk (the directory entry). Not available on every platform. */
    private fun syncDirectory() {
        try {
            val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        } catch (_: Throwable) {
            // Not fatal: the rename is still atomic; only the power-loss window of the directory entry stays open.
        }
    }

    private fun fileFor(sessionId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(StandardCharsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }.take(40)
        return File(directory, name + EXTENSION)
    }

    private companion object {
        const val EXTENSION = ".jnl"
    }
}

/**
 * The single writer of one journal, as a coroutine confined to ONE thread (`Dispatchers.IO.limitedParallelism(1)`):
 * [append] only queues (never blocks the caller), the writer writes in order, and [flush] is the barrier that
 * returns `true` only when everything queued is on disk AND nothing ever failed. Nothing else touches the file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FileJournalSink(private val file: File) : DurableSink {
    private sealed interface Op {
        class Line(val text: String) : Op
        class Barrier(val done: CompletableDeferred<Unit>) : Op
        object Close : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val queued = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    @Volatile override var failure: Throwable? = null
        private set

    @Volatile private var closed = false

    override val pending: Int get() = queued.get()

    private val writerJob: Job = scope.launch { run() }

    override fun append(line: String) {
        if (closed) {
            fail(IOException("journal is closed"))
            queued.incrementAndGet() // a line that cannot be written is pending (lost), never "saved"
            return
        }
        queued.incrementAndGet()
        ops.trySend(Op.Line(line))
    }

    override fun flush(): Boolean {
        if (closed) return failure == null && queued.get() == 0
        val done = CompletableDeferred<Unit>()
        ops.trySend(Op.Barrier(done))
        val reached = runBlocking { withTimeoutOrNull(BARRIER_TIMEOUT_MS) { done.await() } }
        if (reached == null) fail(IOException("journal flush timed out"))
        return reached != null && failure == null && queued.get() == 0
    }

    override fun close(): Boolean {
        if (!closed) {
            closed = true
            ops.trySend(Op.Close)
        }
        val stopped = runBlocking { withTimeoutOrNull(BARRIER_TIMEOUT_MS) { writerJob.join() } != null }
        if (!stopped) fail(IOException("journal writer did not stop in time"))
        return stopped
    }

    private fun fail(e: Throwable) {
        if (failure == null) failure = e
    }

    private suspend fun run() {
        var out: FileOutputStream? = null
        var writer: OutputStreamWriter? = null
        var unsynced = 0
        var lastSyncMs = System.currentTimeMillis()

        // fsync what was written; only then is it durable and no longer pending. A sync that FAILS leaves
        // those lines pending: they are not durable and the failure is sticky.
        fun sync() {
            try {
                writer?.flush()
                out?.fd?.sync()
                queued.addAndGet(-unsynced)
            } catch (e: IOException) {
                fail(e)
            }
            unsynced = 0
            lastSyncMs = System.currentTimeMillis()
        }

        try {
            while (true) {
                val received = withTimeoutOrNull(IDLE_POLL_MS) { ops.receiveCatching() }
                if (received == null) {
                    if (unsynced > 0 && System.currentTimeMillis() - lastSyncMs >= SYNC_INTERVAL_MS) sync()
                    continue
                }
                val op = received.getOrNull() ?: break
                when (op) {
                    is Op.Line -> {
                        try {
                            if (writer == null) {
                                val parent = file.parentFile
                                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                                    throw IOException("cannot create ${parent.path}")
                                }
                                out = FileOutputStream(file, true)
                                writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
                            }
                            writer.write(op.text)
                            writer.write("\n")
                            writer.flush()
                            unsynced++
                        } catch (e: IOException) {
                            // Recording goes on; the loss is visible: the line stays PENDING forever and the failure is sticky.
                            Log.e(LOG_TAG, "journal write failed", e)
                            fail(e)
                        }
                        if (unsynced > 0 && System.currentTimeMillis() - lastSyncMs >= SYNC_INTERVAL_MS) sync()
                    }
                    is Op.Barrier -> {
                        sync()
                        op.done.complete(Unit)
                    }
                    Op.Close -> {
                        sync()
                        break
                    }
                }
            }
        } finally {
            try {
                writer?.close()
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val LOG_TAG = "FlutterTrackingGps"
        const val BARRIER_TIMEOUT_MS = 10_000L
        const val SYNC_INTERVAL_MS = 1_000L
        const val IDLE_POLL_MS = 200L
    }
}
