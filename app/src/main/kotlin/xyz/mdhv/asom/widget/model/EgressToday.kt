package xyz.mdhv.asom.widget.model

import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord

/**
 * Today's ledger, summed for the "Left the device today" widget.
 *
 * "Left the device" counts the ledger rows whose egress is `cloud`,
 * `catalogue` or `download`, the same field the Ledger tab shows. Most are one
 * network event each (brief §1.3): every provider attempt, catalogue fetch and
 * download has its own row. One kind is not: when every candidate failed, the
 * request's terminal row is marked `cloud` (the body did leave) but names no
 * provider and carries no bytes, because the bytes are on the attempt rows. So
 * a request that tried two providers and failed is three rows here, matching
 * the ledger rather than the count of network events.
 *
 * A `local` row is the opposite: in v1 there is no local engine, so a local
 * row means nothing was served and nothing left; it is counted apart and never
 * called on-device.
 *
 * Cost follows §5.4's rule: a row contributes only when it has an estimate AND
 * a basis, exactly the rows whose echo headers carried `X-Asom-Cost-Est`.
 * With no such row the cost is null, never zero, and when only some cloud rows
 * have one the cost sentence says how many.
 */
data class EgressDay(
    val dayStartMs: Long,
    val cloudRows: Int,
    val catalogueRows: Int,
    val downloadRows: Int,
    val localRows: Int,
    val pricedRows: Int,
    val costUsd: Double?,
    val bytesOut: Long,
    val tokensIn: Long,
    val tokensOut: Long,
) {
    val leftRows: Int get() = cloudRows + catalogueRows + downloadRows

    companion object {
        fun of(rows: List<RouteRecord>, dayStartMs: Long): EgressDay {
            val today = rows.filter { it.ts >= dayStartMs }
            val priced = today.filter { it.costEst != null && it.costBasis != CostBasis.NONE }
            return EgressDay(
                dayStartMs = dayStartMs,
                cloudRows = today.count { it.egress == Egress.CLOUD },
                catalogueRows = today.count { it.egress == Egress.CATALOGUE },
                downloadRows = today.count { it.egress == Egress.DOWNLOAD },
                localRows = today.count { it.egress == Egress.LOCAL },
                pricedRows = priced.size,
                costUsd = if (priced.isEmpty()) null else priced.sumOf { it.costEst ?: 0.0 },
                bytesOut = today.filter { it.egress != Egress.LOCAL }.sumOf { it.bytesOut },
                tokensIn = today.filter { it.egress == Egress.CLOUD }.sumOf { it.tokensIn ?: 0L },
                tokensOut = today.filter { it.egress == Egress.CLOUD }.sumOf { it.tokensOut ?: 0L },
            )
        }
    }
}

/** `stat` template, opens the Ledger tab. */
object EgressTodayWidget {

    fun present(day: EgressDay, ledgerDegraded: Boolean, size: SizeClass, nowMs: Long, copy: WidgetCopy): StatFace {
        val title = copy.text(Txt.EGRESS_TITLE)
        val left = day.leftRows
        val rows = copy.count(Cnt.UNIT_ROWS, left)
        // A one-row widget has no line for the warning, so the value itself
        // carries it: an incomplete ledger undercounts this very number.
        val marked = ledgerDegraded && size.compact
        val value = when {
            marked -> Line(copy.number(left.toLong()), Glyph.WARNING, Tone.VIOLET)
            left > 0 -> Line(copy.number(left.toLong()), Glyph.CLOUD, Tone.VIOLET)
            else -> Line(copy.number(0), Glyph.LOCAL, Tone.CYAN)
        }
        val unit = if (marked) copy.text(Txt.UNIT_INCOMPLETE, rows) else rows

        val all = buildList {
            // Honesty first: an incomplete ledger undercounts this very number.
            if (ledgerDegraded) add(Line(copy.text(Txt.LEDGER_DEGRADED), Glyph.WARNING, Tone.VIOLET))
            when {
                day.costUsd != null && day.pricedRows < day.cloudRows -> add(
                    // Partial coverage is part of the sentence, not a separate line
                    // that a smaller layout would drop.
                    Line(
                        copy.count(
                            Cnt.COST_PARTIAL,
                            day.cloudRows,
                            WidgetFormat.usdShort(day.costUsd),
                            day.pricedRows,
                            day.cloudRows,
                        ),
                    ),
                )
                day.costUsd != null -> add(Line(copy.text(Txt.EGRESS_COST, WidgetFormat.usdShort(day.costUsd))))
                day.cloudRows > 0 -> add(Line(copy.text(Txt.NO_COST_BASIS)))
                left == 0 -> add(Line(copy.text(Txt.EGRESS_NOTHING)))
            }
            if (left > 0) add(Line(breakdown(day, copy)))
            if (day.localRows > 0) {
                add(Line(copy.count(Cnt.LOCAL_ROWS, day.localRows), Glyph.LOCAL, Tone.CYAN))
            }
            if (day.cloudRows > 0) {
                add(
                    Line(
                        copy.text(
                            Txt.EGRESS_TRAFFIC,
                            WidgetFormat.bytes(day.bytesOut),
                            copy.number(day.tokensIn),
                            copy.number(day.tokensOut),
                        ),
                    ),
                )
            }
        }
        val lines = all.take(Slots.statLines(size))
        // The stamp is the date, at every size. Every ledger write happens in
        // asom's own process and is observed live, so within a day the count
        // cannot fall behind unseen; what can lapse is the day itself (a late
        // midnight redraw), and the date shows that. A full "as of" date and
        // time would leave the title no room at the medium width.
        val asOf = copy.day(nowMs)
        val footer = if (Slots.hasFooter(size)) copy.text(Txt.EGRESS_FOOTER) else null
        return StatFace(
            title = title,
            asOf = asOf,
            value = value,
            unit = unit,
            lines = lines,
            footer = footer,
            action = null,
            open = Destination.LEDGER,
            description = WidgetFormat.spoken(
                listOf(title, asOf, "${value.text} $unit") + lines.map { it.display },
                Destination.LEDGER,
                copy,
            ),
        )
    }

    /** "cloud 10 · download 2", in the ledger's own egress words. */
    private fun breakdown(day: EgressDay, copy: WidgetCopy): String = buildList {
        if (day.cloudRows > 0) add("${Glyph.CLOUD} " + copy.text(Txt.EGRESS_PART_CLOUD, copy.number(day.cloudRows.toLong())))
        if (day.catalogueRows > 0) {
            add("${Glyph.LOCAL} " + copy.text(Txt.EGRESS_PART_CATALOGUE, copy.number(day.catalogueRows.toLong())))
        }
        if (day.downloadRows > 0) {
            add("${Glyph.LOCAL} " + copy.text(Txt.EGRESS_PART_DOWNLOAD, copy.number(day.downloadRows.toLong())))
        }
    }.joinToString(" · ")
}
