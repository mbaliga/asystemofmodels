package xyz.mdhv.asom.ut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.ledger.LabRouteRecord

/**
 * UTC03: the UI record is the terminal ledger row (Invariant 9). Every projected member equals the row's member byte for byte
 * (JCS), the record's `egress` equals the `X-Asom-Egress` the same row yields, and the provenance line is built from them.
 */
class Utc03ProjectionTest {
    @Test
    fun vectors() {
        val vectors = UtcVectors.load("UTC03-projection.json", "UTC03")
        val laws = Laws("UTC03")
        for (v in vectors) {
            val rowJson = v.input.obj("row")
            val row = LabRouteRecord.fromRow(rowJson)
            val outcome = try {
                Result.success(UiProjection.project(row))
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
            if (v.expectReject != null) {
                val e = outcome.exceptionOrNull() ?: error("${v.id}: expected reject ${v.expectReject}, but the row was projected")
                val code = if (e.message!!.startsWith("malformed row")) "MALFORMED_ROW" else "NOT_TERMINAL"
                assertEquals(v.expectReject, code, "${v.id}: ${v.description}")
                laws.bump("reject-$code")
                continue
            }
            val record = outcome.getOrThrow()
            val want = v.expectOk!!.asObj()
            assertEquals(want.obj("record"), record, "${v.id}: ${v.description}")
            val rowFull = row.toRow()
            for (column in UiProjection.COLUMNS) {
                assertEquals(
                    Jcs.serializeToString(rowFull[column]!!), Jcs.serializeToString(record[column]!!),
                    "${v.id}: member $column is not the ledger row's member byte for byte",
                )
                laws.bump("column-bytes-equal")
            }
            val headers = row.toEchoHeaders()
            assertEquals(want.obj("headers").members.associate { it.first to it.second.asStr() }, headers, "${v.id}: echo headers")
            assertEquals(headers["X-Asom-Egress"], record.str("egress"), "${v.id}: the record's egress differs from the echo header (Invariant 9)")
            laws.bump("egress-header-equal")
            assertEquals(record.str("egress"), record.str("reach"), "${v.id}: on a terminal row egress is reach")
            laws.bump("terminal-egress-is-reach")
            laws.bump("served-${record.strOrNull("servedClass") ?: "none"}")
            if (record.str("provenance").contains('�')) laws.bump("alias-sanitised")
            assertTrue(record.str("provenance").none { it.isISOControl() }, "${v.id}: the provenance line holds a control character")
            laws.bump("provenance-no-control")
        }
        laws.requireAll(
            setOf(
                "column-bytes-equal", "egress-header-equal", "terminal-egress-is-reach", "served-peer", "served-local", "served-cloud", "served-none",
                "alias-sanitised", "provenance-no-control", "reject-NOT_TERMINAL", "reject-MALFORMED_ROW",
            ),
            minimumVectors = 11, vectors = vectors.size,
        )
    }

    @Test
    fun theRecordCarriesNoMemberThatIsNotAColumnOrTheProvenance() {
        val row = FakeNode.terminalRow("r1", "fake", 1L)
        val record: JObject = UiProjection.project(row)
        assertEquals(UiProjection.COLUMNS + "provenance", record.members.map { it.first })
    }
}
