package xyz.mdhv.asom.widget.model

/*
 * The home-screen widgets' pure model. Nothing in this package imports
 * android.*, so the mapping from asom's data to what each widget shows is a
 * plain JVM unit test (app/src/test). The RemoteViews side lives one package
 * up and only binds the faces built here.
 */

/**
 * The four layouts every widget has. The same dp breakpoints key the API 31+
 * `RemoteViews(Map<SizeF, RemoteViews>)` and the pre-31 choice made from
 * `AppWidgetManager.getAppWidgetOptions`, so both paths pick the same layout.
 *
 * Each breakpoint is the height at which that size's layout still fits at a
 * 1.3x font scale with one line per text slot (`WidgetResourcesTest` sums the
 * layouts' slot heights against these numbers). On Android's own cell table a
 * 1-row cell is about 102dp tall in portrait and 51dp in landscape, a 2-row
 * cell about 220dp and 117dp, so in portrait a 1-row widget is SMALL and a
 * 2-row one LARGE. A 2-row landscape cell (117dp) stays SMALL; MEDIUM is what
 * a 3-row landscape cell and the Orrery hub's 120dp preset get.
 */
enum class SizeClass(val minWidthDp: Float, val minHeightDp: Float) {
    /** Below the small layout's height (a 1-row landscape cell): one line, the value and its stamp. */
    TINY(110f, 40f),

    /** About 2x1 to 4x1 in portrait: one value with its label. */
    SMALL(110f, 72f),

    /** A 3-row landscape cell or the hub's short preset: the value plus two lines, and the daemon's button. */
    MEDIUM(180f, 120f),

    /** About 3x2 and up in portrait: everything, plus a footer. */
    LARGE(180f, 200f),
    ;

    /** TINY and SMALL: no lines, no button, no footer; only what a one-row widget can hold. */
    val compact: Boolean get() = this == TINY || this == SMALL

    companion object {
        /**
         * Picks the layout for a widget of [widthDp] x [heightDp]. A host that has
         * not reported a size yet (zero or negative) gets [fallback], the
         * widget's default size, rather than the smallest layout.
         */
        fun of(widthDp: Float, heightDp: Float, fallback: SizeClass = MEDIUM): SizeClass = when {
            widthDp <= 0f || heightDp <= 0f -> fallback
            widthDp >= LARGE.minWidthDp && heightDp >= LARGE.minHeightDp -> LARGE
            widthDp >= MEDIUM.minWidthDp && heightDp >= MEDIUM.minHeightDp -> MEDIUM
            widthDp >= SMALL.minWidthDp && heightDp >= SMALL.minHeightDp -> SMALL
            else -> TINY
        }
    }
}

/**
 * Meaning hue (invariant §1.6): violet = attention / something left / needs
 * you, cyan = active / healthy / stayed here. Red and green never carry
 * meaning, and a toned line always carries a [Glyph] and a word as well.
 */
enum class Tone { VIOLET, CYAN, NEUTRAL }

/** The dashboard's own state shapes, reused so the widget reads the same. */
object Glyph {
    const val ACTIVE = "●"
    const val INACTIVE = "○"
    const val PENDING = "◐"
    const val VERIFYING = "◑"
    const val FAILED = "✕"
    const val CLOUD = "▲"
    const val LOCAL = "■"
    const val WARNING = "⚠"
    const val VERBOSE = "⦿"

    val ALL: Set<String> = setOf(ACTIVE, INACTIVE, PENDING, VERIFYING, FAILED, CLOUD, LOCAL, WARNING, VERBOSE)
}

/**
 * Where a widget tap lands. [tabIndex] is the dashboard tab in MainActivity's
 * order (Status, Hotspot, Models, Keys, Ledger). Keys is deliberately not a
 * destination: no widget has a reason to open the key write path.
 */
enum class Destination(val wire: String, val tabIndex: Int?) {
    STATUS("status", 0),
    HOTSPOT("hotspot", 1),
    MODELS("models", 2),
    LEDGER("ledger", 4),

    /** The consent sheet (PairingActivity), which reads only verified pending rows. */
    CONSENT("consent", null),
    ;

    companion object {
        /** Unknown or missing values open nothing special (the dashboard's first tab). */
        fun fromWire(value: String?): Destination? = entries.firstOrNull { it.wire == value }
    }
}

/** The daemon widget's one state-changing control. */
enum class DaemonAction(val wire: String) {
    START("start"),
    STOP("stop"),
    ;

