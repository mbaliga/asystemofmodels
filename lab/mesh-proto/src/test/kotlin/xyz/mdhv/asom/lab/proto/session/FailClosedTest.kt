package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerUnavailableException
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase

/** FC-1, FC-2, FC-4 and FC-5 of LAB_SPEC 7.7, one scenario each, with the physical write log as the instrument. */
class FailClosedTest {
    /** Index (in that node's append sequence) of the first row matching [pred] in a failure-free run of [script]. */
    private fun indexOf(node: String, script: (World) -> Unit, pred: (LabRouteRecord) -> Boolean): Int {
        val w = World(1)
        script(w)
        val i = (if (node == "A") w.a.rows() else w.b.rows()).indexOfFirst(pred)
        assertTrue(i >= 0, "no matching row on $node")
        return i
    }

    private fun offerScript(model: String = "m1"): (World) -> Unit = { w ->
        val (a, _) = w.connect()
        w.pump()
        a.offer(spec(model), "{}".toByteArray(), Recorder())
        w.pump()
    }

    private fun framesWrittenBy(w: World, conn: String): List<Fr> = w.log.all<Ev.Write>().filter { it.conn == conn }.flatMap { Frames.parse(it.bytes) }

    @Test
    fun fc1AnIntentThatIsNotDurableSendsNothingAndATryAgainNeedsAWriteToSucceed() {
        val idx = indexOf("A", offerScript()) { it.meshKind == MeshKind.INFER_SENT && it.phase == Phase.INTENT }
        for (sticky in listOf(false, true)) {
            val w = World(1, failA = FailPlan(setOf(idx), sticky))
            val (a, _) = w.connect()
            w.pump()
            assertFailsWith<LedgerUnavailableException> { a.offer(spec(), "{}".toByteArray(), Recorder()) }
            assertTrue(w.a.ledger.unavailable, "the ledger is marked unavailable")
            w.pump()
            assertTrue(framesWrittenBy(w, "A>B").none { it.type == 0x10 || it.type == 0x13 }, "FC-1: no byte of the attempt left (sticky=$sticky)")
            assertTrue(w.b.engine.opened.isEmpty())
            assertTrue(a.established, "the session stays open; only the attempt is refused")
            val rec = Recorder()
            if (sticky) {
                assertFailsWith<LedgerUnavailableException>("every further intent fails until a write succeeds") { a.offer(spec(), "{}".toByteArray(), rec) }
                assertTrue(w.a.ledger.unavailable)
            } else {
                a.offer(spec(index = 1), "{}".toByteArray(), rec)
                w.pump()
                assertEquals(200, rec.outcome?.status)
                assertTrue(!w.a.ledger.unavailable, "a write succeeded, so intents work again")
            }
        }
    }

    @Test
    fun fc2AControlRowThatIsNotDurableSendsNothingNotEvenGoawayAndClosesTheConnection() {
        val idx = indexOf("B", { w ->
            val (a, _) = w.connect()
            w.pump()
            a.requestState {}
            w.pump()
        }) { it.meshCode == "STATE_REQ" }
        for (sticky in listOf(false, true)) {
            val w = World(1, failB = FailPlan(setOf(idx), sticky))
            val (a, b) = w.connect()
            w.pump()
            var st: StateResult? = null
            a.requestState { st = it }
            val before = framesWrittenBy(w, "B<A").size
            w.pump()
            assertEquals(before, framesWrittenBy(w, "B<A").size, "nothing further on the session: no STATE, no GOAWAY, no ERROR")
            val failedAt = w.log.all<Ev.AppendFailed>().first { it.node == "B" }
            assertEquals("STATE_REQ", failedAt.row.meshCode)
            assertTrue(w.links[1].conn.closedByUs && b.closed && b.closedByLedgerFailure)
            assertTrue(st is StateResult.Lost, "the peer sees the transport end, nothing else")
            assertEquals(sticky, w.b.ledger.unavailable, "unavailable while writes keep failing, cleared by the next write that succeeds")
            val closeRow = w.log.snapshot().filter { (it is Ev.Appended && it.node == "B" && it.row.meshCode == "close:ledger-failure") || (it is Ev.AppendFailed && it.node == "B" && it.row.meshCode == "close:ledger-failure") }
            assertEquals(1, closeRow.size, "the SESSION close row was attempted exactly once")
            if (sticky) assertFailsWith<LedgerUnavailableException> { w.b.ledger.appendIntent(w.b.ledger.let { l -> LabRouteRecord(l.now(), "x", "m", egress = xyz.mdhv.asom.lab.ledger.LabEgress.peerClass, attemptId = "a", phase = Phase.INTENT, meshKind = MeshKind.INFER_SENT, sessionId = "s") }) }
        }
    }

