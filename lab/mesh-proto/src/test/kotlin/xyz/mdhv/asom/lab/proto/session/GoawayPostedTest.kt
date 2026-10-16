package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.MeshKind

/**
 * Builder action 3 of REVISION_4.md section 6 (trust.md R4-T-08 "Registry listeners"): a registry change hands `GOAWAY` to the session's driver and returns; the
 * frame is written by the driver (`advance` or `tick`), never on the thread that revoked. Evidence label: LAB, oracle: self.
 */
class GoawayPostedTest {
    private fun goaways(rows: List<xyz.mdhv.asom.lab.ledger.LabRouteRecord>) = rows.filter { it.meshKind == MeshKind.CONTROL && it.meshCode?.startsWith("GOAWAY") == true }.map { it.meshCode }

    @Test
    fun aRevokeWritesNothingOnTheRevokingThreadAndTheDriverWritesTheGoaway() {
        val w = World(941)
        val (sa, sb) = w.connect()
        w.pump()
        assertTrue(sa.established && sb.established)
        val bytesBefore = sb.wire.output.count
        w.b.registry.revoke(w.a.pin, CLOCK_BASE)
        assertEquals(bytesBefore, sb.wire.output.count, "the revoking thread wrote to the connection")
        assertTrue(!sb.closed, "the revoking thread closed the session")
        assertEquals(emptyList(), goaways(w.b.rows()), "a GOAWAY row was written on the revoking thread")
        assertTrue(sb.goAwayPosted)
        w.pump()
        assertTrue(sb.closed && sa.closed)
        assertEquals(listOf("GOAWAY:revoked"), goaways(w.b.rows()))
        assertEquals(listOf("GOAWAY:revoked"), goaways(w.a.rows()))
    }

    @Test
    fun aPauseIsPostedTheSameWayAndTickDrainsItToo() {
        val w = World(942)
        val (sa, sb) = w.connect()
        w.pump()
        w.a.registry.pause(w.b.pin, CLOCK_BASE)
        assertTrue(!sa.closed && goaways(w.a.rows()).isEmpty())
        sa.tick(w.a.peek())
        assertTrue(sa.closed)
        w.pump()
        assertEquals(listOf("GOAWAY:suspended"), goaways(w.a.rows()))
        assertTrue(sb.closed)
    }

    @Test
    fun theFirstPostedReasonWinsAndASecondPostIsHarmless() {
        val w = World(943)
        val (sa, sb) = w.connect()
        w.pump()
        w.b.registry.pause(w.a.pin, CLOCK_BASE)
        w.b.registry.revoke(w.a.pin, CLOCK_BASE)
        w.pump()
        assertTrue(sb.closed && sa.closed)
        assertEquals(listOf("GOAWAY:suspended"), goaways(w.b.rows()))
    }

    @Test
    fun aPostOnAClosedSessionDoesNothing() {
        val w = World(944)
        val (sa, sb) = w.connect()
        w.pump()
        sb.close()
        w.pump()
        val rows = w.b.rows().size
        w.b.registry.revoke(w.a.pin, CLOCK_BASE)
        w.pump()
        sb.tick(w.b.peek())
        assertEquals(rows, w.b.rows().size, "a closed session wrote a row after a registry change")
        assertTrue(sa.closed)
    }

    private fun inferSent(rows: List<xyz.mdhv.asom.lab.ledger.LabRouteRecord>) = rows.filter { it.meshKind == MeshKind.INFER_SENT }

    @Test
    fun aRequesterWhoRevokedItsLenderSendsNothingMoreAndOfferThrowsWithoutAnyDriverCall() {
        val w = World(945)
        val (sa, sb) = w.connect()
        w.pump()
        assertTrue(sa.established && sb.established)
        w.a.registry.revoke(w.b.pin, CLOCK_BASE)
        assertFailsWith<SessionClosedForSend> { sa.offer(spec(), "{\"messages\":[\"SECRET-PROMPT\"]}".toByteArray(), Recorder()) }
        assertTrue(sa.closed, "the offer did not apply the posted GOAWAY")
        assertEquals(emptyList(), inferSent(w.a.rows()), "an INFER_SENT row was written after the revoke")
        sb.pumpAvailable()
        assertEquals(emptyList(), w.b.engine.opened, "the revoked lender received an offer")
        assertEquals(listOf("GOAWAY:revoked"), goaways(w.a.rows()))
    }

