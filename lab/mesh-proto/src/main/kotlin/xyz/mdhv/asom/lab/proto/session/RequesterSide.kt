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
    }

    private sealed interface Pending {
        class State(val cb: (StateResult) -> Unit) : Pending

        class Manifest(val challenge: ByteArray, val cb: (ManifestResult) -> Unit) : Pending
    }

    private var nextStream = 1L
    private val attempts = LinkedHashMap<Long, Attempt>()
    private val pending = LinkedHashMap<Long, Pending>()

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
        a.bytesOut += appBytes(frame)
        s.writeAttemptFrame(frame)
    }

    fun requestState(cb: (StateResult) -> Unit) {
        val stream = newStream()
        pending[stream] = Pending.State(cb)
        s.sendControl(StateReq, stream)
    }

    fun requestManifest(challenge: ByteArray, cb: (ManifestResult) -> Unit) {
        val stream = newStream()
        pending[stream] = Pending.Manifest(challenge, cb)
        s.sendControl(ManifestReq(Base64Strict.encodeUrlNoPad(challenge)), stream)
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
            s.fail(Refusal.UNSOLICITED_REPLY)
            return null
        }
        a.bytesIn += appBytes(f)
        return a
    }

    private fun accept(a: Attempt, msg: InferAccept) {
        if (a.stage != Stage.OFFERED) return s.fail(Refusal.UNSOLICITED_REPLY)
        a.stage = Stage.ACCEPTED
        val go = a.listener.onAccepted(msg) && !a.cancelSent
        if (!go) {
            if (!a.cancelSent) cancel(a.id, CancelReason.POLICY_CHANGED)
            return
        }
        val frame = MessageCodec.frame(InferBody(a.body), a.stream)
        a.bytesOut += appBytes(frame)
        a.stage = Stage.BODY_SENT
        s.writeAttemptFrame(frame)
    }

    private fun head(a: Attempt, msg: InferHead) {
        if (a.stage != Stage.BODY_SENT || a.headSeen) return s.fail(Refusal.UNSOLICITED_REPLY)
        a.headSeen = true
        a.listener.onHead(msg)
    }

    private fun chunk(a: Attempt, msg: InferChunk) {
        if (!a.headSeen) return s.fail(Refusal.UNSOLICITED_REPLY)
        a.listener.onChunk(msg.bytes)
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
        when (val p = pending.remove(stream) ?: return false) {
            is Pending.State -> p.cb(StateResult.Failed(code))
            is Pending.Manifest -> p.cb(ManifestResult.Failed(code))
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
        a.listener.onFinished(AttemptOutcome(a.id, status, code, terminal, decline, error, durable))
    }

    // ------------------------------------------------------------------------------------------------------------ STATE and MANIFEST

    private fun state(f: RawFrame, msg: StateMsg) {
        val p = pending[f.stream] as? Pending.State ?: return s.fail(Refusal.UNSOLICITED_REPLY)
        s.recvControl(f, msg)
        pending.remove(f.stream)
        p.cb(StateResult.Ok(msg.doc))
    }

    private sealed interface Verdict {
        class Ok(val verified: Verified) : Verdict

        class Bad(val code: RejectCode, val step: String) : Verdict
    }

    private fun manifest(f: RawFrame, msg: ManifestMsg) {
        val p = pending[f.stream] as? Pending.Manifest ?: return s.fail(Refusal.UNSOLICITED_REPLY)
        val verdict = verify(f.payload, p.challenge)
        s.recvControl(f, msg, verdict = if (verdict is Verdict.Bad) verdict.code.name else "VERIFIED")
        pending.remove(f.stream)
        when (verdict) {
            is Verdict.Ok -> {
                s.node.manifest.onVerified(s.peer, verdict.verified)
                p.cb(ManifestResult.Accepted(verdict.verified))
            }
            is Verdict.Bad -> {
                s.node.counters.count(Refusal.MANIFEST_REJECTED)
                p.cb(ManifestResult.Rejected(verdict.code, verdict.step))
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

    /** Every attempt still open ends as a lost peer (599), every pending request fails. Row failures are ignored: the session is closing. */
    fun abortAll() {
        for (a in attempts.values.toList()) finish(a, 599, "PEER_UNREACHABLE", error = null)
        val p = pending.values.toList()
        pending.clear()
        for (x in p) when (x) {
            is Pending.State -> x.cb(StateResult.Lost)
            is Pending.Manifest -> x.cb(ManifestResult.Lost)
        }
    }
}
