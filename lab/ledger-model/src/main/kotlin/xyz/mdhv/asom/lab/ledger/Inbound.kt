package xyz.mdhv.asom.lab.ledger

/**
 * Inbound connections refused before authentication are not sessions and have no addresses (P5): they are counted in ONE `INBOUND_REFUSED` row per
 * 10 minutes (LAB_SPEC 7.6). The count is in `meshCode` as `refused:<n>` (ERRATA ERR-LL-5: the spec names no column for it).
 */
class InboundRefusedCounter(private val node: NodeLedger, private val windowMs: Long = 600_000) {
    private val lock = Any()
    private var count = 0L
    private var windowStart: Long? = null

    fun refused() {
        synchronized(lock) {
            if (windowStart == null) windowStart = node.now()
            count++
        }
    }

    /**
     * Writes the row when the window has elapsed (or when [force] is set, at shutdown). Returns it, or null when nothing was due.
     * The count is taken out of the counter before the row is appended and put back when the append fails, so a refusal counted while the row is
     * being written belongs to the next window and none is lost or counted twice.
     */
    fun flush(force: Boolean = false): LabRouteRecord? {
        val taken: Long
        val start: Long
        synchronized(lock) {
            start = windowStart ?: return null
            if (!force && node.now() - start < windowMs) return null
            taken = count
            count = 0
            windowStart = null
        }
        val r = LabRouteRecord(
            ts = node.now(), callerPkg = "peer:unknown", requestedModel = "", egress = LabEgress.peerClass, status = 403,
            meshKind = MeshKind.INBOUND_REFUSED, bytesIn = 0, meshCode = "refused:$taken",
        )
        try {
            node.append(r)
        } catch (e: Throwable) {
            synchronized(lock) {
                count += taken
                windowStart = windowStart?.let { minOf(it, start) } ?: start
            }
            throw e
        }
        return r
    }
}
