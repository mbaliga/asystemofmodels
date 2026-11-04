package xyz.mdhv.asom.widget

import dev.aarso.hyle.tokens.HyleTokens
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element
import xyz.mdhv.asom.ui.theme.HyleSeam
import xyz.mdhv.asom.widget.model.SizeClass
import xyz.mdhv.asom.widget.model.Slots
import xyz.mdhv.asom.widget.model.WidgetKind

/**
 * Structural guards over the widget resources: every layout has the slots the
 * renderer binds at its size, every provider is declared the way launchers
 * need, only the providers are exported, and the colours are Hyle's and
 * readable (§1.6). Pure JVM: the XML is parsed from `src/main`.
 */
class WidgetResourcesTest {

    private val main = File("src/main")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.attr(name: String): String = getAttributeNS(androidNs, name)

    private fun Element.descendants(): List<Element> = buildList {
        val nodes = getElementsByTagName("*")
        for (i in 0 until nodes.length) add(nodes.item(i) as Element)
    }

    private fun layout(name: String): Element = parse(File(main, "res/layout/$name.xml"))

    private fun ids(root: Element): Set<String> =
        (listOf(root) + root.descendants()).map { it.attr("id") }.filter { it.isNotEmpty() }.toSet()

    /** The per-template layouts; TINY shares `widget_tiny` across templates. */
    private val sizes = mapOf(SizeClass.SMALL to "small", SizeClass.MEDIUM to "medium", SizeClass.LARGE to "large")

    @Test
    fun `every template layout has exactly the slots its size binds`() {
        for ((size, suffix) in sizes) {
            val lines = { n: Int -> (1..n).map { "@+id/w_line$it" } }
            val footer = if (Slots.hasFooter(size)) listOf("@+id/w_footer") else emptyList()
            val common = listOf("@android:id/background", "@+id/w_title", "@+id/w_asof")
            val expected = mapOf(
                "stat" to common + listOf("@+id/w_value", "@+id/w_unit") + lines(Slots.statLines(size)) + footer +
                    (if (size == SizeClass.SMALL) emptyList() else listOf("@+id/w_action")),
                "card" to common + "@+id/w_headline" + lines(Slots.cardLines(size)) + footer,
                "list" to common + (1..Slots.listRows(size)).flatMap { listOf("@+id/w_row$it", "@+id/w_row${it}_lead", "@+id/w_row${it}_trailing") } + footer,
                "progress" to common + listOf("@+id/w_value", "@+id/w_unit", "@+id/w_bar") + lines(Slots.progressLines(size)) + footer,
            )
            for ((template, want) in expected) {
                assertEquals(want.toSet(), ids(layout("widget_${template}_$suffix")), "widget_${template}_$suffix")
            }
        }
        // TINY: one shared one-line layout for every template (WidgetRenderer binds Face.summary()).
        assertEquals(
            setOf("@android:id/background", "@+id/w_title", "@+id/w_value", "@+id/w_asof"),
            ids(layout("widget_tiny")),
        )
    }

