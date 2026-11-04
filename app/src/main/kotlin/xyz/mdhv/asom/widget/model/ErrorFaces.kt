package xyz.mdhv.asom.widget.model

/**
 * The error state: asom's data could not be read (a Room or file failure).
 * Drawn in the widget's own template so it keeps its title and its tap, and it
 * says so in words rather than showing an empty or zero value.
 */
object ErrorFaces {

    fun titleKey(kind: WidgetKind): Txt = when (kind) {
        WidgetKind.EGRESS_TODAY -> Txt.EGRESS_TITLE
        WidgetKind.DAEMON -> Txt.DAEMON_TITLE
        WidgetKind.LAST_ROUTE -> Txt.LAST_ROUTE_TITLE
        WidgetKind.RECENT_ROUTES -> Txt.RECENT_TITLE
        WidgetKind.HOTSPOT -> Txt.HOTSPOT_TITLE
        WidgetKind.DOWNLOADS -> Txt.DL_TITLE
    }

    fun home(kind: WidgetKind): Destination = when (kind) {
        WidgetKind.EGRESS_TODAY, WidgetKind.LAST_ROUTE, WidgetKind.RECENT_ROUTES -> Destination.LEDGER
        WidgetKind.DAEMON -> Destination.STATUS
        WidgetKind.HOTSPOT -> Destination.HOTSPOT
        WidgetKind.DOWNLOADS -> Destination.MODELS
    }

    fun of(kind: WidgetKind, nowMs: Long, copy: WidgetCopy): Face {
        val title = copy.text(titleKey(kind))
        val open = home(kind)
        val line = Line(copy.text(Txt.ERROR), Glyph.FAILED, Tone.VIOLET)
        val asOf = copy.text(Txt.AS_OF, copy.time(nowMs))
        val description = WidgetFormat.spoken(listOf(title, line.display, asOf), open, copy)
        return when (kind) {
            WidgetKind.EGRESS_TODAY, WidgetKind.DAEMON, WidgetKind.HOTSPOT ->
                StatFace(title, asOf, line, null, emptyList(), null, null, open, description)
            WidgetKind.LAST_ROUTE -> CardFace(title, asOf, line, emptyList(), null, open, description)
            WidgetKind.RECENT_ROUTES -> ListFace(title, asOf, listOf(Row(line)), null, open, description)
            WidgetKind.DOWNLOADS -> ProgressFace(title, asOf, line, null, null, emptyList(), null, open, description)
        }
    }
}
