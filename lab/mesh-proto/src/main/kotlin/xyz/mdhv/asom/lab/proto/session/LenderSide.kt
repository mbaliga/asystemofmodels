package xyz.mdhv.asom.lab.proto.session

import java.security.MessageDigest
import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.ledger.ServedRecord
import xyz.mdhv.asom.lab.policy.DeclineCode
import xyz.mdhv.asom.lab.policy.LenderDecisionTable
import xyz.mdhv.asom.lab.policy.LenderLimits
import xyz.mdhv.asom.lab.policy.LenderReply
import xyz.mdhv.asom.lab.policy.LenderSituation
import xyz.mdhv.asom.lab.policy.MeshErrorCode
import xyz.mdhv.asom.lab.policy.OfferView
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.policy.StateBuilder
import xyz.mdhv.asom.lab.policy.StateParse
import xyz.mdhv.asom.lab.policy.StateParser
import xyz.mdhv.asom.lab.proto.trust.AuthDecision
import xyz.mdhv.asom.lab.proto.trust.DenyReason
import xyz.mdhv.asom.lab.proto.wire.Cancel
import xyz.mdhv.asom.lab.proto.wire.DeclineWire
import xyz.mdhv.asom.lab.proto.wire.FrameEncoder
import xyz.mdhv.asom.lab.proto.wire.InferBody
import xyz.mdhv.asom.lab.proto.wire.InferAccept
import xyz.mdhv.asom.lab.proto.wire.InferChunk
import xyz.mdhv.asom.lab.proto.wire.InferDecline
import xyz.mdhv.asom.lab.proto.wire.InferEnd
import xyz.mdhv.asom.lab.proto.wire.InferHead
import xyz.mdhv.asom.lab.proto.wire.InferOffer
import xyz.mdhv.asom.lab.proto.wire.ManifestReq
import xyz.mdhv.asom.lab.proto.wire.MeshError
import xyz.mdhv.asom.lab.proto.wire.Message
import xyz.mdhv.asom.lab.proto.wire.MessageCodec
import xyz.mdhv.asom.lab.proto.wire.PeerError
import xyz.mdhv.asom.lab.proto.wire.RawFrame
import xyz.mdhv.asom.lab.proto.wire.StateMsg
import xyz.mdhv.asom.lab.proto.wire.StateReq
import xyz.mdhv.asom.lab.proto.wire.Terminal
import xyz.mdhv.asom.lab.proto.wire.WireRefusal

/**
 * The lender half of a session (the TLS server): the decision for every `INFER_OFFER` (trust.md 7.3 with rows 6a, 6b and 8, by `:mesh-policy`), the
 * served attempt through the [EnginePort], `STATE` and `MANIFEST`. Authorisation is read from the registry at every `INFER_OFFER`, `INFER_BODY`,
 * `STATE_REQ` and `MANIFEST_REQ`, never cached.
 */
internal class LenderSide(private val s: Session) {
    private enum class Stage { ACCEPTED, SERVING, CANCELLING }

    private class Served(val stream: Long, val offer: InferOffer, val accept: ModelOffer) {
        var state = Stage.ACCEPTED
        var bytesIn = 0L
        var bytesOut = 0L
        var run: EngineRun? = null
        var intent = false
        var headSent = false
        var done = false
        var servedModel: String = accept.servedModel
        var startTs = 0L
        var acceptedAt = 0L
        var expired = false
    }

    /** An attempt that ended: a frame that raced with its end is tolerated once per kind (ERRATA ERR-PS-14, ERR-FX2-1, ERR-FX2-3), never twice. */
    private class Finished(val attemptId: String, val expired: Boolean) {
        var lateCancelUsed = false
        var lateBodyUsed = false
    }

