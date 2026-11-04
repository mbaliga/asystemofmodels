package xyz.mdhv.asom.widget

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.text.NumberFormat
import java.util.Date
import xyz.mdhv.asom.R
import xyz.mdhv.asom.widget.model.Cnt
import xyz.mdhv.asom.widget.model.Txt
import xyz.mdhv.asom.widget.model.WidgetCopy

/**
 * [WidgetCopy] backed by `res/values/strings.xml`. The `when` blocks are
 * exhaustive, so a key without a string fails compilation rather than a widget.
 * Read with the application context, whose resources follow the current locale.
 */
class ResourceCopy(private val context: Context) : WidgetCopy {

    override fun text(key: Txt, vararg args: Any): String = context.getString(textRes(key), *args)

    override fun count(key: Cnt, n: Int, vararg args: Any): String =
        if (args.isEmpty()) {
            context.resources.getQuantityString(countRes(key), n, n)
        } else {
            context.resources.getQuantityString(countRes(key), n, *args)
        }

    override fun time(epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

    override fun day(epochMs: Long): String = DateUtils.formatDateTime(
        context,
        epochMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )

    override fun dayTime(epochMs: Long): String = DateUtils.formatDateTime(
        context,
        epochMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NO_YEAR or
            DateUtils.FORMAT_ABBREV_MONTH,
    )

    override fun number(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    private fun textRes(key: Txt): Int = when (key) {
        Txt.AS_OF -> R.string.widget_as_of
        Txt.ERROR -> R.string.widget_error
        Txt.OPENS_LEDGER -> R.string.widget_opens_ledger
        Txt.OPENS_STATUS -> R.string.widget_opens_status
        Txt.OPENS_HOTSPOT -> R.string.widget_opens_hotspot
        Txt.OPENS_MODELS -> R.string.widget_opens_models
        Txt.OPENS_CONSENT -> R.string.widget_opens_consent
        Txt.LEDGER_DEGRADED -> R.string.widget_ledger_degraded
        Txt.NO_COST_BASIS -> R.string.widget_no_cost_basis
        Txt.UNIT_INCOMPLETE -> R.string.widget_unit_incomplete
        Txt.EGRESS_TITLE -> R.string.widget_egress_title
        Txt.EGRESS_NOTHING -> R.string.widget_egress_nothing
        Txt.EGRESS_COST -> R.string.widget_egress_cost
        Txt.EGRESS_PART_CLOUD -> R.string.widget_egress_part_cloud
        Txt.EGRESS_PART_CATALOGUE -> R.string.widget_egress_part_catalogue
        Txt.EGRESS_PART_DOWNLOAD -> R.string.widget_egress_part_download
        Txt.EGRESS_TRAFFIC -> R.string.widget_egress_traffic
        Txt.EGRESS_FOOTER -> R.string.widget_egress_footer
        Txt.DAEMON_TITLE -> R.string.widget_daemon_title
        Txt.DAEMON_RUNNING -> R.string.widget_daemon_running
        Txt.DAEMON_STOPPED -> R.string.widget_daemon_stopped
        Txt.DAEMON_FAILED -> R.string.widget_daemon_failed
        Txt.DAEMON_ENDPOINT -> R.string.widget_daemon_endpoint
        Txt.DAEMON_NOT_LISTENING -> R.string.widget_daemon_not_listening
        Txt.DAEMON_PORT_BUSY -> R.string.widget_daemon_port_busy
        Txt.DAEMON_IDLE -> R.string.widget_daemon_idle
        Txt.DAEMON_SERVING -> R.string.widget_daemon_serving
        Txt.DAEMON_STREAMING -> R.string.widget_daemon_streaming
        Txt.DAEMON_VERBOSE -> R.string.widget_daemon_verbose
        Txt.DAEMON_V1 -> R.string.widget_daemon_v1
        Txt.ACTION_START -> R.string.widget_action_start
        Txt.ACTION_STOP -> R.string.widget_action_stop
        Txt.ACTION_START_DESC -> R.string.widget_action_start_desc
        Txt.ACTION_STOP_DESC -> R.string.widget_action_stop_desc
        Txt.LAST_ROUTE_TITLE -> R.string.widget_last_route_title
        Txt.RECENT_TITLE -> R.string.widget_recent_title
        Txt.ROUTE_CLOUD -> R.string.widget_route_cloud
        Txt.ROUTE_LOCAL -> R.string.widget_route_local
        Txt.ROUTE_SERVED_BY -> R.string.widget_route_served_by
        Txt.ROUTE_SENT_TO -> R.string.widget_route_sent_to
        Txt.ROUTE_NOT_SERVED -> R.string.widget_route_not_served
        Txt.ROUTE_STATUS -> R.string.widget_route_status
        Txt.ROUTE_COST -> R.string.widget_route_cost
        Txt.ROUTE_ASKED -> R.string.widget_route_asked
        Txt.ROUTE_DETAIL -> R.string.widget_route_detail
        Txt.ROW_SENT -> R.string.widget_row_sent
        Txt.ROW_CLOUD_NOT_SERVED -> R.string.widget_row_cloud_not_served
        Txt.ROW_LOCAL -> R.string.widget_row_local
        Txt.ROUTES_EMPTY -> R.string.widget_routes_empty
        Txt.ROUTES_HINT -> R.string.widget_routes_hint
        Txt.ROUTES_FOOTER -> R.string.widget_routes_footer
        Txt.HOTSPOT_TITLE -> R.string.widget_hotspot_title
        Txt.HOTSPOT_EMPTY -> R.string.widget_hotspot_empty
        Txt.HOTSPOT_LAST_APPROVED -> R.string.widget_hotspot_last_approved
        Txt.HOTSPOT_WITHHELD -> R.string.widget_hotspot_withheld
        Txt.DL_TITLE -> R.string.widget_dl_title
        Txt.DL_PERCENT -> R.string.widget_dl_percent
        Txt.DL_DOWNLOADING -> R.string.widget_dl_downloading
        Txt.DL_VERIFYING -> R.string.widget_dl_verifying
        Txt.DL_NONE -> R.string.widget_dl_none
        Txt.DL_DETAIL -> R.string.widget_dl_detail
        Txt.DL_SO_FAR -> R.string.widget_dl_so_far
        Txt.DL_ON_DISK -> R.string.widget_dl_on_disk
        Txt.DL_NOTHING_YET -> R.string.widget_dl_nothing_yet
        Txt.DL_NO_CATALOGUE -> R.string.widget_dl_no_catalogue
    }

    private fun countRes(key: Cnt): Int = when (key) {
        Cnt.UNIT_ROWS -> R.plurals.widget_unit_rows
        Cnt.LOCAL_ROWS -> R.plurals.widget_local_rows
        Cnt.COST_PARTIAL -> R.plurals.widget_cost_partial
        Cnt.UNIT_APPS_PAIRED -> R.plurals.widget_unit_apps_paired
        Cnt.PENDING -> R.plurals.widget_pending
        Cnt.PENDING_SHORT -> R.plurals.widget_pending_short
        Cnt.REVOKED -> R.plurals.widget_revoked
        Cnt.UNIT_MODELS_STORED -> R.plurals.widget_unit_models_stored
        Cnt.STORED -> R.plurals.widget_stored
        Cnt.DL_MORE -> R.plurals.widget_dl_more
        Cnt.DL_FAILED -> R.plurals.widget_dl_failed
    }
}
