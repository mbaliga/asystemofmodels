package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.laws.AppendFailed
import xyz.mdhv.asom.lab.ledger.laws.Closed
import xyz.mdhv.asom.lab.ledger.laws.ContentSent
import xyz.mdhv.asom.lab.ledger.laws.EngineRead
import xyz.mdhv.asom.lab.ledger.laws.LawResult
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.laws.Sent
import xyz.mdhv.asom.lab.ledger.sim.SimConfig
import xyz.mdhv.asom.lab.ledger.sim.SimWorld

/** FC-1, FC-2, FC-4, FC-5 (LAB_SPEC 7.7) and law L-L13: with a sink that throws at each durability point, nothing that depends on the row reaches a socket. */
class FailClosedTest {
    /** Index (in that node's append sequence) of the first row matching [pred] in a failure-free run of the full script. */
    private fun indexOf(node: String, pred: (LabRouteRecord) -> Boolean): Int {
        val w = SimWorld(SimConfig(seed = 1))
        w.run(FullScript.steps(w))
        val i = w.rows(node).indexOfFirst(pred)
        assertTrue(i >= 0, "no matching row on $node")
        return i
    }

    private fun failing(node: String, at: Int, mode: FailMode = FailMode.BEFORE_WRITE, sticky: Boolean = false): SimWorld {
        val cfg = SimConfig(seed = 1, sinkFor = { name -> if (name == node) CrashInjectingSink(MemorySink(), setOf(at), mode, sticky) else MemorySink() })
        val w = SimWorld(cfg)
        w.run(FullScript.steps(w))
        return w
    }

    @Test
    fun fc1RequesterIntentFailureSendsNothingAndTriesNoFurtherCandidate() {
        val at = indexOf("A") { it.meshKind == MeshKind.INFER_SENT && it.phase == Phase.INTENT }
        val w = failing("A", at)
        assertEquals("LEDGER_UNAVAILABLE", w.abortReason)
        val failed = w.trace.events.filterIsInstance<AppendFailed>().single().row
        assertTrue(w.trace.events.filterIsInstance<Sent>().none { it.frame.attemptId == failed.attemptId }, "no byte of the attempt whose intent failed")
        val afterFailure = w.trace.events.dropWhile { it !is AppendFailed }
        assertTrue(afterFailure.none { it is ContentSent }, "no further peer or cloud candidate was tried")
        assertEquals(emptyList(), LedgerLaws.l13(w.trace.events).violations)
    }

    @Test
    fun fc2ControlRowFailureSendsNothingFurtherNotEvenAGoawayAndClosesTheSession() {
        val at = indexOf("A") { it.meshKind == MeshKind.CONTROL && it.meshCode == "STATE_REQ" }
        val w = failing("A", at, sticky = true)
        val failed = w.trace.events.filterIsInstance<AppendFailed>().first().row
        assertEquals("STATE_REQ", failed.meshCode)
        val afterFailure = w.trace.events.dropWhile { it !is AppendFailed }
        assertTrue(afterFailure.filterIsInstance<Sent>().none { it.node == "A" && it.sessionId == failed.sessionId }, "zero frames on the session after a control-row failure")
        assertTrue(afterFailure.filterIsInstance<Sent>().none { it.frame.kind == FrameKind.GOAWAY && it.node == "A" }, "no GOAWAY: its row would need the write that just failed")
        assertTrue(afterFailure.any { it is Closed && it.node == "A" }, "the TLS connection and the socket were closed")
        assertTrue(w.a.ledger.unavailable, "the ledger is marked unavailable while writes keep failing")
        assertEquals(emptyList(), LedgerLaws.l13(w.trace.events).violations)
    }

