package xyz.mdhv.asom.widget.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Formatting shared by the widget presenters. Pure, so it is unit-tested. */
object WidgetFormat {

    /**
     * A USD estimate short enough for a widget, as a plain decimal like the
     * ledger's own `RouteRecord.formatUsd` (no exponent, no locale grouping).
     * Values under a hundredth of a cent print as "<0.0001" rather than "0",
     * so a real but tiny estimate is never shown as free.
     */
    fun usdShort(value: Double): String = when {
        value <= 0.0 -> "0"
        value < 0.0001 -> "<0.0001"
        value < 1.0 -> BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else -> BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    /** Same units and rounding as the Models tab ("16.8 GB"). */
    fun bytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(locale, "%.1f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
    }

    /** Whole percent of [done] in [total], clamped to 0..100; null when the total is unknown. */
    fun percent(done: Long, total: Long): Int? {
        if (total <= 0) return null
        val clamped = done.coerceIn(0, total)
        return ((clamped * 100) / total).toInt()
    }

    /** Local midnight that starts the day containing [nowMs]. */
    fun startOfDay(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** The next local midnight after [nowMs] (DST-aware: a day can be 23 or 25 hours). */
    fun nextDayStart(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * When something happened: the time alone for today, date and time before
     * that. Absolute on purpose; a relative "3 min ago" would go stale on a
     * home screen that is not redrawn.
     */
    fun stamp(ts: Long, dayStartMs: Long, copy: WidgetCopy): String =
        if (ts >= dayStartMs) copy.time(ts) else copy.dayTime(ts)

    /**
     * The spoken summary for a whole widget: its visible text without the
     * state glyphs (each glyph sits next to its word already), then where a
     * tap goes.
     */
    fun spoken(parts: List<String?>, open: Destination, copy: WidgetCopy): String {
        val words = parts.mapNotNull { part ->
            part?.let { stripGlyphs(it) }?.takeIf { it.isNotBlank() }
        }
        return (words + copy.text(opensKey(open))).joinToString(". ")
    }

    fun stripGlyphs(text: String): String {
        var out = text
        for (g in Glyph.ALL) out = out.replace(g, "")
        return out.trim().replace(Regex("\\s{2,}"), " ")
    }

    fun opensKey(open: Destination): Txt = when (open) {
        Destination.STATUS -> Txt.OPENS_STATUS
        Destination.HOTSPOT -> Txt.OPENS_HOTSPOT
        Destination.MODELS -> Txt.OPENS_MODELS
        Destination.LEDGER -> Txt.OPENS_LEDGER
        Destination.CONSENT -> Txt.OPENS_CONSENT
    }
}
