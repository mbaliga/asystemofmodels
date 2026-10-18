package xyz.mdhv.asom.lab.ledger

import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

/**
 * What the lender knows about one served attempt. `INFER_HEAD`, `INFER_END` and the lender's outcome row are all built from this one value
 * (Invariant 9 on the peer channel, law L-L12). No `usage`, `ttftMs` or `totalMs` exists here: they move with the lender's local use (LP-1).
 */
data class ServedRecord(val servedModel: String, val status: Int, val terminal: String, val tokensIn: Long?) {
    init {
        require(terminal in TERMINALS) { "terminal '$terminal' is not one of $TERMINALS" }
    }

    /** `done` has no code; every other terminal is its own upper-case code (CANCELLED, INTERRUPTED, OOM, ERROR). */
    val meshCode: String? get() = if (terminal == "done") null else terminal.uppercase()

    fun headPayload(attemptId: String): JObject = JObject(
        listOf("attemptId" to JString(attemptId), "engine" to JString("local"), "servedModel" to JString(servedModel), "status" to JInt(status.toLong())),
    )

    fun endPayload(attemptId: String): JObject = JObject(
        listOf("attemptId" to JString(attemptId), "status" to JInt(status.toLong()), "terminal" to JString(terminal)),
    )

    companion object {
        val TERMINALS = listOf("done", "cancelled", "interrupted", "oom", "error")
    }
}

/** The lab's `LEDGER_UNAVAILABLE` (CD-LU, D5): an intent row could not be made durable, so nothing was sent and no further candidate is tried. */
class LedgerUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** FC-2: the session was closed because a control row could not be appended. Nothing further is sent on it. */
class SessionClosedException(message: String) : Exception(message)

/** The frames leave through here: `send` hands the frame's bytes to the TLS engine, `close` sends `close_notify` and closes the socket. */
interface WireOut {
    /** Null when the stack cannot measure (the ESTIMATED form is used). */
    val meter: TransportMeter?

    fun send(frame: FrameSpec)

    fun close()
}

/** Which kind of network interface a socket's local address sits on (contract.md 4.6). */
enum class IfaceKind { WIFI, ETHERNET, OVERLAY, CELLULAR, OTHER }

object PeerPathRule {
    /**
     * `peerPath` comes from the connected socket's local interface, never guessed (contract.md 4.6). Anything else is refused before TLS
     * (trust.md 6.6), so no third value exists and this returns null for it.
     */
    fun classify(local: IfaceKind, isSelectedOverlay: Boolean, remoteIsPrivateOrLinkLocal: Boolean): PeerPath? = when {
        local == IfaceKind.OVERLAY && isSelectedOverlay -> PeerPath.OVERLAY
        (local == IfaceKind.WIFI || local == IfaceKind.ETHERNET) && remoteIsPrivateOrLinkLocal -> PeerPath.LAN
        else -> null
    }
}

/**
 * One node's ledger handle. It knows whether the last write failed (FC-2: "mark the ledger unavailable, so every further intent fails until
 * a write succeeds"): an intent append is always ATTEMPTED, so a write that succeeds clears the mark, and while the sink still fails every
 * intent fails with [LedgerUnavailableException].
 */
class NodeLedger(val nodeTag: String, private val sink: RowSink, private val clock: () -> Long) {
    var unavailable: Boolean = false
        private set

    fun now(): Long = clock()

    @Throws(LedgerWriteException::class)
    fun append(row: LabRouteRecord) {
        try {
            sink.append(row)
            unavailable = false
        } catch (e: LedgerWriteException) {
            unavailable = true
            throw e
        }
    }

    /** FC-1 helper: an intent append that maps any write failure to [LedgerUnavailableException]. */
    fun appendIntent(row: LabRouteRecord) {
        require(row.phase == Phase.INTENT) { "appendIntent takes an intent row" }
        try {
            append(row)
        } catch (e: LedgerWriteException) {
            throw LedgerUnavailableException("LEDGER_UNAVAILABLE", e)
        }
    }
}

