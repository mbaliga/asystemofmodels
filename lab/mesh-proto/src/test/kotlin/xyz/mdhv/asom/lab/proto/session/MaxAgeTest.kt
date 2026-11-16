package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.MeshKind

/**
 * Builder action 2 of REVISION_4.md section 6 (trust.md R4-T-08, design 4.3 and T9): the maximum session age is 30 minutes, a literal here on purpose so the test
 * does not move with the constant. It supersedes ERR-PI-3's 24 h. Evidence label: LAB, oracle: self.
 */
class MaxAgeTest {
    private val thirtyMinutes = 30L * 60_000L

    @Test
    fun theMaximumSessionAgeIsThirtyMinutes() {
        assertEquals(thirtyMinutes, SessionLimits.MAX_AGE_MS)
    }

    @Test
    fun theRequesterSaysGoawayMaxAgeAtExactlyThirtyMinutesAndNotOneMillisecondBefore() {
        val w = World(931)
        val (sa, sb) = w.connect()
        w.pump()
        assertTrue(sa.established && sb.established)
        val start = sa.lastStreamActivity
        var steps = 0
        while (w.a.peek() + 200_000 < start + thirtyMinutes - 1) {
            w.a.advance(200_000)
            sa.tick(w.a.peek())
            var got: StateResult? = null
            sa.requestState { got = it }
            w.pump()
            assertTrue(got != null && !sa.closed, "the session closed or stopped answering at step $steps")
            steps++
        }
        assertTrue(steps >= 8, "the loop did not cover the thirty minutes ($steps steps)")
        sa.tick(start + thirtyMinutes - 1)
        assertTrue(!sa.closed, "the maximum age fired one millisecond early")
        sa.tick(start + thirtyMinutes)
        assertTrue(sa.closed, "the requester did not close at thirty minutes")
        w.pump()
        assertTrue(sb.closed, "the lender did not follow the GOAWAY")
        val goaways = (w.a.rows() + w.b.rows()).filter { it.meshKind == MeshKind.CONTROL && it.meshCode?.startsWith("GOAWAY") == true }
        assertEquals(listOf("GOAWAY:max-age", "GOAWAY:max-age"), goaways.map { it.meshCode })
    }

    @Test
    fun theLenderAlsoSaysGoawayMaxAgeAtThirtyMinutes() {
        val w = World(932)
        val (sa, sb) = w.connect()
        w.pump()
        val start = sb.lastStreamActivity
        var steps = 0
        while (w.b.peek() + 200_000 < start + thirtyMinutes - 1) {
            w.b.advance(200_000)
            w.a.advance(200_000)
            sb.tick(w.b.peek())
            var got: StateResult? = null
            sa.requestState { got = it }
            w.pump()
            assertTrue(got != null && !sb.closed, "the lender closed or stopped answering at step $steps")
            steps++
        }
        assertTrue(steps >= 8, "the loop did not cover the thirty minutes ($steps steps)")
        sb.tick(start + thirtyMinutes - 1)
        assertTrue(!sb.closed, "the maximum age fired one millisecond early on the lender")
        sb.tick(start + thirtyMinutes)
        assertTrue(sb.closed, "the lender did not close at thirty minutes")
        assertEquals("GOAWAY:max-age", w.b.rows().first { it.meshCode?.startsWith("GOAWAY") == true }.meshCode)
    }
}
