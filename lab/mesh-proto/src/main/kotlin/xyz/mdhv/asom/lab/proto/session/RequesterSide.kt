package xyz.mdhv.asom.lab.proto.session

import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.manifest.Mode
import xyz.mdhv.asom.lab.manifest.RejectCode
import xyz.mdhv.asom.lab.manifest.Rejected
import xyz.mdhv.asom.lab.manifest.Verified
import xyz.mdhv.asom.lab.manifest.VerifyContext
import xyz.mdhv.asom.lab.manifest.Verifier
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.wire.Cancel
import xyz.mdhv.asom.lab.proto.wire.CancelReason
import xyz.mdhv.asom.lab.proto.wire.InferAccept
import xyz.mdhv.asom.lab.proto.wire.InferBody
import xyz.mdhv.asom.lab.proto.wire.InferChunk
import xyz.mdhv.asom.lab.proto.wire.InferDecline
import xyz.mdhv.asom.lab.proto.wire.InferEnd
import xyz.mdhv.asom.lab.proto.wire.InferHead
import xyz.mdhv.asom.lab.proto.wire.InferOffer
import xyz.mdhv.asom.lab.proto.wire.ManifestMsg
import xyz.mdhv.asom.lab.proto.wire.ManifestReq
import xyz.mdhv.asom.lab.proto.wire.MeshError
import xyz.mdhv.asom.lab.proto.wire.Message
import xyz.mdhv.asom.lab.proto.wire.MessageCodec
import xyz.mdhv.asom.lab.proto.wire.PeerError
import xyz.mdhv.asom.lab.proto.wire.RawFrame
import xyz.mdhv.asom.lab.proto.wire.StateMsg
import xyz.mdhv.asom.lab.proto.wire.StateReq
import xyz.mdhv.asom.lab.proto.wire.Terminal
import xyz.mdhv.asom.lab.proto.wire.WireLimits
import xyz.mdhv.asom.lab.proto.wire.WireRefusal

/**
 * The requester half of a session (the TLS client). The intent row of an attempt is durable before any byte of it leaves (FC-1: if it is not, nothing is sent
 * and the caller tries no further candidate); the outcome row is written when the attempt ends, with the frame bytes of the attempt summed per direction.
 * A frame for a stream nobody opened, or out of order, is a protocol error (ERRATA ERR-PW-3).
 */
internal class RequesterSide(private val s: Session) {
    private enum class Stage { OFFERED, ACCEPTED, BODY_SENT }

    private class Attempt(val stream: Long, val id: String, val spec: OfferSpec, val body: ByteArray, val listener: AttemptListener) {
        var stage = Stage.OFFERED
        var bytesOut = 0L
        var bytesIn = 0L
        var cancelSent = false
        var headSeen = false
        var startTs = 0L
        var lastActivity = 0L
        var cancelAt = 0L
    }

    private sealed interface Pending {
        val since: Long

        class State(override val since: Long, val cb: (StateResult) -> Unit) : Pending

        class Manifest(override val since: Long, val challenge: ByteArray, val cb: (ManifestResult) -> Unit) : Pending
    }

    private var nextStream = 1L
    private val attempts = LinkedHashMap<Long, Attempt>()
    private val pending = LinkedHashMap<Long, Pending>()

