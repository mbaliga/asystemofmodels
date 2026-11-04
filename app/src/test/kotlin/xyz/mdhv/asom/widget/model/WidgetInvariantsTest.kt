package xyz.mdhv.asom.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.PairingStatusCode

/**
 * Runs every presenter over a matrix of states and sizes, once with the real
 * English strings from strings.xml, and checks the rules that hold for every
 * widget: shape-and-word redundancy for every coloured line (§1.6), no
 * on-device wording anywhere (v1 serves nothing locally), glyph-free TalkBack
 * text, and every sentence exercised with arguments String.format accepts.
 */
class WidgetInvariantsTest {

    private fun allFaces(copy: WidgetCopy): List<Face> = buildList {
        val egressDays = listOf(
            EgressDay.of(emptyList(), DAY_START),
            EgressDay.of(listOf(route(egress = Egress.LOCAL, provider = null, model = null, status = 501)), DAY_START),
            EgressDay.of(
                listOf(
                    route(cost = 0.002, basis = CostBasis.USAGE, bytesOut = 900, tokensIn = 10, tokensOut = 20),
                    route(provider = "groq", status = 429),
                    route(egress = Egress.CATALOGUE, provider = null, model = null),
                    route(egress = Egress.DOWNLOAD, provider = null, model = null),
                    route(egress = Egress.LOCAL, provider = null, model = null, status = 401),
                ),
                DAY_START,
            ),
            EgressDay.of(listOf(route()), DAY_START),
            EgressDay.of(listOf(route(cost = 0.01, basis = CostBasis.HEURISTIC, tokensIn = 4)), DAY_START),
        )
        val daemonStates = listOf(
            DaemonState(false, DaemonActivity.IDLE, null, false, false, false, "127.0.0.1", 11435),
            DaemonState(false, DaemonActivity.IDLE, null, true, true, true, "127.0.0.1", 11435),
            DaemonState(true, DaemonActivity.IDLE, null, false, false, false, "127.0.0.1", 11435),
            DaemonState(true, DaemonActivity.SERVING, null, false, true, false, "127.0.0.1", 11435),
            DaemonState(true, DaemonActivity.STREAMING, "openrouter", false, true, true, "127.0.0.1", 11435),
        )
        val routes = listOf(
            null,
            route(cost = 0.0001, basis = CostBasis.USAGE, tokensIn = 5, tokensOut = 7, bytesOut = 300),
            route(provider = "groq", status = 429),
            route(provider = null, model = null, status = 503),
            route(egress = Egress.LOCAL, provider = null, model = null, status = 501, requested = ""),
            route(ts = DAY_START - 5),
            route(egress = Egress.LOCAL, provider = null, model = null, status = 501, requested = "local-only"),
        )
        val hotspots = listOf(
            HotspotCounts.of(emptyList()),
            HotspotCounts.of(listOf(PairingRow(PairingStatusCode.PENDING, null))),
            HotspotCounts.of(
                listOf(
                    PairingRow(PairingStatusCode.PAIRED, DAY_START + 1),
                    PairingRow(PairingStatusCode.PENDING, null),
                    PairingRow(PairingStatusCode.PENDING, null),
                    PairingRow(PairingStatusCode.REVOKED, null),
                ),
            ),
        )
        val gb = 1024L * 1024 * 1024
        val downloads = listOf(
            DownloadsSummary.of(emptyList(), emptyList(), 0),
            DownloadsSummary.of(listOf("a"), emptyList(), 0),
            DownloadsSummary.of(listOf("a", "b"), listOf(ModelRow("a", ModelState.DOWNLOADED, gb, gb, 1), ModelRow("b", ModelState.FAILED, 0, gb, 2)), gb),
            DownloadsSummary.of(
                listOf("a", "b", "c"),
                listOf(
                    ModelRow("a", ModelState.DOWNLOADING, 3 * gb, 10 * gb, 9),
                    ModelRow("b", ModelState.DOWNLOADING, 1, 0, 3),
                    ModelRow("c", ModelState.DOWNLOADED, gb, gb, 1),
                ),
                gb,
            ),
            DownloadsSummary.of(listOf("b"), listOf(ModelRow("b", ModelState.DOWNLOADING, 1, 0, 3)), 0),
            DownloadsSummary.of(listOf("v"), listOf(ModelRow("v", ModelState.VERIFYING, 5, 5, 3)), 5),
        )
        for (size in SizeClass.entries) {
            for (day in egressDays) for (degraded in listOf(false, true)) add(EgressTodayWidget.present(day, degraded, size, NOW, copy))
            for (s in daemonStates) add(DaemonWidget.present(s, size, NOW, copy))
            for (r in routes) add(LastRouteWidget.present(r, DAY_START, size, copy))
            add(RecentRoutesWidget.present(routes.filterNotNull(), DAY_START, size, NOW, copy))
            add(RecentRoutesWidget.present(emptyList(), DAY_START, size, NOW, copy))
            for (h in hotspots) add(HotspotWidget.present(h, DAY_START, size, NOW, copy))
            for (d in downloads) add(DownloadsWidget.present(d, size, NOW, copy))
        }
        for (kind in WidgetKind.entries) add(ErrorFaces.of(kind, NOW, copy))
    }

