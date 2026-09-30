package xyz.mdhv.asom.lab.ledger.sim

import java.util.SplittableRandom
import xyz.mdhv.asom.lab.ledger.ByteCounter
import xyz.mdhv.asom.lab.ledger.DialResult
import xyz.mdhv.asom.lab.ledger.FrameKind
import xyz.mdhv.asom.lab.ledger.FrameSpec
import xyz.mdhv.asom.lab.ledger.IfaceKind
import xyz.mdhv.asom.lab.ledger.InboundRefusedCounter
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerClass
import xyz.mdhv.asom.lab.ledger.LedgerUnavailableException
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.MemorySink
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.ledger.PeerPathRule
import xyz.mdhv.asom.lab.ledger.RequestLedger
import xyz.mdhv.asom.lab.ledger.RequesterAttempt
import xyz.mdhv.asom.lab.ledger.LenderAttempt
import xyz.mdhv.asom.lab.ledger.RowSink
import xyz.mdhv.asom.lab.ledger.ServedRecord
import xyz.mdhv.asom.lab.ledger.SessionClosedException
import xyz.mdhv.asom.lab.ledger.SessionLedger
import xyz.mdhv.asom.lab.ledger.SessionMode
import xyz.mdhv.asom.lab.ledger.SessionRole
import xyz.mdhv.asom.lab.ledger.dial
import xyz.mdhv.asom.lab.ledger.laws.ContentSent
import xyz.mdhv.asom.lab.ledger.laws.EngineRead
import xyz.mdhv.asom.lab.ledger.laws.Responded
import xyz.mdhv.asom.lab.ledger.laws.Secrets
import xyz.mdhv.asom.lab.ledger.laws.ServedBy
import xyz.mdhv.asom.lab.ledger.laws.Syn
import xyz.mdhv.asom.lab.ledger.laws.Trace

enum class Side { A, B }

sealed interface Step

data class DialStep(
    val mode: SessionMode = SessionMode.ESTABLISHED,
    val outcome: String = "connected",
    val source: String = "qr",
    val iface: IfaceKind = IfaceKind.WIFI,
    val hostile: Boolean = false,
) : Step

data class FrameStep(
    val from: Side,
    val built: Built,
    val requestId: String? = null,
    val served: ServedRecord? = null,
    val model: String = "qwen3-8b",
    val callerPkg: String = "com.example.notes",
) : Step

data class FinishStep(val attemptId: String, val status: Int, val meshCode: String?, val tokensOut: Long? = null) : Step

data class CloseStep(val by: Side) : Step

data class InboundRefusedStep(val count: Int) : Step

sealed interface AttemptPlan {
    data class PeerDecline(val code: String) : AttemptPlan
    data class PeerServed(val terminal: String, val chunks: Int, val bodyBytes: Int) : AttemptPlan
    data class PeerCancelled(val bodyBytes: Int) : AttemptPlan
    data object CloudFail : AttemptPlan
    data object CloudOk : AttemptPlan
    data object LocalOk : AttemptPlan
}

data class RequestStep(val requestId: String, val model: String, val attempts: List<AttemptPlan>) : Step

class SimConfig(
    val seed: Long = 1,
    val measured: Boolean = true,
    val counter: ByteCounter = ByteCounter.EXACT,
    val fault: EngineFault = EngineFault.NONE,
    val killAt: Int? = null,
    val sinkFor: (String) -> RowSink = { MemorySink() },
    val deviceName: String = "Deck of Doom",
)

class SimNode(val name: String, val tag: String, val ledger: NodeLedger)

/**
 * Two nodes, A (the dialer, TLS client, requester) and B (the listener, TLS server, lender), one connection at a time, driven by [Step]s. Every
 * action goes through the ledger's own classes ([SessionLedger], [RequesterAttempt], [LenderAttempt], [RequestLedger]); the instruments that feed the
 * [Trace] sit outside them.
 */
