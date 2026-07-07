package xyz.mdhv.asom.server.ledger

import java.util.concurrent.CopyOnWriteArrayList
import xyz.mdhv.asom.contract.RouteRecord

/**
 * Write-side seam of the egress ledger (§9). Every routed request appends
 * exactly one row (invariant §1.3/§7); Android backs this with Room.
 */
fun interface LedgerSink {
    fun append(record: RouteRecord)
}

/** Desktop/test ledger: in-memory, inspectable. Never leaves the process. */
class InMemoryLedger : LedgerSink {
    private val rows = CopyOnWriteArrayList<RouteRecord>()

    override fun append(record: RouteRecord) {
        rows.add(record)
    }

    fun all(): List<RouteRecord> = rows.toList()
}
