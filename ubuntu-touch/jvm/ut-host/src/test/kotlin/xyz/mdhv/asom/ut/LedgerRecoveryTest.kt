package xyz.mdhv.asom.ut

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.ledger.CorruptRowException
import xyz.mdhv.asom.lab.ledger.JsonlSink

/** HLU-2 (ERRATA ERR-FX-UT-2): the node's ledger file survives a cut write, and a bad file ends in LEDGER_UNAVAILABLE, never in a crash. */
class LedgerRecoveryTest {
    private val hello = """{"t":"hello","v":1}"""
    private val active = """{"t":"lifecycle","state":"active"}"""
    private val query = """{"t":"ledger","since":0,"limit":10}"""
    private val shutdown = """{"t":"shutdown"}"""

    private fun lines(vararg l: String): ByteArray = l.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)

    private fun <T> inTemp(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("asom-ut-recovery-")
        try {
            return block(dir.resolve("ledger").resolve("ledger.jsonl").also { Files.createDirectories(it.parent) })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun goodRows(file: Path, vararg ts: Long) {
        JsonlSink(file).use { s -> for (t in ts) s.append(FakeNode.terminalRow("r$t", "m", t)) }
    }

    private fun appendRaw(file: Path, text: String) {
        Files.write(file, text.toByteArray(Charsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    private inline fun <T> LedgerPort.using(block: (LedgerPort) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private class Run(val frames: List<NodeFrame>, val stderr: String, val rc: Int)

    /** Runs a whole session through the real [FrameWriter], so an over-size frame fails the way it does on the wire. */
    private fun run(input: ByteArray, ledger: () -> LedgerPort?): Run {
        val frames = CopyOnWriteArrayList<NodeFrame>()
        val err = ByteArrayOutputStream()
        val wire = ByteArrayOutputStream()
        val writer = FrameWriter(wire)
        val rc = NodeSession(
            LineReader(ByteArrayInputStream(input)), { writer.write(it); frames += it }, NodeLifecycle(FakeClock()), Diag(err), ledger,
            { JObject(emptyList()) },
        ).run()
        return Run(frames, String(err.toByteArray(), Charsets.US_ASCII), rc)
    }

    @Test
    fun aTornTailIsRemovedWhenTheLedgerOpens() = inTemp { file ->
        goodRows(file, 10)
        val intact = Files.readAllBytes(file)
        appendRaw(file, "{\"ts\":11,\"callerPkg\":\"x\"")
        FileLedger.open(file)!!.using { l ->
            assertTrue(Files.readAllBytes(file).contentEquals(intact), "the bytes after the last line break are not an acknowledged row and must go")
            assertEquals(1, l.rows(0, 10).size)
        }
    }

    @Test
    fun aTornTailWithNoCompleteRowAtAllLeavesAnEmptyLedger() = inTemp { file ->
        appendRaw(file, "{\"ts\":1,")
        FileLedger.open(file)!!.using { l ->
            assertEquals(0L, Files.size(file))
            assertEquals(emptyList(), l.rows(0, 10))
        }
    }

    @Test
    fun aBadLastRowIsCaughtWhenTheLedgerOpensAndTheNodeFailsClosed() = inTemp { file ->
        goodRows(file, 10)
        appendRaw(file, "{\"ts\":1,\"callerPkg\":\"x\"{\"ts\":2}\n")
        val before = Files.readAllBytes(file)
        assertNull(FileLedger.open(file), "STARTING must fail closed on a corrupt row (FC-1)")
        assertTrue(Files.readAllBytes(file).contentEquals(before), "a corrupt file is left exactly as found: nothing is rewritten")
        val r = run(lines(hello, active, query, shutdown)) { FileLedger.open(file) }
        assertEquals(0, r.rc, "stderr: ${r.stderr}")
        assertEquals(NodeFrame.Error(null, UiErrorCode.LEDGER_UNAVAILABLE), r.frames.filterIsInstance<NodeFrame.Error>().single())
        assertEquals("asom-ut: ledger unavailable\n", r.stderr)
    }

    @Test
    fun aRowThatTurnsBadAfterOpenAnswersLedgerUnavailableAndStopsTheNodeFromBorrowing() {
        val bad = object : LedgerPort {
            override fun rows(since: Long, limit: Int): List<JObject> = throw CorruptRowException(2, "line 2 is not a JSON object")
            override fun close() {}
        }
        val borrow = """{"t":"borrow","rid":"r1","model":"gpt-x","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}"""
        val r = run(lines(hello, active, query, borrow, shutdown)) { bad }
        assertEquals(0, r.rc, "stderr: ${r.stderr}")
        val errors = r.frames.filterIsInstance<NodeFrame.Error>()
        assertEquals(listOf(NodeFrame.Error(null, UiErrorCode.LEDGER_UNAVAILABLE), NodeFrame.Error("r1", UiErrorCode.LEDGER_UNAVAILABLE)), errors)
        assertEquals("interrupted", r.frames.filterIsInstance<NodeFrame.State>().last().node)
        assertEquals("asom-ut: ledger unavailable\n", r.stderr)
    }

    @Test
    fun anUnreadableLedgerFileAnswersLedgerUnavailable() {
        val bad = object : LedgerPort {
            override fun rows(since: Long, limit: Int): List<JObject> = throw java.io.IOException("read failed")
            override fun close() {}
        }
        val r = run(lines(hello, active, query, shutdown)) { bad }
        assertEquals(0, r.rc, "stderr: ${r.stderr}")
        assertEquals(NodeFrame.Error(null, UiErrorCode.LEDGER_UNAVAILABLE), r.frames.filterIsInstance<NodeFrame.Error>().single())
    }

    @Test
    fun aRowsFrameThatCannotFitOnTheWireAnswersLedgerUnavailable() {
        val fat = JObject(listOf("ts" to xyz.mdhv.asom.lab.json.JInt(1), "pad" to JString("x".repeat(CtlProtocol.MAX_LINE_BYTES))))
        val big = object : LedgerPort {
            override fun rows(since: Long, limit: Int): List<JObject> = listOf(fat)
            override fun close() {}
        }
        val r = run(lines(hello, active, query, shutdown)) { big }
        assertEquals(0, r.rc, "stderr: ${r.stderr}")
        assertEquals(NodeFrame.Error(null, UiErrorCode.LEDGER_UNAVAILABLE), r.frames.filterIsInstance<NodeFrame.Error>().single())
        assertTrue(r.frames.none { it is NodeFrame.Rows })
    }

    @Test
    fun aQueryReadsOnlyAsMuchOfTheFileAsItReturns() = inTemp { file ->
        goodRows(file, 10, 20)
        FileLedger.open(file)!!.using { l ->
            appendRaw(file, "this line is damaged after the ledger was opened\n")
            assertEquals(listOf(10L), l.rows(0, 1).map { (it["ts"] as xyz.mdhv.asom.lab.json.JInt).value }, "rows past the limit are never read")
        }
    }

    @Test
    fun aQueryAnswersWhatFitsInOneFrameAndNeverMore() = inTemp { file ->
        val pad = "p".repeat(150_000)
        JsonlSink(file).use { s -> for (t in 1L..9L) s.append(FakeNode.terminalRow("r$t", "m", t).copy(callerPkg = pad)) }
        FileLedger.open(file)!!.using { l ->
            val rows = l.rows(0, 500)
            assertTrue(rows.size in 1..8, "9 rows of 150 KB cannot fit a 1 MiB frame; got ${rows.size}")
            assertEquals((1L..rows.size.toLong()).toList(), rows.map { (it["ts"] as xyz.mdhv.asom.lab.json.JInt).value }, "oldest first, no gaps")
            val wire = FrameCodec.encode(NodeFrame.Rows(rows))
            assertTrue(wire.size <= CtlProtocol.MAX_LINE_BYTES, "frame is ${wire.size} bytes")
        }
        val r = run(lines(hello, active, """{"t":"ledger","since":0,"limit":500}""", shutdown)) { FileLedger.open(file) }
        assertEquals(0, r.rc, "stderr: ${r.stderr}")
        assertNotNull(r.frames.filterIsInstance<NodeFrame.Rows>().singleOrNull())
        Unit
    }

    @Test
    fun aRowAppendedAfterATornTailIsNotGluedOntoIt() = inTemp { file ->
        goodRows(file, 10)
        appendRaw(file, "{\"ts\":11,\"callerPkg\":\"x\"")
        FileLedger.open(file)!!.using { l ->
            (l as FileLedger).append(FakeNode.terminalRow("r12", "m", 12))
            assertEquals(listOf(10L, 12L), l.rows(0, 10).map { (it["ts"] as xyz.mdhv.asom.lab.json.JInt).value }, "the acknowledged row 12 must be readable")
        }
    }

    @Test
    fun aBadRowInTheMiddleOfTheFileEndsInLedgerUnavailableNotACrash() = inTemp { file ->
        goodRows(file, 10)
        appendRaw(file, "{\"ts\":1,\"callerPkg\":\"x\"{\"ts\":2}\n")
        goodRows(file, 30)
        val r = run(lines(hello, active, query, shutdown)) { FileLedger.open(file) }
        assertEquals(0, r.rc, "stderr: ${r.stderr}")
        assertEquals(NodeFrame.Error(null, UiErrorCode.LEDGER_UNAVAILABLE), r.frames.filterIsInstance<NodeFrame.Error>().single())
        assertEquals("interrupted", r.frames.filterIsInstance<NodeFrame.State>().last().node)
    }

    @Test
    fun aFailedAppendRollsTheFileBackToTheLastAcknowledgedLength() = inTemp { file ->
        goodRows(file, 10)
        val acknowledged = Files.readAllBytes(file)
        var failNext = false
        val ledger = FileLedger.open(file, force = { ch -> if (failNext) throw java.io.IOException("injected: disk full") else ch.force(false) })!!
        ledger.using { l ->
            l as FileLedger
            failNext = true
            val e = kotlin.runCatching { l.append(FakeNode.terminalRow("r20", "m", 20)) }.exceptionOrNull()
            assertTrue(e is xyz.mdhv.asom.lab.ledger.LedgerWriteException, "append must throw, got $e")
            assertTrue(Files.readAllBytes(file).contentEquals(acknowledged), "the unacknowledged row must be gone, not left in front of the next one")
            failNext = false
            l.append(FakeNode.terminalRow("r30", "m", 30))
            assertEquals(listOf(10L, 30L), l.rows(0, 10).map { (it["ts"] as xyz.mdhv.asom.lab.json.JInt).value })
        }
    }

    @Test
    fun aRollbackThatFailsPoisonsTheLedger() = inTemp { file ->
        goodRows(file, 10)
        var failForce = true
        var failTruncate = true
        val ledger = FileLedger.open(
            file,
            force = { ch -> if (failForce) throw java.io.IOException("injected: force") else ch.force(false) },
            truncate = { p, n -> if (failTruncate) throw java.io.IOException("injected: truncate") else LedgerFile.truncate(p, n) },
        )!!
        ledger.using { l ->
            l as FileLedger
            assertTrue(kotlin.runCatching { l.append(FakeNode.terminalRow("r20", "m", 20)) }.exceptionOrNull() is xyz.mdhv.asom.lab.ledger.LedgerWriteException)
            failForce = false
            failTruncate = false
            val e = kotlin.runCatching { l.append(FakeNode.terminalRow("r30", "m", 30)) }.exceptionOrNull()
            assertTrue(e is xyz.mdhv.asom.lab.ledger.LedgerWriteException, "after an unrecoverable failure every append throws until the process reopens the file; got $e")
        }
    }
}