class SimWorld(val cfg: SimConfig = SimConfig()) {
    val trace = Trace()
    val kill = SimKill(cfg.killAt)
    private val clock = SimClock()
    private val rng = SplittableRandom(cfg.seed)
    val payloads = PayloadFactory(rng, cfg.deviceName)
    val a: SimNode = node("A")
    val b: SimNode = node("B")
    var conn: SimConnection? = null
        private set
    var sessA: SessionLedger? = null
        private set
    var sessB: SessionLedger? = null
        private set
    var abortReason: String? = null
        private set
    var connectionsClosed: Int = 0
        private set

    private val requesterAttempts = HashMap<String, RequesterAttempt>()
    private val lenderAttempts = HashMap<String, LenderAttempt>()
    private val attemptRequest = HashMap<String, String>()
    private val declinedAfterBody = HashSet<String>()
    private var attemptCounter = 0
    private val bodies = ArrayList<String>()
    private val alphabet = "ΩЖ字⌘§¶ŧħøæßþ¤¥€£¢©®™✓✗∑∆∏√∞≈≠≤≥"

    init {
        trace.add(Secrets("A", emptyList(), listOf("sk-LAB-SECRET-openrouter", "sk-LAB-SECRET-groq"), listOf(cfg.deviceName)))
    }

    private fun tag(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz234567"
        return (0 until 16).map { chars[rng.nextInt(32)] }.joinToString("")
    }

    private fun node(name: String): SimNode {
        val sink = TracingSink(name, cfg.sinkFor(name), trace, kill)
        return SimNode(name, tag(), NodeLedger(name, sink, clock::now))
    }

    fun rows(node: String): List<LabRouteRecord> = trace.events.filterIsInstance<xyz.mdhv.asom.lab.ledger.laws.Appended>().filter { it.node == node }.map { it.row }

    fun fuzzBody(minLen: Int, maxLen: Int): Int {
        val n = minLen + rng.nextInt(maxLen - minLen + 1)
        val sb = StringBuilder()
        repeat(n) { sb.append(alphabet[rng.nextInt(alphabet.length)]) }
        val text = sb.toString()
        bodies += text
        trace.add(Secrets("A", listOf(text), emptyList(), emptyList()))
        return text.toByteArray(Charsets.UTF_8).size
    }

    /** Runs the steps; an FC-1, FC-2 or write failure aborts the script (the failure is the point of such a run) and the world is then cleaned up. */
    fun run(steps: List<Step>) {
        try {
            for (s in steps) apply(s)
        } catch (e: SessionClosedException) {
            abortReason = "session closed: ${e.message}"
        } catch (e: LedgerUnavailableException) {
            abortReason = "LEDGER_UNAVAILABLE"
        } catch (e: LedgerWriteException) {
            abortReason = "write failed: ${e.message}"
        } catch (e: java.io.IOException) {
            abortReason = "connection closed"
        }
        if (abortReason != null) finishAfterAbort()
    }

    /** After an abort the connection is torn down cleanly so the taps are complete. */
    private fun finishAfterAbort() {
        val c = conn ?: return
        try {
            sessA?.close()
            sessB?.close()
        } catch (_: LedgerWriteException) {
        }
        c.endA.close()
        c.endB.close()
        c.finalizeTaps()
        connectionsClosed++
        conn = null
    }

    fun apply(step: Step) {
        when (step) {
            is DialStep -> dial(step)
            is FrameStep -> frame(step)
            is FinishStep -> requesterAttempts.getValue(step.attemptId).finish(step.status, step.meshCode, step.tokensOut)
            is CloseStep -> close(step)
            is InboundRefusedStep -> {
                val c = InboundRefusedCounter(b.ledger)
                repeat(step.count) { c.refused() }
                c.flush(force = true)
            }
            is RequestStep -> request(step)
        }
    }

