package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R4-L-25 (ERR-PI-2): the ESTIMATED form is `R x ceilDiv(appBytesOfFlush, Lmax)` per flush plus 8,192 bytes of handshake per session, with the calibrated
 * `R = 38`, `Lmax = 16,367` for JSSE and the RFC 8446 floor `R = 22`, `Lmax = 16,384` for a stack nobody has calibrated.
 */
class EstimatedOverheadTest {
    @Test
    fun theJsseStackIsThirtyEightBytesPerRecordAndRecordsHoldAtMostSixteenThousandThreeHundredSixtySevenBytes() {
        var cases = 0
        for ((flush, expected) in listOf(
            listOf<Long>() to 0L, listOf(0L) to 0L, listOf(1L) to 38L, listOf(16_366L) to 38L, listOf(16_367L) to 38L, listOf(16_368L) to 76L,
            listOf(16_384L) to 76L, listOf(32_734L) to 76L, listOf(32_735L) to 114L, listOf(20_000L, 5L) to 38L * 2 + 38L,
        )) {
            assertEquals(expected, Overhead.estimatedRecords(flush, Overhead.JSSE_17_21), "JSSE flushes $flush")
            cases++
        }
        assertEquals(10, cases)
    }

    @Test
    fun anUncalibratedStackIsTheRfc8446MinimumAndIsLabelledAsSuch() {
        var cases = 0
        for ((flush, expected) in listOf(
            listOf<Long>() to 0L, listOf(1L) to 22L, listOf(16_367L) to 22L, listOf(16_384L) to 22L, listOf(16_385L) to 44L, listOf(20_000L, 5L) to 22L * 2 + 22L,
        )) {
            assertEquals(expected, Overhead.estimatedRecords(flush, Overhead.UNCALIBRATED), "uncalibrated flushes $flush")
            assertEquals(expected, Overhead.estimatedRecords(flush), "the default stack is the uncalibrated one")
            cases++
        }
        assertEquals(6, cases)
        assertTrue("uncalibrated" in Overhead.UNCALIBRATED.label && "RFC 8446" in Overhead.UNCALIBRATED.label)
        assertTrue("JSSE" in Overhead.JSSE_17_21.label)
    }

    @Test
    fun theHandshakeAllowanceIsEightKilobytesPerSessionWhateverTheStack() {
        assertEquals(8_192L, Overhead.estimatedHandshake())
        assertEquals(8_192L, Overhead.HANDSHAKE_ESTIMATE_PER_SESSION)
        assertEquals(Overhead.estimatedRecords(listOf(100L), Overhead.JSSE_17_21) + 8_192L, Overhead.estimated(listOf(100L), Overhead.JSSE_17_21))
        assertEquals(Overhead.estimatedRecords(listOf(100L), Overhead.UNCALIBRATED) + 8_192L, Overhead.estimated(listOf(100L), Overhead.UNCALIBRATED))
    }

    @Test
    fun theTwoStacksDisagreeSoAFormThatIgnoredTheStackWouldBeCaught() {
        assertNotEquals(Overhead.estimatedRecords(listOf(1L), Overhead.JSSE_17_21), Overhead.estimatedRecords(listOf(1L), Overhead.UNCALIBRATED))
        assertEquals(22L, Overhead.TLS_RECORD_OVERHEAD, "[F51] stays the lower bound of the record-bound check")
        assertEquals(40L, Overhead.JSSE_17_21.closeNotify)
    }
}