    companion object {
        fun fromWire(value: String?): DaemonAction? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * PendingIntent request codes: unique per widget id and per target, so two
 * widgets (or one widget's open tap and its button) never overwrite each
 * other's intent under FLAG_UPDATE_CURRENT.
 */
object RequestCodes {
    private const val SLOTS = 4

    fun open(widgetId: Int): Int = widgetId * SLOTS

    fun action(widgetId: Int, action: DaemonAction): Int = widgetId * SLOTS + 1 + action.ordinal

    /** The midnight redraw alarm. Negative so it can never equal a widget's code. */
    const val DAY_BOUNDARY: Int = -1
}

/**
 * The six widgets. [defaultSize] is the layout a widget placed at its default
 * cells gets in portrait (Android's cell table: 2x1 is about 130x102dp, 3x2
 * about 203x220dp, 4x2 about 276x220dp). It is used only when a pre-31 host
 * has not reported a size yet.
 */
enum class WidgetKind(val defaultSize: SizeClass) {
    EGRESS_TODAY(SizeClass.LARGE),
    DAEMON(SizeClass.LARGE),
    LAST_ROUTE(SizeClass.LARGE),
    RECENT_ROUTES(SizeClass.LARGE),
    HOTSPOT(SizeClass.SMALL),
    DOWNLOADS(SizeClass.LARGE),
}

/** How long a failed data observer waits before it subscribes again. */
object Backoff {
    /** 2 s, 4 s, 8 s, 16 s, 32 s, then a minute for every later attempt. */
    fun delayMs(attempt: Long): Long = minOf(60_000L, 2_000L shl attempt.coerceIn(0, 5).toInt())
}

/** What changed, so a refresh redraws only the widgets that show it. */
enum class Source {
    /** A `route_log` row was written. */
    LEDGER,

    /** A ledger write failed (the ledger is incomplete). */
    LEDGER_HEALTH,

    /** The daemon started, stopped, changed activity or toggled verbose mode. */
    DAEMON,

    /** A pairing row changed (request, approval, denial, revocation, removal). */
    PAIRINGS,

    /** A model download's state or progress changed. */
    DOWNLOADS,

    /** Midnight passed, the clock or time zone changed, or the locale changed. */
    CLOCK,
    ;

    val kinds: Set<WidgetKind>
        get() = when (this) {
            LEDGER -> setOf(WidgetKind.EGRESS_TODAY, WidgetKind.LAST_ROUTE, WidgetKind.RECENT_ROUTES)
            LEDGER_HEALTH -> setOf(WidgetKind.EGRESS_TODAY, WidgetKind.DAEMON)
            DAEMON -> setOf(WidgetKind.DAEMON)
            PAIRINGS -> setOf(WidgetKind.HOTSPOT)
            DOWNLOADS -> setOf(WidgetKind.DOWNLOADS)
            CLOCK -> WidgetKind.entries.toSet()
        }
}

/**
 * One line of widget text. [glyph] and [tone] travel together: the renderer
 * prints the glyph before the words and colours both, so a colour is never the
 * only thing that says what a line means.
 */
data class Line(
    val text: String,
    val glyph: String? = null,
    val tone: Tone = Tone.NEUTRAL,
) {
    val display: String get() = if (glyph == null) text else "$glyph $text"
}

/** One list row: the line and a short trailing value (a time). */
data class Row(val lead: Line, val trailing: String? = null)

/** A progress bar value; a null [percent] draws an indeterminate bar. */
data class Progress(val percent: Int?)

/** A button on the face (the daemon's Start or Stop). */
data class Action(val kind: DaemonAction, val label: String, val description: String)

/** What one widget shows at one size. Every face opens [open] when tapped. */
sealed interface Face {
    val title: String
    val asOf: String?
    val open: Destination

    /** TalkBack text for the whole widget: words only, no glyphs. */
    val description: String
}

/** `stat`: one value with a label, plus lines as space allows. */
data class StatFace(
    override val title: String,
    override val asOf: String?,
    val value: Line,
    val unit: String?,
    val lines: List<Line>,
    val footer: String?,
    val action: Action?,
    override val open: Destination,
    override val description: String,
) : Face

/** `card`: one item read at a glance. */
data class CardFace(
    override val title: String,
    override val asOf: String?,
    val headline: Line,
    val lines: List<Line>,
    val footer: String?,
    override val open: Destination,
    override val description: String,
) : Face

/** `list`: up to five rows. */
data class ListFace(
    override val title: String,
    override val asOf: String?,
    val rows: List<Row>,
    val footer: String?,
    override val open: Destination,
    override val description: String,
) : Face

/** `progress`: a value against a total. */
data class ProgressFace(
    override val title: String,
    override val asOf: String?,
    val value: Line,
    val unit: String?,
    val progress: Progress?,
    val lines: List<Line>,
    val footer: String?,
    override val open: Destination,
    override val description: String,
) : Face

/**
 * The one line a TINY widget shows: the face's value (with its unit) or
 * headline, glyph and tone included, and the stamp that keeps it honest.
 */
data class Summary(val line: Line, val stamp: String?)

fun Face.summary(): Summary = when (this) {
    is StatFace -> Summary(withUnit(value, unit), asOf)
    is CardFace -> Summary(headline, asOf)
    is ListFace -> rows.firstOrNull().let { row ->
        Summary(row?.lead ?: Line(title), row?.trailing ?: asOf)
    }
    is ProgressFace -> Summary(withUnit(value, unit), asOf)
}

private fun withUnit(value: Line, unit: String?): Line =
    if (unit.isNullOrEmpty()) value else value.copy(text = "${value.text} $unit")

/** How many lines (or rows) each template shows at each size. */
object Slots {
    fun statLines(size: SizeClass): Int = when (size) {
        SizeClass.TINY, SizeClass.SMALL -> 0
        SizeClass.MEDIUM -> 2
        SizeClass.LARGE -> 4
    }

    fun cardLines(size: SizeClass): Int = statLines(size)

    fun listRows(size: SizeClass): Int = when (size) {
        SizeClass.TINY, SizeClass.SMALL -> 1
        SizeClass.MEDIUM -> 3
        SizeClass.LARGE -> 5
    }

    fun progressLines(size: SizeClass): Int = when (size) {
        SizeClass.TINY, SizeClass.SMALL -> 0
        SizeClass.MEDIUM -> 1
        SizeClass.LARGE -> 4
    }

    /** Footers need the room only the large layout has. */
    fun hasFooter(size: SizeClass): Boolean = size == SizeClass.LARGE
}