enum class SessionRole { DIALER, LISTENER }

enum class SessionMode(val wire: String) { ESTABLISHED("established"), PAIRING("pairing") }

/** [connection] is the authenticated connection `connect()` made, if any: [dial] closes it when the DIAL outcome row cannot be made durable. */
class DialResult(val code: String, val meter: TransportMeter?, val peerPath: PeerPath? = null, val connection: AutoCloseable? = null)

/** The DIAL outcome codes of LAB_SPEC 7.6; anything else is refused before a row is written (ERRATA ERR-FX4-LG-5). */
object DialCodes {
    val ALL: Set<String> = linkedSetOf("connected", "refused", "timeout", "pin-mismatch", "not-tls", "local-network-denied", "firewall-blocked")
}

/**
 * The session side of the write-ahead rules of LAB_SPEC 7.6 and 7.7:
 *  - `send` appends the frame's row and only then hands the frame to the wire (durable before the first byte reaches the TLS engine);
 *  - `receive` appends the frame's row before it returns, so any reply is sent after the row is durable;
 *  - a control-row failure closes the session (FC-2) and sends nothing further, not even a `GOAWAY`.
 * Attempt frames go through [RequesterAttempt] and [LenderAttempt], which sum their application bytes into the attempt's outcome row.
 */
class SessionLedger(
    val node: NodeLedger,
    val sessionId: String,
    val mode: SessionMode,
    val role: SessionRole,
    private val peerNode: String?,
    private val peerPath: PeerPath?,
    private val wire: WireOut,
    private val counter: ByteCounter = ByteCounter.EXACT,
    private var handshakeRecorded: Long = 0,
) {
    /** The cross-node join id carried in `attemptId` on session-scoped rows (7.5): the HELLO nonce, or the derived pairing id. */
    var joinId: String? = if (mode == SessionMode.ESTABLISHED) sessionId else null

    var closed: Boolean = false
        private set
    var closedByLedgerFailure: Boolean = false
        private set
    private val flushOut = ArrayList<Long>()
    private val flushIn = ArrayList<Long>()
    private val lenderAttempts = LinkedHashMap<String, LenderAttempt>()
    private val requesterAttempts = LinkedHashMap<String, RequesterAttempt>()
    private var totalOut = 0L
    private var totalIn = 0L
    private var coveredOut = 0L
    private var coveredIn = 0L

    private fun callerPkg(): String = "peer:${peerNode ?: "unknown"}"

    internal fun row(kind: MeshKind, code: String?, phase: Phase? = null, bytesOut: Long = 0, bytesIn: Long? = 0, status: Int = 200,
                     overhead: Long? = null, basis: OverheadBasis? = null): LabRouteRecord =
        LabRouteRecord(
            ts = node.now(), callerPkg = callerPkg(), requestedModel = "", egress = LabEgress.peerClass, bytesOut = bytesOut,
            latencyMs = 0, status = status, attemptId = joinId, phase = phase, peerNode = peerNode, peerPath = peerPath, meshKind = kind,
            bytesIn = bytesIn, meshCode = code, overheadBytes = overhead, overheadBasis = basis, sessionId = sessionId,
        )

    /** The listener writes `SESSION` open after the first frame that names the session has arrived and before any reply (R3-CLOSURE-6). */
    fun open(): LabRouteRecord {
        check(role == SessionRole.LISTENER && !closed) { "only a listener opens a session row" }
        val meter = wire.meter
        val basis = meter?.basis ?: OverheadBasis.ESTIMATED
        val overhead = if (meter != null && basis == OverheadBasis.MEASURED) meter.handshakeNetworkBytes() else Overhead.estimatedHandshake()
        val r = row(MeshKind.SESSION, mode.wire, overhead = overhead, basis = basis)
        appendControl(r)
        handshakeRecorded = overhead
        return r
    }

    /** A row of this session became durable: the application bytes it holds are no longer the SESSION close row's to carry. */
    internal fun cover(r: LabRouteRecord) {
        coveredOut += r.bytesOut
        coveredIn += r.bytesIn ?: 0
    }

    internal fun appendControl(r: LabRouteRecord) {
        try {
            node.append(r)
            cover(r)
        } catch (e: LedgerWriteException) {
            failClosed()
            throw SessionClosedException("control row not durable; session closed (FC-2): ${e.message}")
        }
    }

    /** FC-2: nothing further on the session; close the TLS connection and the socket; attempt the SESSION close row. */
    internal fun failClosed() {
        if (closed) return
        closedByLedgerFailure = true
        closed = true
        wire.close()
        try {
            node.append(closeRow("close:ledger-failure", 503))
        } catch (_: LedgerWriteException) {
            // The join tool reports the session as "closed by ledger failure".
        }
    }

    /**
     * The SESSION close row carries every application byte that no other row of the session holds (a frame of an attempt that never got its
     * outcome row, input read after a failure), so the rows of a session always add up to the plaintext that crossed it (law L-L15).
     */
    private fun closeRow(code: String, status: Int): LabRouteRecord {
        val meter = wire.meter
        val basis = meter?.basis ?: OverheadBasis.ESTIMATED
        val overhead = if (meter != null && basis == OverheadBasis.MEASURED) {
            meter.networkBytes() - meter.plaintextBytes() - handshakeRecorded
        } else {
            Overhead.estimatedRecords(flushOut + flushIn)
        }
        return row(
            MeshKind.SESSION, code, bytesOut = (totalOut - coveredOut).coerceAtLeast(0), bytesIn = (totalIn - coveredIn).coerceAtLeast(0),
            overhead = overhead, basis = basis, status = status,
        )
    }

    internal fun handOff(frame: FrameSpec) {
        if (closed) throw SessionClosedException("session closed")
        wire.send(frame)
        val n = counter.frameBytes(frame)
        flushOut += n
        totalOut += n
    }

    internal fun countReceived(frame: FrameSpec) {
        val n = counter.frameBytes(frame)
        flushIn += n
        totalIn += n
    }

    fun send(frame: FrameSpec) {
        if (closed) throw SessionClosedException("session closed")
        require(frame.ledgerClass != LedgerClass.ATTEMPT) { "attempt frames go through an attempt" }
        val shape = FrameRows.shape(frame, sent = true)!!
        appendControl(row(shape.meshKind, shape.meshCode, bytesOut = counter.frameBytes(frame), bytesIn = 0))
        handOff(frame)
    }

    /** The row is durable when this returns; the caller may now reply. */
    fun receive(frame: FrameSpec) {
        if (closed) throw SessionClosedException("session closed")
        require(frame.ledgerClass != LedgerClass.ATTEMPT) { "attempt frames go through an attempt" }
        if (frame.joinId != null && joinId == null) joinId = frame.joinId
        val shape = FrameRows.shape(frame, sent = false)!!
        appendControl(row(shape.meshKind, shape.meshCode, bytesOut = 0, bytesIn = counter.frameBytes(frame)))
        countReceived(frame)
    }

    /**
     * Session close (either side): every attempt still open gets its outcome row first (a lender's as `499 INTERRUPTED`, a requester's as
     * `599 PEER_UNREACHABLE`, the codes of the real session layer), then `SESSION` close with the uncovered application bytes, `overheadBytes`
     * and its basis. If one of those outcome rows cannot be made durable the session ends as a ledger failure (`close:ledger-failure`, 503).
     */
    fun close(code: String = "close"): LabRouteRecord? {
        if (closed) return null
        closed = true
        wire.close()
        var failed = false
        for (a in lenderAttempts.values.toList()) if (!a.abort()) failed = true
        for (a in requesterAttempts.values.toList()) if (!a.abort()) failed = true
        if (failed) closedByLedgerFailure = true
        val r = if (failed) closeRow("close:ledger-failure", 503) else closeRow(code, 200)
        try {
            node.append(r)
        } catch (_: LedgerWriteException) {
            closedByLedgerFailure = true
            return null
        }
        return r
    }

    fun requesterAttempt(requestId: String?, attemptId: String, index: Int, callerPkg: String, requestedModel: String): RequesterAttempt =
        RequesterAttempt(this, requestId, attemptId, index, callerPkg, requestedModel).also { requesterAttempts[attemptId] = it }

    fun lenderAttempt(attemptId: String, requestedModel: String): LenderAttempt =
        LenderAttempt(this, attemptId, requestedModel).also { lenderAttempts[attemptId] = it }

    internal val byteCounter: ByteCounter get() = counter
    internal val peer: String? get() = peerNode
    internal val path: PeerPath? get() = peerPath
    internal fun ledgerNode(): NodeLedger = node
    internal fun wireOut(): WireOut = wire
}

