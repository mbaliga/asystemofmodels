package xyz.mdhv.asom.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteRecordTest {

    private fun record(
        servedProvider: String? = "openrouter",
        servedModel: String? = "llama-3.3-70b",
        egress: Egress = Egress.CLOUD,
        costEst: Double? = null,
        costBasis: CostBasis = CostBasis.NONE,
    ) = RouteRecord(
        ts = 1_720_000_000_000,
        callerPkg = "xyz.mdhv.asom.sample",
        requestedModel = "cheapest",
        servedProvider = servedProvider,
        servedModel = servedModel,
        egress = egress,
        latencyMs = 42,
        status = 200,
        costEst = costEst,
        costBasis = costBasis,
    )

    @Test
    fun `served-by header is provider slash model`() {
        val headers = record().toEchoHeaders()
        assertEquals("openrouter/llama-3.3-70b", headers[AsomHeaders.SERVED_BY])
        assertEquals("cloud", headers[AsomHeaders.EGRESS])
    }

    @Test
    fun `served-by omitted when nothing served`() {
        val headers = record(servedProvider = null, servedModel = null).toEchoHeaders()
        assertNull(headers[AsomHeaders.SERVED_BY])
        // Egress is always present.
        assertEquals("cloud", headers[AsomHeaders.EGRESS])
    }

    @Test
    fun `cost headers appear only with a basis — never estimate without one`() {
        // §5.4 law. Estimate without basis → no cost headers.
        val noBasis = record(costEst = 0.000123, costBasis = CostBasis.NONE).toEchoHeaders()
        assertFalse(AsomHeaders.COST_EST in noBasis)
        assertFalse(AsomHeaders.COST_BASIS in noBasis)

        // No estimate at all → no cost headers.
        val noEst = record(costEst = null, costBasis = CostBasis.USAGE).toEchoHeaders()
        assertFalse(AsomHeaders.COST_EST in noEst)

        // Estimate + basis → both headers.
        val usage = record(costEst = 0.000123, costBasis = CostBasis.USAGE).toEchoHeaders()
        assertEquals("0.000123", usage[AsomHeaders.COST_EST])
        assertEquals("usage", usage[AsomHeaders.COST_BASIS])

        val heuristic = record(costEst = 1.5, costBasis = CostBasis.HEURISTIC).toEchoHeaders()
        assertEquals("1.5", heuristic[AsomHeaders.COST_EST])
        assertEquals("heuristic", heuristic[AsomHeaders.COST_BASIS])
    }

    @Test
    fun `usd formatting is plain decimal — no scientific notation`() {
        assertEquals("0.0000001", RouteRecord.formatUsd(1e-7))
        assertEquals("12", RouteRecord.formatUsd(12.0))
    }

    @Test
    fun `egress wire values are the frozen four classes`() {
        assertEquals(
            listOf("local", "cloud", "catalogue", "download"),
            Egress.entries.map { it.wire },
        )
    }

    @Test
    fun `header names match the frozen contract spelling`() {
        val h = record(costEst = 0.01, costBasis = CostBasis.USAGE).toEchoHeaders()
        assertTrue(h.keys.all { it.startsWith("X-Asom-") })
        assertEquals(
            setOf("X-Asom-Served-By", "X-Asom-Egress", "X-Asom-Cost-Est", "X-Asom-Cost-Basis"),
            h.keys,
        )
    }
}
