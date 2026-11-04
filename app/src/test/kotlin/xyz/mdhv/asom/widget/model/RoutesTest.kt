package xyz.mdhv.asom.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress

class RoutesTest {

    private val copy = FakeCopy()

    @Test
    fun `outcomes follow egress, provider and status`() {
        assertEquals(RouteOutcome.SERVED, RouteView(route()).outcome)
        assertEquals(RouteOutcome.SENT_NOT_SERVED, RouteView(route(status = 429)).outcome)
        assertEquals(RouteOutcome.CLOUD_NOT_SERVED, RouteView(route(provider = null, model = null, status = 503)).outcome)
        // In v1 a local row is "nothing served", whatever its status.
        assertEquals(RouteOutcome.LOCAL_NOT_SERVED, RouteView(route(egress = Egress.LOCAL, provider = null, model = null, status = 501)).outcome)
        assertEquals(RouteOutcome.LOCAL_NOT_SERVED, RouteView(route(egress = Egress.LOCAL, status = 200)).outcome)
    }

    @Test
    fun `the widget reads exactly the echo headers the API sent`() {
        val record = route(cost = 0.00012345, basis = CostBasis.USAGE)
        val view = RouteView(record)
        val headers = record.toEchoHeaders()
        assertEquals(headers[AsomHeaders.SERVED_BY], view.servedBy)
        assertEquals(headers[AsomHeaders.EGRESS], view.egressWire)
        assertEquals(headers[AsomHeaders.COST_EST], view.costEst)
        assertEquals(headers[AsomHeaders.COST_BASIS], view.costBasis)
        val face = LastRouteWidget.present(record, DAY_START, SizeClass.LARGE, copy)
        assertTrue(Line("ROUTE_COST(0.00012345|usage)") in face.lines)
        assertTrue(Line("ROUTE_SERVED_BY(openrouter/llama-3.3-70b)") in face.lines)
    }

    @Test
    fun `catalogue and download rows are not routes`() {
        assertTrue(RouteView.isRoute(route()))
        assertTrue(RouteView.isRoute(route(egress = Egress.LOCAL)))
        assertTrue(!RouteView.isRoute(route(egress = Egress.DOWNLOAD)))
        assertTrue(!RouteView.isRoute(route(egress = Egress.CATALOGUE)))
    }

    @Test
    fun `a local route is never shown as cloud or as on-device`() {
        val local = route(egress = Egress.LOCAL, provider = null, model = null, status = 501, requested = "")
        val face = LastRouteWidget.present(local, DAY_START, SizeClass.LARGE, copy)
        assertEquals(Line("ROUTE_LOCAL", Glyph.LOCAL, Tone.CYAN), face.headline)
        assertEquals(listOf(Line("ROUTE_NOT_SERVED"), Line("ROUTE_STATUS(501|t$NOW)")), face.lines)
        val asked = LastRouteWidget.present(local.copy(requestedModel = "local-only"), DAY_START, SizeClass.LARGE, copy)
        assertEquals(Line("ROUTE_ASKED(local-only)"), asked.lines.last())
        assertTrue(face.allLines().none { it.glyph == Glyph.CLOUD })
        val small = LastRouteWidget.present(local, DAY_START, SizeClass.SMALL, copy)
        assertEquals(Line("ROW_LOCAL(501)", Glyph.LOCAL, Tone.CYAN), small.headline)
    }

    @Test
    fun `last route grows from one line to the full detail`() {
        val record = route(cost = 0.0004, basis = CostBasis.HEURISTIC, bytesOut = 2048, tokensIn = 120, tokensOut = 340)
        val small = LastRouteWidget.present(record, DAY_START, SizeClass.SMALL, copy)
        val medium = LastRouteWidget.present(record, DAY_START, SizeClass.MEDIUM, copy)
        val large = LastRouteWidget.present(record, DAY_START, SizeClass.LARGE, copy)
        assertEquals(Line("openrouter/llama-3.3-70b", Glyph.CLOUD, Tone.VIOLET), small.headline)
        assertTrue(small.lines.isEmpty())
        assertEquals(Line("ROUTE_CLOUD", Glyph.CLOUD, Tone.VIOLET), medium.headline)
        assertEquals(2, medium.lines.size)
        assertEquals(4, large.lines.size)
        assertEquals("ROUTES_FOOTER", large.footer)
        assertEquals("t$NOW", large.asOf)
        assertTrue(large.lines[3].text.startsWith("ROUTE_DETAIL(120|340|420|"), large.lines[3].text)
        assertTrue(large.lines.none { it.text.startsWith("ROUTE_ASKED") }, "a cloud card is full before asked-for")
    }