/**
 * Outbound TCP connect: the `DIAL` intent is durable BEFORE the SYN (law L-L14); the outcome carries the handshake overhead. The dialer
 * generates the session id before the intent (R3-CLOSURE-6), so the DIAL rows already name it. An intent failure means no SYN.
 */
fun NodeLedger.dial(
    sessionId: String,
    peerNode: String?,
    destAddr: String,
    addrSource: String,
    connect: () -> DialResult,
): DialOutcome {
    fun dialRow(phase: Phase, code: String?, status: Int, overhead: Long?, basis: OverheadBasis?, path: PeerPath? = null) = LabRouteRecord(
        ts = now(), callerPkg = "peer:${peerNode ?: "unknown"}", requestedModel = "", egress = LabEgress.peerClass, status = status,
        attemptId = sessionId, phase = phase, peerNode = peerNode, peerPath = path, meshKind = MeshKind.DIAL, bytesIn = if (phase == Phase.INTENT) null else 0,
        meshCode = code, destAddr = destAddr, addrSource = addrSource, overheadBytes = overhead, overheadBasis = basis, sessionId = sessionId,
    )
    appendIntent(dialRow(Phase.INTENT, null, 0, null, null))
    val result = connect()
    if (result.code !in DialCodes.ALL) {
        result.connection?.close()
        throw IllegalArgumentException("a DIAL outcome is one of ${DialCodes.ALL}, not '${result.code}'")
    }
    val ok = result.code == "connected"
    val meter = result.meter
    val basis = if (ok) (meter?.basis ?: OverheadBasis.ESTIMATED) else null
    val overhead = when {
        !ok -> null
        meter != null && basis == OverheadBasis.MEASURED -> meter.handshakeNetworkBytes()
        else -> Overhead.estimatedHandshake()
    }
    try {
        append(dialRow(Phase.OUTCOME, result.code, if (ok) 200 else 599, overhead, basis, if (ok) result.peerPath else null))
    } catch (e: LedgerWriteException) {
        result.connection?.close()
        throw LedgerUnavailableException("LEDGER_UNAVAILABLE", e)
    }
    return DialOutcome(result.code, overhead, basis)
}