    @Test
    fun fc4ALenderIntentThatIsNotDurableDeclinesPeerUnavailableAndTheEngineNeverStarts() {
        val idx = indexOf("B", offerScript()) { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.INTENT }
        val w = World(1, failB = FailPlan(setOf(idx), sticky = false))
        val (a, b) = w.connect()
        w.pump()
        val rec = Recorder()
        a.offer(spec(), "{}".toByteArray(), rec)
        w.pump()
        assertTrue(w.b.engine.opened.isEmpty(), "the engine never reads the body")
        val frames = framesWrittenBy(w, "B<A")
        val decline = frames.single { it.type == 0x12 }
        assertEquals("PEER_UNAVAILABLE", decline.code)
        assertTrue(frames.indexOfFirst { it.type == 0x11 } < frames.indexOfFirst { it.type == 0x12 }, "the offer had been accepted; the decline follows the body")
        assertEquals(503, rec.outcome?.status)
        assertEquals("PEER_UNAVAILABLE", rec.outcome?.meshCode)
        assertTrue(a.established && b.established, "FC-4 declines; it does not close")
        val outcome = w.b.rows().last { it.meshKind == MeshKind.INFER_SERVED }
        assertEquals(Phase.OUTCOME, outcome.phase)
        assertEquals("PEER_UNAVAILABLE", outcome.meshCode)
        RunOracle(w.log, w, strict = false).checkAll()
    }

    @Test
    fun fc4IfTheDeclinesOwnRowFailsToItIsFc2AndNothingIsSent() {
        val idx = indexOf("B", offerScript()) { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.INTENT }
        val w = World(1, failB = FailPlan(setOf(idx, idx + 1), sticky = false))
        val (a, b) = w.connect()
        w.pump()
        val rec = Recorder()
        a.offer(spec(), "{}".toByteArray(), rec)
        w.pump()
        assertTrue(framesWrittenBy(w, "B<A").none { it.type == 0x12 || it.type == 0x16 }, "no decline, no end")
        assertTrue(b.closed && b.closedByLedgerFailure && w.b.engine.opened.isEmpty())
        assertEquals(599, rec.outcome?.status, "the requester ends the attempt as a lost peer")
    }

    @Test
    fun fc5ALenderOutcomeThatIsNotDurableSendsNoInferEndAndClosesTheSession() {
        val idx = indexOf("B", offerScript()) { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.OUTCOME }
        val w = World(1, failB = FailPlan(setOf(idx), sticky = false))
        val (a, b) = w.connect()
        w.pump()
        val rec = Recorder()
        a.offer(spec(), "{}".toByteArray(), rec)
        w.pump()
        val frames = framesWrittenBy(w, "B<A")
        assertTrue(frames.none { it.type == 0x16 }, "no INFER_END")
        assertTrue(frames.none { it.type == 0x17 }, "a lender never sends CANCEL (ERRATA ERR-LL-6)")
        assertEquals(1, w.b.engine.cancelled.size, "the engine stream is cancelled locally")
        assertTrue(b.closed && b.closedByLedgerFailure)
        assertEquals(599, rec.outcome?.status)
        RunOracle(w.log, w, strict = false).checkAll()
        assertNotNull(w.b.rows().firstOrNull { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.INTENT })
    }
}