    /** Streams whose request this side gave up on (ERRATA ERR-FX2-2): the one late reply each may still get is ignored, not a protocol error. */
    private val lapsed = object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean = super.add(element).also { while (size > 4_096) remove(first()) }
    }

    /** While the session closes the host callbacks wait here until the close row is written (ERRATA ERR-FX2-8). */
    private var deferring = false
    private val deferred = ArrayList<() -> Unit>()

    private fun hostLater(block: () -> Unit) {
        if (deferring) deferred += block else s.hostCall(Unit, block)
    }

    fun runDeferred() {
        val todo = deferred.toList()
        deferred.clear()
        for (b in todo) s.hostCall(Unit, b)
    }

    private fun newStream(): Long = nextStream.also { nextStream += 2 }

    fun openCount(): Int = attempts.size + pending.size

    // ------------------------------------------------------------------------------------------------------------ offering

    fun offer(spec: OfferSpec, body: ByteArray, listener: AttemptListener): String {
        require(body.size.toLong() <= WireLimits.INFER_BODY_MAX) { "an INFER_BODY is at most ${WireLimits.INFER_BODY_MAX} bytes" }
        val node = s.node
        val id = node.ids.b64(16)
        val stream = newStream()
        val frame = try {
            MessageCodec.frame(InferOffer(id, spec.deadlineMs, spec.estTokensIn, spec.maxTokens, spec.model, spec.op, body.size.toLong(), spec.stream), stream)
        } catch (e: WireRefusal) {
            throw IllegalArgumentException("the offer is not a valid INFER_OFFER: ${e.reason}")
        }
        node.ledger.appendIntent(intentRow(spec, id))
        val a = Attempt(stream, id, spec, body, listener)
        a.startTs = node.ledger.now()
        a.lastActivity = a.startTs
        a.bytesOut += appBytes(frame)
        attempts[stream] = a
        s.writeAttemptFrame(frame)
        return id
    }

    private fun intentRow(spec: OfferSpec, id: String): LabRouteRecord = LabRouteRecord(
        ts = s.node.ledger.now(), callerPkg = spec.callerPkg, requestedModel = spec.model, egress = LabEgress.peerClass, requestId = spec.requestId, attemptId = id,
        phase = Phase.INTENT, attemptIndex = spec.attemptIndex, peerNode = s.rows.tag, peerPath = s.rows.path, meshKind = MeshKind.INFER_SENT, sessionId = s.rows.sessionId,
    )

    fun cancel(attemptId: String, reason: CancelReason) {
        val a = attempts.values.firstOrNull { it.id == attemptId } ?: return
        if (a.cancelSent) return
        val frame = MessageCodec.frame(Cancel(a.id, reason), a.stream)
        a.cancelSent = true
        a.cancelAt = s.node.ledger.now()
        a.bytesOut += appBytes(frame)
        s.writeAttemptFrame(frame)
    }

    fun requestState(cb: (StateResult) -> Unit) {
        val stream = newStream()
        pending[stream] = Pending.State(s.node.ledger.now(), cb)
        s.sendControl(StateReq, stream)
    }

    fun requestManifest(challenge: ByteArray, cb: (ManifestResult) -> Unit) {
        val msg = try {
            ManifestReq(Base64Strict.encodeUrlNoPad(challenge))
        } catch (e: WireRefusal) {
            throw IllegalArgumentException("the challenge is not a valid MANIFEST_REQ challenge: ${e.reason}")
        }
        val stream = newStream()
        pending[stream] = Pending.Manifest(s.node.ledger.now(), challenge, cb)
        s.sendControl(msg, stream)
    }

    /**
     * The requester's own bounds (ERRATA ERR-FX2-2). A `STATE_REQ` or `MANIFEST_REQ` unanswered for [SessionLimits.REQUEST_WAIT_MS] fails as lost. An attempt with no
     * frame either way for its own `deadlineMs` is cancelled (`CANCEL deadline`), and one cancelled that does not end within [SessionLimits.CANCEL_GRACE_MS] means
     * the lender is wedged: the session closes and every attempt ends as a lost peer. So no stream can pin a session without bound.
     */
    fun expire(now: Long) {
        for ((stream, p) in pending.entries.toList()) {
            if (now - p.since < SessionLimits.REQUEST_WAIT_MS) continue
            pending.remove(stream)
            lapsed.add(stream)
            when (p) {
                is Pending.State -> hostLater { p.cb(StateResult.Lost) }
                is Pending.Manifest -> hostLater { p.cb(ManifestResult.Lost) }
            }
        }
        for (a in attempts.values.toList()) {
            if (s.closed) return
            if (a.cancelSent) {
                if (now - a.cancelAt >= SessionLimits.CANCEL_GRACE_MS) return s.closeNow()
            } else if (now - a.lastActivity >= a.spec.deadlineMs) {
                cancel(a.id, CancelReason.DEADLINE)
            }
        }
    }

    private fun unsolicitedReply(f: RawFrame) = unsolicited(f.stream)

    /** A reply on a stream this side no longer waits for: the one for a request it gave up on is ignored once, any other is a protocol error. */
    private fun unsolicited(stream: Long) {
        if (lapsed.remove(stream)) {
            s.node.lateReplies.incrementAndGet()
            s.ignoreFrame()
        } else {
            s.fail(Refusal.UNSOLICITED_REPLY)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ replies

    fun onFrame(f: RawFrame, msg: Message) {
        when (msg) {
            is InferAccept -> known(f, msg.attemptId)?.let { accept(it, msg) }
            is InferDecline -> known(f, msg.attemptId)?.let { finish(it, 503, msg.code.wire, decline = msg) }
            is InferHead -> known(f, msg.attemptId)?.let { head(it, msg) }
            is InferChunk -> known(f, null)?.let { chunk(it, msg) }
            is InferEnd -> known(f, msg.attemptId)?.let { end(it, msg) }
            is StateMsg -> state(f, msg)
            is ManifestMsg -> manifest(f, msg)
            else -> s.fail(Refusal.UNSOLICITED_REPLY)
        }
    }

    private fun known(f: RawFrame, id: String?): Attempt? {
        val a = attempts[f.stream]
        if (a == null || (id != null && a.id != id)) {
            if (a == null) unsolicited(f.stream) else s.fail(Refusal.UNSOLICITED_REPLY)
            return null
        }
        a.bytesIn += appBytes(f)
        a.lastActivity = s.node.ledger.now()
        return a
    }

    private fun accept(a: Attempt, msg: InferAccept) {
        if (a.stage != Stage.OFFERED) return s.fail(Refusal.UNSOLICITED_REPLY)
        a.stage = Stage.ACCEPTED
        val go = s.hostCall(false) { a.listener.onAccepted(msg) } && !a.cancelSent
        if (!go) {
            if (!a.cancelSent) cancel(a.id, CancelReason.POLICY_CHANGED)
            return
        }
        val frame = MessageCodec.frame(InferBody(a.body), a.stream)
        a.bytesOut += appBytes(frame)
        a.stage = Stage.BODY_SENT
        a.lastActivity = s.node.ledger.now()
        s.writeAttemptFrame(frame)
    }

    private fun head(a: Attempt, msg: InferHead) {
        if (a.stage != Stage.BODY_SENT || a.headSeen) return s.fail(Refusal.UNSOLICITED_REPLY)
        a.headSeen = true
        s.hostCall(Unit) { a.listener.onHead(msg) }
    }

    private fun chunk(a: Attempt, msg: InferChunk) {
        if (!a.headSeen) return s.fail(Refusal.UNSOLICITED_REPLY)
        s.hostCall(Unit) { a.listener.onChunk(msg.bytes) }
    }

    private fun end(a: Attempt, msg: InferEnd) {
        if (a.stage != Stage.BODY_SENT && !a.cancelSent) return s.fail(Refusal.UNSOLICITED_REPLY)
        finish(a, msg.status, if (msg.terminal == Terminal.DONE) null else msg.terminal.wire.uppercase(), terminal = msg.terminal)
    }

    /** An `ERROR` that carries an `attemptId`: the attempt's single outcome. A `PEER_NOT_PAIRED` also tells us the lender is closing. */
    fun onAttemptError(f: RawFrame, e: PeerError) {
        val a = attempts[f.stream]
        if (a == null || a.id != e.attemptId) return s.fail(Refusal.UNSOLICITED_REPLY)
        a.bytesIn += appBytes(f)
        finish(a, 503, e.effective.name, error = e.effective)
        if (e.effective == MeshError.PEER_NOT_PAIRED) s.closeNow()
    }

    /** An `ERROR` that answers a pending `STATE_REQ` or `MANIFEST_REQ`. True when it did. */
    fun onStreamError(stream: Long, code: MeshError): Boolean {
        val p = pending.remove(stream)
        if (p == null) {
            if (!lapsed.remove(stream)) return false
            s.node.lateReplies.incrementAndGet()
            return true
        }
        when (p) {
            is Pending.State -> hostLater { p.cb(StateResult.Failed(code)) }
            is Pending.Manifest -> hostLater { p.cb(ManifestResult.Failed(code)) }
        }
        return true
    }

    private fun finish(a: Attempt, status: Int, code: String?, terminal: Terminal? = null, decline: InferDecline? = null, error: MeshError? = null) {
        attempts.remove(a.stream)
        val row = LabRouteRecord(
            ts = s.node.ledger.now(), callerPkg = a.spec.callerPkg, requestedModel = a.spec.model, egress = LabEgress.peerClass, bytesOut = a.bytesOut,
            latencyMs = (s.node.ledger.now() - a.startTs).coerceAtLeast(0), status = status, requestId = a.spec.requestId, attemptId = a.id, phase = Phase.OUTCOME,
            attemptIndex = a.spec.attemptIndex, peerNode = s.rows.tag, peerPath = s.rows.path, meshKind = MeshKind.INFER_SENT, bytesIn = a.bytesIn, meshCode = code,
            sessionId = s.rows.sessionId,
        )
        val durable = try {
            s.rows.attempt(row)
            row
        } catch (e: LedgerWriteException) {
            null
        }
        val outcome = AttemptOutcome(a.id, status, code, terminal, decline, error, durable)
        hostLater { a.listener.onFinished(outcome) }
    }

    // ------------------------------------------------------------------------------------------------------------ STATE and MANIFEST

    private fun state(f: RawFrame, msg: StateMsg) {
        val p = pending[f.stream] as? Pending.State ?: return unsolicitedReply(f)
        s.recvControl(f, msg)
        pending.remove(f.stream)
        hostLater { p.cb(StateResult.Ok(msg.doc)) }
    }

    private sealed interface Verdict {
        class Ok(val verified: Verified) : Verdict

        class Bad(val code: RejectCode, val step: String) : Verdict
    }

    private fun manifest(f: RawFrame, msg: ManifestMsg) {
        val p = pending[f.stream] as? Pending.Manifest ?: return unsolicitedReply(f)
        val verdict = verify(f.payload, p.challenge)
        s.recvControl(f, msg, verdict = if (verdict is Verdict.Bad) verdict.code.name else "VERIFIED")
        pending.remove(f.stream)
        when (verdict) {
            is Verdict.Ok -> {
                s.node.manifest.onVerified(s.peer, verdict.verified)
                hostLater { p.cb(ManifestResult.Accepted(verdict.verified)) }
            }
            is Verdict.Bad -> {
                s.node.counters.count(Refusal.MANIFEST_REJECTED)
                hostLater { p.cb(ManifestResult.Rejected(verdict.code, verdict.step)) }
            }
        }
    }

    /** The verifier decides; the session adds only that the key it is told to trust must hash to the authenticated pin. */
    private fun verify(doc: ByteArray, challenge: ByteArray): Verdict {
        val ctx = s.node.manifest.contextFor(s.peer, challenge)
        require(ctx.mode == Mode.MESH) { "a received MANIFEST is verified in MESH mode" }
        val safe = if (ctx.pinnedSpki != null && !hashesTo(ctx.pinnedSpki!!, s.peer)) withoutPinnedKey(ctx) else ctx
        return try {
            when (val r = Verifier.verify(doc, safe)) {
                is Verified -> Verdict.Ok(r)
                is Rejected -> Verdict.Bad(r.code, r.step)
            }
        } catch (e: RuntimeException) {
            s.node.internalErrors.incrementAndGet()
            Verdict.Bad(RejectCode.CONTAINER_INVALID, "0")
        }
    }

    private fun hashesTo(spki: ByteArray, pin: Pin): Boolean = Pin.constantTimeEquals(xyz.mdhv.asom.lab.manifest.Spki.pin(spki), pin.bytes())

    private fun withoutPinnedKey(c: VerifyContext) = VerifyContext(
        mode = c.mode, pinnedSpki = null, expectedChallenge = c.expectedChallenge, comparedFingerprint = c.comparedFingerprint, compareMethod = c.compareMethod,
        rollback = c.rollback, requiredTier = c.requiredTier, confFloor = c.confFloor, knownBadConf = c.knownBadConf, productionKeys = c.productionKeys, nowMs = c.nowMs,
    )

    // ------------------------------------------------------------------------------------------------------------ closing

    /**
     * Every attempt still open ends as a lost peer (599), every pending request fails. Row failures are ignored: the session is closing. The outcome rows are
     * written here; the host callbacks are held until the session has closed its connection and written its close row, and then run by [runDeferred].
     */
    fun abortAll() {
        deferring = true
        for (a in attempts.values.toList()) finish(a, 599, "PEER_UNREACHABLE", error = null)
        val p = pending.values.toList()
        pending.clear()
        for (x in p) when (x) {
            is Pending.State -> hostLater { x.cb(StateResult.Lost) }
            is Pending.Manifest -> hostLater { x.cb(ManifestResult.Lost) }
        }
    }
}