/** What the DIAL outcome row recorded: the code and, for a connection, the handshake overhead and its basis. */
class DialOutcome(val code: String, val handshakeOverhead: Long?, val basis: OverheadBasis?)

/**
 * The requester's side of one peer attempt (design 8.4, contract.md 4.4): the intent row is durable before any byte of the attempt
 * (D1), the outcome row is written after the last frame with the attempt's frame bytes summed per direction (D2).
 */
class RequesterAttempt internal constructor(
    private val session: SessionLedger,
    private val requestId: String?,
    val attemptId: String,
    private val index: Int,
    private val callerPkg: String,
    private val requestedModel: String,
) {
    private var begun = false
    private var finished = false
    private var startTs = 0L
    private var bytesOut = 0L
    private var bytesIn = 0L
    private var claimedModel: String? = null

    /** True once `INFER_BODY` was handed to the wire: content reached the peer, so reach rises to peer (E-3: an offer alone does not). */
    var contentSent: Boolean = false
        private set

    /**
     * What the peer called the serving model in its `INFER_HEAD`: a CLAIM (trust.md 11.1), written on the outcome row as `peer:<nodeTag>` /
     * claimed model and used for the echo header. Null until a head arrived.
     */
    val servedBy: Pair<String, String>? get() = claimedModel?.let { "peer:${session.peer ?: "unknown"}" to it }

    private fun base(phase: Phase, status: Int, bytesOut: Long, bytesIn: Long?, code: String?, tokensOut: Long?) = LabRouteRecord(
        ts = session.ledgerNode().now(), callerPkg = callerPkg, requestedModel = requestedModel,
        servedProvider = if (phase == Phase.OUTCOME) servedBy?.first else null, servedModel = if (phase == Phase.OUTCOME) servedBy?.second else null,
        egress = LabEgress.peerClass, bytesOut = bytesOut, tokensOut = tokensOut, latencyMs = if (phase == Phase.OUTCOME) session.ledgerNode().now() - startTs else 0,
        status = status, requestId = requestId, attemptId = attemptId, phase = phase, attemptIndex = index, peerNode = session.peer,
        peerPath = session.path, meshKind = MeshKind.INFER_SENT, bytesIn = bytesIn, meshCode = code, sessionId = session.sessionId,
    )

    /** FC-1: on failure nothing has been sent, and the caller tries no further peer or cloud candidate. */
    fun begin() {
        check(!begun) { "intent already written" }
        startTs = session.ledgerNode().now()
        session.ledgerNode().appendIntent(base(Phase.INTENT, 0, 0, null, null, null))
        begun = true
    }

    fun send(frame: FrameSpec) {
        check(begun) { "no byte of an attempt leaves before its intent row is durable" }
        check(!finished) { "nothing of an attempt leaves after its outcome row: those bytes would be in no row" }
        require(frame.ledgerClass == LedgerClass.ATTEMPT && frame.attemptId == attemptId) { "not a frame of this attempt" }
        session.handOff(frame)
        bytesOut += session.byteCounter.frameBytes(frame)
        if (frame.kind == FrameKind.INFER_BODY) contentSent = true
    }

    /**
     * A frame of this attempt arrived. [claimedModel] is the `servedModel` of an `INFER_HEAD`. After the outcome row a late frame is counted by
     * the session (its close row carries the bytes), not by the attempt.
     */
    fun receive(frame: FrameSpec, claimedModel: String? = null) {
        check(begun) { "frame before intent" }
        require(frame.ledgerClass == LedgerClass.ATTEMPT && frame.attemptId == attemptId) { "not a frame of this attempt" }
        session.countReceived(frame)
        if (finished) return
        bytesIn += session.byteCounter.frameBytes(frame)
        if (frame.kind == FrameKind.INFER_HEAD && claimedModel != null) this.claimedModel = claimedModel
    }

    /** `tokensOut` is the requester's own estimate (6.6 `outTokEst`); the peer's claim is never written. */
    fun finish(status: Int, meshCode: String?, tokensOut: Long?): LabRouteRecord {
        check(begun && !finished) { "one outcome per attempt, after its intent" }
        val r = base(Phase.OUTCOME, status, bytesOut, bytesIn, meshCode, tokensOut)
        session.ledgerNode().append(r)
        finished = true
        session.cover(r)
        return r
    }

    /** The session ended with this attempt open: its outcome row, `599 PEER_UNREACHABLE` (RequesterSide.abortAll). False when the row is not durable. */
    internal fun abort(): Boolean {
        if (!begun || finished) return true
        return try {
            finish(599, "PEER_UNREACHABLE", null)
            true
        } catch (_: LedgerWriteException) {
            false
        }
    }
}