    @Test
    fun `every coloured line also carries a shape and words`() {
        for (face in allFaces(FakeCopy())) {
            // The TINY layout prints the summary line, glyph and tone included.
            for (line in face.allLines() + face.summary().line) {
                if (line.tone != Tone.NEUTRAL) {
                    assertTrue(line.glyph != null, "toned line without a glyph: $line in ${face.title}")
                    assertTrue(line.text.isNotBlank(), "toned line without words: $line")
                }
                line.glyph?.let { assertTrue(it in Glyph.ALL, "unknown glyph $it") }
            }
        }
    }

    @Test
    fun `the real copy formats every sentence and plural without error`() {
        val copy = XmlCopy()
        val faces = allFaces(copy)
        assertTrue(faces.size >= 90, "matrix built ${faces.size} faces")
        assertEquals(Txt.entries.toSet(), copy.usedText, "every Txt is exercised")
        assertEquals(Cnt.entries.toSet(), copy.usedCount, "every Cnt is exercised")
    }

    @Test
    fun `nothing on any widget calls a route on-device or native`() {
        for (face in allFaces(XmlCopy())) {
            for (text in face.allText()) {
                val lower = text.lowercase()
                assertTrue("on-device" !in lower && "on device" !in lower, "on-device wording: $text")
                assertTrue("native" !in lower, "provenance word 'native' used for a v1 route: $text")
            }
        }
    }

    @Test
    fun `talkback text has no glyphs and says where the tap goes`() {
        val copy = XmlCopy()
        for (face in allFaces(copy)) {
            for (g in Glyph.ALL) assertTrue(g !in face.description, "glyph $g in: ${face.description}")
            assertTrue(face.description.endsWith(copy.text(WidgetFormat.opensKey(face.open))))
        }
    }

    @Test
    fun `real English for the states the owner asked about`() {
        val copy = XmlCopy()
        val local = LastRouteWidget.present(
            route(egress = Egress.LOCAL, provider = null, model = null, status = 501, requested = ""),
            DAY_START,
            SizeClass.MEDIUM,
            copy,
        )
        assertEquals("■ local — nothing served, nothing left the device", local.headline.display)
        val daemon = DaemonWidget.present(
            DaemonState(true, DaemonActivity.IDLE, null, false, false, false, "127.0.0.1", 11435),
            SizeClass.MEDIUM,
            NOW,
            copy,
        )
        assertEquals("● running", daemon.value.display)
        assertEquals("serving on 127.0.0.1:11435", daemon.lines[0].text)
        val egress = EgressTodayWidget.present(
            EgressDay.of(listOf(route(cost = 0.0042, basis = CostBasis.USAGE), route(provider = "groq", status = 429)), DAY_START),
            false,
            SizeClass.LARGE,
            NOW,
            copy,
        )
        assertEquals("▲ 2", egress.value.display)
        assertEquals("rows", egress.unit)
        assertEquals("≈ 0.0042 USD estimated (1 of 2 cloud rows priced)", egress.lines[0].text)
        val incomplete = EgressTodayWidget.present(EgressDay.of(listOf(route()), DAY_START), true, SizeClass.SMALL, NOW, copy)
        assertEquals("⚠ 1 row (incomplete)", incomplete.summary().line.display)
        val pending = HotspotWidget.present(
            HotspotCounts.of(listOf(PairingRow(PairingStatusCode.PENDING, null))),
            DAY_START,
            SizeClass.MEDIUM,
            NOW,
            copy,
        )
        assertEquals("◐ 1 app waiting for your approval", pending.lines[0].display)
    }
}
