package io.github.congtuandevmobile.flutter_tracking_gps

import tracker.journal.BridgeSettings
import tracker.journal.JournalCodec
import tracker.journal.JournalRecord
import tracker.journal.JournalStatus
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun header(id: String) = JournalRecord.Header(
    1, 1, id, 5, 6, 0, 0,
    BridgeSettings(40.0, 30_000, 10_000, 10.0, 2.0, 0.5, 0.4, 120_000, 3, true, true, 0.5, false, 15.0, 30_000, -1.0, false, 30_000, false, false),
    emptyMap(),
)

/** The real file-backed store: one writer, a flush barrier, collision-safe names. */
class FileJournalStoreTest {
    private lateinit var dir: File
    private lateinit var store: FileJournalStore

    @BeforeTest fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "journal-test-${System.nanoTime()}")
        store = FileJournalStore(dir)
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun idsThatDifferOnlyByCharactersTheOldNamingStrippedDoNotCollide() {
        store.open("a/b").append(JournalCodec.encode(header("a/b")))
        store.open("a?b").append(JournalCodec.encode(header("a?b")))
        store.open("a/b").flush(); store.open("a?b").flush()
        assertEquals(2, dir.listFiles { f -> f.name.endsWith(".jnl") }?.size)
        assertEquals(setOf("a/b", "a?b"), store.list().toSet())
        assertEquals(1, store.read("a/b").size)
    }

    @Test fun manyAppendsFromTwoThreadsAreAllWrittenAndOrderedPerThread() {
        val sink = store.open("s")
        val threads = (0..1).map { t ->
            Thread { for (i in 0 until 500) sink.append(JournalCodec.encode(JournalRecord.Tick((t * 1_000_000 + i).toLong()))) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        sink.flush()
        val ticks = store.read("s").map { (JournalCodec.decode(it) as JournalRecord.Tick).atMs }
        assertEquals(1000, ticks.size)
        for (t in 0..1) {
            val mine = ticks.filter { it / 1_000_000 == t.toLong() }
            assertEquals(mine.sorted(), mine, "thread $t order")
        }
    }

    @Test fun readWaitsForTheWriterSoTheLastRecordIsNeverMissing() {
        val sink = store.open("s")
        repeat(200) { sink.append(JournalCodec.encode(JournalRecord.Tick(it.toLong()))) }
        assertEquals(200, store.read("s").size) // no explicit flush: read is itself a barrier
    }

    @Test fun clearClosesTheWriterAndNothingRecreatesTheFile() {
        val sink = store.open("s")
        sink.append(JournalCodec.encode(header("s")))
        store.clear("s")
        sink.append(JournalCodec.encode(JournalRecord.Tick(1))) // a late append after clear
        sink.flush()
        assertEquals(emptyList(), store.read("s"))
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(".jnl") }?.size ?: 0)
        assertNotNull(sink.failure, "the late append is reported, not silently lost")
    }

    @Test fun rewriteReplacesTheJournalAtomicallyAndAppendingContinuesAfterIt() {
        val sink = store.open("s")
        sink.append(JournalCodec.encode(header("s")))
        sink.append("this line is damaged")
        sink.flush()
        store.rewrite("s", listOf(JournalCodec.encode(header("s"))))
        store.open("s").append(JournalCodec.encode(JournalRecord.Pause(9)))
        val lines = store.read("s")
        assertEquals(2, lines.size)
        assertEquals(JournalStatus.OK, JournalCodec.read(lines).status)
        assertFalse(File(dir, "x.tmp").exists())
    }

    @Test fun aKilledWriteLeavesATruncatedTailThatTheReaderRecognises() {
        val sink = store.open("s")
        sink.append(JournalCodec.encode(header("s")))
        sink.append(JournalCodec.encode(JournalRecord.Pause(1)))
        sink.flush()
        val file = dir.listFiles { f -> f.name.endsWith(".jnl") }!!.single()
        file.appendText(JournalCodec.encode(JournalRecord.Resume(2, 1)).dropLast(7)) // no newline: killed mid-write
        val c = JournalCodec.read(store.read("s"))
        assertEquals(JournalStatus.TRUNCATED_TAIL, c.status)
        assertEquals(2, c.records.size)
    }

    @Test fun differentSessionsAreDifferentFiles() {
        store.open("one").append(JournalCodec.encode(header("one")))
        store.open("two").append(JournalCodec.encode(header("two")))
        assertNotEquals(store.read("one"), store.read("two"))
        assertTrue(store.list().containsAll(listOf("one", "two")))
    }

    @Test fun pendingDropsToZeroOnceTheWriterReachedTheDisk() {
        val sink = store.open("s")
        repeat(500) { sink.append(JournalCodec.encode(JournalRecord.Tick(it.toLong()))) }
        sink.flush()
        assertEquals(0, sink.pending)
        assertEquals(500, store.read("s").size)
    }

    @Test fun repairingWithBackupKeepsTheOriginalNextToTheFile() {
        val sink = store.open("s")
        sink.append(JournalCodec.encode(header("s")))
        sink.append("damaged middle")
        sink.append(JournalCodec.encode(JournalRecord.Pause(1)))
        sink.flush()
        store.rewrite("s", listOf(JournalCodec.encode(header("s"))), keepBackup = true)
        val backups = dir.listFiles { f -> f.name.endsWith(".corrupt") }!!
        assertEquals(1, backups.size)
        assertEquals(3, backups.single().readLines().size, "the original, damage and all, is kept")
        assertEquals(1, store.read("s").size)
    }
}
