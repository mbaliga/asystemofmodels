package xyz.mdhv.asom.lab.ledger

/**
 * Inbound connections refused before authentication are not sessions and have no addresses (P5): they are counted in ONE `INBOUND_REFUSED` row per
 * 10 minutes (LAB_SPEC 7.6). The count is in `meshCode` as `refused:<n>` (ERRATA ERR-LL-5: the spec names no column for it).
 */
class InboundRefusedCounter(private val node: NodeLedger, private val windowMs: Long = 600_000) {
    private var count = 0L
    private var windowStart: Long? = null

    fun refused() {
        if (windowStart == null) windowStart = node.now()
        count++
    }

    /** Writes the row when the window has elapsed (or when [force] is set, at shutdown). Returns it, or null when nothing was due. */
    fun flush(force: Boolean = false): LabRouteRecord? {
        val start = windowStart ?: return null
        if (!force && node.now() - start < windowMs) return null
        val r = LabRouteRecord(
            ts = node.now(), callerPkg = "peer:unknown", requestedModel = "", egress = LabEgress.peerClass, status = 403,
            meshKind = MeshKind.INBOUND_REFUSED, bytesIn = 0, meshCode = "refused:$count",
        )
        node.append(r)
        count = 0
        windowStart = null
        return r
    }
}
