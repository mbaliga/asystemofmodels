package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