/** The lender's side of one attempt: a decline gets an outcome only; a served attempt gets an intent after the body arrives and an outcome before `INFER_END`. */
class LenderAttempt internal constructor(
    private val session: SessionLedger,
    val attemptId: String,
    private val requestedModel: String,
) {
    private var bytesIn = 0L
    private var bytesOut = 0L
    private var startTs = 0L
    private var offered = false
    private var accepted = false
    private var intentWritten = false
    private var outcomeWritten = false

    /** True once the body arrived and the lender intent row is durable: the attempt then ends with [finish], before that with [finishWithoutBody]. */
    val hasIntent: Boolean get() = intentWritten

    private fun base(phase: Phase, status: Int, bytesOut: Long, bytesIn: Long?, code: String?, servedModel: String?, tokensIn: Long? = null) = LabRouteRecord(
        ts = session.ledgerNode().now(), callerPkg = "peer:${session.peer ?: "unknown"}", requestedModel = requestedModel,
        servedProvider = if (servedModel != null) "local" else null, servedModel = servedModel, egress = LabEgress.peerClass,
        bytesOut = bytesOut, tokensIn = tokensIn, latencyMs = if (phase == Phase.OUTCOME) session.ledgerNode().now() - startTs else 0,
        status = status, attemptId = attemptId, phase = phase, peerNode = session.peer, peerPath = session.path,
        meshKind = MeshKind.INFER_SERVED, bytesIn = bytesIn, meshCode = code, sessionId = session.sessionId,
    )

    fun receiveOffer(frame: FrameSpec) {
        require(frame.kind == FrameKind.INFER_OFFER && frame.attemptId == attemptId)
        offered = true
        startTs = session.ledgerNode().now()
        session.countReceived(frame)
        bytesIn += session.byteCounter.frameBytes(frame)
    }

    /**
     * A decline gets an outcome row only (503 + the decline code), durable before the `INFER_DECLINE` is sent. If that append fails, FC-2 applies
     * and nothing is sent.
     */
    fun decline(frame: FrameSpec) {
        require((frame.kind == FrameKind.INFER_DECLINE || frame.kind == FrameKind.ERROR) && frame.attemptId == attemptId)
        check(!outcomeWritten) { "one outcome per attempt" }
        val out = bytesOut + session.byteCounter.frameBytes(frame)
        val r = base(Phase.OUTCOME, 503, out, bytesIn, frame.code, null)
        session.appendControl(r)
        outcomeWritten = true
        bytesOut = out
        session.handOff(frame)
    }

    /** A frame of this attempt that only needs counting (a `CANCEL` from the requester). */
    fun receiveOther(frame: FrameSpec) {
        require(frame.ledgerClass == LedgerClass.ATTEMPT && frame.attemptId == attemptId)
        session.countReceived(frame)
        bytesIn += session.byteCounter.frameBytes(frame)
    }

    fun accept(frame: FrameSpec) {
        require(frame.kind == FrameKind.INFER_ACCEPT && frame.attemptId == attemptId)
        session.handOff(frame)
        bytesOut += session.byteCounter.frameBytes(frame)
        accepted = true
    }

    /**
     * `INFER_BODY` arrived. The intent row is durable before the engine may read it: returns true when it may. FC-4: if the append fails, the lender declines
     * `PEER_UNAVAILABLE` (its own row is the next write; if that fails too, FC-2) and the engine never starts (false).
     */
    fun receiveBody(frame: FrameSpec, declineFrame: () -> FrameSpec): Boolean {
        require(frame.kind == FrameKind.INFER_BODY && frame.attemptId == attemptId)
        session.countReceived(frame)
        bytesIn += session.byteCounter.frameBytes(frame)
        return try {
            session.ledgerNode().append(base(Phase.INTENT, 0, 0, null, null, null))
            intentWritten = true
            true
        } catch (_: LedgerWriteException) {
            decline(declineFrame())
            false
        }
    }

    fun send(frame: FrameSpec) {
        check(intentWritten && !outcomeWritten) { "head and chunks follow the intent and precede the outcome" }
        session.handOff(frame)
        bytesOut += session.byteCounter.frameBytes(frame)
    }

    /**
     * The outcome row is durable BEFORE `INFER_END` is written (law L-L3), and its bytes already include the `INFER_END` frame, whose size is
     * known before the send. The row's status, served model and code all come from [served], the same value that builds the `INFER_HEAD`
     * and `INFER_END` payloads (law L-L12). FC-5: if the append fails, no `INFER_END` is sent, the engine stream is cancelled locally, and
     * the session is closed (FC-2). No `CANCEL` frame is sent: `CANCEL` is a requester-to-lender frame (7.2), see ERRATA ERR-LL-6.
     */
    fun finish(end: FrameSpec, served: ServedRecord): Boolean {
        check(intentWritten && !outcomeWritten) { "one outcome per served attempt, after its intent" }
        return writeOutcome(end, served, withModel = true)
    }

    /**
     * An accepted attempt that ends before its body arrived: the requester cancelled it (`499 cancelled`) or the body did not come in time (`408 error`).
     * The outcome row has no intent before it (no content was processed), names no served model, and is durable before the `INFER_END` is written
     * (L-L3), exactly as the real lender does (`LenderSide.finish` on an accepted attempt, ERRATA ERR-FX4-LG-3).
     */
    fun finishWithoutBody(end: FrameSpec, served: ServedRecord): Boolean {
        check(accepted && !intentWritten && !outcomeWritten) { "an attempt ends without a body only after its accept and before its body" }
        return writeOutcome(end, served, withModel = false)
    }

    private fun writeOutcome(end: FrameSpec, served: ServedRecord, withModel: Boolean): Boolean {
        require(end.kind == FrameKind.INFER_END && end.attemptId == attemptId)
        val out = bytesOut + session.byteCounter.frameBytes(end)
        val r = base(Phase.OUTCOME, served.status, out, bytesIn, served.meshCode, if (withModel) served.servedModel else null, served.tokensIn)
        try {
            session.ledgerNode().append(r)
        } catch (_: LedgerWriteException) {
            session.failClosed()
            return false
        }
        outcomeWritten = true
        bytesOut = out
        session.cover(r)
        session.handOff(end)
        return true
    }

    /** The session ended with this attempt open (no `INFER_END` can follow): `499 INTERRUPTED` (LenderSide.abortAll). False when the row is not durable. */
    internal fun abort(): Boolean {
        if (!offered || outcomeWritten) return true
        val r = base(Phase.OUTCOME, 499, bytesOut, bytesIn, "INTERRUPTED", if (intentWritten) requestedModel else null)
        try {
            session.ledgerNode().append(r)
        } catch (_: LedgerWriteException) {
            return false
        }
        outcomeWritten = true
        session.cover(r)
        return true
    }
}

