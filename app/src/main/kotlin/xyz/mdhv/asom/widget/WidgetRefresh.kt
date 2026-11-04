package xyz.mdhv.asom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.service.AsomService
import xyz.mdhv.asom.widget.model.Backoff
import xyz.mdhv.asom.widget.model.RequestCodes
import xyz.mdhv.asom.widget.model.Source
import xyz.mdhv.asom.widget.model.WidgetFormat
import xyz.mdhv.asom.widget.model.WidgetKind

/**
 * Pushes widget updates when asom's data changes (no `updatePeriodMillis`).
 *
 * Every write the widgets show happens in this one process (the ledger sink,
 * the pairing service, the download worker, the daemon service), so instead
 * of touching each write site this observes the same Room tables and state
 * flows the dashboard observes, while any widget is placed. Bursts (a stream,
 * download progress every second) are conflated to one redraw per
 * [THROTTLE_MS] per source, and the last state is always drawn.
 */
object WidgetRefresh {

    private const val TAG = "asom-widget"
    private const val THROTTLE_MS = 2_000L

    /** How late the midnight redraw may run; 10 minutes is the shortest window API 31+ honours. */
    private const val DAY_BOUNDARY_WINDOW_MS = 10 * 60_000L

    private val watching = AtomicBoolean(false)

    fun providerFor(kind: WidgetKind): Class<out AppWidgetProvider> = when (kind) {
        WidgetKind.EGRESS_TODAY -> EgressTodayWidgetProvider::class.java
        WidgetKind.DAEMON -> DaemonWidgetProvider::class.java
        WidgetKind.LAST_ROUTE -> LastRouteWidgetProvider::class.java
        WidgetKind.RECENT_ROUTES -> RecentRoutesWidgetProvider::class.java
        WidgetKind.HOTSPOT -> HotspotWidgetProvider::class.java
        WidgetKind.DOWNLOADS -> DownloadsWidgetProvider::class.java
    }

    /** Fire-and-forget redraw of every widget that shows [source]. */
    fun request(context: Context, source: Source) {
        val app = context.applicationContext
        ServiceLocator.scope.launch { renderKinds(app, source.kinds) }
    }

    suspend fun renderKinds(context: Context, kinds: Set<WidgetKind>) {
        for (kind in kinds) render(context, kind, null)
    }

    /** Reads [kind]'s data once and redraws [ids] (or every placed widget of that kind). */
    suspend fun render(context: Context, kind: WidgetKind, ids: IntArray?) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val targets = ids ?: manager.getAppWidgetIds(ComponentName(context, providerFor(kind)))
        if (targets.isEmpty()) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val faces = WidgetData.faces(kind, ResourceCopy(context), now, zone)
        for (id in targets) {
            manager.updateAppWidget(id, WidgetRenderer.views(context, manager, kind, id, faces))
        }
        scheduleDayBoundary(context, now, zone)
    }

    /** From Application.onCreate: watch only if a widget is actually placed. */
    fun onProcessStart(context: Context) {
        val app = context.applicationContext
        ServiceLocator.scope.launch {
            if (anyPlaced(app)) ensureWatching(app)
        }
    }

    private fun anyPlaced(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return WidgetKind.entries.any { kind ->
            manager.getAppWidgetIds(ComponentName(context, providerFor(kind))).isNotEmpty()
        }
    }

    /** Starts the observers once per process. The first emission redraws, which corrects a stale widget. */
    fun ensureWatching(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        // Off the main thread: first touches open Room and read preferences.
        ServiceLocator.scope.launch {
            watch(app, Source.LEDGER, ServiceLocator.ledgerDb.dao().count())
            watch(app, Source.LEDGER_HEALTH, ServiceLocator.ledgerDegraded)
            watch(
                app,
                Source.DAEMON,
                merge(AsomService.running, AsomService.activity, AsomService.startError, ServiceLocator.verboseMode),
            )
            watch(app, Source.PAIRINGS, ServiceLocator.pairingDb.dao().observeAll())
            watch(app, Source.DOWNLOADS, ServiceLocator.modelDownloadManager.observeAll())
        }
    }

    /**
     * One source's observer. A failure upstream (a Room re-query hitting
     * SQLITE_BUSY, say) is retried with a backoff instead of ending the
     * observer, which would leave the widget refreshing only at midnight for
     * as long as the process lives.
     */
    private fun watch(app: Context, source: Source, flow: Flow<*>) {
        ServiceLocator.scope.launch {
            flow
                .retryWhen { e, attempt ->
                    Log.w(TAG, "widget watch on $source failed, retrying: ${e.javaClass.name}")
                    delay(Backoff.delayMs(attempt))
                    true
                }
                .conflate()
                .collect {
                    runCatching { renderKinds(app, source.kinds) }
                        .onFailure { e -> Log.w(TAG, "widget redraw for $source failed: ${e.javaClass.name}") }
                    delay(THROTTLE_MS)
                }
        }
    }

    /**
     * "Today" ends at local midnight. One inexact, non-waking alarm redraws
     * then, inside a 10-minute window (`setWindow`: no exact-alarm permission,
     * and unlike `set` it cannot drift by hours on API 29-30). Doze or an OEM
     * battery manager may still hold it while the phone sleeps, which is why
     * the egress widget also prints its date.
     */
    private fun scheduleDayBoundary(context: Context, nowMs: Long, zone: ZoneId) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, WidgetTickReceiver::class.java).setAction(WidgetTickReceiver.ACTION_DAY_BOUNDARY)
        val pending = PendingIntent.getBroadcast(
            context,
            RequestCodes.DAY_BOUNDARY,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setWindow(AlarmManager.RTC, WidgetFormat.nextDayStart(nowMs, zone) + 1_000, DAY_BOUNDARY_WINDOW_MS, pending)
    }
}
