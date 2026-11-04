package xyz.mdhv.asom.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import xyz.mdhv.asom.R
import xyz.mdhv.asom.widget.model.Action
import xyz.mdhv.asom.widget.model.CardFace
import xyz.mdhv.asom.widget.model.Face
import xyz.mdhv.asom.widget.model.Line
import xyz.mdhv.asom.widget.model.ListFace
import xyz.mdhv.asom.widget.model.ProgressFace
import xyz.mdhv.asom.widget.model.SizeClass
import xyz.mdhv.asom.widget.model.Slots
import xyz.mdhv.asom.widget.model.StatFace
import xyz.mdhv.asom.widget.model.Tone
import xyz.mdhv.asom.widget.model.WidgetKind
import xyz.mdhv.asom.widget.model.summary

/**
 * Binds the pure faces into RemoteViews. Text only, no bitmaps, so an update
 * stays far below AppWidgetManager's size cap.
 */
object WidgetRenderer {

    private val LINE_IDS = intArrayOf(R.id.w_line1, R.id.w_line2, R.id.w_line3, R.id.w_line4)
    private val ROW_IDS = intArrayOf(R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4, R.id.w_row5)
    private val ROW_LEAD_IDS =
        intArrayOf(R.id.w_row1_lead, R.id.w_row2_lead, R.id.w_row3_lead, R.id.w_row4_lead, R.id.w_row5_lead)
    private val ROW_TRAILING_IDS = intArrayOf(
        R.id.w_row1_trailing,
        R.id.w_row2_trailing,
        R.id.w_row3_trailing,
        R.id.w_row4_trailing,
        R.id.w_row5_trailing,
    )

