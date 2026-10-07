package xyz.mdhv.asom.server.ledger

import java.util.concurrent.CopyOnWriteArrayList
import xyz.mdhv.asom.contract.RouteRecord

/**
 * Write-side seam of the egress ledger (§9). Every routed request appends
 * exactly one row (invariant §1.3/§7); Android backs this with Room.
 *
 * `append` suspends because the server treats it as COMMITTED once it returns:
 * the response is not finished until the row for the egress that already
 * happened is durable. An implementation that hands the row to a background
 * scope and returns loses it to a process kill in that window.
 */
fun interface LedgerSink {
    suspend fun append(record: RouteRecord)
}

/** Desktop/test ledger: in-memory, inspectable. Never leaves the process. */
class InMemoryLedger : LedgerSink {
    private val rows = CopyOnWriteArrayList<RouteRecord>()

    override suspend fun append(record: RouteRecord) {
        rows.add(record)
    }

    fun all(): List<RouteRecord> = rows.toList()
}
