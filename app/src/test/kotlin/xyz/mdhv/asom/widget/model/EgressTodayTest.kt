package xyz.mdhv.asom.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress

class EgressTodayTest {

    private val copy = FakeCopy()

    private val busyDay = listOf(
        route(cost = 0.002, basis = CostBasis.USAGE, bytesOut = 1000, tokensIn = 100, tokensOut = 50),
        route(cost = 0.001, basis = CostBasis.HEURISTIC, bytesOut = 500, tokensIn = 40, tokensOut = null),
        // A failover attempt: the body reached groq, no cost basis.
        route(provider = "groq", status = 429, bytesOut = 700),
        // A cost value without a basis must not be summed (§5.4: never estimate without a basis).
        route(cost = 9.99, basis = CostBasis.NONE),
        route(egress = Egress.DOWNLOAD, provider = null, model = null, caller = "asom"),
        route(egress = Egress.CATALOGUE, provider = null, model = null, caller = "asom"),
        route(egress = Egress.LOCAL, provider = null, model = null, status = 501),
        // Yesterday: ignored even if the query hands it over.
        route(ts = DAY_START - 1, cost = 5.0, basis = CostBasis.USAGE),
    )

    @Test
    fun `left the device counts cloud, catalogue and download rows but never local rows`() {
        val day = EgressDay.of(busyDay, DAY_START)
        assertEquals(4, day.cloudRows)
        assertEquals(1, day.catalogueRows)
        assertEquals(1, day.downloadRows)
        assertEquals(1, day.localRows)
        assertEquals(6, day.leftRows)
        assertEquals(2200L, day.bytesOut)
        assertEquals(140L, day.tokensIn)
        assertEquals(50L, day.tokensOut)
    }

    @Test
    fun `cost sums only rows with an estimate and a basis`() {
        val day = EgressDay.of(busyDay, DAY_START)
        assertEquals(2, day.pricedRows)
        assertEquals(0.003, day.costUsd!!, 1e-12)
    }

    @Test
    fun `no basis means no cost at all, not zero`() {
        val day = EgressDay.of(listOf(route(), route(provider = "groq", status = 429)), DAY_START)
        assertNull(day.costUsd)
        val face = EgressTodayWidget.present(day, false, SizeClass.MEDIUM, NOW, copy)
        assertTrue(face.lines.any { it.text == "NO_COST_BASIS" })
        assertTrue(face.lines.none { it.text.startsWith("EGRESS_COST") })
    }

    @Test
    fun `an empty day says nothing left, in cyan with the local shape`() {
        val day = EgressDay.of(emptyList(), DAY_START)
        val face = EgressTodayWidget.present(day, false, SizeClass.LARGE, NOW, copy)
        assertEquals(Line("0", Glyph.LOCAL, Tone.CYAN), face.value)
        assertEquals("UNIT_ROWS[0]", face.unit)
        assertEquals(listOf(Line("EGRESS_NOTHING")), face.lines)
        assertEquals(Destination.LEDGER, face.open)
    }

    @Test
    fun `a local-only day is not egress and says so`() {
        val day = EgressDay.of(listOf(route(egress = Egress.LOCAL, provider = null, model = null, status = 501)), DAY_START)
        assertEquals(0, day.leftRows)
        val face = EgressTodayWidget.present(day, false, SizeClass.LARGE, NOW, copy)
        assertEquals("0", face.value.text)
        assertTrue(face.lines.contains(Line("LOCAL_ROWS[1]", Glyph.LOCAL, Tone.CYAN)))
    }