/**
 * The request-level rows of one app request (LAB_SPEC 7.5): the attempts are tracked, and the ONE terminal row (P7, L-L5b) carries
 * `reach` (the furthest class that received CONTENT, local < peer < cloud), `servedClass` and `egress = reach`, from which the echo headers
 * are built.
 */
class RequestLedger(
    private val node: NodeLedger,
    val requestId: String,
    private val callerPkg: String,
    private val requestedModel: String,
) {
    class Attempt(val cls: LabEgress, val contentSent: Boolean, val served: Boolean, val provider: String?, val model: String?)

    private val attempts = ArrayList<Attempt>()
    private var terminalWritten = false

    fun note(cls: LabEgress, contentSent: Boolean, served: Boolean, provider: String? = null, model: String? = null) {
        require(cls.reachRank != null) { "$cls carries no request content" }
        attempts += Attempt(cls, contentSent, served, provider, model)
    }

    /**
     * A peer attempt, read from the attempt itself: whether content reached the peer is [RequesterAttempt.contentSent], and when it served, the
     * header's provider and model are `peer:<nodeTag>` and the model the peer CLAIMED in its `INFER_HEAD` (trust.md 11.1), the same pair the
     * attempt's outcome row carries (ERRATA ERR-FX4-LG-7), never a string the caller makes up.
     */
    fun notePeer(attempt: RequesterAttempt, served: Boolean) {
        val by = attempt.servedBy
        require(!served || by != null) { "a peer-served attempt has the INFER_HEAD claim of the model that served it" }
        note(LabEgress.peerClass, attempt.contentSent, served, if (served) by!!.first else null, if (served) by!!.second else null)
    }

    /** A cloud attempt of this request: intent row before the prompt is transmitted, outcome row after (contract.md 4.11 H1; ERRATA ERR-FX4-LG-8). */
    fun cloudAttempt(attemptId: String, index: Int, provider: String): CloudAttempt =
        CloudAttempt(node, requestId, callerPkg, requestedModel, attemptId, index, provider)

    fun reach(): LabEgress = LabEgress.reachOf(attempts.filter { it.contentSent }.map { it.cls })

    fun terminal(status: Int, tokensIn: Long? = null, tokensOut: Long? = null, costEst: Double? = null, costBasis: String = "none", bytesOut: Long = 0): LabRouteRecord {
        check(!terminalWritten) { "exactly one terminal row per request" }
        val served = attempts.singleOrNull { it.served }
        val reach = reach()
        val r = LabRouteRecord(
            ts = node.now(), callerPkg = callerPkg, requestedModel = requestedModel, servedProvider = served?.provider, servedModel = served?.model,
            egress = reach, bytesOut = bytesOut, tokensIn = tokensIn, tokensOut = tokensOut, costEst = costEst, costBasis = costBasis,
            status = status, requestId = requestId, reach = reach, terminal = true, servedClass = served?.cls,
        )
        node.append(r)
        terminalWritten = true
        return r
    }
}

