package xyz.mdhv.asom.widget.model

import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord

/** How a routed request ended, as far as egress is concerned. */
enum class RouteOutcome {
    /** Cloud, a provider answered 2xx. */
    SERVED,

    /** Cloud, the body reached a provider that did not answer 2xx (a failover attempt or a relayed error). */
    SENT_NOT_SERVED,

    /** Cloud, but the row names no provider (the request-level row after every candidate failed). */
    CLOUD_NOT_SERVED,

    /** Local: in v1 nothing was served and nothing left the device. Never "on-device". */
    LOCAL_NOT_SERVED,
}

/**
 * One ledger row read the way the API answered it. The served-by, egress and
 * cost texts are taken from [RouteRecord.toEchoHeaders], the same function
 * that wrote the response headers (invariant §1.9), so the widget cannot
 * disagree with the API or the dashboard.
 */
class RouteView(val record: RouteRecord) {
    private val headers = record.toEchoHeaders()
    val servedBy: String? = headers[AsomHeaders.SERVED_BY]
    val egressWire: String = headers.getValue(AsomHeaders.EGRESS)
    val costEst: String? = headers[AsomHeaders.COST_EST]
    val costBasis: String? = headers[AsomHeaders.COST_BASIS]

    val outcome: RouteOutcome = when {
        record.egress != Egress.CLOUD -> RouteOutcome.LOCAL_NOT_SERVED
        servedBy != null && record.status in 200..299 -> RouteOutcome.SERVED
        servedBy != null -> RouteOutcome.SENT_NOT_SERVED
        else -> RouteOutcome.CLOUD_NOT_SERVED
    }

    companion object {
        /** Routes are request rows; catalogue and download rows are not. */
        fun isRoute(record: RouteRecord): Boolean = record.egress == Egress.CLOUD || record.egress == Egress.LOCAL
    }
}

/** Shared by both route widgets: one line per route, glyph and word first. */
object RouteLines {
    fun row(route: RouteView, copy: WidgetCopy): Line {
        val status = route.record.status.toString()
        return when (route.outcome) {
            RouteOutcome.SERVED -> Line(route.servedBy!!, Glyph.CLOUD, Tone.VIOLET)
            RouteOutcome.SENT_NOT_SERVED -> Line(copy.text(Txt.ROW_SENT, route.servedBy!!, status), Glyph.CLOUD, Tone.VIOLET)
            RouteOutcome.CLOUD_NOT_SERVED -> Line(copy.text(Txt.ROW_CLOUD_NOT_SERVED, status), Glyph.CLOUD, Tone.VIOLET)
            RouteOutcome.LOCAL_NOT_SERVED -> Line(copy.text(Txt.ROW_LOCAL, status), Glyph.LOCAL, Tone.CYAN)
        }
    }
}

/**
 * `card` template: the newest route. The caller package is withheld (a home
 * screen is public, and which app asked is the user's business); everything
 * shown is ledger metadata, never a prompt, a reply, a key or a token.
 */
object LastRouteWidget {

