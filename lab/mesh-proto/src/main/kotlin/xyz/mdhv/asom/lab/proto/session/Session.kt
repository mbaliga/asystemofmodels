package xyz.mdhv.asom.lab.proto.session

import java.io.IOException
import kotlin.math.abs
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.policy.StateBuilder
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.trust.AuthDecision
import xyz.mdhv.asom.lab.proto.trust.GoawayReason as RegistryGoaway
import xyz.mdhv.asom.lab.proto.trust.NetworkEvent
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.SessionDecision
import xyz.mdhv.asom.lab.proto.wire.Cancel
import xyz.mdhv.asom.lab.proto.wire.GoAway
import xyz.mdhv.asom.lab.proto.wire.GoAwayReason
import xyz.mdhv.asom.lab.proto.wire.Handshake
import xyz.mdhv.asom.lab.proto.wire.HelloAck
import xyz.mdhv.asom.lab.proto.wire.AckDecision
import xyz.mdhv.asom.lab.proto.wire.Hello
import xyz.mdhv.asom.lab.proto.wire.HelloDecision
import xyz.mdhv.asom.lab.proto.wire.Inbound
import xyz.mdhv.asom.lab.proto.wire.InferAccept
import xyz.mdhv.asom.lab.proto.wire.InferBody
import xyz.mdhv.asom.lab.proto.wire.InferChunk
import xyz.mdhv.asom.lab.proto.wire.InferDecline
import xyz.mdhv.asom.lab.proto.wire.InferEnd
import xyz.mdhv.asom.lab.proto.wire.InferHead
import xyz.mdhv.asom.lab.proto.wire.InferOffer
import xyz.mdhv.asom.lab.proto.wire.Limits
import xyz.mdhv.asom.lab.proto.wire.ManifestMsg
import xyz.mdhv.asom.lab.proto.wire.ManifestReq
import xyz.mdhv.asom.lab.proto.wire.MeshError
import xyz.mdhv.asom.lab.proto.wire.Message
import xyz.mdhv.asom.lab.proto.wire.MessageCodec
import xyz.mdhv.asom.lab.proto.wire.Parsed
import xyz.mdhv.asom.lab.proto.wire.PeerError
import xyz.mdhv.asom.lab.proto.wire.PeerRole
import xyz.mdhv.asom.lab.proto.wire.RawFrame
import xyz.mdhv.asom.lab.proto.wire.RevokeNotice
import xyz.mdhv.asom.lab.proto.wire.Scope
import xyz.mdhv.asom.lab.proto.wire.StateMsg
import xyz.mdhv.asom.lab.proto.wire.StateReq
import xyz.mdhv.asom.lab.proto.wire.St
import xyz.mdhv.asom.lab.proto.wire.WireRefusal

/** Callbacks the host may want. Every one is invoked after the rows it depends on are durable. */
interface SessionListener {
    fun onEstablished() {}

    fun onGoAway(reason: GoAwayReason) {}

    fun onRevokeNotice() {}

    fun onClosed() {}

    companion object {
        val NONE: SessionListener = object : SessionListener {}
    }
}

/**
 * One peer session over a [MeshConnection], in either TLS role: the TLS client is the requester (it sends HELLO, offers, asks for state and manifests) and
 * the TLS server is the lender (LAB_SPEC 7.2 fixes the direction of every frame). Everything is single-threaded in effect: every entry point holds the
 * session lock, and the engine and the transport are driven by the host through [pumpAvailable], [onBytes] and [advance].
 *
 * Durable before: a per-frame row is appended (force included) before the frame's first byte is handed to the transport, and a received frame's row is
 * appended before any reply (LAB_SPEC 7.6). A control-row failure runs FC-2: nothing further is sent, the connection is closed, the SESSION close row is
 * attempted.
 */