    private fun dial(s: DialStep) {
        check(conn == null) { "one connection at a time" }
        val sid = payloads.b64u(16)
        val expected = PeerPathRule.classify(s.iface, s.iface == IfaceKind.OVERLAY, remoteIsPrivateOrLinkLocal = true)
        val addr = "192.168.${rng.nextInt(255)}.${1 + rng.nextInt(250)}:11436"
        var made: SimConnection? = null
        val out = a.ledger.dial(sid, b.tag, addr, s.source) {
            trace.add(Syn(a.name, sid))
            kill.tick()
            if (s.outcome != "connected" || expected == null) {
                DialResult(if (s.outcome != "connected") s.outcome else "local-network-denied", null)
            } else {
                val c = SimConnection(trace, kill, rng, a.name, if (s.hostile) null else b.name, cfg.measured, cfg.fault, expected)
                c.sidA = sid
                c.handshake()
                made = c
                DialResult("connected", c.endA.meter, expected)
            }
        }
        if (out.code != "connected") return
        val c = made!!
        conn = c
        sessA = SessionLedger(a.ledger, sid, s.mode, SessionRole.DIALER, b.tag, expected, c.endA, cfg.counter, out.handshakeOverhead ?: 0)
        if (!s.hostile && s.mode == SessionMode.PAIRING) {
            val derived = payloads.b64u(16)
            c.sidB = derived
            sessB = SessionLedger(b.ledger, derived, SessionMode.PAIRING, SessionRole.LISTENER, a.tag, expected, c.endB, cfg.counter)
            sessB!!.open()
        }
    }

    private fun close(s: CloseStep) {
        val c = conn ?: return
        val first = if (s.by == Side.A) sessA else sessB
        val second = if (s.by == Side.A) sessB else sessA
        first?.close()
        second?.close()
        if (sessB == null) c.endB.close()
        c.endA.close()
        c.finalizeTaps()
        connectionsClosed++
        conn = null
        sessA = null
        sessB = null
    }

    private fun frame(step: FrameStep) {
        val c = conn ?: error("no connection")
        var frame = step.built.frame
        val fromA = step.from == Side.A
        if (frame.kind == FrameKind.HELLO && fromA && frame.joinId == null) frame = frame.copy(joinId = sessA!!.sessionId)
        if (frame.kind == FrameKind.PAIR_HELLO && !fromA && frame.joinId == null) frame = frame.copy(joinId = sessB!!.sessionId)
        if (!fromA && sessB == null && c.nodeB == null) {
            c.inject(frame)
            c.deliver(true, frame)
            sessA!!.receive(frame)
            return
        }
        if (frame.ledgerClass == LedgerClass.ATTEMPT) attemptFrame(step, frame, step.built.text) else controlFrame(step, frame, step.built.text)
    }

    private fun controlFrame(step: FrameStep, frame: FrameSpec, text: String?) {
        val c = conn!!
        val fromA = step.from == Side.A
        val sender = if (fromA) sessA!! else sessB!!
        c.setPayload(text)
        sender.send(frame)
        if (fromA && sessB == null && frame.kind == FrameKind.HELLO) {
            c.sidB = frame.joinId
            sessB = SessionLedger(b.ledger, frame.joinId!!, SessionMode.ESTABLISHED, SessionRole.LISTENER, a.tag, c.expectedPath, c.endB, cfg.counter)
            c.deliver(false, frame)
            sessB!!.open()
            sessB!!.receive(frame)
            return
        }
        c.deliver(!fromA, frame)
        (if (fromA) sessB else sessA)!!.receive(frame)
    }

