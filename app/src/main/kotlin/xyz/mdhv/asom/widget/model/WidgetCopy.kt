package xyz.mdhv.asom.widget.model

/**
 * Every word a widget prints comes through here. On the device it is backed by
 * `res/values/strings.xml` (`widget_*` strings and plurals, see
 * `xyz.mdhv.asom.widget.ResourceCopy`); in the JVM tests by a fake that echoes
 * the key, so a test can say which sentence a face chose without pinning copy.
 */
interface WidgetCopy {
    fun text(key: Txt, vararg args: Any): String

    /** A plural; with no [args] the count itself is the only format argument. */
    fun count(key: Cnt, n: Int, vararg args: Any): String

    /** Clock time in the user's 12/24-hour setting, e.g. "14:05". */
    fun time(epochMs: Long): String

    /** Short date, e.g. "4 Nov". */
    fun day(epochMs: Long): String

    /** Short date and time, e.g. "4 Nov, 14:05". */
    fun dayTime(epochMs: Long): String

    /** A grouped integer, e.g. "1,234". */
    fun number(n: Long): String
}

/** Sentences (`widget_<name>` in strings.xml). */
enum class Txt {
    AS_OF,
    ERROR,
    OPENS_LEDGER,
    OPENS_STATUS,
    OPENS_HOTSPOT,
    OPENS_MODELS,
    OPENS_CONSENT,
    LEDGER_DEGRADED,
    NO_COST_BASIS,
    UNIT_INCOMPLETE,

    EGRESS_TITLE,
    EGRESS_NOTHING,
    EGRESS_COST,
    EGRESS_PART_CLOUD,
    EGRESS_PART_CATALOGUE,
    EGRESS_PART_DOWNLOAD,
    EGRESS_TRAFFIC,
    EGRESS_FOOTER,

    DAEMON_TITLE,
    DAEMON_RUNNING,
    DAEMON_STOPPED,
    DAEMON_FAILED,
    DAEMON_ENDPOINT,
    DAEMON_NOT_LISTENING,
    DAEMON_PORT_BUSY,
    DAEMON_IDLE,
    DAEMON_SERVING,
    DAEMON_STREAMING,
    DAEMON_VERBOSE,
    DAEMON_V1,
    ACTION_START,
    ACTION_STOP,
    ACTION_START_DESC,
    ACTION_STOP_DESC,

    LAST_ROUTE_TITLE,
    RECENT_TITLE,
    ROUTE_CLOUD,
    ROUTE_LOCAL,
    ROUTE_SERVED_BY,
    ROUTE_SENT_TO,
    ROUTE_NOT_SERVED,
    ROUTE_STATUS,
    ROUTE_COST,
    ROUTE_ASKED,
    ROUTE_DETAIL,
    ROW_SENT,
    ROW_CLOUD_NOT_SERVED,
    ROW_LOCAL,
    ROUTES_EMPTY,
    ROUTES_HINT,
    ROUTES_FOOTER,

    HOTSPOT_TITLE,
    HOTSPOT_EMPTY,
    HOTSPOT_LAST_APPROVED,
    HOTSPOT_WITHHELD,

    DL_TITLE,
    DL_PERCENT,
    DL_DOWNLOADING,
    DL_VERIFYING,
    DL_NONE,
    DL_DETAIL,
    DL_SO_FAR,
    DL_ON_DISK,
    DL_NOTHING_YET,
    DL_NO_CATALOGUE,
}

/** Plurals (`widget_<name>` plurals in strings.xml). */
enum class Cnt {
    UNIT_ROWS,
    LOCAL_ROWS,
    COST_PARTIAL,
    UNIT_APPS_PAIRED,
    PENDING,
    PENDING_SHORT,
    REVOKED,
    UNIT_MODELS_STORED,
    STORED,
    DL_MORE,
    DL_FAILED,
}
