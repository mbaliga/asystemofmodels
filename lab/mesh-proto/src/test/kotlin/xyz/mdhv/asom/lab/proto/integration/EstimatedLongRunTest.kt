package xyz.mdhv.asom.lab.proto.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.ledger.Overhead
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.StateResult
import xyz.mdhv.asom.lab.proto.wire.GoAwayReason

/**
 * The ESTIMATED form against the record tap in a run long enough to tell the record expansion apart (R4-L-25, ERR-R4B-LT-3 and LT-5). A short run cannot: the
 * 8,192-byte handshake allowance is larger than a real handshake (about 4,450 bytes) by about 3,700 bytes, so 16 bytes per record (38 against 22) disappear under it until a
 * session has more than about 230 records, and the difference alone exceeds the whole allowance only beyond 512. This run has 603 frames in each end's two directions
 * (16 x 603 = 9,648 bytes, above the allowance), so a form with R 22 undercounts the tap by thousands of bytes and a form with R 54 overcounts it by more than the allowance.
 * Evidence label: LAB, oracle: self; JDK 17 and 21; NOT DEVICE EVIDENCE.
 */
@Timeout(600)
class EstimatedLongRunTest {
    private companion object {
        const val STATE_REQUESTS = 300
        const val ALLOWANCE = 8_192L
        val tooWide = Overhead.Stack("R 54 (negative control)", 54, 16_367, 40)
    }

    @Test
    fun aLongRunSeparatesRecordExpansion38From22AndFromAnyOtherValue() {
        TlsWorld(seed = 4242).use { w ->
            val link = w.connect()
            w.awaitEstablished(link)
            val sa = link.sessionA!!
            val sb = link.sessionB!!
            repeat(STATE_REQUESTS) { i ->
                var got: StateResult? = null
                sa.requestState { got = it }
                Wait.until("STATE answer $i") { got != null || sa.closed }
                assertNotNull(got, "request $i was answered")
            }
            sa.goAway(GoAwayReason.SHUTDOWN)
            Wait.until("both sessions to close") { sa.closed && sb.closed }
            TlsRuns.settle(w)
            val (a, b) = TlsOracle.ends(link)
            Wait.stable("the record taps of both ends") { listOf(a, b).map { it.conn.tap.bytesRead to it.conn.tap.bytesWritten } }

            val l15 = L15Stats()
            TlsOracle.check(link, strict = true, c = Counts(), l15 = l15)
            assertEquals(2, l15.measuredSessions, "both ends of the long run are MEASURED sessions checked by L-L15, ceiling included")

            var discriminated = 0
            for ((me, peer) in listOf(a to b, b to a)) {
                val stack = TlsOracle.stackOf(me.conn)
                TlsOracle.pinStackToCalibration(stack)
                val r = TlsOracle.estimateReading(w, me, peer, stack)
                assertTrue(16L * r.flushes > ALLOWANCE, "${me.name}: ${r.flushes} frames make the 16-byte difference per record (${16L * r.flushes}) exceed the $ALLOWANCE-byte allowance")
                assertNull(TlsOracle.estimateVerdict(r, stack, me.name), "${me.name}: the JSSE form fits the tap (estimate ${r.estimate}, tap ${r.real})")

                val floor = TlsOracle.estimateReading(w, me, peer, Overhead.UNCALIBRATED)
                val floorVerdict = assertNotNull(TlsOracle.estimateVerdict(floor, Overhead.UNCALIBRATED, me.name), "${me.name}: the RFC 8446 floor (22) must not fit a long JSSE run (estimate ${floor.estimate}, tap ${floor.real})")
                assertTrue("undercounts" in floorVerdict, floorVerdict)

                val wide = TlsOracle.estimateReading(w, me, peer, tooWide)
                val wideVerdict = assertNotNull(TlsOracle.estimateVerdict(wide, tooWide, me.name), "${me.name}: R 54 must not fit (estimate ${wide.estimate}, tap ${wide.real})")
                assertTrue("overcounts" in wideVerdict, wideVerdict)
                discriminated++
                println("L-L15 ESTIMATED long run ${me.name}: ${r.flushes} frames; JSSE estimate ${r.estimate} / tap ${r.real}; R 22 estimate ${floor.estimate}; R 54 estimate ${wide.estimate}")
            }
            assertEquals(2, discriminated, "non-vacuity: both ends discriminated")
            TlsOracle.estimated(w, a, b, l15)
            TlsOracle.estimated(w, b, a, l15)
            assertEquals(2, l15.estimatedSessions)
        }
    }
}
