package xyz.mdhv.asom.widget.model

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetModelTest {

    @Test
    fun `size class follows the breakpoints`() {
        // Android's cell table: 1 row is about 102dp tall in portrait and 51dp in
        // landscape; 2 rows about 220dp and 117dp; 3 rows in landscape about 184dp.
        assertEquals(SizeClass.TINY, SizeClass.of(269f, 51f)) // 2x1 landscape: one short row
        assertEquals(SizeClass.TINY, SizeClass.of(554f, 51f)) // 4x1 landscape
        assertEquals(SizeClass.TINY, SizeClass.of(100f, 220f)) // narrower than any layout
        assertEquals(SizeClass.SMALL, SizeClass.of(130f, 102f)) // 2x1 portrait
        assertEquals(SizeClass.SMALL, SizeClass.of(276f, 102f)) // 4x1 portrait: one row stays small
        assertEquals(SizeClass.SMALL, SizeClass.of(130f, 220f)) // 2x2 portrait: too narrow for more
        assertEquals(SizeClass.SMALL, SizeClass.of(412f, 117f)) // 3x2 landscape: medium needs 120dp
        assertEquals(SizeClass.SMALL, SizeClass.of(110f, 72f)) // the small breakpoint itself
        assertEquals(SizeClass.TINY, SizeClass.of(110f, 71f))
        assertEquals(SizeClass.MEDIUM, SizeClass.of(180f, 120f)) // the medium breakpoint itself
        assertEquals(SizeClass.MEDIUM, SizeClass.of(360f, 120f)) // Orrery hub, short preset
        assertEquals(SizeClass.MEDIUM, SizeClass.of(554f, 184f)) // 4x3 landscape
        assertEquals(SizeClass.SMALL, SizeClass.of(179f, 120f))
        assertEquals(SizeClass.SMALL, SizeClass.of(180f, 119f))
        assertEquals(SizeClass.LARGE, SizeClass.of(203f, 220f)) // 3x2 portrait, the default placement
        assertEquals(SizeClass.LARGE, SizeClass.of(180f, 200f)) // the large breakpoint itself
        assertEquals(SizeClass.LARGE, SizeClass.of(360f, 200f)) // Orrery hub, medium preset
    }

    @Test
    fun `compact means the sizes with no lines, buttons or footer`() {
        assertEquals(listOf(true, true, false, false), SizeClass.entries.map { it.compact })
    }

    @Test
    fun `an unreported size uses the widget default, not the smallest layout`() {
        assertEquals(SizeClass.MEDIUM, SizeClass.of(0f, 0f))
        assertEquals(SizeClass.LARGE, SizeClass.of(0f, 300f, fallback = SizeClass.LARGE))
        assertEquals(SizeClass.SMALL, SizeClass.of(-1f, 120f, fallback = SizeClass.SMALL))
    }

    @Test
    fun `breakpoints grow monotonically so each larger layout needs at least as much room`() {
        val sizes = SizeClass.entries
        for (i in 1 until sizes.size) {
            assertTrue(sizes[i].minWidthDp >= sizes[i - 1].minWidthDp)
            assertTrue(sizes[i].minHeightDp > sizes[i - 1].minHeightDp)
        }
        // Each breakpoint maps back onto its own class.
        for (s in sizes) assertEquals(s, SizeClass.of(s.minWidthDp, s.minHeightDp))
    }

    @Test
    fun `destinations round-trip and point at the dashboard tab order`() {
        for (d in Destination.entries) assertEquals(d, Destination.fromWire(d.wire))
        assertNull(Destination.fromWire(null))
        assertNull(Destination.fromWire("keys"), "no widget may open the key write path")
        assertNull(Destination.fromWire("STATUS"))
        val tabs = listOf("Status", "Hotspot", "Models", "Keys", "Ledger") // MainActivity order
        assertEquals("Status", tabs[Destination.STATUS.tabIndex!!])
        assertEquals("Hotspot", tabs[Destination.HOTSPOT.tabIndex!!])
        assertEquals("Models", tabs[Destination.MODELS.tabIndex!!])
        assertEquals("Ledger", tabs[Destination.LEDGER.tabIndex!!])
        assertNull(Destination.CONSENT.tabIndex)
    }

    @Test
    fun `daemon actions accept only their own names`() {
        assertEquals(DaemonAction.START, DaemonAction.fromWire("start"))
        assertEquals(DaemonAction.STOP, DaemonAction.fromWire("stop"))
        assertNull(DaemonAction.fromWire("restart"))
        assertNull(DaemonAction.fromWire(null))
        assertNull(DaemonAction.fromWire("START"))
    }

    @Test
    fun `request codes never collide across widgets and targets`() {
        val codes = mutableSetOf<Int>()
        for (id in 0..500) {
            assertTrue(codes.add(RequestCodes.open(id)))
            for (a in DaemonAction.entries) assertTrue(codes.add(RequestCodes.action(id, a)))
        }
        assertTrue(RequestCodes.DAY_BOUNDARY !in codes)
    }

    @Test
    fun `every widget is redrawn by some data change and by the clock`() {
        for (kind in WidgetKind.entries) {
            assertTrue(Source.entries.filter { it != Source.CLOCK }.any { kind in it.kinds }, "$kind has no data source")
            assertTrue(kind in Source.CLOCK.kinds)
        }
        assertEquals(setOf(WidgetKind.DOWNLOADS), Source.DOWNLOADS.kinds)
        assertEquals(setOf(WidgetKind.HOTSPOT), Source.PAIRINGS.kinds)
    }

    @Test
    fun `slots grow with size`() {
        assertEquals(listOf(0, 0, 2, 4), SizeClass.entries.map { Slots.statLines(it) })
        assertEquals(listOf(1, 1, 3, 5), SizeClass.entries.map { Slots.listRows(it) })
        assertEquals(listOf(0, 0, 1, 4), SizeClass.entries.map { Slots.progressLines(it) })
        assertEquals(listOf(false, false, false, true), SizeClass.entries.map { Slots.hasFooter(it) })
    }

    @Test
    fun `a failed observer retries after 2, 4, 8, 16, 32 seconds, then every minute`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0L..6L).map { Backoff.delayMs(it) })
        assertEquals(60_000L, Backoff.delayMs(Long.MAX_VALUE))
        assertEquals(2_000L, Backoff.delayMs(-1))
    }

    @Test
    fun `the one-line summary keeps the value's glyph, tone and unit, and the stamp`() {
        val stat = StatFace("T", "t1", Line("12", Glyph.CLOUD, Tone.VIOLET), "rows", listOf(Line("x")), "f", null, Destination.LEDGER, "d")
        assertEquals(Summary(Line("12 rows", Glyph.CLOUD, Tone.VIOLET), "t1"), stat.summary())
        assertEquals(Summary(Line("12", Glyph.CLOUD, Tone.VIOLET), null), stat.copy(unit = null, asOf = null).summary())
        val card = CardFace("T", "t2", Line("served", Glyph.CLOUD, Tone.VIOLET), emptyList(), null, Destination.LEDGER, "d")
        assertEquals(Summary(card.headline, "t2"), card.summary())
        val list = ListFace("T", "as of", listOf(Row(Line("a", Glyph.LOCAL, Tone.CYAN), "t3"), Row(Line("b"))), null, Destination.LEDGER, "d")
        assertEquals(Summary(Line("a", Glyph.LOCAL, Tone.CYAN), "t3"), list.summary(), "the newest row's own time")
        assertEquals(Summary(Line("T"), "as of"), list.copy(rows = emptyList()).summary())
        val progress = ProgressFace("T", null, Line("42%", Glyph.PENDING, Tone.VIOLET), "m", Progress(42), emptyList(), null, Destination.MODELS, "d")
        assertEquals(Summary(Line("42% m", Glyph.PENDING, Tone.VIOLET), null), progress.summary())
    }
}

