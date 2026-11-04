package xyz.mdhv.asom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.widget.model.Source
import xyz.mdhv.asom.widget.model.WidgetKind

/**
 * Runs [block] on asom's IO scope and finishes the broadcast at [BUDGET_MS]
 * whether or not the block is done. The reads inside are blocking (Room DAO
 * calls, the catalogue parse, a walk of the model files) and a timeout cannot
 * interrupt those, so `finish()` waits on a cancellable `join()` instead of
 * on the work itself. Work still running at the budget carries on, and its
 * redraw lands when the read returns.
 */
internal fun BroadcastReceiver.PendingResult.finishWithin(block: suspend () -> Unit) {
    val work = ServiceLocator.scope.launch { block() }
    ServiceLocator.scope.launch {
        try {
            withTimeoutOrNull(BUDGET_MS) { work.join() }
        } finally {
            finish()
        }
    }
}

/** Well inside the 10 s a broadcast receiver is given. */
internal const val BUDGET_MS = 8_000L

/** One provider per widget kind. Each broadcast is finished within [BUDGET_MS] (see [finishWithin]). */
abstract class AsomWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRefresh.ensureWatching(context)
        runAsync { WidgetRefresh.render(context.applicationContext, kind, appWidgetIds) }
    }

    /** Below API 31 the layout is chosen from the reported size, so a resize needs a redraw. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runAsync { WidgetRefresh.render(context.applicationContext, kind, intArrayOf(appWidgetId)) }
        }
    }

    /** Clock, time zone, 12/24-hour and language changes redraw this provider's widgets. */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED ->
                runAsync { WidgetRefresh.render(context.applicationContext, kind, null) }
            else -> super.onReceive(context, intent)
        }
    }

    private fun runAsync(block: suspend () -> Unit) = goAsync().finishWithin(block)
}

class EgressTodayWidgetProvider : AsomWidgetProvider(WidgetKind.EGRESS_TODAY)

class DaemonWidgetProvider : AsomWidgetProvider(WidgetKind.DAEMON)

class LastRouteWidgetProvider : AsomWidgetProvider(WidgetKind.LAST_ROUTE)

class RecentRoutesWidgetProvider : AsomWidgetProvider(WidgetKind.RECENT_ROUTES)

class HotspotWidgetProvider : AsomWidgetProvider(WidgetKind.HOTSPOT)

class DownloadsWidgetProvider : AsomWidgetProvider(WidgetKind.DOWNLOADS)

/**
 * The midnight redraw ([WidgetRefresh] schedules it). Not exported: only
 * asom's own immutable alarm intent reaches it.
 */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAY_BOUNDARY) return
        val app = context.applicationContext
        goAsync().finishWithin { WidgetRefresh.renderKinds(app, Source.CLOCK.kinds) }
    }

    companion object {
        const val ACTION_DAY_BOUNDARY = "xyz.mdhv.asom.widget.action.DAY_BOUNDARY"
    }
}