    @Test
    fun aStateOrManifestRequestAfterARevokeIsLostAndNothingIsWritten() {
        val w = World(946)
        val (sa, _) = w.connect()
        w.pump()
        w.a.registry.revoke(w.b.pin, CLOCK_BASE)
        var st: StateResult? = null
        var mf: ManifestResult? = null
        sa.requestState { st = it }
        sa.requestManifest(ByteArray(32)) { mf = it }
        assertTrue(st is StateResult.Lost && mf is ManifestResult.Lost, "state=$st manifest=$mf")
        assertTrue(w.a.rows().none { it.meshCode == "STATE_REQ" || it.meshCode == "MANIFEST_REQ" })
    }

    @Test
    fun anInFlightAttemptOfARevokedLenderEndsAsAPeerFailureNotAsAnAnswerEvenWithoutAdvance() {
        val w = World(947)
        val (sa, sb) = w.connect()
        w.pump()
        val rec = Recorder()
        sa.offer(spec(), "{\"messages\":[\"x\"]}".toByteArray(), rec)
        var rounds = 0
        while (!rec.accepted) {
            sb.pumpAvailable()
            sb.advance()
            sa.pumpAvailable()
            check(++rounds < 100) { "the lender never accepted" }
        }
        repeat(8) {
            sb.pumpAvailable()
            sb.advance()
        }
        w.a.registry.revoke(w.b.pin, CLOCK_BASE)
        sa.pumpAvailable()
        val o = assertNotNull(rec.outcome, "the attempt did not end")
        assertEquals(599, o.status)
        assertEquals("PEER_UNREACHABLE", o.meshCode)
        assertEquals("", rec.chunks.toString(), "answer bytes of a revoked lender were taken as a success")
        assertTrue(sa.closed)
    }

    @Test
    fun aRevokeThatLandedBeforeTheSessionWasRegisteredIsCaughtWhenTheAckArrives() {
        val w = World(948)
        w.a.registry.revoke(w.b.pin, CLOCK_BASE)
        val (sa, _) = w.connect()
        w.pump()
        assertTrue(!sa.established, "a session to a peer that is not PAIRED was established")
        assertTrue(sa.closed)
        assertEquals(emptyList(), inferSent(w.a.rows()))
    }

    @Test
    fun aRequesterReadsTheRegistryItselfWhenNoPostEverReachedIt() {
        val w = World(951)
        val (sa, _) = w.connect()
        w.pump()
        w.a.node.unregister(sa)
        w.a.registry.revoke(w.b.pin, CLOCK_BASE)
        assertTrue(!sa.goAwayPosted, "the scenario needs a revoke that was never posted")
        assertFailsWith<SessionClosedForSend> { sa.offer(spec(), "{}".toByteArray(), Recorder()) }
        assertTrue(sa.closed)
        assertEquals(listOf("GOAWAY:revoked"), goaways(w.a.rows()))
        assertEquals(emptyList(), inferSent(w.a.rows()))
    }

    private class RevokeOnFrame(private val type: Int, private val node: String) : Log() {
        var action: (() -> Unit)? = null

        override fun <E : Ev> add(e: E): E {
            val r = super.add(e)
            if (e is Ev.Dispatch && e.node == node && e.type == type) action?.also { action = null }?.invoke()
            return r
        }
    }

    @Test
    fun aRevokeThatLandsWhileTheAckIsBeingProcessedStopsTheSessionFromEstablishing() {
        val log = RevokeOnFrame(xyz.mdhv.asom.lab.proto.wire.FrameTypes.HELLO_ACK, "A")
        val w = World(952, log = log)
        log.action = { w.a.registry.revoke(w.b.pin, CLOCK_BASE) }
        val (sa, sb) = w.connect()
        sb.pumpAvailable()
        sb.advance()
        sa.pumpAvailable()
        assertTrue(!sa.established, "the requester established on an ack that arrived while its lender was being revoked")
        assertTrue(sa.closed)
    }
}
