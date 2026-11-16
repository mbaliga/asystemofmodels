package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LTQ-04: the ERROR code and the GOAWAY reason are peer-authored. A row stores only a member of the closed set of LAB_SPEC 7.2 (`UNKNOWN` and
 * `unknown` otherwise), so no address, prompt text or device name reaches a ledger row through them.
 */
class HostileCodesTest {
    private class NullWire : WireOut {
        override val meter: TransportMeter? = null
        override fun send(frame: FrameSpec) {}
        override fun close() {}
    }

    private fun session(sink: MemorySink): SessionLedger =
        SessionLedger(NodeLedger("A", sink, { 1L }), "sess", SessionMode.ESTABLISHED, SessionRole.DIALER, "peerx", null, NullWire())

    private val hostile = listOf(
        "x 192.168.1.20 my prompt text", "see you, Alice's iPad", "", "peer_busy", "PEER_BUSY ", "PEER_NOT_PAIRED\nPEER_BUSY", "ERROR:PEER_BUSY", "unknown", "\u0000", "A".repeat(5000),
    )

    @Test
    fun aReceivedErrorCodeOutsideTheClosedSetIsStoredAsUNKNOWN_LTQ04() {
        var cases = 0
        for (code in hostile) {
            val sink = MemorySink()
            session(sink).receive(FrameSpec(FrameKind.ERROR, 40, 0, null, code))
            val row = sink.all().single()
            assertEquals("ERROR:UNKNOWN", row.meshCode, "code '${code.take(30)}'")
            assertTrue(row.violations().isEmpty())
            cases++
        }
        assertEquals(hostile.size, cases)
    }

    @Test
    fun aReceivedGoawayReasonOutsideTheClosedSetIsStoredAsUnknown_LTQ04() {
        var cases = 0
        for (reason in hostile) {
            val sink = MemorySink()
            session(sink).receive(FrameSpec(FrameKind.GOAWAY, 20, 0, null, reason))
            assertEquals("GOAWAY:unknown", sink.all().single().meshCode, "reason '${reason.take(30)}'")
            cases++
        }
        assertEquals(hostile.size, cases)
    }

    @Test
    fun theCodesOfTheClosedSetsAreStoredVerbatimAndAMissingCodeIsUnknown_LTQ04() {
        var cases = 0
        for (code in ClosedCodes.MESH_ERRORS) {
            val sink = MemorySink()
            session(sink).receive(FrameSpec(FrameKind.ERROR, 40, 0, null, code))
            assertEquals("ERROR:$code", sink.all().single().meshCode)
            cases++
        }
        for (reason in ClosedCodes.GOAWAY_REASONS) {
            val sink = MemorySink()
            session(sink).receive(FrameSpec(FrameKind.GOAWAY, 20, 0, null, reason))
            assertEquals("GOAWAY:$reason", sink.all().single().meshCode)
            cases++
        }
        assertEquals(setOf("revoked", "suspended", "shutdown", "network-change", "idle", "max-age", "unknown"), ClosedCodes.GOAWAY_REASONS)
        assertEquals(14, ClosedCodes.MESH_ERRORS.count { it != "UNKNOWN" })
        assertTrue(cases > 20)
        val none = MemorySink()
        session(none).receive(FrameSpec(FrameKind.ERROR, 40))
        assertEquals("ERROR:UNKNOWN", none.all().single().meshCode)
    }

    @Test
    fun aSentFrameWithAnUnknownCodeIsAlsoStoredClosed_LTQ04() {
        val sink = MemorySink()
        session(sink).send(FrameSpec(FrameKind.ERROR, 40, 0, null, "not a code"))
        assertEquals("ERROR:UNKNOWN", sink.all().single().meshCode)
    }

    private fun control(code: String?) = LabRouteRecord(
        ts = 1, callerPkg = "peer:x", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.CONTROL, meshCode = code, sessionId = "s",
    )

    @Test
    fun aControlRowWhoseMeshCodeIsOutsideTheClosedGrammarIsMalformed_LTQ04() {
        val good = listOf("HELLO", "HELLO_ACK", "STATE_REQ", "STATE", "MANIFEST_REQ", "EXT_IGNORED", "ERROR:UNKNOWN", "ERROR:PEER_BUSY", "GOAWAY:idle", "GOAWAY:unknown")
        for (c in good) assertEquals(emptyList(), control(c).violations(), c)
        val bad = hostile.filter { it != "ERROR:PEER_BUSY" } + listOf("ERROR:x 192.168.1.20 my prompt text", "GOAWAY:see you, Alice's iPad", "ERROR:", "GOAWAY:", "ERROR", "hello", "ERROR:peer_busy", "GOAWAY:IDLE")
        for (c in bad) assertTrue(control(c).violations().isNotEmpty(), "'${c.take(40)}' must be refused")
        assertTrue(control(null).violations().isNotEmpty(), "a CONTROL row names its frame")
        assertFailsWith<IllegalArgumentException> { MemorySink().append(control("ERROR:x 192.168.1.20 my prompt text")) }
    }
}