    private fun attemptFrame(step: FrameStep, frame: FrameSpec, text: String?) {
        val c = conn!!
        val id = frame.attemptId!!
        val fromA = step.from == Side.A
        when (frame.kind) {
            FrameKind.INFER_OFFER -> {
                val ra = sessA!!.requesterAttempt(step.requestId, id, requesterAttempts.size, step.callerPkg, step.model)
                ra.begin()
                requesterAttempts[id] = ra
                if (step.requestId != null) attemptRequest[id] = step.requestId
                c.setPayload(text)
                ra.send(frame)
                c.deliver(false, frame)
                val la = sessB!!.lenderAttempt(id, step.model)
                lenderAttempts[id] = la
                la.receiveOffer(frame)
            }
            FrameKind.INFER_ACCEPT -> {
                c.setPayload(text)
                lenderAttempts.getValue(id).accept(frame)
                c.deliver(true, frame)
                requesterAttempts.getValue(id).receive(frame)
            }
            FrameKind.INFER_DECLINE -> {
                c.setPayload(text)
                lenderAttempts.getValue(id).decline(frame)
                c.deliver(true, frame)
                requesterAttempts.getValue(id).receive(frame)
            }
            FrameKind.INFER_BODY -> {
                val ra = requesterAttempts.getValue(id)
                c.setPayload(null)
                ra.send(frame)
                attemptRequest[id]?.let { trace.add(ContentSent("A", it, id, LabEgress.peerClass)) }
                c.deliver(false, frame)
                var declined: FrameSpec? = null
                val ok = lenderAttempts.getValue(id).receiveBody(frame) {
                    val d = payloads.decline(id, frame.stream, "PEER_UNAVAILABLE")
                    c.setPayload(d.text)
                    d.frame.also { declined = it }
                }
                if (ok) {
                    kill.tick()
                    trace.add(EngineRead("B", id))
                } else {
                    declinedAfterBody += id
                    c.deliver(true, declined!!)
                    ra.receive(declined!!)
                }
            }
            FrameKind.INFER_HEAD, FrameKind.INFER_CHUNK -> {
                c.setPayload(text)
                lenderAttempts.getValue(id).send(frame)
                c.deliver(true, frame)
                requesterAttempts.getValue(id).receive(frame)
            }
            FrameKind.INFER_END -> {
                c.setPayload(text)
                val ok = lenderAttempts.getValue(id).finish(frame, step.served ?: error("INFER_END needs the served record"))
                if (ok) {
                    c.deliver(true, frame)
                    requesterAttempts.getValue(id).receive(frame)
                }
            }
            FrameKind.CANCEL -> {
                c.setPayload(text)
                requesterAttempts.getValue(id).send(frame)
                c.deliver(false, frame)
                lenderAttempts.getValue(id).receiveOther(frame)
            }
            FrameKind.ERROR -> {
                c.setPayload(text)
                if (fromA) {
                    requesterAttempts.getValue(id).send(frame)
                    c.deliver(false, frame)
                    lenderAttempts.getValue(id).receiveOther(frame)
                } else {
                    lenderAttempts.getValue(id).decline(frame)
                    c.deliver(true, frame)
                    requesterAttempts.getValue(id).receive(frame)
                }
            }
            else -> error("not an attempt frame: ${frame.kind}")
        }
    }

    private fun nextStream(): Int = 1 + 2 * (attemptCounter++)

