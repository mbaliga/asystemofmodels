package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FC-2 observed on [SessionLedger] itself (LTQ-10). The simulation harness tears every aborted connection down on its own, so a session that
 * forgot to close its wire, to write `close:ledger-failure` or to refuse a hand-off would still pass the world-level tests; here the only
 * observer is a recording wire and a recording sink.
 */
class SessionLedgerFc2Test {
    private class RecWire : WireOut {
        override val meter: TransportMeter? = null
        val sent = ArrayList<FrameSpec>()
        var closes = 0

        override fun send(frame: FrameSpec) {
            sent += frame
        }

        override fun close() {
            closes++
        }
    }

    /** Fails the appends whose 0-based index is in [failAt]; [onAppend] sees each attempt before it succeeds or fails. */
    private class ScriptedSink(private val failAt: Set<Int>, private val onAppend: (LabRouteRecord) -> Unit = {}) : RowSink {
        val rows = ArrayList<LabRouteRecord>()
        var attempts = 0

        override fun append(row: LabRouteRecord) {
            val i = attempts++
            onAppend(row)
            if (i in failAt) throw LedgerWriteException("injected failure at append #$i")
            rows += row
        }
    }

    private class Fixture(failAt: Set<Int>, role: SessionRole = SessionRole.DIALER, hook: (RecWire, LabRouteRecord) -> Unit = { _, _ -> }) {
        val wire = RecWire()
        val sink = ScriptedSink(failAt) { hook(wire, it) }
        val node = NodeLedger("A", sink, { 7L })
        val session = SessionLedger(node, "sess", SessionMode.ESTABLISHED, role, "peerx", null, wire)
    }

    private fun hello() = FrameSpec(FrameKind.HELLO, 50)

    private fun assertFc2(f: Fixture, rowsBefore: Int) {
        assertTrue(f.session.closed && f.session.closedByLedgerFailure)
        assertEquals(1, f.wire.closes, "the TLS connection and the socket were closed exactly once by the session itself")
        val last = f.sink.rows.drop(rowsBefore)
        assertEquals(1, last.size, "the only row after the failure is the SESSION close row")
        assertEquals(MeshKind.SESSION, last.single().meshKind)
        assertEquals("close:ledger-failure", last.single().meshCode)
        assertEquals(503, last.single().status)
        assertTrue(f.sink.rows.none { it.meshCode == "close" }, "no ordinary close row: the session did not end normally")
    }

    @Test
    fun aFailingSendRowClosesTheWireWritesCloseLedgerFailureAndSendsNothing_FC2() {
        val f = Fixture(setOf(0))
        assertFailsWith<SessionClosedException> { f.session.send(hello()) }
        assertEquals(emptyList(), f.wire.sent, "the frame whose row is not durable was not sent")
        assertFc2(f, 0)
    }

    @Test
    fun aFailingReceiveRowClosesTheWireWritesCloseLedgerFailureAndTheCallerGetsNoReplyRoute_FC2() {
        val f = Fixture(setOf(0), SessionRole.LISTENER)
        assertFailsWith<SessionClosedException> { f.session.receive(hello()) }
        assertEquals(emptyList(), f.wire.sent)
        assertFc2(f, 0)
    }

    @Test
    fun aFailingSessionOpenRowOnTheListenerIsFc2Too_FC2() {
        val f = Fixture(setOf(0), SessionRole.LISTENER)
        assertFailsWith<SessionClosedException> { f.session.open() }
        assertFc2(f, 0)
    }

    @Test
    fun theWireIsClosedBeforeTheCloseRowIsAttempted_FC2() {
        val seenCloses = ArrayList<Int>()
        val f = Fixture(setOf(0)) { wire, row -> if (row.meshCode == "close:ledger-failure") seenCloses += wire.closes }
        assertFailsWith<SessionClosedException> { f.session.send(hello()) }
        assertEquals(listOf(1), seenCloses, "wire.close() ran before the close row was appended")
    }

    @Test
    fun aFailingCloseRowDoesNotUndoTheCloseAndIsNotThrown_FC2() {
        val f = Fixture(setOf(0, 1))
        assertFailsWith<SessionClosedException> { f.session.send(hello()) }
        assertTrue(f.session.closed && f.session.closedByLedgerFailure)
        assertEquals(1, f.wire.closes)
        assertEquals(emptyList(), f.sink.rows)
        assertEquals(2, f.sink.attempts, "the close row was attempted once")
    }

    @Test
    fun afterAFailureEveryHandOffAndEveryFurtherFrameIsRefusedAndNothingReachesTheWire_FC2() {
        val f = Fixture(setOf(2))
        f.session.send(hello())
        val ra = f.session.requesterAttempt("req", "a1", 0, "app", "m")
        val la = f.session.lenderAttempt("a2", "m")
        ra.begin()
        assertFailsWith<SessionClosedException> { f.session.receive(FrameSpec(FrameKind.HELLO_ACK, 50)) }
        val sentBefore = f.wire.sent.size
        assertFailsWith<SessionClosedException> { ra.send(FrameSpec(FrameKind.INFER_OFFER, 60, 1, "a1")) }
        assertFailsWith<SessionClosedException> { la.accept(FrameSpec(FrameKind.INFER_ACCEPT, 40, 3, "a2")) }
        assertFailsWith<SessionClosedException> { f.session.send(hello()) }
        assertFailsWith<SessionClosedException> { f.session.receive(hello()) }
        assertEquals(sentBefore, f.wire.sent.size, "no frame reached the wire after the failure")
        assertEquals(1, f.wire.closes)
    }

    @Test
    fun aLenderDeclineWhoseRowFailsClosesTheSessionAndSendsNoDecline_FC2() {
        val f = Fixture(setOf(0), SessionRole.LISTENER)
        val la = f.session.lenderAttempt("a1", "m")
        assertFailsWith<SessionClosedException> { la.decline(FrameSpec(FrameKind.INFER_DECLINE, 40, 1, "a1", "PEER_BUSY")) }
        assertEquals(emptyList(), f.wire.sent)
        assertFc2(f, 0)
    }

    @Test
    fun aLenderOutcomeWhoseRowFailsSendsNoInferEndAndClosesTheSession_FC5() {
        val f = Fixture(setOf(1), SessionRole.LISTENER)
        val la = f.session.lenderAttempt("a1", "m")
        la.receiveOffer(FrameSpec(FrameKind.INFER_OFFER, 60, 1, "a1"))
        la.accept(FrameSpec(FrameKind.INFER_ACCEPT, 40, 1, "a1"))
        assertTrue(la.receiveBody(FrameSpec(FrameKind.INFER_BODY, 100, 1, "a1")) { error("no decline expected") })
        val delivered = f.wire.sent.size
        assertFalse(la.finish(FrameSpec(FrameKind.INFER_END, 40, 1, "a1"), ServedRecord("m", 200, "done", 3)))
        assertEquals(delivered, f.wire.sent.size, "no INFER_END")
        assertFc2(f, 1)
    }

    @Test
    fun aSessionThatClosesNormallyClosesTheWireOnceAndAClosedSessionDoesNotCloseAgain() {
        val f = Fixture(emptySet())
        f.session.send(hello())
        val row = f.session.close()
        assertEquals("close", row?.meshCode)
        assertEquals(200, row?.status)
        assertEquals(1, f.wire.closes)
        assertNull(f.session.close())
        assertFalse(f.session.closedByLedgerFailure)
        assertEquals(1, f.wire.closes)
    }
}
