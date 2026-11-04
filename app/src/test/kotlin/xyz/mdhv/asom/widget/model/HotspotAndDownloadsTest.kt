package xyz.mdhv.asom.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.PairingStatusCode

class HotspotWidgetTest {

    private val copy = FakeCopy()

    private val rows = listOf(
        PairingRow(PairingStatusCode.PAIRED, DAY_START + 10),
        PairingRow(PairingStatusCode.PAIRED, DAY_START + 30),
        PairingRow(PairingStatusCode.PENDING, null),
        PairingRow(PairingStatusCode.REVOKED, DAY_START - 99),
        PairingRow(PairingStatusCode.NOT_PAIRED, null),
    )

    @Test
    fun `counts by status and the newest approval of a paired app`() {
        val counts = HotspotCounts.of(rows)
        assertEquals(HotspotCounts(paired = 2, pending = 1, revoked = 1, lastApprovedAt = DAY_START + 30), counts)
    }

    @Test
    fun `a waiting request opens the consent sheet and leads the small widget`() {
        val counts = HotspotCounts.of(rows)
        val small = HotspotWidget.present(counts, DAY_START, SizeClass.SMALL, NOW, copy)
        assertEquals(Line("PENDING_SHORT[1]", Glyph.PENDING, Tone.VIOLET), small.value)
        assertNull(small.unit)
        assertEquals(Destination.CONSENT, small.open)
        val large = HotspotWidget.present(counts, DAY_START, SizeClass.LARGE, NOW, copy)
        assertEquals(Line("2", Glyph.ACTIVE, Tone.CYAN), large.value)
        assertEquals("UNIT_APPS_PAIRED[2]", large.unit)
        assertEquals(
            listOf(
                Line("PENDING[1]", Glyph.PENDING, Tone.VIOLET),
                Line("REVOKED[1]", Glyph.FAILED, Tone.VIOLET),
                Line("HOTSPOT_LAST_APPROVED(t${DAY_START + 30})"),
            ),
            large.lines,
        )
        assertEquals("HOTSPOT_WITHHELD", large.footer)
    }

    @Test
    fun `with nothing waiting the tap opens the Hotspot tab`() {
        val counts = HotspotCounts.of(listOf(PairingRow(PairingStatusCode.PAIRED, DAY_START)))
        val face = HotspotWidget.present(counts, DAY_START, SizeClass.SMALL, NOW, copy)
        assertEquals(Destination.HOTSPOT, face.open)
        assertEquals(Line("1", Glyph.ACTIVE, Tone.CYAN), face.value)
    }

    @Test
    fun `no pairings at all is the app's own empty sentence`() {
        val face = HotspotWidget.present(HotspotCounts.of(emptyList()), DAY_START, SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("0", Glyph.INACTIVE, Tone.NEUTRAL), face.value)
        assertEquals(listOf(Line("HOTSPOT_EMPTY")), face.lines)
    }
}

class DownloadsWidgetTest {

    private val copy = FakeCopy()
    private val gb = 1024L * 1024 * 1024

    private fun row(id: String, state: ModelState, done: Long = 0, total: Long = 0, at: Long = 0) =
        ModelRow(id, state, done, total, at)

    @Test
    fun `stored names parse like the Models tab, unknown reads as not downloaded`() {
        assertEquals(ModelState.DOWNLOADING, ModelState.parse("DOWNLOADING"))
        assertEquals(ModelState.NOT_DOWNLOADED, ModelState.parse("PAUSED"))
        assertEquals(ModelState.NOT_DOWNLOADED, ModelState.parse(null))
    }