    private val dimens: Map<String, String> by lazy {
        parse(File(main, "res/values/dimens.xml")).descendants().filter { it.tagName == "dimen" }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private val styles: Map<String, Element> by lazy {
        parse(File(main, "res/values/styles.xml")).descendants().filter { it.tagName == "style" }
            .associateBy { it.getAttribute("name") }
    }

    /** A style item, following explicit and dotted-name parents the way aapt does. */
    private fun styleItem(style: String?, item: String): String? {
        var name = style
        while (name != null) {
            val e = styles[name] ?: return null
            e.descendants().firstOrNull { it.tagName == "item" && it.getAttribute("name") == item }
                ?.let { return it.textContent.trim() }
            name = when {
                e.hasAttribute("parent") -> e.getAttribute("parent").removePrefix("@style/").ifEmpty { null }
                '.' in name -> name.substringBeforeLast('.')
                else -> null
            }
        }
        return null
    }

    private fun dp(value: String?): Double = when {
        value.isNullOrEmpty() -> 0.0
        value.startsWith("@dimen/") -> dp(dimens.getValue(value.removePrefix("@dimen/")))
        value.endsWith("dp") -> value.removeSuffix("dp").toDouble()
        else -> error("not a dp value: $value")
    }

    /**
     * The height a layout needs with one line in every text slot, every
     * optional slot shown, at [fontScale]. A text line is 1.2 x its sp size:
     * Roboto's ascent plus descent is 1.17em with includeFontPadding off, and
     * the rest is margin for OEM fonts.
     */
    private fun need(e: Element, fontScale: Double): Double {
        val style = e.getAttribute("style").takeIf { it.startsWith("@style/") }?.removePrefix("@style/")
        val padding = dp(e.attr("paddingTop")) + dp(e.attr("paddingBottom"))
        val content = when (e.tagName) {
            "LinearLayout" -> {
                val kids = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
                val heights = kids.map { need(it, fontScale) + dp(it.attr("layout_marginTop")) + dp(it.attr("layout_marginBottom")) }
                if (e.attr("orientation") == "vertical") heights.sum() else heights.maxOrNull() ?: 0.0
            }
            "TextView", "Button" -> {
                val sp = (e.attr("textSize").ifEmpty { null } ?: styleItem(style, "android:textSize")!!).removeSuffix("sp").toDouble()
                val minHeight = dp(e.attr("minHeight").ifEmpty { null } ?: styleItem(style, "android:minHeight"))
                maxOf(sp * fontScale * 1.2, minHeight)
            }
            "ProgressBar" -> dp(e.attr("layout_height"))
            else -> error("no height rule for ${e.tagName}")
        }
        return padding + content
    }

    @Test
    fun `every layout fits its size's breakpoint height at 1_3x font scale`() {
        val bySize = mapOf(SizeClass.TINY to listOf("widget_tiny")) +
            sizes.mapValues { (_, suffix) -> listOf("stat", "card", "list", "progress").map { "widget_${it}_$suffix" } }
        assertEquals(SizeClass.entries.toSet(), bySize.keys)
        for ((size, names) in bySize) {
            for (name in names) {
                val needed = need(layout(name), fontScale = 1.3)
                assertTrue(
                    needed <= size.minHeightDp,
                    "$name needs ${"%.1f".format(needed)}dp at 1.3x but $size starts at ${size.minHeightDp}dp",
                )
            }
        }
        // The next size up is never chosen for a box its layout cannot fill at 1.0x either.
        assertTrue(need(layout("widget_stat_medium"), 1.0) <= SizeClass.MEDIUM.minHeightDp)
    }

    @Test
    fun `layouts use only views RemoteViews can inflate`() {
        val allowed = setOf("LinearLayout", "FrameLayout", "TextView", "Button", "ProgressBar", "ImageView")
        val layouts = File(main, "res/layout").listFiles { f -> f.name.startsWith("widget_") }!!
        assertTrue(layouts.size >= 19)
        for (f in layouts) {
            val root = parse(f)
            for (e in listOf(root) + root.descendants()) assertTrue(e.tagName in allowed, "${f.name}: ${e.tagName}")
            assertEquals("@android:id/background", root.attr("id"), "${f.name} root id")
        }
    }

    @Test
    fun `text is at least 12sp, body text at least 14sp`() {
        val styles = parse(File(main, "res/values/styles.xml"))
        val items = styles.descendants().filter { it.tagName == "item" && it.getAttribute("name") == "android:textSize" }
        assertTrue(items.isNotEmpty())
        for (item in items) {
            val sp = item.textContent.trim().removeSuffix("sp").toFloat()
            assertTrue(sp >= 12f, "textSize ${item.textContent}")
        }
        val byStyle = items.associate { (it.parentNode as Element).getAttribute("name") to it.textContent.trim() }
        assertEquals("14sp", byStyle["AsomWidget.Line"])
        assertEquals("14sp", byStyle["AsomWidget.Button"])
    }

    @Test
    fun `providers are declared for any launcher, resizable, pushed not polled`() {
        val files = mapOf(
            WidgetKind.EGRESS_TODAY to "egress",
            WidgetKind.DAEMON to "daemon",
            WidgetKind.LAST_ROUTE to "last_route",
            WidgetKind.RECENT_ROUTES to "recent_routes",
            WidgetKind.HOTSPOT to "hotspot",
            WidgetKind.DOWNLOADS to "downloads",
        )
        assertEquals(WidgetKind.entries.toSet(), files.keys)
        for ((kind, name) in files) {
            val info = parse(File(main, "res/xml/widget_info_$name.xml"))
            assertEquals("appwidget-provider", info.tagName)
            assertEquals("0", info.attr("updatePeriodMillis"), name)
            assertEquals("horizontal|vertical", info.attr("resizeMode"), name)
            assertEquals("home_screen", info.attr("widgetCategory"), name)
            assertEquals("@layout/widget_loading", info.attr("initialLayout"), name)
            assertEquals("@layout/widget_preview_$name", info.attr("previewLayout"), name)
            assertEquals("@drawable/widget_preview_$name", info.attr("previewImage"), name)
            assertEquals("@string/widget_desc_$name", info.attr("description"), name)
            assertTrue(File(main, "res/layout/widget_preview_$name.xml").exists())
            assertTrue(File(main, "res/drawable/widget_preview_$name.xml").exists())
            for (a in listOf("minWidth", "minHeight", "minResizeWidth", "minResizeHeight", "targetCellWidth", "targetCellHeight")) {
                assertTrue(info.attr(a).isNotEmpty(), "$name lacks $a")
            }
            // One default size: the legacy minWidth/minHeight (70n - 30 dp per n cells)
            // and the API 31+ targetCell* place the same number of cells.
            val w = info.attr("minWidth").removeSuffix("dp").toFloat()
            val h = info.attr("minHeight").removeSuffix("dp").toFloat()
            val cellsW = info.attr("targetCellWidth").toInt()
            val cellsH = info.attr("targetCellHeight").toInt()
            assertEquals(cellsW, legacyCells(w), "$name: minWidth and targetCellWidth disagree")
            assertEquals(cellsH, legacyCells(h), "$name: minHeight and targetCellHeight disagree")
            // The pre-31 fallback layout is what those cells get in portrait (Android's cell table).
            assertEquals(kind.defaultSize, SizeClass.of(portraitWidthDp.getValue(cellsW), portraitHeightDp.getValue(cellsH)), "$name default size")
            // Resizable down to the one-line layout.
            assertEquals(SizeClass.TINY, SizeClass.of(info.attr("minResizeWidth").removeSuffix("dp").toFloat(), info.attr("minResizeHeight").removeSuffix("dp").toFloat()))
        }
    }

    private fun legacyCells(dp: Float): Int = ceil((dp + 30f) / 70f).toInt()

    /** Android's widget sizing table, portrait, in dp. */
    private val portraitWidthDp = mapOf(1 to 57f, 2 to 130f, 3 to 203f, 4 to 276f, 5 to 349f)
    private val portraitHeightDp = mapOf(1 to 102f, 2 to 220f, 3 to 337f, 4 to 455f)

    @Test
    fun `only the providers are exported, and each is wired to its info file`() {
        val manifest = parse(File(main, "AndroidManifest.xml"))
        val receivers = manifest.descendants().filter { it.tagName == "receiver" }
        val providers = receivers.filter { r ->
            r.descendants().any { it.tagName == "action" && it.attr("name") == "android.appwidget.action.APPWIDGET_UPDATE" }
        }
        assertEquals(6, providers.size, "six home-screen widgets")
        for (p in providers) {
            assertEquals("true", p.attr("exported"), p.attr("name"))
            assertTrue(p.attr("label").startsWith("@string/widget_label_"))
            val meta = p.descendants().single { it.tagName == "meta-data" }
            assertEquals("android.appwidget.provider", meta.attr("name"))
            assertTrue(meta.attr("resource").startsWith("@xml/widget_info_"))
            val actions = p.descendants().filter { it.tagName == "action" }.map { it.attr("name") }.toSet()
            assertTrue("android.intent.action.TIME_SET" in actions && "android.intent.action.TIMEZONE_CHANGED" in actions)
        }
        val classes = providers.map { it.attr("name").substringAfterLast('.') }.toSet()
        assertEquals(WidgetKind.entries.map { WidgetRefresh.providerFor(it).simpleName }.toSet(), classes)
        val tick = receivers.single { it.attr("name") == ".widget.WidgetTickReceiver" }
        assertEquals("false", tick.attr("exported"))
        val action = manifest.descendants().single { it.tagName == "activity" && it.attr("name") == ".widget.WidgetActionActivity" }
        assertEquals("false", action.attr("exported"))
    }

    private fun colors(dir: String): Map<String, Long> {
        val root = parse(File(main, "res/$dir/colors.xml"))
        return root.descendants().filter { it.tagName == "color" }.associate { e ->
            val hex = e.textContent.trim().removePrefix("#")
            e.getAttribute("name") to (if (hex.length == 6) "FF$hex" else hex).toLong(16)
        }
    }

    @Test
    fun `widget colours are Hyle tokens, identical day and night, never red or green`() {
        val day = colors("values")
        assertEquals(day, colors("values-night"), "asom is dark-only by design")
        assertEquals(HyleSeam.Violet, day["widget_violet"])
        assertEquals(HyleSeam.Cyan, day["widget_cyan"])
        // The same seam roles the dashboard draws with (§1.7), so a rebind of the seam fails here.
        assertEquals(HyleSeam.Surface, day["widget_surface"])
        assertEquals(HyleSeam.OnSurface, day["widget_ink"])
        assertEquals(HyleTokens.Color.colorPaletteMaterialStone, day["widget_ink_dim"])
        assertEquals(HyleTokens.Color.colorBorderStrong, day["widget_hairline"])
        for ((name, value) in day) assertTrue(value !in HyleSeam.ForbiddenRedGreen, "$name is a banned red/green hue")
    }

    @Test
    fun `every text colour reads at 4_5 to 1 or better on the widget surface`() {
        val c = colors("values")
        val surface = c.getValue("widget_surface")
        for (name in listOf("widget_ink", "widget_ink_dim", "widget_violet", "widget_cyan")) {
            val ratio = contrast(c.getValue(name), surface)
            assertTrue(ratio >= 4.5, "$name contrast ${"%.2f".format(ratio)}")
        }
    }

    /** WCAG 2 contrast of [fg] (alpha-blended onto [bg]) against opaque [bg]. */
    private fun contrast(fg: Long, bg: Long): Double {
        val a = ((fg shr 24) and 0xFF) / 255.0
        fun ch(v: Long, shift: Int) = ((v shr shift) and 0xFF).toDouble()
        val blended = listOf(16, 8, 0).map { s -> ch(fg, s) * a + ch(bg, s) * (1 - a) }
        val base = listOf(16, 8, 0).map { s -> ch(bg, s) }
        val l1 = luminance(blended)
        val l2 = luminance(base)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun luminance(rgb: List<Double>): Double {
        val lin = rgb.map { v ->
            val s = v / 255.0
            if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    }
}