    @Test
    fun `more room shows more lines, and every size shows the date`() {
        val day = EgressDay.of(busyDay, DAY_START)
        val tiny = EgressTodayWidget.present(day, false, SizeClass.TINY, NOW, copy)
        val small = EgressTodayWidget.present(day, false, SizeClass.SMALL, NOW, copy)
        val medium = EgressTodayWidget.present(day, false, SizeClass.MEDIUM, NOW, copy)
        val large = EgressTodayWidget.present(day, false, SizeClass.LARGE, NOW, copy)
        assertEquals(0, tiny.lines.size)
        assertEquals(0, small.lines.size)
        // The date stays visible at every size, so a missed midnight redraw shows.
        for (face in listOf(tiny, small, medium, large)) assertEquals("d$NOW", face.asOf)
        assertEquals(2, medium.lines.size)
        assertEquals(4, large.lines.size)
        assertEquals("EGRESS_FOOTER", large.footer)
        assertNull(medium.footer)
        assertEquals(Line("6", Glyph.CLOUD, Tone.VIOLET), small.value)
        assertEquals("UNIT_ROWS[6]", small.unit)
        // 2 of the 4 cloud rows carry a basis: the coverage is in the cost sentence itself.
        assertEquals(Line("COST_PARTIAL[4](0.003|2|4)"), medium.lines[0])
        assertEquals(
            Line("${Glyph.CLOUD} EGRESS_PART_CLOUD(4) · ${Glyph.LOCAL} EGRESS_PART_CATALOGUE(1) · ${Glyph.LOCAL} EGRESS_PART_DOWNLOAD(1)"),
            medium.lines[1],
        )
        assertEquals(medium.lines, large.lines.take(2))
    }

    @Test
    fun `a partly priced day says how many cloud rows the estimate covers, at medium too`() {
        val rows = listOf(route(cost = 0.0004, basis = CostBasis.USAGE)) + List(9) { route() }
        val face = EgressTodayWidget.present(EgressDay.of(rows, DAY_START), false, SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("COST_PARTIAL[10](0.0004|1|10)"), face.lines[0])
        assertTrue(face.allText().none { it.startsWith("EGRESS_COST(") }, "never the plain sentence for a partial sum")
    }

    @Test
    fun `a fully priced day uses the plain cost sentence`() {
        val rows = listOf(route(cost = 0.001, basis = CostBasis.USAGE), route(cost = 0.002, basis = CostBasis.HEURISTIC))
        val face = EgressTodayWidget.present(EgressDay.of(rows, DAY_START), false, SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("EGRESS_COST(0.003)"), face.lines[0])
    }

    @Test
    fun `an incomplete ledger is said first, because it undercounts this number`() {
        val day = EgressDay.of(busyDay, DAY_START)
        val face = EgressTodayWidget.present(day, true, SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("LEDGER_DEGRADED", Glyph.WARNING, Tone.VIOLET), face.lines.first())
        assertEquals(Line("6", Glyph.CLOUD, Tone.VIOLET), face.value, "the warning line carries it here")
    }

    @Test
    fun `a compact widget marks the value itself when the ledger is incomplete`() {
        val day = EgressDay.of(busyDay, DAY_START)
        for (size in listOf(SizeClass.TINY, SizeClass.SMALL)) {
            val face = EgressTodayWidget.present(day, true, size, NOW, copy)
            assertEquals(Line("6", Glyph.WARNING, Tone.VIOLET), face.value, "$size")
            assertEquals("UNIT_INCOMPLETE(UNIT_ROWS[6])", face.unit)
            assertTrue("6 UNIT_INCOMPLETE(UNIT_ROWS[6])" in face.description, face.description)
            assertEquals(Line("6 UNIT_INCOMPLETE(UNIT_ROWS[6])", Glyph.WARNING, Tone.VIOLET), face.summary().line)
        }
        val empty = EgressTodayWidget.present(EgressDay.of(emptyList(), DAY_START), true, SizeClass.SMALL, NOW, copy)
        assertEquals(Line("0", Glyph.WARNING, Tone.VIOLET), empty.value, "an empty but incomplete day is not 'nothing left'")
    }

    @Test
    fun `the caller package never reaches the face`() {
        val day = EgressDay.of(busyDay, DAY_START)
        for (size in SizeClass.entries) {
            val face = EgressTodayWidget.present(day, true, size, NOW, copy)
            assertTrue(face.allText().none { "journal" in it })
            assertTrue(face.description.endsWith("OPENS_LEDGER"))
        }
    }
}