    @Test
    fun `summary keeps catalogue models only and orders active work newest first`() {
        val summary = DownloadsSummary.of(
            listOf("a", "b", "c", "d"),
            listOf(
                row("a", ModelState.DOWNLOADING, at = 5),
                row("b", ModelState.VERIFYING, at = 9),
                row("c", ModelState.DOWNLOADED),
                row("d", ModelState.FAILED),
                row("gone-from-catalogue", ModelState.DOWNLOADED),
            ),
            bytesOnDisk = 3 * gb,
        )
        assertEquals(listOf("b", "a"), summary.active.map { it.modelId })
        assertEquals(1, summary.stored)
        assertEquals(1, summary.failed)
        assertEquals(4, summary.catalogueModels)
    }

    @Test
    fun `an active download shows its percent, model and bytes`() {
        val summary = DownloadsSummary.of(
            listOf("llama-3.3-70b", "qwen"),
            listOf(row("llama-3.3-70b", ModelState.DOWNLOADING, done = 42 * gb, total = 100 * gb, at = 1), row("qwen", ModelState.DOWNLOADED)),
            bytesOnDisk = 50 * gb,
        )
        val small = DownloadsWidget.present(summary, SizeClass.SMALL, NOW, copy)
        assertEquals(Line("DL_PERCENT(42)", Glyph.PENDING, Tone.VIOLET), small.value)
        assertEquals("llama-3.3-70b", small.unit)
        assertEquals(Progress(42), small.progress)
        assertTrue(small.lines.isEmpty())
        val medium = DownloadsWidget.present(summary, SizeClass.MEDIUM, NOW, copy)
        assertEquals(1, medium.lines.size)
        assertTrue(medium.lines[0].text.startsWith("DL_DETAIL("))
        val large = DownloadsWidget.present(summary, SizeClass.LARGE, NOW, copy)
        assertEquals(Line("STORED[1]", Glyph.ACTIVE, Tone.CYAN), large.lines[1])
        assertTrue(large.lines[2].text.startsWith("DL_ON_DISK("))
        assertEquals(Destination.MODELS, large.open)
    }

    @Test
    fun `verifying and unknown totals draw an indeterminate bar`() {
        val verifying = DownloadsSummary.of(listOf("m"), listOf(row("m", ModelState.VERIFYING, done = 10, total = 10)), 0)
        val v = DownloadsWidget.present(verifying, SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("DL_VERIFYING", Glyph.VERIFYING, Tone.VIOLET), v.value)
        assertEquals(Progress(null), v.progress)
        val unknown = DownloadsSummary.of(listOf("m"), listOf(row("m", ModelState.DOWNLOADING, done = 2048, total = 0)), 0)
        val u = DownloadsWidget.present(unknown, SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("DL_DOWNLOADING", Glyph.PENDING, Tone.VIOLET), u.value)
        assertEquals(Progress(null), u.progress)
        assertTrue(u.lines[0].text.startsWith("DL_SO_FAR("))
    }

    @Test
    fun `idle shows what is stored and hides the bar`() {
        val summary = DownloadsSummary.of(listOf("a", "b"), listOf(row("a", ModelState.DOWNLOADED), row("b", ModelState.FAILED)), gb)
        val face = DownloadsWidget.present(summary, SizeClass.LARGE, NOW, copy)
        assertEquals(Line("1", Glyph.ACTIVE, Tone.CYAN), face.value)
        assertEquals("UNIT_MODELS_STORED[1]", face.unit)
        assertNull(face.progress)
        assertEquals(Line("DL_FAILED[1]", Glyph.FAILED, Tone.VIOLET), face.lines[0])
    }

    @Test
    fun `nothing downloaded and an empty catalogue are different honest states`() {
        val nothing = DownloadsWidget.present(DownloadsSummary.of(listOf("a"), emptyList(), 0), SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("0", Glyph.INACTIVE, Tone.NEUTRAL), nothing.value)
        assertEquals(listOf(Line("DL_NOTHING_YET")), nothing.lines)
        val none = DownloadsWidget.present(DownloadsSummary.of(emptyList(), emptyList(), 0), SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("DL_NONE", Glyph.INACTIVE, Tone.NEUTRAL), none.value)
        assertEquals(listOf(Line("DL_NO_CATALOGUE")), none.lines)
    }
}