    @Test
    fun `a route from before today carries its date`() {
        val face = LastRouteWidget.present(route(ts = DAY_START - 10), DAY_START, SizeClass.MEDIUM, copy)
        assertEquals("dt${DAY_START - 10}", face.asOf)
    }

    @Test
    fun `failed attempts say where the body went and that it was not served`() {
        val face = LastRouteWidget.present(route(provider = "groq", status = 429), DAY_START, SizeClass.MEDIUM, copy)
        assertEquals(Line("ROUTE_SENT_TO(groq/llama-3.3-70b)"), face.lines[0])
        val all = LastRouteWidget.present(route(provider = null, model = null, status = 503), DAY_START, SizeClass.MEDIUM, copy)
        assertEquals(Line("ROUTE_NOT_SERVED"), all.lines[0])
        assertEquals(Line("ROUTE_CLOUD", Glyph.CLOUD, Tone.VIOLET), all.headline, "it did leave: cloud, not served")
    }

    @Test
    fun `no route yet is an honest empty state`() {
        val face = LastRouteWidget.present(null, DAY_START, SizeClass.MEDIUM, copy)
        assertEquals(Line("ROUTES_EMPTY", Glyph.INACTIVE, Tone.NEUTRAL), face.headline)
        assertEquals(listOf(Line("ROUTES_HINT")), face.lines)
        assertNull(face.asOf)
    }

    @Test
    fun `recent routes show one, three or five rows, newest first`() {
        val rows = (1..7).map { route(ts = DAY_START + it, provider = "p$it") } +
            route(ts = DAY_START + 100, egress = Egress.DOWNLOAD, provider = null, model = null)
        val small = RecentRoutesWidget.present(rows, DAY_START, SizeClass.SMALL, NOW, copy)
        val medium = RecentRoutesWidget.present(rows, DAY_START, SizeClass.MEDIUM, NOW, copy)
        val large = RecentRoutesWidget.present(rows, DAY_START, SizeClass.LARGE, NOW, copy)
        assertEquals(1, small.rows.size)
        assertEquals(3, medium.rows.size)
        assertEquals(5, large.rows.size)
        assertEquals("p7/llama-3.3-70b", small.rows[0].lead.text, "the download row is skipped, p7 is newest")
        assertEquals("t${DAY_START + 7}", small.rows[0].trailing)
        assertEquals(listOf("p7", "p6", "p5", "p4", "p3"), large.rows.map { it.lead.text.substringBefore('/') })
        assertEquals("ROUTES_FOOTER", large.footer)
        assertNull(small.asOf)
        assertEquals("AS_OF(t$NOW)", medium.asOf)
    }

    @Test
    fun `recent routes empty state fits every size`() {
        assertEquals(1, RecentRoutesWidget.present(emptyList(), DAY_START, SizeClass.SMALL, NOW, copy).rows.size)
        val large = RecentRoutesWidget.present(emptyList(), DAY_START, SizeClass.LARGE, NOW, copy)
        assertEquals(listOf("ROUTES_EMPTY", "ROUTES_HINT"), large.rows.map { it.lead.text })
    }

    @Test
    fun `route widgets never show the caller`() {
        val rows = listOf(route(caller = "com.example.health.diary"), route(egress = Egress.LOCAL, caller = "com.example.health.diary"))
        for (size in SizeClass.entries) {
            val texts = RecentRoutesWidget.present(rows, DAY_START, size, NOW, copy).allText() +
                LastRouteWidget.present(rows[0], DAY_START, size, copy).allText()
            assertTrue(texts.none { "diary" in it || "health" in it })
        }
    }
}