/**
 * One cloud attempt (contract.md 4.11 H1): the intent row is durable BEFORE the prompt is transmitted, and the outcome row after the call returned
 * or failed, naming the provider either way, so that a failed attempt still says which provider received the content and a death in between leaves
 * an intent without an outcome ("outcome unknown"). The rows are request rows (`meshKind` null) with `egress = cloud`; they are not terminal rows.
 */
class CloudAttempt internal constructor(
    private val node: NodeLedger,
    private val requestId: String,
    private val callerPkg: String,
    private val requestedModel: String,
    val attemptId: String,
    private val index: Int,
    val provider: String,
) {
    private var begun = false
    private var finished = false
    private var startTs = 0L

    private fun row(phase: Phase, status: Int, bytesOut: Long, servedModel: String?, tokensIn: Long?, tokensOut: Long?) = LabRouteRecord(
        ts = node.now(), callerPkg = callerPkg, requestedModel = requestedModel, servedProvider = provider, servedModel = servedModel, egress = LabEgress.CLOUD,
        bytesOut = bytesOut, tokensIn = tokensIn, tokensOut = tokensOut, latencyMs = if (phase == Phase.OUTCOME) node.now() - startTs else 0, status = status,
        requestId = requestId, attemptId = attemptId, phase = phase, attemptIndex = index,
    )

    /** FC-1 for the cloud: if the intent is not durable nothing is transmitted and no further candidate is tried. */
    fun begin() {
        check(!begun) { "intent already written" }
        startTs = node.now()
        node.appendIntent(row(Phase.INTENT, 0, 0, null, null, null))
        begun = true
    }

    /**
     * [providerError] is the upstream error text, which can echo the key (`Incorrect API key provided: sk-...`). It is accepted so that the call
     * site looks like the real one, and it is never written to a row (law L-L8).
     */
    @Suppress("UNUSED_PARAMETER")
    fun finish(status: Int, bytesOut: Long, servedModel: String? = null, tokensIn: Long? = null, tokensOut: Long? = null, providerError: String? = null): LabRouteRecord {
        check(begun && !finished) { "one outcome per attempt, after its intent" }
        val r = row(Phase.OUTCOME, status, bytesOut, servedModel, tokensIn, tokensOut)
        node.append(r)
        finished = true
        return r
    }
}
