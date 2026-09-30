package xyz.mdhv.asom.ut

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.ledger.JsonlSink

class NodeSessionTest {
    private fun lines(vararg l: String): ByteArray = l.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)

    private val hello = """{"t":"hello","v":1}"""
    private val active = """{"t":"lifecycle","state":"active"}"""

    private class Captured {
        val frames = CopyOnWriteArrayList<NodeFrame>()
        val err = ByteArrayOutputStream()
    }

    private fun session(input: ByteArray, cap: Captured, fake: Boolean = false, clock: FakeClock = FakeClock(), ledger: LedgerPort? = object : LedgerPort {
        override fun rows(since: Long, limit: Int): List<JObject> = emptyList()
        override fun close() {}
    }): NodeSession =
        if (fake) FakeNode.session(LineReader(ByteArrayInputStream(input)), { cap.frames += it }, clock, Diag(cap.err))
        else NodeSession(LineReader(ByteArrayInputStream(input)), { cap.frames += it }, NodeLifecycle(clock), Diag(cap.err), { ledger }, { JObject(listOf("selftest" to JString("ok"))) })

    private fun types(cap: Captured) = cap.frames.map { it::class.simpleName }

    @Test
    fun aBorrowThroughTheFakeNodeStreamsAndEndsWithTheProjectedTerminalRow() {
        val cap = Captured()
        val rc = session(lines(hello, active, """{"t":"borrow","rid":"r1","model":"fake","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}""", """{"t":"shutdown"}"""), cap, fake = true).run()
        assertEquals(0, rc)
        val chunks = cap.frames.filterIsInstance<NodeFrame.Chunk>()
        assertEquals(listOf("Hello ", "from the fake node."), chunks.map { it.delta })
        val end = cap.frames.filterIsInstance<NodeFrame.End>().single()
        assertEquals(UiProjection.project(FakeNode.terminalRow("r1", "fake", 2_000L)), end.record)
        assertEquals("served by peer:fake-deck/fake · via lan", (end.record["provenance"] as JString).value)
        assertTrue(cap.frames.filterIsInstance<NodeFrame.State>().any { it.node == NodeStates.ACTIVE })
    }

    @Test
    fun theFakeNodeMapsItsThreeErrorPathsThroughTheRealMapping() {
        fun codeFor(model: String): NodeFrame {
            val cap = Captured()
            session(lines(hello, active, """{"t":"borrow","rid":"r1","model":"$model","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}""", """{"t":"shutdown"}"""), cap, fake = true).run()
            return cap.frames.first { it is NodeFrame.Error || it is NodeFrame.End }
        }
        assertEquals(NodeFrame.Error("r1", UiErrorCode.MODEL_UNKNOWN), codeFor("nope"))
        assertEquals(NodeFrame.Error("r1", UiErrorCode.ALL_PROVIDERS_COOLING), codeFor("fake-cooling"))
        assertEquals(NodeFrame.Error("r1", UiErrorCode.LOCAL_ENGINE_ABSENT), codeFor("local-only"))
        val cap = Captured()
        session(lines(hello, active, """{"t":"borrow","rid":"r1","model":"fake-interrupt","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}""", """{"t":"shutdown"}"""), cap, fake = true).run()
        assertTrue(NodeFrame.Error("r1", UiErrorCode.MESH_STREAM_INTERRUPTED) in cap.frames)
    }

    @Test
    fun aBorrowWhileTheUiIsNotActiveIsRefusedAsInterruptedBySuspend() {
        val cap = Captured()
        session(lines(hello, """{"t":"borrow","rid":"r1","model":"fake","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}""", """{"t":"shutdown"}"""), cap, fake = true).run()
        assertTrue(NodeFrame.Error("r1", UiErrorCode.INTERRUPTED_BY_SUSPEND) in cap.frames)
        assertTrue(cap.frames.none { it is NodeFrame.Chunk }, "nothing may be dialled while the UI has not reported active")
    }

    @Test
    fun theLedgerFrameReturnsRowsFromTheNodesOwnLedgerFile() {
        val dir = Files.createTempDirectory("asom-ut-ledger-")
        try {
            val file = dir.resolve("ledger").resolve("ledger.jsonl")
            Files.createDirectories(file.parent)
            val r1 = FakeNode.terminalRow("a", "m", 10)
            val r2 = FakeNode.terminalRow("b", "m", 20)
            val r3 = FakeNode.terminalRow("c", "m", 30)
            JsonlSink(file).use { s -> s.append(r1); s.append(r2); s.append(r3) }
            val cap = Captured()
            val rc = NodeSession(
                LineReader(ByteArrayInputStream(lines(hello, """{"t":"ledger","since":15,"limit":1}""", """{"t":"shutdown"}"""))), { cap.frames += it },
                NodeLifecycle(FakeClock()), Diag(cap.err), { FileLedger.open(file) }, { JObject(emptyList()) },
            ).run()
            assertEquals(0, rc)
            val rows = cap.frames.filterIsInstance<NodeFrame.Rows>().single().rows
            assertEquals(listOf(r2.toRow()), rows)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun theWatchdogAnnouncesInterruptedAndThenIdleAfterAGapOfMoreThanThreeSeconds() {
        val pin = PipedOutputStream()
        val pout = PipedInputStream(pin, 1 shl 16)
        val cap = Captured()
        val clock = FakeClock()
        val session = NodeSession(LineReader(pout), { cap.frames += it }, NodeLifecycle(clock), Diag(cap.err), { object : LedgerPort {
            override fun rows(since: Long, limit: Int): List<JObject> = emptyList()
            override fun close() {}
        } }, { JObject(emptyList()) })
        val done = CountDownLatch(1)
        var rc = -1
        val t = Thread { rc = session.run(); done.countDown() }.also { it.start() }
        fun send(s: String) { pin.write((s + "\n").toByteArray()); pin.flush() }
        fun await(cond: () -> Boolean) { val end = System.nanoTime() + 5_000_000_000L; while (!cond() && System.nanoTime() < end) Thread.sleep(5); assertTrue(cond()) }
        send(hello); send(active); send("""{"t":"peers","op":"open"}""")
        await { cap.frames.any { it is NodeFrame.PeersList } }
        clock.now = 1_000
        send(active); send(active)
        clock.now = 5_000
        session.tick()
        val states = cap.frames.filterIsInstance<NodeFrame.State>().map { it.node }
        assertEquals(listOf("idle", "active", "interrupted", "idle"), states, "a gap over 3 s must be announced as interrupted, then idle")
        pin.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        t.join()
        assertEquals(0, rc)
    }

    @Test
    fun aUiThatStopsReportingActiveFreezesTheNodeAfterTenSeconds() {
        val pin = PipedOutputStream()
        val pout = PipedInputStream(pin, 1 shl 16)
        val cap = Captured()
        val clock = FakeClock()
        val session = NodeSession(LineReader(pout), { cap.frames += it }, NodeLifecycle(clock), Diag(cap.err), { object : LedgerPort {
            override fun rows(since: Long, limit: Int): List<JObject> = emptyList()
            override fun close() {}
        } }, { JObject(emptyList()) })
        val t = Thread { session.run() }.also { it.start() }
        fun send(s: String) { pin.write((s + "\n").toByteArray()); pin.flush() }
        send(hello); send(active); send("""{"t":"peers","op":"open"}""")
        val end = System.nanoTime() + 5_000_000_000L
        while (cap.frames.none { it is NodeFrame.PeersList } && System.nanoTime() < end) Thread.sleep(5)
        for (ms in 1000..10_000 step 1000) { clock.now = ms.toLong(); session.tick() }
        assertEquals("active", cap.frames.filterIsInstance<NodeFrame.State>().last().node)
        clock.now = 11_000
        session.tick()
        assertEquals("interrupted", cap.frames.filterIsInstance<NodeFrame.State>().last().node)
        pin.close()
        t.join(5000)
    }

    @Test
    fun aRequestWithNoPairedPeerIsAnsweredWithoutRaisingTheNodeToActive() {
        val cap = Captured()
        session(lines(hello, active, """{"t":"borrow","rid":"r1","model":"gpt-x","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}""", """{"t":"shutdown"}"""), cap).run()
        assertEquals(listOf("HelloAck", "State", "Error"), types(cap))
        assertEquals(NodeFrame.Error("r1", UiErrorCode.NO_PROVIDER_KEY), cap.frames.last())
    }
}
