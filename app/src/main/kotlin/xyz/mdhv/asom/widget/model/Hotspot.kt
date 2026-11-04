package xyz.mdhv.asom.widget.model

import xyz.mdhv.asom.contract.PairingStatusCode

/**
 * The only two pairing fields the widget ever receives. Labels, package
 * names, certificate hashes and token hashes are dropped before the data
 * reaches this layer: which apps use asom stays inside asom, and the home
 * screen gets counts.
 */
data class PairingRow(val status: Int, val approvedAt: Long?)

data class HotspotCounts(val paired: Int, val pending: Int, val revoked: Int, val lastApprovedAt: Long?) {
    companion object {
        fun of(rows: List<PairingRow>): HotspotCounts = HotspotCounts(
            paired = rows.count { it.status == PairingStatusCode.PAIRED },
            pending = rows.count { it.status == PairingStatusCode.PENDING },
            revoked = rows.count { it.status == PairingStatusCode.REVOKED },
            lastApprovedAt = rows.filter { it.status == PairingStatusCode.PAIRED }.mapNotNull { it.approvedAt }.maxOrNull(),
        )
    }
}

/**
 * `stat` template. A waiting request opens the consent sheet, which shows the
 * binder-verified identity (§5.7); the widget itself never approves anything.
 */
object HotspotWidget {

    fun present(counts: HotspotCounts, dayStartMs: Long, size: SizeClass, nowMs: Long, copy: WidgetCopy): StatFace {
        val title = copy.text(Txt.HOTSPOT_TITLE)
        val waiting = counts.pending > 0
        val open = if (waiting) Destination.CONSENT else Destination.HOTSPOT

        val (value, unit) = if (waiting && size.compact) {
            // The one thing a 2x1 must not hide is a request waiting for you.
            Line(copy.count(Cnt.PENDING_SHORT, counts.pending), Glyph.PENDING, Tone.VIOLET) to null
        } else if (counts.paired > 0) {
            Line(copy.number(counts.paired.toLong()), Glyph.ACTIVE, Tone.CYAN) to copy.count(Cnt.UNIT_APPS_PAIRED, counts.paired)
        } else {
            Line(copy.number(0), Glyph.INACTIVE, Tone.NEUTRAL) to copy.count(Cnt.UNIT_APPS_PAIRED, 0)
        }
        val all = buildList {
            if (waiting) add(Line(copy.count(Cnt.PENDING, counts.pending), Glyph.PENDING, Tone.VIOLET))
            if (counts.revoked > 0) add(Line(copy.count(Cnt.REVOKED, counts.revoked), Glyph.FAILED, Tone.VIOLET))
            if (counts.paired == 0 && !waiting) add(Line(copy.text(Txt.HOTSPOT_EMPTY)))
            counts.lastApprovedAt?.let {
                add(Line(copy.text(Txt.HOTSPOT_LAST_APPROVED, WidgetFormat.stamp(it, dayStartMs, copy))))
            }
        }
        val lines = all.take(Slots.statLines(size))
        val asOf = if (size.compact) null else copy.text(Txt.AS_OF, copy.time(nowMs))
        val footer = if (Slots.hasFooter(size)) copy.text(Txt.HOTSPOT_WITHHELD) else null
        return StatFace(
            title = title,
            asOf = asOf,
            value = value,
            unit = unit,
            lines = lines,
            footer = footer,
            action = null,
            open = open,
            description = WidgetFormat.spoken(
                listOf(title, asOf, listOfNotNull(value.text, unit).joinToString(" ")) + lines.map { it.display },
                open,
                copy,
            ),
        )
    }
}