    /**
     * API 31+: one RemoteViews per size class, keyed by its breakpoint, so the
     * launcher picks the layout on every resize without waking asom. Below
     * that: the sizes the host reported for this widget pick a portrait layout
     * (narrowest width, tallest height) and a landscape one (widest width,
     * shortest height), sent as a pair so a rotation shows the right one
     * without a redraw; the provider re-renders on `onAppWidgetOptionsChanged`.
     */
    fun views(
        context: Context,
        manager: AppWidgetManager,
        kind: WidgetKind,
        widgetId: Int,
        faces: Map<SizeClass, Face>,
    ): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(
                SizeClass.entries.associate { size ->
                    SizeF(size.minWidthDp, size.minHeightDp) to build(context, widgetId, faces.getValue(size), size)
                },
            )
        } else {
            val options = manager.getAppWidgetOptions(widgetId)
            fun dp(key: String) = options.getInt(key).toFloat()
            val portrait = SizeClass.of(
                dp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                dp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
                kind.defaultSize,
            )
            val landscape = SizeClass.of(
                dp(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                dp(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                kind.defaultSize,
            )
            val portraitViews = build(context, widgetId, faces.getValue(portrait), portrait)
            if (landscape == portrait) {
                portraitViews
            } else {
                RemoteViews(build(context, widgetId, faces.getValue(landscape), landscape), portraitViews)
            }
        }

    private fun build(context: Context, widgetId: Int, face: Face, size: SizeClass): RemoteViews {
        val rv = RemoteViews(context.packageName, layoutFor(face, size))
        rv.setTextViewText(R.id.w_title, face.title)
        if (size == SizeClass.TINY) {
            // One line: the value (or headline, or newest row) and its stamp.
            val summary = face.summary()
            bindLine(context, rv, R.id.w_value, summary.line)
            setOptional(rv, R.id.w_asof, summary.stamp)
        } else {
            setOptional(rv, R.id.w_asof, face.asOf)
            when (face) {
                is StatFace -> bindStat(context, rv, widgetId, face, size)
                is CardFace -> bindCard(context, rv, face, size)
                is ListFace -> bindList(context, rv, face, size)
                is ProgressFace -> bindProgress(context, rv, face, size)
            }
        }
        // The whole widget is one target (at least the 48dp a cell gives);
        // TalkBack reads the words-only summary instead of the glyphs.
        rv.setOnClickPendingIntent(android.R.id.background, WidgetLinks.open(context, widgetId, face.open))
        rv.setContentDescription(android.R.id.background, face.description)
        return rv
    }

    private fun layoutFor(face: Face, size: SizeClass): Int = when (face) {
        is StatFace -> pick(size, R.layout.widget_stat_small, R.layout.widget_stat_medium, R.layout.widget_stat_large)
        is CardFace -> pick(size, R.layout.widget_card_small, R.layout.widget_card_medium, R.layout.widget_card_large)
        is ListFace -> pick(size, R.layout.widget_list_small, R.layout.widget_list_medium, R.layout.widget_list_large)
        is ProgressFace ->
            pick(size, R.layout.widget_progress_small, R.layout.widget_progress_medium, R.layout.widget_progress_large)
    }

    private fun pick(size: SizeClass, small: Int, medium: Int, large: Int): Int = when (size) {
        SizeClass.TINY -> R.layout.widget_tiny
        SizeClass.SMALL -> small
        SizeClass.MEDIUM -> medium
        SizeClass.LARGE -> large
    }

    private fun bindStat(context: Context, rv: RemoteViews, widgetId: Int, face: StatFace, size: SizeClass) {
        bindLine(context, rv, R.id.w_value, face.value)
        setOptional(rv, R.id.w_unit, face.unit)
        bindLines(context, rv, face.lines, Slots.statLines(size))
        if (!size.compact) bindAction(context, rv, widgetId, face.action)
        if (Slots.hasFooter(size)) setOptional(rv, R.id.w_footer, face.footer)
    }

    private fun bindCard(context: Context, rv: RemoteViews, face: CardFace, size: SizeClass) {
        bindLine(context, rv, R.id.w_headline, face.headline)
        bindLines(context, rv, face.lines, Slots.cardLines(size))
        if (Slots.hasFooter(size)) setOptional(rv, R.id.w_footer, face.footer)
    }

    private fun bindList(context: Context, rv: RemoteViews, face: ListFace, size: SizeClass) {
        for (i in 0 until Slots.listRows(size)) {
            val row = face.rows.getOrNull(i)
            if (row == null) {
                rv.setViewVisibility(ROW_IDS[i], View.GONE)
            } else {
                rv.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                bindLine(context, rv, ROW_LEAD_IDS[i], row.lead)
                setOptional(rv, ROW_TRAILING_IDS[i], row.trailing)
            }
        }
        if (Slots.hasFooter(size)) setOptional(rv, R.id.w_footer, face.footer)
    }

    private fun bindProgress(context: Context, rv: RemoteViews, face: ProgressFace, size: SizeClass) {
        bindLine(context, rv, R.id.w_value, face.value)
        setOptional(rv, R.id.w_unit, face.unit)
        val progress = face.progress
        if (progress == null) {
            rv.setViewVisibility(R.id.w_bar, View.GONE)
        } else {
            rv.setViewVisibility(R.id.w_bar, View.VISIBLE)
            rv.setProgressBar(R.id.w_bar, 100, progress.percent ?: 0, progress.percent == null)
        }
        bindLines(context, rv, face.lines, Slots.progressLines(size))
        if (Slots.hasFooter(size)) setOptional(rv, R.id.w_footer, face.footer)
    }

    private fun bindAction(context: Context, rv: RemoteViews, widgetId: Int, action: Action?) {
        if (action == null) {
            rv.setViewVisibility(R.id.w_action, View.GONE)
            return
        }
        rv.setViewVisibility(R.id.w_action, View.VISIBLE)
        rv.setTextViewText(R.id.w_action, action.label)
        rv.setContentDescription(R.id.w_action, action.description)
        rv.setOnClickPendingIntent(R.id.w_action, WidgetLinks.action(context, widgetId, action.kind))
    }

    private fun bindLines(context: Context, rv: RemoteViews, lines: List<Line>, slots: Int) {
        for (i in 0 until slots) {
            val line = lines.getOrNull(i)
            if (line == null) {
                rv.setViewVisibility(LINE_IDS[i], View.GONE)
            } else {
                rv.setViewVisibility(LINE_IDS[i], View.VISIBLE)
                bindLine(context, rv, LINE_IDS[i], line)
            }
        }
    }

    /** Glyph, words and colour together (§1.6): the colour is never alone. */
    private fun bindLine(context: Context, rv: RemoteViews, id: Int, line: Line) {
        rv.setTextViewText(id, line.display)
        rv.setTextColor(id, context.getColor(toneColor(line.tone)))
    }

    private fun toneColor(tone: Tone): Int = when (tone) {
        Tone.VIOLET -> R.color.widget_violet
        Tone.CYAN -> R.color.widget_cyan
        Tone.NEUTRAL -> R.color.widget_ink
    }

    private fun setOptional(rv: RemoteViews, id: Int, text: String?) {
        if (text.isNullOrEmpty()) {
            rv.setTextViewText(id, "")
            rv.setViewVisibility(id, View.GONE)
        } else {
            rv.setTextViewText(id, text)
            rv.setViewVisibility(id, View.VISIBLE)
        }
    }
}
