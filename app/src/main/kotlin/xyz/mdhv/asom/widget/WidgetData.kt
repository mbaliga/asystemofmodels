package xyz.mdhv.asom.widget

import android.content.Context
import android.util.Log
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.ledger.toRecord
import xyz.mdhv.asom.service.AsomService
import xyz.mdhv.asom.widget.model.DaemonActivity
import xyz.mdhv.asom.widget.model.DaemonState
import xyz.mdhv.asom.widget.model.DaemonWidget
import xyz.mdhv.asom.widget.model.DownloadsSummary
import xyz.mdhv.asom.widget.model.DownloadsWidget
import xyz.mdhv.asom.widget.model.EgressDay
import xyz.mdhv.asom.widget.model.EgressTodayWidget
import xyz.mdhv.asom.widget.model.ErrorFaces
import xyz.mdhv.asom.widget.model.Face
import xyz.mdhv.asom.widget.model.HotspotCounts
import xyz.mdhv.asom.widget.model.HotspotWidget
import xyz.mdhv.asom.widget.model.LastRouteWidget
import xyz.mdhv.asom.widget.model.ModelRow
import xyz.mdhv.asom.widget.model.ModelState
import xyz.mdhv.asom.widget.model.PairingRow
import xyz.mdhv.asom.widget.model.RecentRoutesWidget
import xyz.mdhv.asom.widget.model.SizeClass
import xyz.mdhv.asom.widget.model.WidgetCopy
import xyz.mdhv.asom.widget.model.WidgetFormat
import xyz.mdhv.asom.widget.model.WidgetKind

/**
 * Reads asom's real data for one widget kind and builds its face at every
 * size. Called off the main thread only (Room refuses the main thread). Reads
 * the same stores the dashboard reads, through [ServiceLocator]; nothing is
 * cached on disk, so there is no widget snapshot to leak into a backup.
 */
object WidgetData {

    private const val TAG = "asom-widget"

    suspend fun faces(kind: WidgetKind, copy: WidgetCopy, nowMs: Long, zone: ZoneId): Map<SizeClass, Face> =
        try {
            build(kind, copy, nowMs, zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: no message, which could carry a path or a value.
            Log.w(TAG, "widget data unavailable for $kind: ${e.javaClass.name}")
            val face = ErrorFaces.of(kind, nowMs, copy)
            SizeClass.entries.associateWith { face }
        }

    private suspend fun build(kind: WidgetKind, copy: WidgetCopy, nowMs: Long, zone: ZoneId): Map<SizeClass, Face> {
        val dayStart = WidgetFormat.startOfDay(nowMs, zone)
        return when (kind) {
            WidgetKind.EGRESS_TODAY -> {
                val rows = ServiceLocator.ledgerDb.dao().since(dayStart).map { it.toRecord() }
                val day = EgressDay.of(rows, dayStart)
                val degraded = ServiceLocator.ledgerDegraded.value
                each { EgressTodayWidget.present(day, degraded, it, nowMs, copy) }
            }
            WidgetKind.DAEMON -> {
                val state = daemonState()
                each { DaemonWidget.present(state, it, nowMs, copy) }
            }
            WidgetKind.LAST_ROUTE -> {
                val latest = ServiceLocator.ledgerDb.dao().recentRoutes(1).firstOrNull()?.toRecord()
                each { LastRouteWidget.present(latest, dayStart, it, copy) }
            }
            WidgetKind.RECENT_ROUTES -> {
                val rows = ServiceLocator.ledgerDb.dao().recentRoutes(RecentRoutesWidget.MAX_ROWS).map { it.toRecord() }
                each { RecentRoutesWidget.present(rows, dayStart, it, nowMs, copy) }
            }
            WidgetKind.HOTSPOT -> {
                // Status and approval time only: labels, packages and hashes stop here.
                val rows = ServiceLocator.pairingDb.dao().all().map { PairingRow(it.status, it.approvedAt) }
                val counts = HotspotCounts.of(rows)
                each { HotspotWidget.present(counts, dayStart, it, nowMs, copy) }
            }
            WidgetKind.DOWNLOADS -> {
                val manager = ServiceLocator.modelDownloadManager
                val rows = manager.observeAll().first().map {
                    ModelRow(it.modelId, ModelState.parse(it.status), it.bytesDownloaded, it.bytesTotal, it.updatedAt)
                }
                val downloadable = ServiceLocator.catalogue.models.filter { it.files.isNotEmpty() }.map { it.id }
                val summary = DownloadsSummary.of(downloadable, rows, manager.totalBytesOnDisk())
                each { DownloadsWidget.present(summary, it, nowMs, copy) }
            }
        }
    }

    private fun daemonState(): DaemonState {
        val activity = AsomService.activity.value
        return DaemonState(
            running = AsomService.running.value,
            activity = when (activity) {
                AsomService.Activity.Idle -> DaemonActivity.IDLE
                AsomService.Activity.Serving -> DaemonActivity.SERVING
                is AsomService.Activity.Streaming -> DaemonActivity.STREAMING
            },
            streamingProvider = (activity as? AsomService.Activity.Streaming)?.providerId,
            startFailed = AsomService.startError.value != null,
            verbose = ServiceLocator.verboseMode.value,
            ledgerDegraded = ServiceLocator.ledgerDegraded.value,
            host = Asom.BIND_HOST,
            port = Asom.DEFAULT_PORT,
        )
    }

    private inline fun each(build: (SizeClass) -> Face): Map<SizeClass, Face> = SizeClass.entries.associateWith(build)
}
