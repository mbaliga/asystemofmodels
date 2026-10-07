package xyz.mdhv.asom.lab.proto.session

import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.ledger.Overhead
import xyz.mdhv.asom.lab.ledger.OverheadBasis
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.wire.FrameTypes
import xyz.mdhv.asom.lab.proto.wire.GoAway
import xyz.mdhv.asom.lab.proto.wire.Message
import xyz.mdhv.asom.lab.proto.wire.PairMsg
import xyz.mdhv.asom.lab.proto.wire.PeerError
import xyz.mdhv.asom.lab.proto.wire.RevokeNotice

/** Thrown after FC-2 has run: the control row was not durable, the session is closed, and nothing further may be sent on it. */
internal class FailClosed : RuntimeException("control row not durable; session closed (FC-2)", null, false, false)

/** The row a per-frame class produces, by LAB_SPEC 7.6. `null` for the frames that attempt rows cover. */
internal class Shape(val kind: MeshKind, val code: String)

internal object RowTable {
    /** The CONTROL, MANIFEST, PAIRING or REVOCATION row of a frame that is sent or received, or null for an attempt frame. [verdict] names a received manifest. */
    fun of(msg: Message, sent: Boolean, verdict: String? = null): Shape? = when (msg) {
        is PeerError -> if (msg.attemptId != null) null else Shape(MeshKind.CONTROL, "ERROR:${msg.code.name}")
        is GoAway -> Shape(MeshKind.CONTROL, "GOAWAY:${msg.reason.wire}")
        is RevokeNotice -> Shape(MeshKind.REVOCATION, "REVOKE_NOTICE")
        is PairMsg -> Shape(MeshKind.PAIRING, FrameTypes.nameOf(msg.type))
        else -> when (msg.type) {
            FrameTypes.HELLO, FrameTypes.HELLO_ACK, FrameTypes.STATE_REQ, FrameTypes.STATE, FrameTypes.MANIFEST_REQ ->
                Shape(MeshKind.CONTROL, FrameTypes.nameOf(msg.type))
            FrameTypes.MANIFEST -> if (sent) Shape(MeshKind.MANIFEST_SENT, "MANIFEST") else Shape(MeshKind.MANIFEST_RECEIVED, verdict ?: "UNVERIFIED")
            else -> null
        }
    }
}

/**
 * Every row of one session, written to this node's ledger. [control] is the durability point of a per-frame row: it returns only after the append returned,
 * and a failure runs FC-2 through [onControlFailure] and throws [FailClosed], so the caller sends nothing. The SESSION close row carries every application
 * byte that no other row of the session covers (a refused frame, input read after a failure), so that the rows of a session always add up to the plaintext
 * that crossed the stream.
 */
internal class SessionRows(
    private val node: NodeLedger,
    var sessionId: String,
    var joinId: String?,
    private val peerTag: String,
    private val peerPath: PeerPath?,
    private val wire: Wire,
    var handshakeRecorded: Long,
    private val onControlFailure: () -> Unit,
) {
    private var coveredOut = 0L
    private var coveredIn = 0L
    var opened = false
        private set
    var closeWritten = false
        private set

    val callerPkg: String get() = "peer:$peerTag"

    val tag: String get() = peerTag

    val path: PeerPath? get() = peerPath

    fun ledger(): NodeLedger = node

    fun frameRow(kind: MeshKind, code: String, out: Long = 0, inn: Long = 0, routeDetail: String? = null, status: Int = 200): LabRouteRecord = LabRouteRecord(
        ts = node.now(), callerPkg = callerPkg, requestedModel = "", egress = LabEgress.peerClass, bytesOut = out, status = status, attemptId = joinId,
        peerNode = peerTag, peerPath = peerPath, meshKind = kind, bytesIn = inn, meshCode = code, routeDetail = routeDetail, sessionId = sessionId,
    )

    private fun track(r: LabRouteRecord) {
        coveredOut += r.bytesOut
        coveredIn += r.bytesIn ?: 0
    }

    /** A per-frame row. Returns when it is durable; on failure FC-2 has already run and [FailClosed] is thrown. */
    fun control(r: LabRouteRecord) {
        try {
            node.append(r)
        } catch (e: LedgerWriteException) {
            onControlFailure()
            throw FailClosed()
        }
        track(r)
    }

    /** An attempt row. The caller decides what a failure means (FC-1, FC-4, FC-5). */
    @Throws(LedgerWriteException::class)
    fun attempt(r: LabRouteRecord) {
        node.append(r)
        track(r)
    }

    /** The listener's `SESSION` open row, after the first frame that names the session and before any reply. */
    fun open(modeWire: String) {
        check(!opened) { "one SESSION open row per session" }
        val basis = if (wire.measured) OverheadBasis.MEASURED else OverheadBasis.ESTIMATED
        val overhead = if (wire.measured) wire.handshakeNetwork else Overhead.estimatedHandshake()
        val r = LabRouteRecord(
            ts = node.now(), callerPkg = callerPkg, requestedModel = "", egress = LabEgress.peerClass, status = 200, attemptId = joinId, peerNode = peerTag,
            peerPath = peerPath, meshKind = MeshKind.SESSION, bytesIn = 0, meshCode = modeWire, overheadBytes = overhead, overheadBasis = basis, sessionId = sessionId,
        )
        control(r)
        opened = true
        handshakeRecorded = overhead
    }

    /** The `SESSION` close row. Returns whether it is durable; a failure here changes nothing further (the join tool reports the session as closed by ledger failure). */
    fun close(code: String, status: Int): Boolean {
        if (closeWritten) return true
        closeWritten = true
        val basis = if (wire.measured) OverheadBasis.MEASURED else OverheadBasis.ESTIMATED
        val overhead = if (wire.measured) {
            (wire.networkNow() - (wire.input.count + wire.output.count) - handshakeRecorded).coerceAtLeast(0)
        } else {
            wire.estimatedRecordBytes
        }
        val r = LabRouteRecord(
            ts = node.now(), callerPkg = callerPkg, requestedModel = "", egress = LabEgress.peerClass, bytesOut = (wire.output.count - coveredOut).coerceAtLeast(0), status = status,
            attemptId = joinId, peerNode = peerTag, peerPath = peerPath, meshKind = MeshKind.SESSION, bytesIn = (wire.input.count - coveredIn).coerceAtLeast(0), meshCode = code,
            overheadBytes = overhead, overheadBasis = basis, sessionId = sessionId,
        )
        return try {
            node.append(r)
            true
        } catch (e: LedgerWriteException) {
            false
        }
    }

    fun intentBase(kind: MeshKind): LabRouteRecord = LabRouteRecord(
        ts = node.now(), callerPkg = callerPkg, requestedModel = "", egress = LabEgress.peerClass, phase = Phase.INTENT, peerNode = peerTag, peerPath = peerPath,
        meshKind = kind, sessionId = sessionId,
    )
}
