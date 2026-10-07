package xyz.mdhv.asom.lab.ledger

/** Which rows a frame produces (LAB_SPEC 7.6). */
enum class LedgerClass {
    /** One CONTROL row per frame on each node. */
    CONTROL,

    /** `MANIFEST_SENT` on the sender, `MANIFEST_RECEIVED` on the receiver. */
    MANIFEST,

    /** One PAIRING row per frame on each node. */
    PAIRING,

    /** One REVOCATION row per frame on each node. */
    REVOCATION,

    /** Covered by the attempt's intent and outcome rows (laws L-L1..L-L5b, L-L13, L-L14), not by per-frame rows. */
    ATTEMPT,

    /** An ignorable extension frame (type 0x80..0xFF): received only, CONTROL `EXT_IGNORED`. */
    EXTENSION,
}

/** The mesh-1 frame types this module reasons about (LAB_SPEC 7.2). The codec itself is `:mesh-proto`'s. */
enum class FrameKind(val type: Int, val cls: LedgerClass) {
    HELLO(0x01, LedgerClass.CONTROL),
    HELLO_ACK(0x02, LedgerClass.CONTROL),
    GOAWAY(0x05, LedgerClass.CONTROL),
    ERROR(0x06, LedgerClass.CONTROL),
    INFER_OFFER(0x10, LedgerClass.ATTEMPT),
    INFER_ACCEPT(0x11, LedgerClass.ATTEMPT),
    INFER_DECLINE(0x12, LedgerClass.ATTEMPT),
    INFER_BODY(0x13, LedgerClass.ATTEMPT),
    INFER_HEAD(0x14, LedgerClass.ATTEMPT),
    INFER_CHUNK(0x15, LedgerClass.ATTEMPT),
    INFER_END(0x16, LedgerClass.ATTEMPT),
    CANCEL(0x17, LedgerClass.ATTEMPT),
    STATE_REQ(0x20, LedgerClass.CONTROL),
    STATE(0x21, LedgerClass.CONTROL),
    MANIFEST_REQ(0x22, LedgerClass.CONTROL),
    MANIFEST(0x23, LedgerClass.MANIFEST),
    PAIR_HELLO(0x30, LedgerClass.PAIRING),
    PAIR_CHALLENGE(0x31, LedgerClass.PAIRING),
    PAIR_DECISION(0x32, LedgerClass.PAIRING),
    PAIR_COMMIT(0x33, LedgerClass.PAIRING),
    PAIR_COMMIT_ACK(0x34, LedgerClass.PAIRING),
    REVOKE_NOTICE(0x40, LedgerClass.REVOCATION),
    EXTENSION(0x80, LedgerClass.EXTENSION),
    ;

    /** An `ERROR` frame that carries an `attemptId` belongs to the attempt, not to the control plane. */
    fun classFor(attemptId: String?): LedgerClass = if (this == ERROR && attemptId != null) LedgerClass.ATTEMPT else cls

    val isAttemptFrame: Boolean get() = cls == LedgerClass.ATTEMPT

    /** Content-bearing frames: what "no content bytes after a failure" (L-L13) is about. */
    val carriesContent: Boolean get() = this == INFER_BODY || this == INFER_CHUNK || this == MANIFEST
}

/**
 * A frame as the ledger sees it. There is deliberately NO request id member: no frame has a field for it (law L-L7).
 * `code` carries the closed-enum detail that names the row: the `ERROR` code, the `GOAWAY` reason, or the manifest verdict.
 * `joinId` is the session id a `HELLO` (its `sessionNonce`) or a `PAIR_HELLO` (the derived id) announces.
 */
data class FrameSpec(
    val kind: FrameKind,
    val payloadLen: Int,
    val stream: Int = 0,
    val attemptId: String? = null,
    val code: String? = null,
    val joinId: String? = null,
) {
    init {
        require(payloadLen >= 0) { "negative payload" }
        require(kind != FrameKind.EXTENSION || payloadLen >= 0)
    }

    /** Application bytes of a frame: the 9-byte header (length, type, stream) plus the payload (LAB_SPEC 7.1). */
    val appBytes: Long get() = AppBytes.frameBytes(payloadLen)

    val ledgerClass: LedgerClass get() = kind.classFor(attemptId)
}

object AppBytes {
    const val HEADER: Long = 9

    fun frameBytes(payloadLen: Int): Long = HEADER + payloadLen
}

/** How the row writer counts one frame. A seam so that a law test can plug in a miscounting MUTANT (R3-OVERCLAIM-3). */
fun interface ByteCounter {
    fun frameBytes(frame: FrameSpec): Long

    companion object {
        val EXACT: ByteCounter = ByteCounter { it.appBytes }
    }
}

