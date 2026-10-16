package xyz.mdhv.asom.lab.ledger

/**
 * What a transport can measure about one connection (LAB_SPEC 7.6). On JSSE the mesh transport drives an `SSLEngine` itself, so each
 * `wrap`/`unwrap` result gives exact network bytes and exact plaintext bytes. Both figures come from the ENGINE, never from the row
 * writer: overhead derived from the writer's own frame counts would make law L-L15 true by construction (R3-OVERCLAIM-3).
 */
interface TransportMeter {
    val basis: OverheadBasis

    /** Network bytes in both directions, from the engine's results. */
    fun networkBytes(): Long

    /** Application plaintext bytes in both directions, from the engine's results (`wrap` consumed, `unwrap` produced). */
    fun plaintextBytes(): Long

    /** Network bytes of the handshake flights, before the first application record. */
    fun handshakeNetworkBytes(): Long
}

object Overhead {
    /** 5-byte record header, 1-byte inner content type, 16-byte AEAD tag [F51]. */
    const val TLS_RECORD_OVERHEAD: Long = 22

    /** Largest TLS 1.3 plaintext per record (2^14). */
    const val MAX_PLAINTEXT: Long = 16_384

    /** [A36]: PROVISIONAL, used only for ESTIMATED overhead (R4-L-25: 8,192 bytes per session, not per direction). */
    const val HANDSHAKE_ESTIMATE_PER_SESSION: Long = 8_192

    /**
     * Per-stack record arithmetic of the ESTIMATED form (R4-L-25): [recordExpansion] bytes added to every record, at most [maxRecordPlaintext] plaintext
     * bytes in a record written in one go. [closeNotify] is the close_notify record where it was measured, else null.
     */
    class Stack(val label: String, val recordExpansion: Long, val maxRecordPlaintext: Long, val closeNotify: Long?)

    /** Nobody has measured this stack: the RFC 8446 minimum (22 bytes per record, 2^14 plaintext), which is a lower bound and says so. */
    val UNCALIBRATED = Stack("uncalibrated (RFC 8446 minimum)", TLS_RECORD_OVERHEAD, MAX_PLAINTEXT, null)

    /** JSSE 17.0.12 and 21.0.10 with TLS_AES_256_GCM_SHA384, measured under a record tap (LAB, ERR-PI-2). The cause of the 16 extra bytes was not examined. */
    val JSSE_17_21 = Stack("JSSE 17.0.12 / 21.0.10 TLS_AES_256_GCM_SHA384 (LAB)", 38, 16_367, 40)

    /** The ESTIMATED form per flush: `R x ceilDiv(appBytesOfFlush, Lmax)`. A stack nobody named is [UNCALIBRATED]. */
    fun estimatedRecords(flushAppBytes: List<Long>, stack: Stack = UNCALIBRATED): Long =
        flushAppBytes.sumOf { stack.recordExpansion * ceilDiv(it, stack.maxRecordPlaintext) }

    fun estimatedHandshake(): Long = HANDSHAKE_ESTIMATE_PER_SESSION

    /** The whole ESTIMATED figure of a session: the records of every flush in either direction plus the handshake allowance. */
    fun estimated(flushAppBytes: List<Long>, stack: Stack = UNCALIBRATED): Long = estimatedRecords(flushAppBytes, stack) + estimatedHandshake()

    /** Lab tolerance for the handshake and alerts of one direction when checking a MEASURED figure against the RFC 8446 bound. */
    const val HANDSHAKE_TOLERANCE_PER_DIRECTION: Long = 16_384

    /**
     * The independent plausibility check of R3-OVERCLAIM-3: a MEASURED overhead must lie between the RFC 8446 record cost of the
     * application bytes (one 22-byte record cost per 16,384 plaintext bytes at least) and the record cost of one record per flush plus
     * a generous handshake allowance. It is checked against the plaintext tap, not against the rows.
     */
    fun withinRecordBound(overhead: Long, appBytesOut: Long, appBytesIn: Long, flushesOut: Int, flushesIn: Int): Boolean {
        val lower = TLS_RECORD_OVERHEAD * (ceilDiv(appBytesOut, MAX_PLAINTEXT) + ceilDiv(appBytesIn, MAX_PLAINTEXT))
        val upper = TLS_RECORD_OVERHEAD * (ceilDiv(appBytesOut, MAX_PLAINTEXT) + ceilDiv(appBytesIn, MAX_PLAINTEXT) + flushesOut + flushesIn) +
            2 * HANDSHAKE_TOLERANCE_PER_DIRECTION
        return overhead in lower..upper
    }
}