    private val served = LinkedHashMap<Long, Served>()
    private val finished = object : LinkedHashMap<Long, Finished>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Finished>?): Boolean = size > 4_096
    }

    fun onFrame(f: RawFrame, msg: Message) {
        when (msg) {
            is InferOffer -> offer(f, msg)
            is InferBody -> body(f, msg)
            is Cancel -> cancel(f, msg)
            is StateReq -> stateReq(f, msg)
            is ManifestReq -> manifestReq(f, msg)
            else -> s.fail(Refusal.UNSOLICITED_REPLY)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ INFER_OFFER

    private fun offer(f: RawFrame, o: InferOffer) {
        if (!s.useStream(f.stream)) return
        val node = s.node
        val now = node.ledger.now()
        val auth = node.registry.authorize(s.peer, "infer")
        val seen = node.attempts.seenBeforeAndRecord(s.peer, o.attemptId, now)
        val perMinute = node.rpm.countAndRecord(s.peer, now)
        val model = node.engine.offered(o.model)
        val view = node.policy.view()
        val l = node.cfg.limits
        val status = when {
            auth is AuthDecision.Allow -> PeerStatus.PAIRED
            auth is AuthDecision.Deny && (auth.reason == DenyReason.SCOPE_NOT_GRANTED || auth.reason == DenyReason.UNREADABLE_SCOPES) -> PeerStatus.PAIRED
            else -> PeerStatus.SUSPENDED
        }
        val sit = LenderSituation(
            registryStatus = status, inferScopeGranted = auth is AuthDecision.Allow, attemptSeenWithin24h = seen, modelAllowedAndLoadable = model != null,
            limits = LenderLimits(l.maxBodyBytes, l.maxTokens, l.maxConcurrent.toInt(), l.rpm.toInt()), servingConditionsOk = view.servingConditionsOk,
            presenceActive = view.presenceActive, presenceHoldRemainingMs = view.presenceHoldRemainingMs, predictedThermalHold = view.predictedThermalHold,
            estStartMs = view.estStartMs, inflight = node.inflight.get(), requestsThisMinute = perMinute,
        )
        val decision = LenderDecisionTable.decide(OfferView(o.attemptId, o.model, o.op.wire, o.promptBytes, o.maxTokens, o.deadlineMs, o.stream), sit)
        val a = Served(f.stream, o, model ?: ModelOffer(o.model, "0".repeat(64)))
        a.bytesIn += appBytes(f)
        a.startTs = now
        when (val r = decision.reply) {
            is LenderReply.Accept -> if (s.node.servedOf(s.peer).get() >= SessionLimits.MAX_STREAMS) {
                s.node.counters.count(Refusal.PEER_BUSY)
                decline(a, DeclineWire.PEER_BUSY, LenderDecisionTable.BUSY_RETRY_MS)
            } else {
                accept(a)
            }
            is LenderReply.Decline -> {
                s.node.counters.count(refusalOf(r.code))
                decline(a, DeclineWire.entries.first { it.name == r.code.name }, r.retryAfterMs)
            }
            is LenderReply.Error -> when (r.code) {
                MeshErrorCode.PEER_NOT_PAIRED -> {
                    s.node.counters.count(Refusal.PEER_NOT_PAIRED)
                    errorAttempt(a, MeshError.PEER_NOT_PAIRED)
                    s.closeNow()
                }
                MeshErrorCode.FRAME_TOO_LARGE -> {
                    s.node.counters.count(Refusal.OFFER_TOO_LARGE)
                    errorAttempt(a, MeshError.FRAME_TOO_LARGE)
                }
            }
        }
    }

    private fun refusalOf(c: DeclineCode): Refusal = when (c) {
        DeclineCode.PEER_BUSY -> Refusal.PEER_BUSY
        DeclineCode.PEER_UNAVAILABLE -> Refusal.PEER_UNAVAILABLE
        DeclineCode.MODEL_NOT_OFFERED -> Refusal.MODEL_NOT_OFFERED
        DeclineCode.SCOPE_DENIED -> Refusal.SCOPE_DENIED
        DeclineCode.DUPLICATE_ATTEMPT -> Refusal.DUPLICATE_ATTEMPT
    }

    private fun accept(a: Served) {
        val frame = MessageCodec.frame(InferAccept(a.offer.attemptId, a.accept.fileSha256, a.accept.servedModel, s.stIfGranted()), a.stream)
        a.bytesOut += appBytes(frame)
        a.acceptedAt = s.node.ledger.now()
        served[a.stream] = a
        s.node.inflight.incrementAndGet()
        s.node.servedOf(s.peer).incrementAndGet()
        s.writeAttemptFrame(frame)
    }

    // ------------------------------------------------------------------------------------------------------------ rows and frames of an attempt

    private fun outcomeRow(a: Served, status: Int, code: String?, out: Long, servedModel: String?, tokensIn: Long? = null): LabRouteRecord = LabRouteRecord(
        ts = s.node.ledger.now(), callerPkg = s.rows.callerPkg, requestedModel = a.offer.model, servedProvider = servedModel?.let { "local" }, servedModel = servedModel,
        egress = LabEgress.peerClass, bytesOut = out, tokensIn = tokensIn, latencyMs = (s.node.ledger.now() - a.startTs).coerceAtLeast(0), status = status,
        attemptId = a.offer.attemptId, phase = Phase.OUTCOME, peerNode = s.rows.tag, peerPath = s.rows.path, meshKind = MeshKind.INFER_SERVED, bytesIn = a.bytesIn,
        meshCode = code, sessionId = s.rows.sessionId,
    )

    private fun intentRow(a: Served): LabRouteRecord = LabRouteRecord(
        ts = s.node.ledger.now(), callerPkg = s.rows.callerPkg, requestedModel = a.offer.model, egress = LabEgress.peerClass, attemptId = a.offer.attemptId,
        phase = Phase.INTENT, peerNode = s.rows.tag, peerPath = s.rows.path, meshKind = MeshKind.INFER_SERVED, sessionId = s.rows.sessionId,
    )

    private fun release(a: Served) {
        if (served.remove(a.stream) != null) {
            s.node.inflight.decrementAndGet()
            s.node.servedOf(s.peer).decrementAndGet()
        }
        finished[a.stream] = Finished(a.offer.attemptId, a.expired)
    }

    /** A decline: an outcome row only (503 and the code), durable before the `INFER_DECLINE` leaves. A failed append is FC-2. */
    private fun decline(a: Served, code: DeclineWire, retryAfterMs: Long) {
        val frame = MessageCodec.frame(InferDecline(a.offer.attemptId, code, retryAfterMs, s.stIfGranted()), a.stream)
        val out = a.bytesOut + appBytes(frame)
        s.rows.control(outcomeRow(a, 503, code.wire, out, null))
        a.bytesOut = out
        release(a)
        s.writeAttemptFrame(frame)
    }

    /** An attempt-scoped `ERROR` (carries the `attemptId`): the same single outcome row as a decline. */
    private fun errorAttempt(a: Served, code: MeshError) {
        val frame = MessageCodec.frame(PeerError(code, a.offer.attemptId), a.stream)
        val out = a.bytesOut + appBytes(frame)
        s.rows.control(outcomeRow(a, 503, code.name, out, null))
        a.bytesOut = out
        release(a)
        s.writeAttemptFrame(frame)
    }

    // ------------------------------------------------------------------------------------------------------------ INFER_BODY and the engine

    private fun body(f: RawFrame, b: InferBody) {
        val a = served[f.stream]
        if (a == null) {
            val late = finished[f.stream]
            if (late != null && late.expired && !late.lateBodyUsed) {
                late.lateBodyUsed = true
                s.node.lateBodies.incrementAndGet()
                return s.ignoreFrame()
            }
            return s.fail(Refusal.BODY_WITHOUT_OFFER)
        }
        if (a.state != Stage.ACCEPTED) return s.fail(Refusal.BODY_WITHOUT_OFFER)
        a.bytesIn += appBytes(f)
        when (val auth = s.node.registry.authorize(s.peer, "infer")) {
            is AuthDecision.Allow -> Unit
            is AuthDecision.Deny -> {
                if (auth.reason == DenyReason.SCOPE_NOT_GRANTED || auth.reason == DenyReason.UNREADABLE_SCOPES) {
                    s.node.counters.count(Refusal.SCOPE_DENIED)
                    return errorAttempt(a, MeshError.SCOPE_DENIED)
                }
                s.node.counters.count(Refusal.PEER_NOT_PAIRED)
                errorAttempt(a, MeshError.PEER_NOT_PAIRED)
                return s.closeNow()
            }
        }
        if (b.bytes.size.toLong() > s.node.cfg.limits.maxBodyBytes) {
            s.node.counters.count(Refusal.OFFER_TOO_LARGE)
            return errorAttempt(a, MeshError.FRAME_TOO_LARGE)
        }
        try {
            s.rows.attempt(intentRow(a))
        } catch (e: LedgerWriteException) {
            s.node.counters.count(Refusal.PEER_UNAVAILABLE)
            return decline(a, DeclineWire.PEER_UNAVAILABLE, LenderDecisionTable.CONDITION_RETRY_MS)
        }
        a.intent = true
        a.state = Stage.SERVING
        try {
            a.run = s.node.engine.open(EngineRequest(a.offer.attemptId, a.offer.model, a.offer.op, a.offer.stream, b.bytes))
        } catch (e: Exception) {
            finish(a, EngineEvent.End(Terminal.ERROR, 500, null))
        }
    }

    fun openCount(): Int = served.size

    /**
     * An accepted attempt whose `INFER_BODY` has not arrived by `min(offer.deadlineMs, BODY_WAIT_MS)` after the accept ends as `error` 408: the outcome row is durable
     * before `INFER_END` and the concurrency slot is released (ERRATA ERR-PI-4). A failed outcome append is FC-5, as for any lender outcome.
     */
    fun expireBodies(now: Long) {
        for (a in served.values.toList()) {
            if (a.state != Stage.ACCEPTED || s.closed) continue
            val wait = minOf(a.offer.deadlineMs, SessionLimits.BODY_WAIT_MS)
            if (now - a.acceptedAt >= wait) {
                a.expired = true
                finish(a, EngineEvent.End(Terminal.ERROR, 408, null))
            }
        }
    }

    /** When the accept of the attempt on [stream] was written, on the node's clock; null if it is not open. */
    internal fun acceptedAt(stream: Long): Long? = served[stream]?.acceptedAt

    fun advance(): Int {
        var n = 0
        for (a in served.values.toList()) {
            if (a.state == Stage.ACCEPTED || s.closed) continue
            step(a)
            n++
        }
        return n
    }

    private fun step(a: Served) {
        val ev = try {
            a.run!!.next()
        } catch (e: Exception) {
            EngineEvent.End(Terminal.ERROR, 500, null)
        }
        when (ev) {
            is EngineEvent.Head -> if (a.state == Stage.SERVING && !a.headSent) {
                a.servedModel = ev.servedModel
                sendHead(a, ev.status)
            }
            is EngineEvent.Chunk -> if (a.state == Stage.SERVING) {
                if (!a.headSent) sendHead(a, 200)
                if (!s.closed) send(a) { MessageCodec.frame(InferChunk(ev.bytes), a.stream) }
            }
            is EngineEvent.End -> finish(a, ev)
        }
    }

    private fun sendHead(a: Served, status: Int) {
        a.headSent = true
        send(a) { MessageCodec.frame(InferHead(a.offer.attemptId, a.servedModel, status), a.stream) }
    }

    private fun send(a: Served, build: () -> RawFrame) {
        if (a.done) return
        val frame = try {
            build().also { FrameEncoder.encode(it) }
        } catch (e: WireRefusal) {
            a.run?.cancel()
            return finish(a, EngineEvent.End(Terminal.ERROR, 500, null))
        }
        a.bytesOut += appBytes(frame)
        s.writeAttemptFrame(frame)
    }

    /**
     * The outcome row is durable BEFORE `INFER_END` is written (L-L3) and already counts the `INFER_END` frame. The row, the head and the end are built from one
     * [ServedRecord]. A failed append is FC-5: no `INFER_END`, no `CANCEL` (a lender never sends one, ERRATA ERR-LL-6), the engine is cancelled locally, FC-2.
     */
    private fun finish(a: Served, ev: EngineEvent.End) {
        if (a.done) return
        val rec = ServedRecord(a.servedModel, ev.status, ev.terminal.wire, ev.tokensIn)
        val end = MessageCodec.frame(InferEnd(a.offer.attemptId, rec.status, ev.terminal, s.stIfGranted()), a.stream)
        val out = a.bytesOut + appBytes(end)
        val row = outcomeRow(a, rec.status, rec.meshCode, out, if (a.intent) rec.servedModel else null, rec.tokensIn)
        try {
            s.rows.attempt(row)
        } catch (e: LedgerWriteException) {
            a.run?.cancel()
            release(a)
            s.failClosedNow()
            throw FailClosed()
        }
        a.bytesOut = out
        a.done = true
        release(a)
        s.writeAttemptFrame(end)
    }

    // ------------------------------------------------------------------------------------------------------------ CANCEL

    private fun cancel(f: RawFrame, c: Cancel) {
        val a = served[f.stream]
        val late = finished[f.stream]
        if (a == null && late != null && late.attemptId == c.attemptId && !late.lateCancelUsed) {
            late.lateCancelUsed = true
            s.node.lateCancels.incrementAndGet()
            return s.ignoreFrame()
        }
        if (a == null || a.offer.attemptId != c.attemptId) return s.fail(Refusal.CANCEL_UNKNOWN_ATTEMPT)
        a.bytesIn += appBytes(f)
        when (a.state) {
            Stage.ACCEPTED -> finish(a, EngineEvent.End(Terminal.CANCELLED, 499, null))
            Stage.SERVING -> {
                a.state = Stage.CANCELLING
                a.run?.cancel()
            }
            Stage.CANCELLING -> Unit
        }
    }

    // ------------------------------------------------------------------------------------------------------------ closing

    /** Every attempt that is still open ends with an `interrupted` outcome row (no `INFER_END` can follow); engines are cancelled. Failures are ignored: the session is closing. */
    fun abortAll() {
        for (a in served.values.toList()) {
            try {
                a.run?.cancel()
            } catch (e: RuntimeException) {
                s.node.callbackErrors.incrementAndGet()
            }
            val rec = ServedRecord(a.servedModel, 499, "interrupted", null)
            try {
                s.rows.attempt(outcomeRow(a, rec.status, rec.meshCode, a.bytesOut, if (a.intent) rec.servedModel else null))
            } catch (_: LedgerWriteException) {
            }
            release(a)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ STATE and MANIFEST

    private fun stateReq(f: RawFrame, r: StateReq) {
        if (!s.useStream(f.stream)) return
        s.recvControl(f, r)
        when (val auth = s.node.registry.authorize(s.peer, "state")) {
            is AuthDecision.Allow -> {
                val node = s.node
                val view = node.live.view()
                val seq = node.live.nextSeq()
                val age = node.live.sampledAgeMs().coerceIn(0, 60_000)
                val payload = Jcs.serialize(StateBuilder.build(view, seq, age))
                val doc = (StateParser.parse(payload) as StateParse.Ok).doc
                val frame = MessageCodec.frame(StateMsg(doc), f.stream)
                s.sendControl(frame, StateMsg(doc))
            }
            is AuthDecision.Deny -> deny(f.stream, auth)
        }
    }

    private fun deny(stream: Long, auth: AuthDecision.Deny) {
        if (auth.reason == DenyReason.SCOPE_NOT_GRANTED || auth.reason == DenyReason.UNREADABLE_SCOPES) {
            s.node.counters.count(Refusal.SCOPE_DENIED)
            s.sendControl(PeerError(MeshError.SCOPE_DENIED), stream)
        } else {
            s.node.counters.count(Refusal.PEER_NOT_PAIRED)
            s.sendControl(PeerError(MeshError.PEER_NOT_PAIRED), stream)
            s.closeNow()
        }
    }

    private fun manifestReq(f: RawFrame, r: ManifestReq) {
        if (!s.useStream(f.stream)) return
        s.recvControl(f, r)
        when (val auth = s.node.registry.authorize(s.peer, "manifest")) {
            is AuthDecision.Deny -> deny(f.stream, auth)
            is AuthDecision.Allow -> {
                val challenge = (Base64Strict.decodeUrlNoPad(r.challenge) as B64Result.Ok).bytes
                val container = try {
                    s.node.manifest.present(challenge)?.also { FrameEncoder.encode(RawFrame(xyz.mdhv.asom.lab.proto.wire.FrameTypes.MANIFEST, f.stream, it)) }
                } catch (e: WireRefusal) {
                    null
                }
                if (container == null) {
                    s.node.counters.count(Refusal.MANIFEST_UNAVAILABLE)
                    return s.sendControl(PeerError(MeshError.MANIFEST_UNAVAILABLE), f.stream)
                }
                val digest = Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(container))
                val msg = xyz.mdhv.asom.lab.proto.wire.ManifestMsg(container)
                s.sendControl(MessageCodec.frame(msg, f.stream), msg, routeDetail = "sha256=$digest")
            }
        }
    }
}
