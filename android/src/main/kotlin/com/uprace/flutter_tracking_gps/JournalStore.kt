package com.uprace.flutter_tracking_gps

import android.util.Log
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import tracker.journal.JournalSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A [JournalSink] that can report that a write failed and be closed. */
internal interface DurableSink : JournalSink {
    /** The first write failure, or null. A failure never stops the recording; the engine shows it in the snapshot. */
    val failure: Throwable?

    /** Lines accepted by [append] that are not on disk yet. Zero means everything appended is durable. */
    val pending: Int

    /** Drains the queue and releases the file. */
    fun close()
}

/**
 * Where the session journals live. One file per session, named from a hash of the
 * session id (two ids can never collide, whatever characters they hold; the real
 * id is inside the header). The files hold coordinates: they live in app-private
 * storage and are deleted by [clear].
 *
 * [read], [rewrite], [clear] and [list] block on I/O: call them from `Dispatchers.IO`.
 */
internal interface JournalStore {
    /** The append-only writer of [sessionId]: ONE writer per session, shared by every caller. */
    fun open(sessionId: String): DurableSink

    /** Every line of the journal, after the writer (if any) reached the disk. Empty when there is none. */
    @Throws(IOException::class)
    fun read(sessionId: String): List<String>

    /**
     * Atomically replaces the journal with [lines] (used to drop a damaged tail before appending
     * again). With [keepBackup] the original is first copied to `<name>.corrupt`, because a damaged
     * MIDDLE line means everything after it is dropped, and that must never destroy the only copy.
     */
    @Throws(IOException::class)
    fun rewrite(sessionId: String, lines: List<String>, keepBackup: Boolean = false)

    /** Closes the writer, waits for it to drain, then deletes the journal: nothing can re-create the file. */
    @Throws(IOException::class)
    fun clear(sessionId: String)

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
        writers.remove(sessionId)?.close()
        val file = fileFor(sessionId)
        ensureDirectory()
        if (keepBackup && file.exists()) file.copyTo(File(directory, file.name + ".corrupt"), overwrite = true)
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(lines.joinToString("") { it + "\n" }, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("cannot replace ${file.name}")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun clear(sessionId: String) {
        writers.remove(sessionId)?.close()
        val file = fileFor(sessionId)
        if (file.exists() && !file.delete()) throw IOException("cannot delete ${file.name}")
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

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) throw IOException("cannot create ${directory.path}")
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
 * The single writer of one journal: [append] only queues (never blocks the
 * caller), one thread writes in order, and [flush] is the barrier that returns
 * once everything queued is on disk. Nothing else ever touches the file.
 */
internal class FileJournalSink(private val file: File) : DurableSink {
    private sealed interface Op {
        class Line(val text: String) : Op
        class Barrier(val done: CountDownLatch) : Op
        object Close : Op
    }

    private val queue = LinkedBlockingQueue<Op>()
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)

    override val pending: Int get() = queued.get()

    @Volatile override var failure: Throwable? = null
        private set

    @Volatile private var closed = false
    private val thread = Thread({ run() }, "tracking-journal").apply { isDaemon = true; start() }

    override fun append(line: String) {
        if (closed) {
            failure = failure ?: IOException("journal is closed")
            return
        }
        queued.incrementAndGet()
        queue.offer(Op.Line(line))
    }

    override fun flush() {
        if (closed || !thread.isAlive) return
        val done = CountDownLatch(1)
        queue.offer(Op.Barrier(done))
        if (!done.await(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            failure = failure ?: IOException("journal flush timed out")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val done = CountDownLatch(1)
        queue.offer(Op.Barrier(done))
        queue.offer(Op.Close)
        done.await(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun run() {
        var out: FileOutputStream? = null
        var writer: OutputStreamWriter? = null
        try {
            while (true) {
                when (val op = queue.take()) {
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
                        } catch (e: IOException) {
                            // Recording goes on; the loss is visible in the snapshot.
                            Log.e(LOG_TAG, "journal write failed", e)
                            failure = failure ?: e
                        } finally {
                            queued.decrementAndGet()
                        }
                    }
                    is Op.Barrier -> {
                        try {
                            writer?.flush()
                            out?.fd?.sync()
                        } catch (e: IOException) {
                            failure = failure ?: e
                        }
                        op.done.countDown()
                    }
                    Op.Close -> return
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
        const val FLUSH_TIMEOUT_SECONDS = 10L
    }
}
