package xyz.mdhv.asom.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord

class RecordMappingTest {

    @Test
    fun `record to entity to record round-trips losslessly`() {
        val record = RouteRecord(
            ts = 123L,
            callerPkg = "xyz.mdhv.asom.sample",
            requestedModel = "cheapest",
            servedProvider = "openrouter",
            servedModel = "llama-3.3-70b",
            egress = Egress.CLOUD,
            bytesOut = 512,
            tokensIn = 10,
            tokensOut = 20,
            costEst = 0.000123,
            costBasis = CostBasis.USAGE,
            latencyMs = 240,
            status = 200,
        )
        assertEquals(record, record.toEntity().toRecord())
    }

    @Test
    fun `null fields survive the round-trip`() {
        val record = RouteRecord(
            ts = 1L,
            callerPkg = "unknown",
            requestedModel = "local-only",
            egress = Egress.LOCAL,
            latencyMs = 3,
            status = 501,
        )
        assertEquals(record, record.toEntity().toRecord())
        assertEquals("local", record.toEntity().egress)
        assertEquals("none", record.toEntity().costBasis)
    }
}