class Session private constructor(
    internal val node: MeshNode,
    conn: MeshConnection,
    val peer: Pin,
    sessionId: String,
    handshakeRecorded: Long,
    peerPath: xyz.mdhv.asom.lab.ledger.PeerPath?,
) {
    internal enum class Phase { NEW, AWAIT_HELLO, HELLO_SENT, ESTABLISHED, CLOSED }

    private val lock = Any()
    val role: PeerRole = conn.role
    internal val wire = Wire(conn)
    internal val rows = SessionRows(node.ledger, sessionId, if (conn.role == PeerRole.TLS_CLIENT) sessionId else null, peer.nodeTag, peerPath, wire, handshakeRecorded) { failClosedNow() }
    internal var phase: Phase = if (role == PeerRole.TLS_SERVER) Phase.AWAIT_HELLO else Phase.NEW
        private set
    internal val lender = LenderSide(this)
    internal val requester = RequesterSide(this)
    private val usedStreams = HashSet<Long>()
    private var failClosedDone = false
    private val startedAt = node.ledger.now()

    var listener: SessionListener = SessionListener.NONE

    /** What the lender granted us (requester role), from `HELLO_ACK`. */
    var granted: Set<Scope> = emptySet()
        private set
    var limits: Limits? = null
        private set

    val established: Boolean get() = phase == Phase.ESTABLISHED
    val closed: Boolean get() = phase == Phase.CLOSED
    val sessionId: String get() = rows.sessionId

    /** True when the SESSION close row was written because of a ledger failure (FC-2). */
    val closedByLedgerFailure: Boolean get() = failClosedDone

    // ------------------------------------------------------------------------------------------------------------ driving

    private fun <T> guarded(default: T, block: () -> T): T = synchronized(lock) {
        try {
            block()
        } catch (e: FailClosed) {
            default
        } catch (e: IOException) {
            try {
                closeNow()
            } catch (_: FailClosed) {
            }
            default
        }
    }

    /** The dialer sends `HELLO` (its session id is the nonce). */
    fun start() = guarded(Unit) {
        check(role == PeerRole.TLS_CLIENT && phase == Phase.NEW) { "only a new dialed session starts" }
        val c = node.cfg
        val hello = Hello(c.endpoints, c.features, c.keyTier, c.versions.minV, c.versions.maxV, c.name, c.pin.nodeId, c.platform, rows.sessionId, c.sw, node.ledger.now(), c.versions.maxV)
        sendControl(hello, 0)
        phase = Phase.HELLO_SENT
    }

    /** Bytes arrived from the transport. Any chunking gives the same result. */
    fun onBytes(buf: ByteArray, off: Int = 0, len: Int = buf.size - off) = guarded(Unit) {
        if (phase == Phase.CLOSED) return@guarded
        for (ev in wire.decoder.feed(buf, off, len)) {
            if (phase == Phase.CLOSED) break
            when (ev) {
                is Inbound.Frame -> frame(ev.frame)
                is Inbound.ExtIgnored -> extension(ev)
                is Inbound.Failure -> fail(Refusal.ofDecoder(ev.reason), ev.error)
            }
        }
    }

    /** Reads what the transport has ready (at most [maxChunk] bytes) and processes it. Returns the number of bytes read. */
    fun pumpAvailable(maxChunk: Int = 4096): Int {
        val buf: ByteArray
        val n: Int
        synchronized(lock) {
            if (phase == Phase.CLOSED) return 0
            val avail = wire.input.available()
            if (avail <= 0) return 0
            buf = ByteArray(minOf(avail, maxChunk))
            n = wire.input.read(buf, 0, buf.size)
        }
        if (n > 0) onBytes(buf, 0, n)
        return maxOf(n, 0)
    }

    /** The blocking loop a real transport uses: read until the peer closes or the session ends. */
    fun runReadLoop(chunk: Int = 16_384) {
        val buf = ByteArray(chunk)
        while (!closed) {
            val n = try {
                wire.input.read(buf, 0, buf.size)
            } catch (e: IOException) {
                -1
            }
            if (n < 0) {
                onTransportClosed()
                return
            }
            onBytes(buf, 0, n)
        }
    }

    /** The peer closed the transport. A frame cut in half is a typed refusal (counted); there is nobody to send an ERROR to. */
    fun onTransportClosed() = guarded(Unit) {
        if (phase == Phase.CLOSED) return@guarded
        wire.decoder.finish()?.let { node.counters.count(Refusal.TRUNCATED) }
        closeNow()
    }

    /** Lets the engine of a served attempt produce one event. Returns the number of events handled. */
    fun advance(): Int = guarded(0) { if (phase == Phase.ESTABLISHED) lender.advance() else 0 }

    /** The handshake timeout (LAB_SPEC trust.md 5.1: 5 s): a session that has not established by then is closed without a word. */
    fun tick(nowMs: Long) = guarded(Unit) {
        if (phase == Phase.CLOSED || phase == Phase.ESTABLISHED) return@guarded
        if (nowMs - startedAt > node.cfg.handshakeTimeoutMs) closeNow()
    }

    // ------------------------------------------------------------------------------------------------------------ the requester API

    fun offer(spec: OfferSpec, body: ByteArray, listener: AttemptListener): String = synchronized(lock) {
        try {
            if (phase == Phase.CLOSED) throw SessionClosedForSend()
            check(phase == Phase.ESTABLISHED && role == PeerRole.TLS_CLIENT) { "offers go from an established requester session" }
            requester.offer(spec, body, listener)
        } catch (e: FailClosed) {
            throw SessionClosedForSend()
        } catch (e: IOException) {
            try {
                closeNow()
            } catch (_: FailClosed) {
            }
            throw SessionClosedForSend()
        }
    }

    fun cancel(attemptId: String, reason: xyz.mdhv.asom.lab.proto.wire.CancelReason) = guarded(Unit) { requester.cancel(attemptId, reason) }

    fun requestState(cb: (StateResult) -> Unit) = guarded(Unit) {
        if (phase == Phase.CLOSED) return@guarded cb(StateResult.Lost)
        check(phase == Phase.ESTABLISHED && role == PeerRole.TLS_CLIENT)
        requester.requestState(cb)
    }

    fun requestManifest(challenge: ByteArray, cb: (ManifestResult) -> Unit) = guarded(Unit) {
        if (phase == Phase.CLOSED) return@guarded cb(ManifestResult.Lost)
        check(phase == Phase.ESTABLISHED && role == PeerRole.TLS_CLIENT)
        requester.requestManifest(challenge, cb)
    }

    /** A courtesy only: the local revoke is authoritative (LAB_SPEC 7.2). A closed session sends nothing. */
    fun sendRevokeNotice() = guarded(Unit) {
        if (phase == Phase.CLOSED) return@guarded
        check(phase == Phase.ESTABLISHED)
        sendControl(RevokeNotice, 0)
    }

    /** A graceful close that the host starts (shutdown, idle, network change, max age): `GOAWAY`, then the TLS close. */
    fun goAway(reason: GoAwayReason) = guarded(Unit) {
        if (phase == Phase.CLOSED) return@guarded
        if (phase == Phase.ESTABLISHED) sendControl(GoAway(reason), 0)
        closeNow()
    }

    internal fun goAwayForRegistry(g: RegistryGoaway) = goAway(GoAwayReason.entries.first { it.wire == g.wire })

    /** Closes without a `GOAWAY` (the transport is gone). */
    fun close() = guarded(Unit) { closeNow() }

    // ------------------------------------------------------------------------------------------------------------ inbound dispatch

    private fun frame(f: RawFrame) {
        node.observer?.inbound(this, f.type, f.stream, f.payload.size)
        wire.noteReceived(appBytes(f))
        val msg = when (val p = MessageCodec.parse(f)) {
            is Parsed.Ok -> p.message
            is Parsed.Reject -> return fail(Refusal.BAD_PAYLOAD, p.error)
        }
        when (phase) {
            Phase.AWAIT_HELLO -> if (msg is Hello) onHello(f, msg) else fail(Refusal.FRAME_BEFORE_HELLO)
            Phase.HELLO_SENT -> when (msg) {
                is HelloAck -> onAck(f, msg)
                is PeerError -> onError(f, msg)
                else -> fail(Refusal.FRAME_BEFORE_HELLO)
            }
            Phase.ESTABLISHED -> established(f, msg)
            Phase.NEW, Phase.CLOSED -> Unit
        }
    }

    private fun extension(e: Inbound.ExtIgnored) {
        node.observer?.inbound(this, e.type, e.stream, (e.appBytes - 9).toInt())
        wire.noteReceived(e.appBytes)
        openIfListener()
        rows.control(rows.frameRow(MeshKind.CONTROL, "EXT_IGNORED", inn = e.appBytes))
    }

    private fun established(f: RawFrame, msg: Message) {
        when (msg) {
            is Hello, is HelloAck -> fail(Refusal.SECOND_HELLO)
            is GoAway -> onGoAway(f, msg)
            is PeerError -> onError(f, msg)
            is RevokeNotice -> {
                recvControl(f, msg)
                node.registry.onNetworkEvent(NetworkEvent.RevokeNotice(peer))
                listener.onRevokeNotice()
            }
            else -> if (role == PeerRole.TLS_SERVER) lender.onFrame(f, msg) else requester.onFrame(f, msg)
        }
    }

    private fun onHello(f: RawFrame, h: Hello) {
        rows.sessionId = h.sessionNonce
        rows.joinId = h.sessionNonce
        openIfListener()
        recvControl(f, h)
        val c = node.cfg
        when (val d = Handshake.onHello(c.versions, c.features, h, peer.nodeId)) {
            is HelloDecision.Refuse -> fail(if (d.error.code == MeshError.VERSION_UNSUPPORTED) Refusal.HELLO_BAD_VERSION else Refusal.HELLO_NODE_MISMATCH)
            is HelloDecision.Established -> {
                if (abs(h.ts - node.ledger.now()) > c.clockSkewMs) return fail(Refusal.CLOCK_SKEW)
                if (node.registry.sessionDecision(peer) !is SessionDecision.Keep) return fail(Refusal.PEER_NOT_PAIRED)
                val granted = node.registry.granted(peer).mapNotNull { g -> Scope.entries.firstOrNull { it.wire == g.wire } }.toSet()
                val ack = Handshake.ack(d, c.pin.nodeId, c.endpoints, granted, c.limits, stIfGranted(), node.ledger.now())
                sendControl(ack, 0)
                phase = Phase.ESTABLISHED
                listener.onEstablished()
            }
        }
    }

    private fun onAck(f: RawFrame, ack: HelloAck) {
        recvControl(f, ack)
        val c = node.cfg
        when (val d = Handshake.onAck(c.versions, c.features, ack, peer.nodeId)) {
            is AckDecision.Refuse -> fail(if (d.error.code == MeshError.VERSION_UNSUPPORTED) Refusal.HELLO_BAD_VERSION else Refusal.HELLO_NODE_MISMATCH)
            is AckDecision.Established -> {
                if (abs(ack.ts - node.ledger.now()) > c.clockSkewMs) return fail(Refusal.CLOCK_SKEW)
                granted = d.granted
                limits = d.limits
                phase = Phase.ESTABLISHED
                listener.onEstablished()
            }
        }
    }

    private fun onGoAway(f: RawFrame, g: GoAway) {
        recvControl(f, g)
        listener.onGoAway(g.reason)
        closeNow()
    }

    private fun onError(f: RawFrame, e: PeerError) {
        if (e.attemptId != null) {
            if (role == PeerRole.TLS_CLIENT && phase == Phase.ESTABLISHED) requester.onAttemptError(f, e) else fail(Refusal.UNSOLICITED_REPLY)
            return
        }
        recvControl(f, e)
        if (role == PeerRole.TLS_CLIENT && f.stream != 0L && e.effective in STREAM_LEVEL && requester.onStreamError(f.stream, e.effective)) return
        closeNow()
    }

    // ------------------------------------------------------------------------------------------------------------ the one place a per-frame row precedes a frame

    /** A per-frame row, then the frame. The row is durable before the first byte leaves; a failed row sends nothing (FC-2). */
    internal fun sendControl(msg: Message, stream: Long, routeDetail: String? = null) {
        val frame = MessageCodec.frame(msg, stream)
        sendControl(frame, msg, routeDetail)
    }

    internal fun sendControl(frame: RawFrame, msg: Message, routeDetail: String? = null) {
        val shape = checkNotNull(RowTable.of(msg, sent = true)) { "attempt frames do not go through sendControl" }
        rows.control(rows.frameRow(shape.kind, shape.code, out = appBytes(frame), routeDetail = routeDetail))
        wire.write(frame)
    }

    /** The row of a received per-frame class, durable before the caller replies. [verdict] names a received manifest. */
    internal fun recvControl(f: RawFrame, msg: Message, verdict: String? = null) {
        val shape = checkNotNull(RowTable.of(msg, sent = false, verdict = verdict)) { "attempt frames are covered by attempt rows" }
        rows.control(rows.frameRow(shape.kind, shape.code, inn = appBytes(f)))
    }

    /** An attempt frame leaves; its covering attempt row was written by the caller. */
    internal fun writeAttemptFrame(frame: RawFrame) {
        wire.write(frame)
    }

    internal fun openIfListener() {
        if (role == PeerRole.TLS_SERVER && !rows.opened) rows.open("established")
    }

    /** A typed refusal on the connection: `ERROR`, then (for a closing refusal) the close. Counted. */
    internal fun fail(refusal: Refusal, error: MeshError = refusal.error) {
        node.counters.count(refusal)
        if (phase == Phase.CLOSED) return
        openIfListener()
        sendControl(PeerError(error), 0)
        if (refusal.closes) closeNow()
    }

    /** Refuses a new stream id that was used before. */
    internal fun useStream(stream: Long): Boolean {
        if (usedStreams.add(stream)) return true
        fail(Refusal.STREAM_REUSE)
        return false
    }

    internal fun stIfGranted(): St? {
        if (node.registry.authorize(peer, "state") !is AuthDecision.Allow) return null
        val v = node.live.view()
        return St(v.fsm, v.governor, StateBuilder.queueBucket(v.localQueued, v.peerQueued), node.live.nextSeq(), v.thermalBand)
    }

    // ------------------------------------------------------------------------------------------------------------ closing

    /** Ends the session after a normal or protocol close: attempts are finished, the TLS connection closed, the SESSION close row written. */
    internal fun closeNow(code: String = "close", status: Int = 200) {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        try {
            openIfListener()
        } catch (e: FailClosed) {
            return
        }
        lender.abortAll()
        requester.abortAll()
        wire.kill()
        rows.close(code, status)
        node.unregister(this)
        listener.onClosed()
    }

    /** FC-2. Nothing further is sent (not even GOAWAY), the connection closes, the ledger is already marked unavailable by the failed append, the close row is attempted. */
    internal fun failClosedNow() {
        if (failClosedDone) return
        failClosedDone = true
        phase = Phase.CLOSED
        wire.kill()
        lender.abortAll()
        requester.abortAll()
        rows.close("close:ledger-failure", 503)
        node.unregister(this)
        listener.onClosed()
    }

    companion object {
        /** Error codes that answer one stream and leave the session open when they are the reply to a request. */
        private val STREAM_LEVEL = setOf(MeshError.SCOPE_DENIED, MeshError.MANIFEST_UNAVAILABLE)

        internal fun listener(node: MeshNode, conn: MeshConnection, peer: Pin, peerPath: xyz.mdhv.asom.lab.ledger.PeerPath?): Session {
            val s = Session(node, conn, peer, node.ids.b64(16), 0, peerPath)
            node.register(s)
            return s
        }

        internal fun dialer(node: MeshNode, conn: MeshConnection, peer: Pin, sessionId: String, handshakeRecorded: Long, peerPath: xyz.mdhv.asom.lab.ledger.PeerPath?): Session {
            val s = Session(node, conn, peer, sessionId, handshakeRecorded, peerPath)
            node.register(s)
            return s
        }
    }
}

/** An offer or a request could not be sent because the session is closed (a failed control row closes it). */
class SessionClosedForSend : Exception("the session is closed; nothing was sent")
