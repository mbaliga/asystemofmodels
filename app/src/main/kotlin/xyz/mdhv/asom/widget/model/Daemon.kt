package xyz.mdhv.asom.widget.model

/** What the daemon is doing right now (mirrors `AsomService.Activity`). */
enum class DaemonActivity { IDLE, SERVING, STREAMING }

/**
 * The daemon's live state as the process sees it. It lives in memory in
 * `AsomService`, so a widget drawn from a fresh process reads "stopped", which
 * is true: the service runs in this same process.
 */
data class DaemonState(
    val running: Boolean,
    val activity: DaemonActivity,
    val streamingProvider: String?,
    val startFailed: Boolean,
    val verbose: Boolean,
    val ledgerDegraded: Boolean,
    val host: String,
    val port: Int,
)

/** `stat` template with one pad (Start or Stop) from medium up; opens the Status tab. */
object DaemonWidget {

    fun present(state: DaemonState, size: SizeClass, nowMs: Long, copy: WidgetCopy): StatFace {
        val title = copy.text(Txt.DAEMON_TITLE)
        val port = state.port.toString()
        val value = when {
            state.running -> Line(copy.text(Txt.DAEMON_RUNNING), Glyph.ACTIVE, Tone.CYAN)
            state.startFailed -> Line(copy.text(Txt.DAEMON_FAILED), Glyph.FAILED, Tone.VIOLET)
            else -> Line(copy.text(Txt.DAEMON_STOPPED), Glyph.INACTIVE, Tone.VIOLET)
        }
        val all = buildList {
            // Warnings first, so the two-line medium layout shows them ahead of
            // the routine endpoint and activity lines. §9: while verbose mode is
            // on, bodies are being kept; say so. An incomplete ledger is the
            // other thing this widget must not hide.
            if (state.verbose) add(Line(copy.text(Txt.DAEMON_VERBOSE), Glyph.VERBOSE, Tone.VIOLET))
            if (state.ledgerDegraded) add(Line(copy.text(Txt.LEDGER_DEGRADED), Glyph.WARNING, Tone.VIOLET))
            if (state.running) {
                add(Line(copy.text(Txt.DAEMON_ENDPOINT, state.host, port)))
                add(Line(activityText(state, copy)))
            } else {
                add(Line(copy.text(Txt.DAEMON_NOT_LISTENING, port)))
                if (state.startFailed) add(Line(copy.text(Txt.DAEMON_PORT_BUSY, port), Glyph.FAILED, Tone.VIOLET))
            }
        }
        val lines = all.take(Slots.statLines(size))
        // Below medium there is no room for a 48dp control: the whole widget is
        // one tap that opens the Status tab instead.
        val action = if (size.compact) {
            null
        } else if (state.running) {
            Action(DaemonAction.STOP, copy.text(Txt.ACTION_STOP), copy.text(Txt.ACTION_STOP_DESC))
        } else {
            Action(DaemonAction.START, copy.text(Txt.ACTION_START), copy.text(Txt.ACTION_START_DESC))
        }
        // The daemon's state lives in memory, so a killed process leaves the
        // last drawing in place: every size prints when it was drawn (the time
        // alone where a one-row widget has no room for "as of").
        val time = copy.time(nowMs)
        val spokenAsOf = copy.text(Txt.AS_OF, time)
        val asOf = if (size.compact) time else spokenAsOf
        val footer = if (Slots.hasFooter(size)) copy.text(Txt.DAEMON_V1) else null
        return StatFace(
            title = title,
            asOf = asOf,
            value = value,
            unit = null,
            lines = lines,
            footer = footer,
            action = action,
            open = Destination.STATUS,
            description = WidgetFormat.spoken(
                listOf(title, value.display, spokenAsOf) + lines.map { it.display },
                Destination.STATUS,
                copy,
            ),
        )
    }

    private fun activityText(state: DaemonState, copy: WidgetCopy): String = when (state.activity) {
        DaemonActivity.IDLE -> copy.text(Txt.DAEMON_IDLE)
        DaemonActivity.SERVING -> copy.text(Txt.DAEMON_SERVING)
        DaemonActivity.STREAMING ->
            state.streamingProvider?.let { copy.text(Txt.DAEMON_STREAMING, it) } ?: copy.text(Txt.DAEMON_SERVING)
    }
}
