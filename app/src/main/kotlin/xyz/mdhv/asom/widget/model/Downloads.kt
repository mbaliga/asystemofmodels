package xyz.mdhv.asom.widget.model

/** Mirrors `storage.DownloadStatus`; an unknown stored name reads as not downloaded, as on the Models tab. */
enum class ModelState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    VERIFYING,
    DOWNLOADED,
    FAILED,
    ;

    companion object {
        fun parse(name: String?): ModelState = entries.firstOrNull { it.name == name } ?: NOT_DOWNLOADED
    }
}

/** One `model_download_state` row, without its error text (it can carry a URL or host name). */
data class ModelRow(
    val modelId: String,
    val state: ModelState,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val updatedAt: Long,
)

data class DownloadsSummary(
    /** Downloadable catalogue models (the Models tab's list). */
    val catalogueModels: Int,
    /** Downloading or verifying, most recently updated first. */
    val active: List<ModelRow>,
    val stored: Int,
    val failed: Int,
    val bytesOnDisk: Long,
) {
    companion object {
        fun of(downloadableIds: List<String>, rows: List<ModelRow>, bytesOnDisk: Long): DownloadsSummary {
            val known = downloadableIds.toSet()
            val mine = rows.filter { it.modelId in known }
            return DownloadsSummary(
                catalogueModels = known.size,
                active = mine.filter { it.state == ModelState.DOWNLOADING || it.state == ModelState.VERIFYING }
                    .sortedWith(compareByDescending<ModelRow> { it.updatedAt }.thenBy { it.modelId }),
                stored = mine.count { it.state == ModelState.DOWNLOADED },
                failed = mine.count { it.state == ModelState.FAILED },
                bytesOnDisk = bytesOnDisk,
            )
        }
    }
}

/** `progress` template, opens the Models tab. */
object DownloadsWidget {

    fun present(summary: DownloadsSummary, size: SizeClass, nowMs: Long, copy: WidgetCopy): ProgressFace {
        val title = copy.text(Txt.DL_TITLE)
        val current = summary.active.firstOrNull()

        val value: Line
        val unit: String?
        val progress: Progress?
        val all = mutableListOf<Line>()
        when {
            current != null -> {
                val pct = WidgetFormat.percent(current.bytesDownloaded, current.bytesTotal)
                value = when {
                    current.state == ModelState.VERIFYING -> Line(copy.text(Txt.DL_VERIFYING), Glyph.VERIFYING, Tone.VIOLET)
                    pct != null -> Line(copy.text(Txt.DL_PERCENT, pct.toString()), Glyph.PENDING, Tone.VIOLET)
                    else -> Line(copy.text(Txt.DL_DOWNLOADING), Glyph.PENDING, Tone.VIOLET)
                }
                unit = current.modelId
                // Hashing has no byte count to show; an unknown total has no percent.
                progress = Progress(if (current.state == ModelState.VERIFYING) null else pct)
                all += if (current.bytesTotal > 0) {
                    Line(copy.text(Txt.DL_DETAIL, WidgetFormat.bytes(current.bytesDownloaded), WidgetFormat.bytes(current.bytesTotal)))
                } else {
                    Line(copy.text(Txt.DL_SO_FAR, WidgetFormat.bytes(current.bytesDownloaded)))
                }
                if (summary.active.size > 1) all += Line(copy.count(Cnt.DL_MORE, summary.active.size - 1))
                if (summary.stored > 0) all += Line(copy.count(Cnt.STORED, summary.stored), Glyph.ACTIVE, Tone.CYAN)
            }
            summary.catalogueModels == 0 -> {
                value = Line(copy.text(Txt.DL_NONE), Glyph.INACTIVE, Tone.NEUTRAL)
                unit = null
                progress = null
                all += Line(copy.text(Txt.DL_NO_CATALOGUE))
            }
            else -> {
                value = if (summary.stored > 0) {
                    Line(copy.number(summary.stored.toLong()), Glyph.ACTIVE, Tone.CYAN)
                } else {
                    Line(copy.number(0), Glyph.INACTIVE, Tone.NEUTRAL)
                }
                unit = copy.count(Cnt.UNIT_MODELS_STORED, summary.stored)
                progress = null
                if (summary.stored == 0 && summary.failed == 0) all += Line(copy.text(Txt.DL_NOTHING_YET))
            }
        }
        if (summary.failed > 0) all += Line(copy.count(Cnt.DL_FAILED, summary.failed), Glyph.FAILED, Tone.VIOLET)
        if (summary.bytesOnDisk > 0) all += Line(copy.text(Txt.DL_ON_DISK, WidgetFormat.bytes(summary.bytesOnDisk)))

        val lines = all.take(Slots.progressLines(size))
        // A running download is the one state here that can freeze unseen (the
        // worker lives in asom's process), so a compact widget stamps it too,
        // with the time alone where "as of" does not fit.
        val time = copy.time(nowMs)
        val spokenAsOf = copy.text(Txt.AS_OF, time)
        val asOf = when {
            !size.compact -> spokenAsOf
            current != null -> time
            else -> null
        }
        return ProgressFace(
            title = title,
            asOf = asOf,
            value = value,
            unit = unit,
            progress = progress,
            lines = lines,
            footer = null,
            open = Destination.MODELS,
            description = WidgetFormat.spoken(
                listOf(title, if (asOf != null) spokenAsOf else null, listOfNotNull(value.display, unit).joinToString(" ")) + lines.map { it.display },
                Destination.MODELS,
                copy,
            ),
        )
    }
}