    @Test
    fun fc2TheSessionClosesTheConnectionItselfBeforeItsCloseLedgerFailureRowNotTheHarness_LTQ10() {
        val at = indexOf("A") { it.meshKind == MeshKind.CONTROL && it.meshCode == "STATE_REQ" }
        val w = failing("A", at)
        val ev = w.trace.events
        val failed = ev.indexOfFirst { it is AppendFailed && it.node == "A" }
        val closed = ev.indexOfFirst { it is Closed && it.node == "A" }
        val closeRow = ev.indexOfFirst { it is xyz.mdhv.asom.lab.ledger.laws.Appended && it.node == "A" && it.row.meshCode == "close:ledger-failure" }
        assertTrue(failed >= 0 && closed > failed, "the connection was closed after the failed append")
        assertTrue(closeRow > closed, "the SESSION close row follows the close of the connection (wire.close() before the close row)")
        val row = (ev[closeRow] as xyz.mdhv.asom.lab.ledger.laws.Appended).row
        assertEquals(503, row.status)
        assertEquals(MeshKind.SESSION, row.meshKind)
        assertTrue(ev.drop(failed).none { it is xyz.mdhv.asom.lab.ledger.laws.Appended && it.node == "A" && it.row.meshKind == MeshKind.SESSION && it.row.meshCode == "close" },
            "no ordinary close row: only close:ledger-failure")
    }

    @Test
    fun fc2ReceivedFrameControlRowFailureSendsNoReply() {
        val at = indexOf("B") { it.meshKind == MeshKind.CONTROL && it.meshCode == "HELLO" }
        val w = failing("B", at, sticky = true)
        val failed = w.trace.events.filterIsInstance<AppendFailed>().first().row
        assertEquals("HELLO", failed.meshCode)
        assertTrue(w.trace.events.filterIsInstance<Sent>().none { it.frame.kind == FrameKind.HELLO_ACK }, "no reply to a frame whose row is not durable")
        assertEquals(emptyList(), LedgerLaws.l13(w.trace.events).violations)
    }

    @Test
    fun aWriteThatSucceedsClearsTheUnavailableMark() {
        val node = NodeLedger("A", CrashInjectingSink(MemorySink(), setOf(0, 1)), { 1L })
        val intent = LabRouteRecord(ts = 1, callerPkg = "p", requestedModel = "", egress = LabEgress.peerClass, phase = Phase.INTENT, meshKind = MeshKind.INFER_SENT, attemptId = "a", sessionId = "s")
        assertFailsWith<LedgerUnavailableException> { node.appendIntent(intent) }
        assertTrue(node.unavailable)
        assertFailsWith<LedgerUnavailableException> { node.appendIntent(intent) }
        node.appendIntent(intent)
        assertTrue(!node.unavailable, "every further intent fails until a write succeeds; then it does not")
    }

    @Test
    fun fc4LenderIntentFailureDeclinesPeerUnavailableAndTheEngineNeverStarts() {
        val at = indexOf("B") { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.INTENT }
        val w = failing("B", at)
        val failed = w.trace.events.filterIsInstance<AppendFailed>().single().row
        assertTrue(w.trace.events.filterIsInstance<EngineRead>().none { it.attemptId == failed.attemptId }, "the engine never starts")
        val decline = w.trace.events.filterIsInstance<Sent>().single { it.frame.kind == FrameKind.INFER_DECLINE && it.frame.attemptId == failed.attemptId && it.frame.code == "PEER_UNAVAILABLE" }
        val rows = w.rows("B").filter { it.attemptId == failed.attemptId }
        assertEquals(listOf(Phase.OUTCOME), rows.map { it.phase }, "the decline's own row is the next write, an outcome only")
        assertEquals("PEER_UNAVAILABLE", rows.single().meshCode)
        assertNotNull(decline)
        assertEquals(emptyList(), LedgerLaws.l13(w.trace.events).violations)
    }

