package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.laws.Appended
import xyz.mdhv.asom.lab.ledger.laws.Ev
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.laws.PlainTap
import xyz.mdhv.asom.lab.ledger.laws.Tap

/**
 * LTQ-14: both ends of the R3-OVERCLAIM-3 plausibility bound. A MEASURED overhead below the RFC 8446 record cost of the application bytes
 * (22 bytes per 16,384 plaintext bytes at least) is as implausible as one far above it, and both ends are inclusive.
 */
class OverheadBoundTest {
    @Test
    fun theLowerBoundIsTwentyTwoBytesPerStartedSixteenKiBRecordInEachDirectionAndInclusive() {
        var cases = 0
        for ((out, inn, lower) in listOf(
            Triple(1L, 0L, 22L), Triple(16_384L, 0L, 22L), Triple(16_385L, 0L, 44L), Triple(0L, 16_385L, 44L), Triple(16_384L, 16_384L, 44L), Triple(32_769L, 1L, 88L), Triple(0L, 0L, 0L),
        )) {
            assertTrue(Overhead.withinRecordBound(lower, out, inn, 100, 100), "overhead $lower is exactly the lower bound for $out+$inn")
            if (lower > 0) assertFalse(Overhead.withinRecordBound(lower - 1, out, inn, 100, 100), "overhead ${lower - 1} is below the record cost of $out+$inn application bytes")
            cases++
        }
        assertEquals(7, cases)
    }

    @Test
    fun theUpperBoundIsOneRecordPerFlushPlusTheHandshakeAllowanceAndInclusive() {
        val upper = Overhead.TLS_RECORD_OVERHEAD * (1 + 1 + 2 + 3) + 2 * Overhead.HANDSHAKE_TOLERANCE_PER_DIRECTION
        assertTrue(Overhead.withinRecordBound(upper, 50, 60, 2, 3))
        assertFalse(Overhead.withinRecordBound(upper + 1, 50, 60, 2, 3))
    }

    @Test
    fun l15FailsOnAMeasuredOverheadBelowTheRecordCostOfTheApplicationBytes() {
        fun run(overhead: Long): LedgerLaws.L15 {
            val counted = LabRouteRecord(ts = 1, callerPkg = "peer:x", requestedModel = "", egress = LabEgress.peerClass, bytesOut = 50, bytesIn = 0, meshKind = MeshKind.CONTROL, sessionId = "s1", meshCode = "HELLO")
            val close = counted.copy(meshKind = MeshKind.SESSION, meshCode = "close", bytesOut = 0, overheadBytes = overhead, overheadBasis = OverheadBasis.MEASURED)
            val t = listOf<Ev>(Appended("A", counted), Appended("A", close), Tap("A", "s1", 50 + overhead), PlainTap("A", "s1", 50, 0, 1, 0))
            return LedgerLaws.l15(t)
        }
        val atBound = run(22)
        assertEquals(0, atBound.mismatches, "22 bytes for one record is the least a 50-byte write can cost")
        assertEquals(1, atBound.measuredSessions)
        for (tooLow in listOf(0L, 1L, 10L, 21L)) {
            val r = run(tooLow)
            assertTrue(r.mismatches > 0 && r.result.violations.any { "RFC 8446 record bound" in it }, "overhead $tooLow: rows + overhead equal the tap, so only the record bound can notice")
        }
    }
}