    private fun request(r: RequestStep) {
        val rl = RequestLedger(a.ledger, r.requestId, "com.example.notes", r.model)
        var served = false
        plans@ for (plan in r.attempts) {
            if (served) break
            when (plan) {
                is AttemptPlan.PeerDecline -> {
                    val id = payloads.attemptId()
                    val st = nextStream()
                    frame(FrameStep(Side.A, payloads.offer(id, st, r.model, 1200), r.requestId, model = r.model))
                    frame(FrameStep(Side.B, payloads.decline(id, st, plan.code), model = r.model))
                    apply(FinishStep(id, 503, plan.code))
                    rl.note(LabEgress.peerClass, contentSent = false, served = false)
                }
                is AttemptPlan.PeerServed -> {
                    val id = payloads.attemptId()
                    val st = nextStream()
                    val rec = ServedRecord(r.model, if (plan.terminal == "done") 200 else 502, plan.terminal, 300)
                    frame(FrameStep(Side.A, payloads.offer(id, st, r.model, plan.bodyBytes), r.requestId, model = r.model))
                    frame(FrameStep(Side.B, payloads.accept(id, st, r.model), model = r.model))
                    frame(FrameStep(Side.A, payloads.raw(FrameKind.INFER_BODY, plan.bodyBytes, st, id), r.requestId, model = r.model))
                    if (id in declinedAfterBody) {
                        apply(FinishStep(id, 503, "PEER_UNAVAILABLE"))
                        rl.note(LabEgress.peerClass, contentSent = true, served = false)
                        continue@plans
                    }
                    frame(FrameStep(Side.B, payloads.head(id, st, rec), model = r.model))
                    repeat(plan.chunks) { frame(FrameStep(Side.B, payloads.raw(FrameKind.INFER_CHUNK, 200 + rng.nextInt(20_000), st, id), model = r.model)) }
                    frame(FrameStep(Side.B, payloads.end(id, st, rec), served = rec, model = r.model))
                    val status = if (plan.terminal == "done") 200 else 502
                    apply(FinishStep(id, status, if (plan.terminal == "done") null else "PEER_${plan.terminal.uppercase()}", 240))
                    val ok = plan.terminal == "done"
                    rl.note(LabEgress.peerClass, contentSent = true, served = ok, provider = if (ok) "peer:deck" else null, model = if (ok) r.model else null)
                    served = ok
                }
                is AttemptPlan.PeerCancelled -> {
                    val id = payloads.attemptId()
                    val st = nextStream()
                    val rec = ServedRecord(r.model, 499, "cancelled", 300)
                    frame(FrameStep(Side.A, payloads.offer(id, st, r.model, plan.bodyBytes), r.requestId, model = r.model))
                    frame(FrameStep(Side.B, payloads.accept(id, st, r.model), model = r.model))
                    frame(FrameStep(Side.A, payloads.raw(FrameKind.INFER_BODY, plan.bodyBytes, st, id), r.requestId, model = r.model))
                    if (id in declinedAfterBody) {
                        apply(FinishStep(id, 503, "PEER_UNAVAILABLE"))
                        rl.note(LabEgress.peerClass, contentSent = true, served = false)
                        continue@plans
                    }
                    frame(FrameStep(Side.B, payloads.head(id, st, rec), model = r.model))
                    frame(FrameStep(Side.A, payloads.cancel(id, st, "deadline"), model = r.model))
                    frame(FrameStep(Side.B, payloads.end(id, st, rec), served = rec, model = r.model))
                    apply(FinishStep(id, 499, "CANCELLED", 0))
                    rl.note(LabEgress.peerClass, contentSent = true, served = false)
                }
                AttemptPlan.CloudFail, AttemptPlan.CloudOk -> {
                    kill.tick()
                    trace.add(ContentSent("A", r.requestId, "cloud-${attemptCounter++}", LabEgress.CLOUD))
                    val ok = plan == AttemptPlan.CloudOk
                    rl.note(LabEgress.CLOUD, contentSent = true, served = ok, provider = if (ok) "openrouter" else null, model = if (ok) r.model else null)
                    served = ok
                }
                AttemptPlan.LocalOk -> {
                    rl.note(LabEgress.LOCAL, contentSent = false, served = true, provider = "local", model = r.model)
                    served = true
                }
            }
        }
        val cloudServed = served && r.attempts.lastOrNull { it == AttemptPlan.CloudOk } != null
        val row = rl.terminal(if (served) 200 else 502, costEst = if (cloudServed) 0.0000029 else null, costBasis = if (cloudServed) "usage" else "none")
        val servedClass = row.servedClass
        trace.add(ServedBy("A", r.requestId, servedClass))
        trace.add(Responded("A", r.requestId, row.toEchoHeaders()))
    }
}
