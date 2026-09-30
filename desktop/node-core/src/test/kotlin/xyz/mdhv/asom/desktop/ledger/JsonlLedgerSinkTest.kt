package xyz.mdhv.asom.desktop.ledger

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.server.ledger.LedgerSink

/** In-process durability rules: order (write, force, ack), fail-closed, rollback, torn-tail recovery, permissions. */
class JsonlLedgerSinkTest {
    /** Records every call, keeps its bytes in memory, and can be told to fail an operation. */
    private class RecordingChannel : DurableChannel {
        val log = ArrayList<String>()
        var data = ByteArray(0)
        val failOn = HashSet<String>()
        var halfWriteThenFail = false

        override fun size(): Long = data.size.toLong()
        override fun readTail(maxBytes: Int): ByteArray = data.takeLast(maxBytes).toByteArray()
        override fun write(bytes: ByteArray) {
            log += "write"
            if (halfWriteThenFail) {
                data += bytes.copyOfRange(0, bytes.size / 2)
                throw java.io.IOException("disk full (injected mid-write)")
            }
            if ("write" in failOn) throw java.io.IOException("injected write failure")
            data += bytes
        }
        override fun force() {
            log += "force"
            if ("force" in failOn) throw java.io.IOException("injected force failure")
        }
        override fun truncate(size: Long) {
            log += "truncate"
            if ("truncate" in failOn) throw java.io.IOException("injected truncate failure")
            data = data.copyOf(size.toInt())
        }
        override fun close() {}
        fun text() = String(data, Charsets.UTF_8)
    }

    @Test
    fun `append returns only after write then force, and the acknowledge point is after both`() {
        val ch = RecordingChannel()
        val seen = ArrayList<String>()
        val sink = JsonlLedgerSink(ch) { p, i -> seen += "${p.name}#$i:" + ch.log.joinToString(",") }
        sink.appendLine("{\"a\":1}")
        sink.appendLine("{\"a\":2}")
        assertEquals(listOf("write", "force", "write", "force"), ch.log)
        assertEquals(
            listOf(
                "BEFORE_WRITE#1:", "AFTER_WRITE_BEFORE_FORCE#1:write", "AFTER_FORCE_BEFORE_ACK#1:write,force", "AFTER_ACK#1:write,force",
                "BEFORE_WRITE#2:write,force", "AFTER_WRITE_BEFORE_FORCE#2:write,force,write", "AFTER_FORCE_BEFORE_ACK#2:write,force,write,force",
                "AFTER_ACK#2:write,force,write,force",
            ),
            seen,
        )
        assertEquals("{\"a\":1}\n{\"a\":2}\n", ch.text())
    }

    @Test
    fun `a failing force throws, acknowledges nothing and rolls the file back to the last acknowledged length`() {
        val ch = RecordingChannel()
        val sink = JsonlLedgerSink(ch)
        sink.appendLine("{\"a\":1}")
        ch.failOn += "force"
        assertFailsWith<LedgerUnavailableException> { sink.appendLine("{\"a\":2}") }
        assertEquals("{\"a\":1}\n", ch.text(), "the unforced row was rolled back")
        ch.failOn.clear()
        sink.appendLine("{\"a\":3}")
        assertEquals("{\"a\":1}\n{\"a\":3}\n", ch.text())
    }

    @Test
    fun `a write that fails half way leaves no torn line in front of the next row`() {
        val ch = RecordingChannel()
        val sink = JsonlLedgerSink(ch)
        sink.appendLine("{\"a\":1}")
        ch.halfWriteThenFail = true
        assertFailsWith<LedgerUnavailableException> { sink.appendLine("{\"row\":\"two\"}") }
        ch.halfWriteThenFail = false
        assertEquals("{\"a\":1}\n", ch.text())
        sink.appendLine("{\"a\":3}")
        assertEquals("{\"a\":1}\n{\"a\":3}\n", ch.text())
    }

    @Test
    fun `an append that cannot be rolled back poisons the sink and every later append throws`() {
        val ch = RecordingChannel()
        val sink = JsonlLedgerSink(ch)
        sink.appendLine("{\"a\":1}")
        ch.failOn += "force"
        ch.failOn += "truncate"
        assertFailsWith<LedgerUnavailableException> { sink.appendLine("{\"a\":2}") }
        ch.failOn.clear()
        assertFailsWith<LedgerUnavailableException> { sink.appendLine("{\"a\":3}") }
    }

    @Test
    fun `a row is exactly one line`() {
        val sink = JsonlLedgerSink(RecordingChannel())
        assertFailsWith<IllegalArgumentException> { sink.appendLine("a\nb") }
        assertFailsWith<IllegalArgumentException> { sink.appendLine("a\rb") }
    }

    @Test
    fun `a torn tail from a crashed writer is removed when the file is opened and acknowledged rows stay`() {
        val ch = RecordingChannel()
        ch.data = "{\"a\":1}\n{\"a\":2}\n{\"a\":3-torn".toByteArray()
        val sink = JsonlLedgerSink(ch)
        assertEquals("{\"a\":3-torn".length.toLong(), sink.recoveredTornBytes)
        assertEquals("{\"a\":1}\n{\"a\":2}\n", ch.text())
        // a file that is all torn tail (no newline at all) is emptied
        val ch2 = RecordingChannel().also { it.data = "partial".toByteArray() }
        assertEquals(7, JsonlLedgerSink(ch2).recoveredTornBytes)
        assertEquals("", ch2.text())
    }

    @Test
    fun `a tail longer than the first scan window is still found`() {
        val ch = RecordingChannel()
        ch.data = ("{\"a\":1}\n" + "x".repeat(50_000)).toByteArray()
        val sink = JsonlLedgerSink(ch)
        assertEquals(50_000, sink.recoveredTornBytes.toInt())
        assertEquals("{\"a\":1}\n", ch.text())
    }

    @Test
    fun `real file gets mode 0600 in a 0700 directory and round-trips a RouteRecord through the server's LedgerSink seam`() {
        val dir = Files.createTempDirectory("asom-ledger-")
        try {
            val file = dir.resolve("ledger").resolve("ledger.jsonl")
            val sink: LedgerSink = JsonlLedgerSink.open(file)
            val rec = harnessRow(7)
            runBlocking { sink.append(rec) }
            (sink as JsonlLedgerSink).close()
            val lines = Files.readAllLines(file)
            assertEquals(1, lines.size)
            assertEquals(rec, JsonlLedgerSink.JSON.decodeFromString(RouteRecord.serializer(), lines[0]))
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(file.parent),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `appending a row writes nothing to stdout or stderr`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            val sink = JsonlLedgerSink(RecordingChannel())
            runBlocking { sink.append(harnessRow(1)) }
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertTrue(out.size() == 0 && err.size() == 0, "the ledger must never print a row (T17(c))")
    }
}
