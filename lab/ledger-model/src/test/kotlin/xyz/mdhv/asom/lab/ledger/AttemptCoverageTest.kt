package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ERR-FX4-LG-3..6: every attempt that crossed a frame on a node has an outcome row on that node (router.md 8.5, "accepted, cancelled"), a closed
 * attempt hands nothing further to the wire, and a failed DIAL outcome neither leaks the connection nor escapes as a raw write failure.
 */
class AttemptCoverageTest {
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

    private val id = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun frame(kind: FrameKind, payload: Int) = FrameSpec(kind, payload, 1, id)

    private fun lender(sink: RowSink = MemorySink()): Triple<SessionLedger, RecWire, RowSink> {
        val wire = RecWire()
        val s = SessionLedger(NodeLedger("B", sink, { 7L }), "sess", SessionMode.ESTABLISHED, SessionRole.LISTENER, "peerx", PeerPath.LAN, wire)
        return Triple(s, wire, sink)
    }

    private fun served(sink: RowSink) = (sink as MemorySink).all().filter { it.meshKind == MeshKind.INFER_SERVED }

    @Test
    fun anAcceptedAttemptThatTheRequesterCancelsBeforeTheBodyLeavesAnOutcomeRowOnTheLender_ERR_FX4_LG_3() {
        val (s, _, sink) = lender()
        val la = s.lenderAttempt(id, "qwen3-8b")
        la.receiveOffer(frame(FrameKind.INFER_OFFER, 150))
        la.accept(frame(FrameKind.INFER_ACCEPT, 120))
        la.receiveOther(frame(FrameKind.CANCEL, 50))
        s.close()
        val rows = served(sink)
        val outcome = rows.singleOrNull { it.phase == Phase.OUTCOME }
        assertNotNull(outcome, "329 application bytes crossed for this attempt and the lender wrote no row at all: $rows")
        assertEquals(499, outcome.status)
        assertEquals(129L, outcome.bytesOut)
        assertEquals(159L + 59L, outcome.bytesIn, "the row carries every byte of the attempt: the offer and the cancel came in, the accept went out")
    }

    @Test
    fun aSessionClosedWhileAnAcceptedAttemptWaitsForItsBodyEndsTheAttemptInItsOwnRow_ERR_FX4_LG_3() {
        val (s, _, sink) = lender()
        val la = s.lenderAttempt(id, "qwen3-8b")
        la.receiveOffer(frame(FrameKind.INFER_OFFER, 150))
        la.accept(frame(FrameKind.INFER_ACCEPT, 120))
        val close = s.close()
        assertNotNull(close)
        val outcome = served(sink).singleOrNull { it.phase == Phase.OUTCOME }
        assertNotNull(outcome, "the close ended no open attempt")
        assertEquals("INTERRUPTED", outcome.meshCode)
        assertEquals(159L, outcome.bytesIn)
        assertEquals(129L, outcome.bytesOut)
        assertEquals(0L, close.bytesOut + (close.bytesIn ?: 0), "the SESSION close row carries nothing the attempt row already holds")
    }

    @Test
    fun theRequesterCannotHandContentToTheWireAfterItsOutcomeRowWasWritten_ERR_FX4_LG_4() {
        val wire = RecWire()
        val sink = MemorySink()
        val s = SessionLedger(NodeLedger("A", sink, { 7L }), "sess", SessionMode.ESTABLISHED, SessionRole.DIALER, "peerx", PeerPath.LAN, wire)
        val ra = s.requesterAttempt("req", id, 0, "com.example", "qwen3-8b")
        ra.begin()
        ra.send(frame(FrameKind.INFER_OFFER, 150))
        ra.finish(503, "PEER_BUSY", null)
        assertFailsWith<IllegalStateException> { ra.send(frame(FrameKind.INFER_BODY, 5000)) }
        assertEquals(listOf(FrameKind.INFER_OFFER), wire.sent.map { it.kind }, "the body never reached the wire")
        assertTrue(!ra.contentSent)
    }

    @Test
    fun aFailedDialOutcomeRowIsLedgerUnavailableNotARawWriteFailure_ERR_FX4_LG_5() {
        val node = NodeLedger("A", CrashInjectingSink(MemorySink(), setOf(1)), { 7L })
        val e = assertFailsWith<LedgerUnavailableException> {
            node.dial("sid", "peerx", "192.168.1.5:11436", "qr") { DialResult("connected", null, PeerPath.LAN) }
        }
        assertEquals("LEDGER_UNAVAILABLE", e.message)
    }

    @Test
    fun aDialOutcomeOutsideTheClosedSetIsRefused_ERR_FX4_LG_5() {
        val node = NodeLedger("A", MemorySink(), { 7L })
        assertFailsWith<IllegalArgumentException> { node.dial("sid", "peerx", "192.168.1.5:11436", "qr") { DialResult("unreachable", null) } }
    }
}