/**
 * The row a per-frame class produces (L02). `meshCode` names the frame (`ERROR:<code>`, `GOAWAY:<reason>`), except for a manifest
 * received, whose `meshCode` is the verdict.
 */
data class RowShape(val meshKind: MeshKind, val meshCode: String)

/**
 * The closed sets a CONTROL row's `meshCode` may draw from (LAB_SPEC 7.2). `ERROR` and `GOAWAY` codes arrive from the peer, so the row writer
 * stores a member of these sets or the fallback, never the received text (ERRATA ERR-FX-2).
 */
object ClosedCodes {
    const val UNKNOWN_ERROR = "UNKNOWN"
    const val UNKNOWN_REASON = "unknown"

    /** `<MeshError>` of LAB_SPEC 7.2 plus `UNKNOWN`, the stored form of a code outside the set. */
    val MESH_ERRORS: Set<String> = linkedSetOf(
        "PEER_NOT_PAIRED", "SCOPE_DENIED", "PROTOCOL_ERROR", "VERSION_UNSUPPORTED", "FRAME_TOO_LARGE", "DUPLICATE_ATTEMPT", "CLOCK_SKEW", "MODEL_NOT_OFFERED",
        "PEER_BUSY", "PEER_UNAVAILABLE", "MANIFEST_UNAVAILABLE", "PAIRING_WINDOW_CLOSED", "PAIRING_PROOF_INVALID", "PAIRING_REFUSED", UNKNOWN_ERROR,
    )

    /** The `GOAWAY` reasons of LAB_SPEC 7.2 plus `unknown`. */
    val GOAWAY_REASONS: Set<String> = linkedSetOf("revoked", "suspended", "shutdown", "network-change", "idle", "max-age", UNKNOWN_REASON)

    private val FRAME_NAMES = setOf("HELLO", "HELLO_ACK", "STATE_REQ", "STATE", "MANIFEST_REQ", "EXT_IGNORED")

    fun errorCode(raw: String?): String = if (raw != null && raw in MESH_ERRORS) raw else UNKNOWN_ERROR

    fun goawayReason(raw: String?): String = if (raw != null && raw in GOAWAY_REASONS) raw else UNKNOWN_REASON

    /** True when [code] is a `meshCode` a CONTROL row may carry: a frame name, `ERROR:<MeshError>` or `GOAWAY:<reason>`. */
    fun isControlCode(code: String?): Boolean = when {
        code == null -> false
        code in FRAME_NAMES -> true
        code.startsWith("ERROR:") -> code.substring(6) in MESH_ERRORS
        code.startsWith("GOAWAY:") -> code.substring(7) in GOAWAY_REASONS
        else -> false
    }
}

object FrameRows {
    /** Frames of the "yes" rows of the L-L16 table: exactly one row on each node that sent or received them. */
    val L16_KINDS: List<String> = listOf(
        "HELLO", "HELLO_ACK", "STATE_REQ", "STATE", "MANIFEST_REQ", "MANIFEST", "GOAWAY", "ERROR", "REVOKE_NOTICE",
        "PAIR_HELLO", "PAIR_CHALLENGE", "PAIR_DECISION", "PAIR_COMMIT", "PAIR_COMMIT_ACK", "EXT_IGNORED",
    )

    /** The L-L16 type name of a frame event: connection-level `ERROR` counts as `ERROR`; an extension frame as `EXT_IGNORED`. */
    fun l16Name(f: FrameSpec): String? = when (f.ledgerClass) {
        LedgerClass.ATTEMPT -> null
        LedgerClass.EXTENSION -> "EXT_IGNORED"
        else -> f.kind.name
    }

    /** The per-frame row's shape, or null for attempt frames (covered by attempt rows). */
    fun shape(f: FrameSpec, sent: Boolean): RowShape? = when (f.ledgerClass) {
        LedgerClass.ATTEMPT -> null
        LedgerClass.EXTENSION -> {
            require(!sent) { "mesh-1 senders never send an extension frame" }
            RowShape(MeshKind.CONTROL, "EXT_IGNORED")
        }
        LedgerClass.CONTROL -> RowShape(
            MeshKind.CONTROL,
            when (f.kind) {
                FrameKind.ERROR -> "ERROR:${ClosedCodes.errorCode(f.code)}"
                FrameKind.GOAWAY -> "GOAWAY:${ClosedCodes.goawayReason(f.code)}"
                else -> f.kind.name
            },
        )
        LedgerClass.MANIFEST -> if (sent) RowShape(MeshKind.MANIFEST_SENT, "MANIFEST") else RowShape(MeshKind.MANIFEST_RECEIVED, f.code ?: "unverified")
        LedgerClass.PAIRING -> RowShape(MeshKind.PAIRING, f.kind.name)
        LedgerClass.REVOCATION -> RowShape(MeshKind.REVOCATION, "REVOKE_NOTICE")
    }
}
