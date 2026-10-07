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

    /** [A36]: an unmeasured figure, PROVISIONAL, used only for ESTIMATED overhead. */
    const val HANDSHAKE_ESTIMATE_PER_DIRECTION: Long = 4_096

    /** The ESTIMATED form: `22 x ceilDiv(appBytesOfFlush, 16384)` per flush, plus 4,096 bytes per direction for the handshake. */
    fun estimatedRecords(flushAppBytes: List<Long>): Long = flushAppBytes.sumOf { TLS_RECORD_OVERHEAD * ceilDiv(it, MAX_PLAINTEXT) }

    fun estimatedHandshake(): Long = 2 * HANDSHAKE_ESTIMATE_PER_DIRECTION

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