class WidgetFormatTest {

    @Test
    fun `usd estimates are short plain decimals and never round a real cost down to zero`() {
        assertEquals("0", WidgetFormat.usdShort(0.0))
        assertEquals("<0.0001", WidgetFormat.usdShort(0.00000123))
        assertEquals("0.0042", WidgetFormat.usdShort(0.0041987))
        assertEquals("0.12", WidgetFormat.usdShort(0.12))
        assertEquals("1.23", WidgetFormat.usdShort(1.2345))
        assertEquals("12.00", WidgetFormat.usdShort(12.0))
        assertTrue('E' !in WidgetFormat.usdShort(1e-9))
    }

    @Test
    fun `bytes use the Models tab units`() {
        assertEquals("0 B", WidgetFormat.bytes(0, Locale.US))
        assertEquals("512.0 B", WidgetFormat.bytes(512, Locale.US))
        assertEquals("1.5 KB", WidgetFormat.bytes(1536, Locale.US))
        assertEquals("40.0 GB", WidgetFormat.bytes(40L * 1024 * 1024 * 1024, Locale.US))
    }

    @Test
    fun `percent clamps and refuses an unknown total`() {
        assertNull(WidgetFormat.percent(10, 0))
        assertNull(WidgetFormat.percent(10, -1))
        assertEquals(0, WidgetFormat.percent(-5, 100))
        assertEquals(42, WidgetFormat.percent(42, 100))
        assertEquals(99, WidgetFormat.percent(999, 1000))
        assertEquals(100, WidgetFormat.percent(150, 100))
    }

    @Test
    fun `today starts at local midnight and the next day may be 23 hours away`() {
        val zone = ZoneId.of("Europe/Berlin")
        val noon = LocalDateTime.of(2026, 3, 29, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val midnight = LocalDateTime.of(2026, 3, 29, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(midnight, WidgetFormat.startOfDay(noon, zone))
        val next = WidgetFormat.nextDayStart(noon, zone)
        assertEquals(LocalDateTime.of(2026, 3, 30, 0, 0).atZone(zone).toInstant().toEpochMilli(), next)
        assertEquals(23L * 60 * 60 * 1000, next - midnight) // DST spring-forward day
        assertEquals(midnight, WidgetFormat.startOfDay(midnight, zone))
    }

    @Test
    fun `stamps are the time today and date plus time before`() {
        val copy = FakeCopy()
        assertEquals("t${DAY_START + 5}", WidgetFormat.stamp(DAY_START + 5, DAY_START, copy))
        assertEquals("t$DAY_START", WidgetFormat.stamp(DAY_START, DAY_START, copy))
        assertEquals("dt${DAY_START - 1}", WidgetFormat.stamp(DAY_START - 1, DAY_START, copy))
    }

    @Test
    fun `spoken text drops glyphs and ends with where the tap goes`() {
        val spoken = WidgetFormat.spoken(listOf("● running", null, " ", "▲ cloud 3 · ■ download 1"), Destination.STATUS, FakeCopy())
        assertEquals("running. cloud 3 · download 1. OPENS_STATUS", spoken)
        for (g in Glyph.ALL) assertTrue(g !in spoken)
    }
}