    fun present(latest: RouteRecord?, dayStartMs: Long, size: SizeClass, copy: WidgetCopy): CardFace {
        val title = copy.text(Txt.LAST_ROUTE_TITLE)
        val footer = if (Slots.hasFooter(size)) copy.text(Txt.ROUTES_FOOTER) else null
        if (latest == null) {
            val headline = Line(copy.text(Txt.ROUTES_EMPTY), Glyph.INACTIVE, Tone.NEUTRAL)
            val lines = listOf(Line(copy.text(Txt.ROUTES_HINT))).take(Slots.cardLines(size))
            return CardFace(
                title = title,
                asOf = null,
                headline = headline,
                lines = lines,
                footer = footer,
                open = Destination.LEDGER,
                description = WidgetFormat.spoken(listOf(title, headline.display) + lines.map { it.text }, Destination.LEDGER, copy),
            )
        }
        val route = RouteView(latest)
        val r = route.record
        val at = WidgetFormat.stamp(r.ts, dayStartMs, copy)

        val headline = if (size.compact) {
            RouteLines.row(route, copy)
        } else if (route.outcome == RouteOutcome.LOCAL_NOT_SERVED) {
            Line(copy.text(Txt.ROUTE_LOCAL), Glyph.LOCAL, Tone.CYAN)
        } else {
            Line(copy.text(Txt.ROUTE_CLOUD), Glyph.CLOUD, Tone.VIOLET)
        }
        val all = buildList {
            add(
                Line(
                    when (route.outcome) {
                        RouteOutcome.SERVED -> copy.text(Txt.ROUTE_SERVED_BY, route.servedBy!!)
                        RouteOutcome.SENT_NOT_SERVED -> copy.text(Txt.ROUTE_SENT_TO, route.servedBy!!)
                        RouteOutcome.CLOUD_NOT_SERVED, RouteOutcome.LOCAL_NOT_SERVED -> copy.text(Txt.ROUTE_NOT_SERVED)
                    },
                ),
            )
            add(Line(copy.text(Txt.ROUTE_STATUS, r.status.toString(), at)))
            if (route.costEst != null && route.costBasis != null) {
                add(Line(copy.text(Txt.ROUTE_COST, route.costEst, route.costBasis)))
            } else if (r.egress == Egress.CLOUD) {
                add(Line(copy.text(Txt.NO_COST_BASIS)))
            }
            if (r.egress == Egress.CLOUD) {
                add(
                    Line(
                        copy.text(
                            Txt.ROUTE_DETAIL,
                            r.tokensIn?.let { copy.number(it) } ?: "?",
                            r.tokensOut?.let { copy.number(it) } ?: "?",
                            copy.number(r.latencyMs),
                            WidgetFormat.bytes(r.bytesOut),
                        ),
                    ),
                )
            }
            // Last: a cloud card fills its four lines before this; a local row
            // (no cost, no traffic) shows what was asked for instead.
            if (r.requestedModel.isNotBlank()) add(Line(copy.text(Txt.ROUTE_ASKED, r.requestedModel)))
        }
        val lines = all.take(Slots.cardLines(size))
        return CardFace(
            title = title,
            asOf = at,
            headline = headline,
            lines = lines,
            footer = footer,
            open = Destination.LEDGER,
            description = WidgetFormat.spoken(
                listOf(title, at, headline.display) + lines.map { it.display },
                Destination.LEDGER,
                copy,
            ),
        )
    }
}

/** `list` template: the newest routes, one, three or five by size. */
object RecentRoutesWidget {

    /** How many rows the data layer needs to read for the largest layout. */
    const val MAX_ROWS = 5

    fun present(newestFirst: List<RouteRecord>, dayStartMs: Long, size: SizeClass, nowMs: Long, copy: WidgetCopy): ListFace {
        val title = copy.text(Txt.RECENT_TITLE)
        val routes = newestFirst.filter { RouteView.isRoute(it) }.sortedByDescending { it.ts }
        val capacity = Slots.listRows(size)
        val rows = if (routes.isEmpty()) {
            listOf(
                Row(Line(copy.text(Txt.ROUTES_EMPTY), Glyph.INACTIVE, Tone.NEUTRAL)),
                Row(Line(copy.text(Txt.ROUTES_HINT))),
            ).take(capacity)
        } else {
            routes.take(capacity).map { record ->
                Row(RouteLines.row(RouteView(record), copy), WidgetFormat.stamp(record.ts, dayStartMs, copy))
            }
        }
        // The rows carry their own times; the stamp says when the list itself
        // was read, so missing newer rows on a stale widget are detectable.
        val asOf = if (size.compact) null else copy.text(Txt.AS_OF, copy.time(nowMs))
        val footer = if (Slots.hasFooter(size)) copy.text(Txt.ROUTES_FOOTER) else null
        return ListFace(
            title = title,
            asOf = asOf,
            rows = rows,
            footer = footer,
            open = Destination.LEDGER,
            description = WidgetFormat.spoken(
                listOf(title, asOf) + rows.map { row -> listOfNotNull(row.lead.display, row.trailing).joinToString(" ") },
                Destination.LEDGER,
                copy,
            ),
        )
    }
}