    @Test
    fun fc4IfTheDeclineRowFailsToo_Fc2AppliesAndNothingIsSent() {
        val at = indexOf("B") { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.INTENT }
        val cfg = SimConfig(seed = 1, sinkFor = { name -> if (name == "B") CrashInjectingSink(MemorySink(), setOf(at, at + 1)) else MemorySink() })
        val w = SimWorld(cfg)
        w.run(FullScript.steps(w))
        val fails = w.trace.events.filterIsInstance<AppendFailed>()
        assertEquals(2, fails.size)
        val id = fails.first().row.attemptId
        assertTrue(w.trace.events.filterIsInstance<Sent>().none { it.frame.kind == FrameKind.INFER_DECLINE && it.frame.attemptId == id }, "the decline was not sent: its row was not durable")
        assertTrue(w.trace.events.any { it is Closed && it.node == "B" })
        assertEquals(emptyList(), LedgerLaws.l13(w.trace.events).violations)
    }

    @Test
    fun fc5LenderOutcomeFailureSendsNoInferEndAndClosesTheSession() {
        val at = indexOf("B") { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.OUTCOME && it.status != 503 }
        val w = failing("B", at)
        val failed = w.trace.events.filterIsInstance<AppendFailed>().single().row
        assertTrue(w.trace.events.filterIsInstance<Sent>().none { it.frame.kind == FrameKind.INFER_END && it.frame.attemptId == failed.attemptId }, "no INFER_END")
        assertTrue(w.trace.events.any { it is Closed && it.node == "B" })
        assertEquals(emptyList(), LedgerLaws.l13(w.trace.events).violations)
    }

    @Test
    fun aDialIntentFailureMeansNoSyn() {
        val at = indexOf("A") { it.meshKind == MeshKind.DIAL && it.phase == Phase.INTENT }
        val w = failing("A", at)
        assertEquals("LEDGER_UNAVAILABLE", w.abortReason)
        assertTrue(w.trace.events.none { it is xyz.mdhv.asom.lab.ledger.laws.Syn }, "no SYN was issued")
        assertEquals(emptyList(), LedgerLaws.l13(w.trace.events).violations)
    }

    @Test
    fun l13SweepFailsEveryAppendOfEveryNodeInBothModesAndStickiness() {
        var runs = 0
        var aborted = 0
        var l13 = LawResult("L-L13", 0, emptyList())
        val other = HashMap<String, Int>()
        for (seed in 1L..30L) {
            val ref = runWorld(seed)
            for (node in listOf("A", "B")) {
                val n = ref.rows(node).size
                for (i in 0 until n) for (mode in FailMode.entries) for (sticky in listOf(false, true)) {
                    if (mode == FailMode.AFTER_WRITE && i % 3 != 0) continue
                    val cfg = SimConfig(seed = seed, sinkFor = { name -> if (name == node) CrashInjectingSink(MemorySink(), setOf(i), mode, sticky) else MemorySink() })
                    val w = SimWorld(cfg)
                    w.run(xyz.mdhv.asom.lab.ledger.sim.Scenarios.random(w, seed))
                    runs++
                    if (w.abortReason != null) aborted++
                    val t = w.trace.events
                    l13 += LedgerLaws.l13(t)
                    for (r in listOf(LedgerLaws.l1(t), LedgerLaws.l2(t), LedgerLaws.l3(t), LedgerLaws.l4(t), LedgerLaws.l9(t), LedgerLaws.l14(t))) {
                        assertEquals(emptyList(), r.violations, "seed $seed node $node failing append #$i $mode sticky=$sticky: ${r.law}")
                        other[r.law] = (other[r.law] ?: 0) + r.cases
                    }
                }
            }
        }
        assertEquals(emptyList(), l13.violations.take(5))
        assertTrue(l13.cases > 500, "L-L13 examined ${l13.cases} injected failures")
        assertTrue(aborted > 100 && runs > 1000, "runs $runs, aborted $aborted")
        println("L-L13 iterations: ${l13.cases} injected append failures over $runs runs ($aborted aborted a script); laws L-L1..L-L4, L-L9, L-L14 also held in every one: $other")
    }
}
