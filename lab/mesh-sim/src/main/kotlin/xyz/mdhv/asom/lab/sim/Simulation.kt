package xyz.mdhv.asom.lab.sim

import java.io.File
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.ledger.FrameKind
import xyz.mdhv.asom.lab.ledger.FrameSpec
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerUnavailableException
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.MemorySink
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.ledger.RequestLedger
import xyz.mdhv.asom.lab.ledger.RequesterAttempt
import xyz.mdhv.asom.lab.ledger.RowSink
import xyz.mdhv.asom.lab.ledger.ServedRecord
import xyz.mdhv.asom.lab.ledger.SessionClosedException
import xyz.mdhv.asom.lab.ledger.SessionLedger
import xyz.mdhv.asom.lab.ledger.SessionMode
import xyz.mdhv.asom.lab.ledger.SessionRole
import xyz.mdhv.asom.lab.ledger.dial
import xyz.mdhv.asom.lab.ledger.DialResult
import xyz.mdhv.asom.lab.ledger.LenderAttempt
import xyz.mdhv.asom.lab.ledger.ByteCounter
import xyz.mdhv.asom.lab.ledger.laws.ContentSent
import xyz.mdhv.asom.lab.ledger.laws.EngineRead
import xyz.mdhv.asom.lab.ledger.laws.Responded
import xyz.mdhv.asom.lab.ledger.laws.ServedBy
import xyz.mdhv.asom.lab.ledger.laws.Syn
import xyz.mdhv.asom.lab.ledger.laws.Trace
import xyz.mdhv.asom.lab.ledger.sim.Built
import xyz.mdhv.asom.lab.ledger.sim.EngineFault
import xyz.mdhv.asom.lab.ledger.sim.PayloadFactory
import xyz.mdhv.asom.lab.ledger.sim.SimConnection
import xyz.mdhv.asom.lab.ledger.sim.SimKill
import xyz.mdhv.asom.lab.ledger.sim.TracingSink
import xyz.mdhv.asom.lab.policy.DeclineCode
import xyz.mdhv.asom.lab.policy.Dest
import xyz.mdhv.asom.lab.policy.FsmEvent
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.HostInput
import xyz.mdhv.asom.lab.policy.LenderReply
import xyz.mdhv.asom.lab.policy.OfferView
import xyz.mdhv.asom.lab.policy.PeerEligibility
import xyz.mdhv.asom.lab.policy.PeerRegistryView
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.policy.StDigest
import xyz.mdhv.asom.lab.policy.StateBuilder
import xyz.mdhv.asom.lab.policy.StateParse
import xyz.mdhv.asom.lab.policy.StateParser
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.router.Action
import xyz.mdhv.asom.lab.router.AttemptContext
import xyz.mdhv.asom.lab.router.AttemptEvent
import xyz.mdhv.asom.lab.router.AttemptObserver
import xyz.mdhv.asom.lab.router.AttemptPhase
import xyz.mdhv.asom.lab.router.BreakerEffect
import xyz.mdhv.asom.lab.router.CapDelta
import xyz.mdhv.asom.lab.router.ClaimKey
import xyz.mdhv.asom.lab.router.ClaimState
import xyz.mdhv.asom.lab.router.ClaimTracker
import xyz.mdhv.asom.lab.router.Estimator
import xyz.mdhv.asom.lab.router.Failover
import xyz.mdhv.asom.lab.router.MeshConfig
import xyz.mdhv.asom.lab.router.MeshPlan
import xyz.mdhv.asom.lab.router.MeshPlanException
import xyz.mdhv.asom.lab.router.MeshQuery
import xyz.mdhv.asom.lab.router.MeshRouter
import xyz.mdhv.asom.lab.router.PerfPrior
import xyz.mdhv.asom.lab.router.PlannedAttempt
import xyz.mdhv.asom.lab.router.StDigestParser
import xyz.mdhv.asom.lab.router.Tier
import xyz.mdhv.asom.lab.router.TrackerState
import xyz.mdhv.asom.lab.router.TrackerEffect
import xyz.mdhv.asom.lab.router.LiveStateCache

enum class Variant { B3, B0, B1, B2 }

class AttemptLog(val requestId: String, val index: Int, val tier: Tier, val target: String, val startT: Long) {
    var status: String = "PLANNED"
    var offerAt: Long? = null
    var answeredAt: Long? = null
    var bodyAt: Long? = null
    var endedAt: Long? = null
    var declined = false
    var bodySent = false
    var deliveredToClient = false
    var attemptId: String? = null
    var registryOkAtBody: Boolean? = null
}

class ReqRecord(val id: String, val app: AppSpec, val startT: Long, val model: String, val P: Int, val B: Long, val N: Int, val maxTokens: Int, val stream: Boolean, val deadlineMs: Long) {
    val attempts = ArrayList<AttemptLog>()
    var status = 0
    var servedBy: String? = null
    var servedTier: Tier? = null
    var error: String? = null
    var interrupted = false
    var endT = 0L
    var firstReason: String? = null
    var firstTarget: String? = null
    var planned: List<String> = emptyList()
    var hindsight: String? = null
    var ledgerUnavailable = false
}

/** An attempt that wants to hear when its peer disappears: the requester notices a dead connection after about two round trips. */
interface Losable {
    fun peerVanished()
}

class DecisionRec(val requestId: String, val t: Long, val seq: Long, val attempts: List<String>, val error: String?, val excluded: List<String> = emptyList())

class SimResult(
    val scenario: String, val seed: Long, val variant: Variant, val requests: List<ReqRecord>, val events: List<SimEvent>, val decisions: List<DecisionRec>, val trace: Trace,
    val ledgerRows: Map<String, List<LabRouteRecord>>, val selfEnergyMilliJ: Long, val discrepantAfterKept: Int?, val keptObservationsToDiscrepant: Map<String, Int>,
) {
    val label: String get() = SIM_LABEL

    fun eventsJsonl(): List<String> = listOf(Jcs.serializeToString(JObject(listOf("label" to JString(SIM_LABEL))))) + events.map { Jcs.serializeToString(EventCodec.toJson(it)) }

    fun decisionsJsonl(): List<String> = listOf(Jcs.serializeToString(JObject(listOf("label" to JString(SIM_LABEL))))) + decisions.map { it.line() }
}

/**
 * The discrete-event simulator of LAB_SPEC 6.9. It drives the REAL [MeshRouter], the real reducers (through [RequesterModel]), the real ledger model
 * (`NodeLedger`, `SessionLedger`, `RequesterAttempt`, `LenderAttempt`, `RequestLedger`, with their write-ahead assertions), the real lender decision table and the
 * real availability FSM. Only the transport, the engines, the clock and the probes are simulated (everything below `SimPeer`, the links and the cloud).
 * Evidence label: SIMULATED — NOT DEVICE EVIDENCE.
 */
class Simulation(val sc: Scenario, val catalogue: Catalogue, val variant: Variant = Variant.B3, private val cfg: MeshConfig = MeshConfig()) {
    private val rnd = SimRandom(sc.seed)
    private val loop = EventLoop()
    val model = RequesterModel(sc, catalogue, cfg)
    private val events = ArrayList<SimEvent>()
    private val decisions = ArrayList<DecisionRec>()
    private val requests = ArrayList<ReqRecord>()
    val trace = Trace()
    private val kill = SimKill(null)
    private val selfName = sc.self.id
    private val payloads = PayloadFactory(rnd.ids)
    private val peerSims: Map<String, SimPeer> = sc.peers.associate { it.id to SimPeer(it, rnd) }
    private val linkSpecs = HashMap<String, LinkSpec>()
    private val sinkFail = BooleanArray(1)
    private val rowsByNode = HashMap<String, MemorySink>()
    private val ledgers = HashMap<String, NodeLedger>()
    private var evSeq = 0L
    private var streamCounter = 1
    private val sessions = HashMap<String, PeerSession>()
    private val lastPullAt = HashMap<String, Long>()
    private val generation = HashMap<String, Int>()
    private var selfBusyUntil = 0L
    private var selfBusySince: Long? = null
    private var selfEnergyMilliJ = 0L
    private var selfBatteryMilliJ: Long? = sc.self.power.batteryPermille?.let { p -> (sc.self.power.designMilliWh ?: 0) * 3600 * p / 1000 }
    private var selfThermalUntil = -1L
    private var selfBatteryOverride: Int? = null
    private var selfCharging = sc.self.power.source == "ac"
    private var dupArmed = false
    private val lastAttemptIdTo = HashMap<String, String>()
    private val liveAttempts = HashMap<String, MutableList<Losable>>()
    private val keptToDiscrepant = HashMap<String, Int>()
    private var firstDiscrepantKept: Int? = null

    private class ToggleSink(val inner: MemorySink, val flag: BooleanArray) : RowSink {
        override fun append(row: LabRouteRecord) {
            if (flag[0]) throw LedgerWriteException("injected: the ledger is full")
            inner.append(row)
        }
    }

    init {
        for (l in sc.links) linkSpecs[if (l.a == selfName) l.b else l.a] = l
        for (n in sc.nodes) {
            val mem = MemorySink()
            rowsByNode[n.id] = mem
            val inner: RowSink = if (n.tier == Tier.SELF) ToggleSink(mem, sinkFail) else mem
            ledgers[n.id] = NodeLedger(n.id, TracingSink(n.id, inner, trace, kill), loop::now)
        }
    }

    private val selfLedger: NodeLedger get() = ledgers.getValue(selfName)

    init {
        loop.onError = ::handleError
    }

    /**
     * A ledger failure inside a callback (FC-1, FC-2): the request the callback belongs to fails closed with LEDGER_UNAVAILABLE and tries no other candidate. A `SessionClosedException`
     * that is not the requester's own session (the lender's side of a torn-down connection) only means the frame was lost, and the request's timers decide what follows.
     */
    private fun handleError(ctx: Any?, e: Throwable): Boolean {
        val run = ctx as? RequestRun ?: return false
        return when (e) {
            is LedgerWriteException, is LedgerUnavailableException -> {
                run.ledgerAbort()
                true
            }
            is SessionClosedException -> {
                val bad = sessions.filter { it.value.sessA.closed }.keys.sorted()
                if (bad.isNotEmpty()) {
                    bad.forEach { closeSession(it, "ledger-failure") }
                    run.ledgerAbort()
                }
                true
            }
            else -> false
        }
    }

    /** Test seams (module-internal): a callback at every offer, a way to schedule work on the virtual clock and a way to log a requester event. None is reachable from a scenario file. */
    internal var onOffer: ((String) -> Unit)? = null

    internal fun scheduleForTest(atMs: Long, f: () -> Unit) = loop.at(atMs, "test-hook", f)

    internal fun afterForTest(dtMs: Long, f: () -> Unit) = loop.after(dtMs, "test-hook", f)

    internal fun emitForTest(kind: String, vararg fields: Pair<String, Any?>) = emit(kind, *fields)

    internal fun nowForTest(): Long = loop.now

    private fun emit(kind: String, vararg fields: Pair<String, Any?>): SimEvent {
        val e = SimEvent(loop.now, evSeq++, kind, fields.toMap())
        events += e
        model.apply(e)
        return e
    }

    private fun rtt(peer: String): Long = SimRandom.lognormal(linkSpecs.getValue(peer).rttMedianMs, linkSpecs.getValue(peer).rttSigmaPermille, rnd.links)

    private fun nextStream(): Int = streamCounter.also { streamCounter += 2 }

    private fun built(kind: FrameKind, members: List<Pair<String, JValue>>, stream: Int, attemptId: String? = null, code: String? = null, joinId: String? = null): Built {
        val text = Jcs.serializeToString(JObject(members))
        return Built(FrameSpec(kind, text.toByteArray(Charsets.UTF_8).size, stream, attemptId, code, joinId), text)
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------
    // setup
    // ----------------------------------------------------------------------------------------------------------------------------------------------
    private fun selfSituationEvent() {
        val t = loop.now
        val spec = sc.self
        val inSpan = spec.presence.any { t >= it.fromMs && t <= it.toMs }
        val perm = selfBatteryOverride ?: selfBatteryMilliJ?.let { mj -> ((mj * 1000) / ((spec.power.designMilliWh ?: 1) * 3600)).toInt().coerceIn(0, 1000) }
        val busyFor = if (selfBusySince != null && t < selfBusyUntil + 60_000) t - selfBusySince!! else 0L
        emit(
            "self", "battery" to (perm?.toLong() ?: -1L), "charging" to selfCharging, "onBattery" to (!selfCharging && spec.power.source == "battery"), "design" to (spec.power.designMilliWh ?: -1L),
            "thermal" to (if (t < selfThermalUntil) 3L else 0L), "gov" to "RUN", "avail" to 30_000_000_000L, "loaded" to spec.files.map { it.fileSha256 }, "active" to inSpan,
            "busy" to busyFor, "queue" to maxOf(0L, selfBusyUntil - t), "engine" to (variant != Variant.B0),
        )
    }

    fun run(): SimResult {
        val spec = sc.self
        for (f in spec.files) {
            val tr = spec.truth.getValue(f.fileSha256)
            emit(
                "calib", "sha" to f.fileSha256, "backend" to spec.backend, "prefill" to tr.prefillMilliTokPerSec, "decode" to tr.decodeMilliTokPerSec, "ttft0" to tr.ttft0Ms,
                "steady" to tr.steadyMilliTokPerSec, "onset" to (tr.throttleOnsetMs ?: -1L), "power" to -1L, "kv" to 100_000L, "peak" to 6_000_000_000L,
            )
        }
        emit("mesh", "on" to (variant != Variant.B0))
        for (p in sc.peers) {
            val ps = peerSims.getValue(p.id)
            val l = linkSpecs.getValue(p.id)
            emit("registry", "peer" to p.id, "paired" to true, "route" to true, "grant" to true, "requireCharging" to false)
            emit("link", "peer" to p.id, "rtt" to 0L, "kbps" to 0L, "path" to l.path, "metered" to l.metered, "warm" to false, "samples" to 0L)
            publishClaims(p, 1)
            ps.start(0)
            for (span in p.presence) {
                loop.at(span.fromMs, "presence") { ps.presence(loop.now) }
                loop.at(span.toMs, "presence-end") { ps.presence(loop.now) }
            }
        }
        scheduleArrivals()
        scheduleFaults()
        loop.runAll()
        for (id in sessions.keys.sorted()) closeSession(id, "end")
        return SimResult(
            sc.id, sc.seed, variant, requests, events, decisions, trace, rowsByNode.mapValues { it.value.all() }, selfEnergyMilliJ, firstDiscrepantKept, keptToDiscrepant,
        )
    }

    private fun publishClaims(p: SimNodeSpec, seq: Long) {
        val ps = peerSims.getValue(p.id)
        for (f in p.files) {
            val c = ps.claim(f.fileSha256)
            emit(
                "claim", "peer" to p.id, "sha" to f.fileSha256, "backend" to p.backend, "cseq" to seq, "prefill" to c.prefillMilliTokPerSec, "decode" to c.decodeAt.first().second,
                "ttft0" to c.ttft0Ms, "steady" to c.steadyMilliTokPerSec, "onset" to (c.throttleOnsetMs ?: -1L), "power" to -1L, "kv" to c.kvBytesPerToken, "peak" to c.peakProcessBytes,
                "commit" to ps.commit,
            )
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------
    // workload
    // ----------------------------------------------------------------------------------------------------------------------------------------------
    private fun scheduleArrivals() {
        var n = 0
        for (app in sc.apps) {
            if (app.arrivalsPerHour <= 0) continue
            var t = 0L
            val mean = 3_600_000.0 / app.arrivalsPerHour
            while (true) {
                t += SimRandom.exponentialMs(mean, rnd.workload)
                if (t >= sc.durationMs) break
                val p = SimRandom.lognormal(app.promptTokensMedian, app.sigmaPermille, rnd.workload).coerceIn(1, 30_000).toInt()
                val out = SimRandom.lognormal(app.outTokensMedian, app.sigmaPermille, rnd.workload).coerceIn(1, 3_000).toInt()
                val stream = rnd.workload.nextInt(1000) < app.streamPermille
                val id = "req-${sc.id.lowercase()}-%05d".format(++n)
                val model = if (variant == Variant.B1) "local-only" else app.policy
                val maxTokens = (app.maxTokensCap ?: minOf(4_096L, 4 * app.outTokensMedian)).toInt()
                val rec = ReqRecord(id, app, t, model, p, p * 4L, minOf(out, maxTokens), maxTokens, stream, 120_000)
                loop.at(t, "arrival") { start(rec) }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------
    // faults (LAB_SPEC 6.9, closed list)
    // ----------------------------------------------------------------------------------------------------------------------------------------------
    private fun scheduleFaults() {
        for (f in sc.faults.sortedBy { it.atMs }) {
            loop.at(f.atMs, "fault:${f.kind}") { applyFault(f) }
        }
    }

    private fun revive(f: FaultSpec, undo: () -> Unit) {
        f.durationMs?.let { loop.after(it, "fault-end:${f.kind}") { undo() } }
    }

    private fun applyFault(f: FaultSpec) {
        val now = loop.now
        val node = f.node
        val ps = node?.let { peerSims[it] }
        when (f.kind) {
            "peer-vanish" -> {
                ps!!
                when (f.phase) {
                    "mid-stream" -> ps.armedMidStreamMs = f.durationMs
                    "before-head" -> ps.armedBeforeHeadMs = f.durationMs
                    else -> vanish(ps, f.durationMs)
                }
            }
            "session-drop" -> dropSession(node!!)
            "network-change" -> {
                for (id in peerSims.keys.sorted()) {
                    val old = linkSpecs.getValue(id)
                    linkSpecs[id] = old.copy(rttMedianMs = old.rttMedianMs * (f.valuePermille ?: 3000) / 1000)
                    dropSession(id)
                    emit("link", "peer" to id, "rtt" to 0L, "kbps" to 0L, "path" to old.path, "metered" to old.metered, "warm" to false, "samples" to 0L)
                }
            }
            "thermal-spike" -> {
                if (ps != null) {
                    ps.thermalBand = 2
                    ps.conditionChanged(now)
                    revive(f) { ps.thermalBand = 0; ps.conditionChanged(loop.now) }
                } else {
                    selfThermalUntil = now + (f.durationMs ?: 600_000)
                }
            }
            "charger-unplug" -> {
                if (ps != null) {
                    ps.powerSource = "battery"
                    ps.batteryPermille = ps.batteryPermille ?: 900
                    ps.conditionChanged(now)
                    revive(f) { ps.powerSource = "ac"; ps.conditionChanged(loop.now) }
                } else {
                    selfCharging = false
                }
            }
            "battery-floor" -> {
                if (ps != null) {
                    ps.batteryPermille = (f.valuePermille ?: 150).toInt()
                    ps.powerSource = "battery"
                    ps.conditionChanged(now)
                } else {
                    selfBatteryOverride = (f.valuePermille ?: 150).toInt()
                }
            }
            "presence" -> {
                if (ps != null) {
                    ps.presence(now)
                    f.durationMs?.let { d -> loop.after(d, "presence-end") { ps.presence(loop.now) } }
                }
            }
            "claim-lie" -> {
                ps!!.claimScale = f.valuePermille ?: 2000
                loop.after(1_000, "claim-publish") { publishClaims(ps.spec, loop.now / 86_400_000 + 2) }
            }
            "claim-stale" -> ps!!.commit = "beefcafe"
            "decline-storm" -> ps!!.stormUntil = now + (f.durationMs ?: 300_000)
            "state-delay" -> {
                ps!!.stateDelayUntil = now + (f.durationMs ?: 300_000)
                ps.stateDelayMs = f.valuePermille ?: 3_000
            }
            "state-drop" -> ps!!.stateDropUntil = now + (f.durationMs ?: 300_000)
            "clock-skew" -> ps!!.clockSkewMs = f.valuePermille ?: 600_000
            "duplicate-attempt" -> dupArmed = true
            "overlay-only-high-rtt" -> {
                val old = linkSpecs.getValue(node!!)
                linkSpecs[node] = old.copy(path = "OVERLAY", rttMedianMs = f.valuePermille ?: 200, kbps = 20_000)
                emit("link-meta", "peer" to node, "metered" to old.metered, "path" to "OVERLAY")
            }
            "metered-underlay" -> {
                val old = linkSpecs.getValue(node!!)
                linkSpecs[node] = old.copy(metered = true)
                emit("link-meta", "peer" to node, "metered" to true, "path" to old.path)
            }
            "ledger-full" -> {
                sinkFail[0] = true
                revive(f) { sinkFail[0] = false }
            }
        }
    }

    private fun vanish(ps: SimPeer, durationMs: Long?) {
        if (!ps.alive) return
        ps.alive = false
        generation[ps.spec.id] = (generation[ps.spec.id] ?: 0) + 1
        liveAttempts[ps.spec.id]?.toList()?.forEach { it.peerVanished() }
        durationMs?.takeIf { it >= 0 }?.let { d ->
            loop.after(d, "revive") {
                ps.alive = true
                ps.inflight = 0
                ps.start(loop.now)
            }
        }
    }

    private fun dropSession(peer: String) {
        generation[peer] = (generation[peer] ?: 0) + 1
        closeSession(peer, "drop")
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------
    // sessions (the real ledger classes; the "network" is the lab's SimConnection)
    // ----------------------------------------------------------------------------------------------------------------------------------------------
    inner class PeerSession(val peer: SimPeer, val conn: SimConnection, val sessA: SessionLedger, var sessB: SessionLedger?, val gen: Int) {
        var lastUse = loop.now
        val openedAt = loop.now
    }

    private fun closeSession(peer: String, why: String) {
        val s = sessions.remove(peer) ?: return
        try {
            s.sessA.close()
            if (s.peer.alive) s.sessB?.close()
            s.conn.endA.close()
            if (s.peer.alive) s.conn.endB.close()
            s.conn.finalizeTaps()
        } catch (_: LedgerWriteException) {
        }
        emit("session-close", "peer" to peer, "why" to why)
    }

    private fun sessionUsable(s: PeerSession): Boolean {
        val g = generation[s.peer.spec.id] ?: 0
        return s.gen == g && s.peer.alive && !s.sessA.closed && loop.now - s.lastUse < 300_000 && loop.now - s.openedAt < 1_800_000
    }

    /** Opens (or reuses) the session to [peer]. `k(false)` when the peer cannot be reached. A ledger failure on the DIAL intent aborts with [LedgerUnavailableException]. */
    private fun ensureSession(peer: String, k: (Boolean) -> Unit) {
        val old = sessions[peer]
        if (old != null && sessionUsable(old)) {
            old.lastUse = loop.now
            k(true)
            return
        }
        if (old != null) closeSession(peer, "idle")
        val ps = peerSims.getValue(peer)
        val sid = payloads.b64u(16)
        val addr = "192.168.1.${10 + peerSims.keys.sorted().indexOf(peer)}:11436"
        var made: SimConnection? = null
        val path = PeerPath.valueOf(linkSpecs.getValue(peer).path)
        val out = selfLedger.dial(sid, peer, addr, "qr") {
            trace.add(Syn(selfName, sid))
            kill.tick()
            if (!ps.alive) {
                DialResult("unreachable", null)
            } else {
                val c = SimConnection(trace, kill, rnd.ids, selfName, peer, true, EngineFault.NONE, path)
                c.sidA = sid
                c.handshake()
                made = c
                DialResult("connected", c.endA.meter, path)
            }
        }
        if (out.code != "connected") {
            loop.after(1_000, "dial-timeout") {
                emit("breaker-fail", "peer" to peer)
                k(false)
            }
            return
        }
        val r = rtt(peer)
        val hs = 3 * r + 40
        loop.after(hs, "handshake") {
            if (!ps.alive) {
                emit("breaker-fail", "peer" to peer)
                k(false)
                return@after
            }
            val c = made!!
            val sessA = SessionLedger(selfLedger, sid, SessionMode.ESTABLISHED, SessionRole.DIALER, peer, path, c.endA, ByteCounter.EXACT, out.handshakeOverhead ?: 0)
            val hello = payloads.hello(sid)
            c.setPayload(hello.text)
            sessA.send(hello.frame)
            val sessB = SessionLedger(ledgers.getValue(peer), sid, SessionMode.ESTABLISHED, SessionRole.LISTENER, selfName, path, c.endB, ByteCounter.EXACT)
            c.sidB = sid
            c.deliver(false, hello.frame)
            sessB.open()
            sessB.receive(hello.frame)
            ps.fsmAt(loop.now)
            val view = ps.localView(loop.now)
            ps.stateSeq++
            val ack = built(
                FrameKind.HELLO_ACK,
                listOf(
                    "endpoints" to JArray(emptyList()), "features" to JArray(emptyList()), "granted" to JArray(listOf("infer", "manifest", "state").map { JString(it) }),
                    "limits" to JObject(listOf("idleUnloadMs" to JInt(300_000), "maxBodyBytes" to JInt(8_388_608), "maxConcurrent" to JInt(1), "maxTokens" to JInt(4096), "rpm" to JInt(30))),
                    "nodeId" to JString(peer), "st" to StDigest.build(view, ps.stateSeq), "ts" to JInt(WALL_BASE_MS + loop.now + ps.clockSkewMs), "v" to JInt(1),
                ),
                0,
            )
            c.setPayload(ack.text)
            sessB.send(ack.frame)
            c.deliver(true, ack.frame)
            sessA.receive(ack.frame)
            sessions[peer] = PeerSession(ps, c, sessA, sessB, generation[peer] ?: 0)
            emit("session-open", "peer" to peer)
            emit("link-rtt", "peer" to peer, "ms" to r)
            k(true)
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------
    // one request
    // ----------------------------------------------------------------------------------------------------------------------------------------------
    private fun start(rec: ReqRecord) {
        requests += rec
        RequestRun(rec).begin()
    }

    private fun peerAllowedFor(rec: ReqRecord): Boolean =
        model.meshOn && rec.app.meshAllowed && !rec.app.deviceOnly && rec.model != "local-only" && variant != Variant.B0

    private fun needsPull(p: RequesterModel.Peer): Boolean {
        if (!(p.paired && p.route && p.grant)) return false
        if (PureBreakerCooling(p)) return false
        if ((p.declineUntilMono ?: 0) > loop.now) return false
        if (loop.now - (lastPullAt[p.spec.id] ?: -1_000_000) < 5_000) return false
        val fr = LiveStateCache.freshness(p.cache, loop.now)
        return fr == Freshness.STALE || fr == Freshness.EXPIRED
    }

    @Suppress("FunctionName")
    private fun PureBreakerCooling(p: RequesterModel.Peer): Boolean = xyz.mdhv.asom.lab.router.PureBreaker.isCooling(p.transport, loop.now)

    private fun pull(peer: String, done: () -> Unit) {
        lastPullAt[peer] = loop.now
        try {
            ensureSession(peer) { ok ->
                if (!ok) {
                    done()
                    return@ensureSession
                }
                val s = sessions[peer]!!
                val ps = s.peer
                val r = rtt(peer)
                val stream = nextStream()
                val req = payloads.stateReq(stream)
                s.conn.setPayload(req.text)
                try {
                    s.sessA.send(req.frame)
                } catch (e: xyz.mdhv.asom.lab.ledger.SessionClosedException) {
                    done()
                    return@ensureSession
                }
                s.lastUse = loop.now
                loop.after(r / 2, "state-req-arrives") {
                    if (!ps.alive || (generation[peer] ?: 0) != s.gen || loop.now < ps.stateDropUntil) {
                        loop.after(2_000, "state-timeout") { done() }
                        return@after
                    }
                    s.conn.deliver(false, req.frame)
                    s.sessB!!.receive(req.frame)
                    ps.stateSeq++
                    val view = ps.localView(loop.now)
                    val doc = StateBuilder.build(view, ps.stateSeq, 0)
                    val text = Jcs.serializeToString(doc)
                    val frame = FrameSpec(FrameKind.STATE, text.toByteArray().size, stream)
                    val extra = if (loop.now < ps.stateDelayUntil) ps.stateDelayMs else 0L
                    loop.after(extra, "state-ready") {
                        s.conn.setPayload(text)
                        s.sessB!!.send(frame)
                        loop.after(r / 2, "state-arrives") {
                            s.conn.deliver(true, frame)
                            s.sessA.receive(frame)
                            when (val p = StateParser.parse(text.toByteArray())) {
                                is StateParse.Ok -> {
                                    val d = p.doc
                                    emit(
                                        "state", "peer" to peer, "sseq" to d.seq, "age" to d.sampledAgeMs, "fsm" to d.fsm.name, "src" to d.powerSource, "chg" to d.charging,
                                        "band" to d.batteryBand?.wire, "tb" to d.thermalBand.toLong(), "gov" to d.governor.name, "backend" to d.backend, "commit" to d.commit,
                                        "held" to d.held, "qb" to d.queueBucket.toLong(),
                                    )
                                }
                                is StateParse.Reject -> Unit
                            }
                            emit("link-rtt", "peer" to peer, "ms" to r)
                            done()
                        }
                    }
                }
            }
        } catch (e: LedgerUnavailableException) {
            done()
        }
    }

    inner class RequestRun(val rec: ReqRecord) {
        private lateinit var rl: RequestLedger
        private var plan: MeshPlan? = null
        private var idx = -1
        private var delivered = false
        private var aborted = false
        private var tokensOut = 0L

        fun begin() {
            loop.context = this
            rl = RequestLedger(selfLedger, rec.id, rec.app.pkg, rec.model)
            val pending = if (peerAllowedFor(rec) && rec.app.meshAllowed) model.peers.values.sortedBy { it.spec.id }.filter { needsPull(it) } else emptyList()
            if (pending.isEmpty()) {
                planAndRun()
                return
            }
            var left = pending.size
            for (p in pending) pull(p.spec.id) { if (--left == 0) planAndRun() }
        }

        private fun planAndRun() {
            selfSituationEvent()
            val e = emit(
                "request", "id" to rec.id, "app" to rec.app.pkg, "model" to rec.model, "header" to null, "fallback" to emptyList<String>(), "noTrain" to false, "op" to "chat",
                "stream" to rec.stream, "P" to rec.P.toLong(), "B" to rec.B, "cap" to (rec.app.maxTokensCap ?: -1L), "deadline" to rec.deadlineMs,
            )
            val q = model.query(e)
            val snap = model.snapshot(loop.now)
            var p: MeshPlan? = null
            var err: String? = null
            try {
                p = MeshRouter().plan(q, snap)
            } catch (x: MeshPlanException) {
                err = x.code
            }
            val ordered = p?.let { if (variant == Variant.B2) it.copy(attempts = staticOrder(it.attempts)) else it }
            decisions += DecisionRec(rec.id, loop.now, e.seq, DecisionFormat.attempts(ordered), err, DecisionFormat.excluded(p))
            rec.planned = ordered?.attempts?.map { idOf(it) }.orEmpty()
            rec.firstReason = ordered?.attempts?.firstOrNull()?.reason
            rec.firstTarget = ordered?.attempts?.firstOrNull()?.let { targetOf(it) }
            if (p == null) {
                rec.error = err
                finish(if (err == "LOCAL_ENGINE_ABSENT") 501 else if (err == "MODEL_UNKNOWN") 404 else 503, null)
                return
            }
            rec.hindsight = hindsight(q, snap)
            plan = ordered
            for (c in p.capDelta.entries) {
                emit("cap", "node" to c.key.nodeId, "sha" to c.key.fileSha256, "backend" to c.key.backend, "wouldWinInc" to c.wouldWinInc.toLong(), "wonInc" to c.wonInc.toLong(), "swapped" to c.swapped)
            }
            next()
        }

        private fun staticOrder(a: List<PlannedAttempt>): List<PlannedAttempt> =
            a.filter { it.tier == Tier.SELF } + a.filter { it.tier == Tier.PEER }.sortedBy { it.nodeId } + a.filter { it.tier == Tier.CLOUD }

        private fun next() {
            if (aborted) return
            idx++
            val attempts = plan!!.attempts
            if (idx >= attempts.size) {
                rec.error = rec.error ?: "ALL_ATTEMPTS_FAILED"
                finish(502, null)
                return
            }
            if (loop.now > rec.startT + rec.deadlineMs) {
                rec.error = "DEADLINE"
                finish(504, null)
                return
            }
            val a = attempts[idx]
            val log = AttemptLog(rec.id, idx, a.tier, targetOf(a), loop.now)
            rec.attempts += log
            try {
                when (a.tier) {
                    Tier.SELF -> selfAttempt(a, log)
                    Tier.CLOUD -> cloudAttempt(a, log)
                    Tier.PEER -> PeerAttempt(a, log).start()
                }
            } catch (x: LedgerUnavailableException) {
                log.status = "LEDGER_UNAVAILABLE"
                rec.ledgerUnavailable = true
                rec.error = "LEDGER_UNAVAILABLE"
                aborted = true
                finishNoRow(503)
            }
        }

        fun ledgerAbort() {
            if (aborted || rec.status != 0) return
            aborted = true
            rec.ledgerUnavailable = true
            rec.error = "LEDGER_UNAVAILABLE"
            rec.attempts.lastOrNull()?.let { if (it.endedAt == null && it.status in setOf("PLANNED", "RECEIVING")) it.status = "LEDGER_UNAVAILABLE" }
            finishNoRow(503)
        }

        private fun deliver(log: AttemptLog) {
            if (!delivered) {
                delivered = true
                log.deliveredToClient = true
            }
        }

        private fun finishNoRow(status: Int) {
            rec.status = status
            rec.endT = loop.now
        }

        fun finish(status: Int, served: PlannedAttempt?, servedProvider: String? = null) {
            rec.status = status
            rec.endT = loop.now
            try {
                val row = rl.terminal(
                    status, tokensIn = rec.P.toLong(), tokensOut = if (status == 200) tokensOut else null, costEst = if (served?.tier == Tier.CLOUD && status == 200) 0.0000029 else null,
                    costBasis = if (served?.tier == Tier.CLOUD && status == 200) "usage" else "none",
                )
                trace.add(ServedBy(selfName, rec.id, row.servedClass))
                trace.add(Responded(selfName, rec.id, row.toEchoHeaders()))
            } catch (_: LedgerWriteException) {
                rec.ledgerUnavailable = true
            }
            if (status == 200 && served != null) {
                rec.servedTier = served.tier
                rec.servedBy = servedProvider ?: targetOf(served)
                emit("app-ewma", "app" to rec.app.pkg, "tokens" to tokensOut)
            }
        }

        // ---- this device --------------------------------------------------------------------------------------------------------------------------
        private fun selfAttempt(a: PlannedAttempt, log: AttemptLog) {
            val spec = sc.self
            val sha = a.file!!.fileSha256
            val t = spec.truth.getValue(sha)
            val start = maxOf(loop.now, selfBusyUntil)
            val f = SimRandom.lognormal(1000, 60, rnd.truth)
            val prefill = Estimator.prefillMs(rec.P.toLong(), t.prefillMilliTokPerSec, t.ttft0Ms) * f / 1000
            val busyFor = if (selfBusySince != null && start - selfBusyUntil < 60_000) start - selfBusySince!! else 0L
            val decode = Estimator.thermalAwareDecode(rec.N - 1L, t.decodeMilliTokPerSec, t.steadyMilliTokPerSec, t.throttleOnsetMs, busyFor, 0, prefill, false) * f / 1000
            if (selfBusySince == null || start - selfBusyUntil >= 60_000) selfBusySince = start
            selfBusyUntil = start + prefill + decode
            val power = cfg.powerMilliW.getValue(spec.deviceClass)
            val energy = power * (prefill + decode) / 1000
            log.attemptId = "self"
            loop.at(start + prefill, "self-first-token") { if (rec.stream) deliver(log) }
            loop.at(start + prefill + decode, "self-end") {
                selfEnergyMilliJ += energy
                selfBatteryMilliJ = selfBatteryMilliJ?.let { maxOf(0L, it - energy) }
                log.status = "ok"
                log.endedAt = loop.now
                deliver(log)
                rl.note(LabEgress.LOCAL, contentSent = false, served = true, provider = "local", model = a.file!!.modelId)
                tokensOut = rec.N.toLong()
                finish(200, a)
            }
        }

        // ---- cloud --------------------------------------------------------------------------------------------------------------------------------
        private fun cloudAttempt(a: PlannedAttempt, log: AttemptLog) {
            if (sinkFail[0]) throw LedgerUnavailableException("LEDGER_UNAVAILABLE")
            val provider = a.cloud!!.provider.id
            val cs = sc.cloud.firstOrNull { it.provider == provider } ?: CloudSpec(provider, 800, 50_000, 0, 0)
            val ttft = SimRandom.lognormal(cs.ttftMedianMs, 300, rnd.truth)
            val draw = rnd.truth.nextInt(1000)
            val failure = if (draw < cs.err429Permille) "429" else if (draw < cs.err429Permille + cs.err5xxPermille) "5xx" else null
            log.attemptId = "cloud"
            log.bodySent = true
            val cid = "cloud-${rec.id}-${idx}"
            trace.add(ContentSent(selfName, rec.id, cid, LabEgress.CLOUD))
            if (failure != null) {
                loop.after(ttft / 2, "cloud-fail") {
                    log.status = failure
                    log.endedAt = loop.now
                    rl.note(LabEgress.CLOUD, contentSent = true, served = false)
                    emit("cloud-fail", "provider" to provider)
                    next()
                }
                return
            }
            val decode = (rec.N - 1L) * 1_000_000 / cs.decodeMilliTokPerSec
            loop.after(ttft, "cloud-first-byte") { if (rec.stream) deliver(log) }
            loop.after(ttft + decode, "cloud-end") {
                log.status = "ok"
                log.endedAt = loop.now
                deliver(log)
                emit("cloud-ok", "provider" to provider, "ms" to ttft)
                rl.note(LabEgress.CLOUD, contentSent = true, served = true, provider = provider, model = a.cloud!!.modelId)
                tokensOut = rec.N.toLong()
                finish(200, a, "cloud:$provider")
            }
        }

        // ---- a peer -------------------------------------------------------------------------------------------------------------------------------
        inner class PeerAttempt(private val a: PlannedAttempt, private val log: AttemptLog) : Losable {
            private val peerId = a.nodeId!!
            private val ps = peerSims.getValue(peerId)
            private val file = a.file!!
            private val sha = file.fileSha256
            private val stream = nextStream()
            private val attemptId: String = if (dupArmed && lastAttemptIdTo[peerId] != null) lastAttemptIdTo[peerId]!!.also { dupArmed = false } else payloads.attemptId()
            private lateinit var s: PeerSession
            private lateinit var ra: RequesterAttempt
            private var la: LenderAttempt? = null
            private var r = 1L
            private var answered = false
            private var headSeen = false
            private var finished = false
            private var gen = 0
            private val observer = AttemptObserver()
            private var tBodyMs = 0L
            private var reservation = 0L
            private var lostArmed = false
            private val offerFrame: Built by lazy {
                built(
                    FrameKind.INFER_OFFER,
                    listOf(
                        "attemptId" to JString(attemptId), "deadlineMs" to JInt(rec.deadlineMs), "estTokensIn" to JInt(rec.P.toLong()), "maxTokens" to JInt(rec.maxTokens.toLong()),
                        "model" to JString(file.modelId), "op" to JString("chat"), "promptBytes" to JInt(rec.B), "stream" to xyz.mdhv.asom.lab.json.JBool(rec.stream),
                    ),
                    stream, attemptId,
                )
            }

            private val ctx get() = AttemptContext(rec.stream, rec.stream && headSeen)

            init {
                liveAttempts.getOrPut(peerId) { ArrayList() }.let { l ->
                    l.removeAll { (it as? PeerAttempt)?.isDone() == true }
                    l += this
                }
            }

            fun isDone(): Boolean = finished

            override fun peerVanished() {
                if (finished || aborted || !log.bodySent) return
                loop.after(2 * r + 200, "loss-detected") { if (!finished && !aborted) onConnectionLost() }
            }

            fun start() {
                log.attemptId = attemptId
                if (!eligibleNow()) {
                    log.status = "F1_ELIGIBILITY"
                    finished = true
                    next()
                    return
                }
                ensureSession(peerId) { ok ->
                    if (!ok) {
                        log.status = "PEER_UNREACHABLE"
                        finished = true
                        next()
                        return@ensureSession
                    }
                    s = sessions[peerId]!!
                    gen = s.gen
                    sendOffer()
                }
            }

            private fun eligibleNow(): Boolean {
                val p = model.peers.getValue(peerId)
                val dests = setOf(Dest.T, Dest.O, Dest.C).filter { it != Dest.O || peerAllowedFor(rec) }.toSet()
                return PeerEligibility.evaluate(dests, PeerRegistryView(if (p.paired) PeerStatus.PAIRED else PeerStatus.SUSPENDED, p.route, p.grant)) == xyz.mdhv.asom.lab.policy.Eligibility.Eligible
            }

            private fun connectionOk(): Boolean = ps.alive && (generation[peerId] ?: 0) == gen

            private fun sendOffer() {
                lastAttemptIdTo[peerId] = attemptId
                onOffer?.invoke(peerId)
                ra = s.sessA.requesterAttempt(rec.id, attemptId, idx, rec.app.pkg, file.modelId)
                ra.begin()
                r = rtt(peerId)
                log.offerAt = loop.now
                reservation = (a.estimate?.totalMs ?: 0L)
                emit("reserve", "peer" to peerId, "delta" to reservation)
                s.conn.setPayload(offerFrame.text)
                ra.send(offerFrame.frame)
                s.lastUse = loop.now
                loop.after(r / 2, "offer-arrives") { lenderReceivesOffer() }
                loop.after(cfg.offerTimeoutMs, "offer-timeout") { if (!answered && !finished && !aborted) onOfferTimeout() }
            }

            private fun release() {
                if (reservation != 0L) {
                    emit("reserve", "peer" to peerId, "delta" to -reservation)
                    reservation = 0
                }
            }

            private fun stDigestText(): Pair<JObject, Long> {
                ps.stateSeq++
                return StDigest.build(ps.localView(loop.now), ps.stateSeq) to ps.stateSeq
            }

            private fun piggybackFrom(text: String) {
                val root = (StrictJson.parse(text.toByteArray()) as? ParseResult.Ok)?.value as? JObject ?: return
                val st = root["st"] as? JObject ?: return
                val d = StDigestParser.parse(Jcs.serialize(st)) ?: return
                emit("piggyback", "peer" to peerId, "sseq" to d.seq, "fsm" to d.fsm.name, "tb" to d.tb.toLong(), "gov" to d.gov.name, "qb" to d.qb.toLong())
            }

            private fun lenderReceivesOffer() {
                if (finished || aborted || !connectionOk()) return
                s.conn.deliver(false, offerFrame.frame)
                val l = s.sessB!!.lenderAttempt(attemptId, file.modelId)
                la = l
                l.receiveOffer(offerFrame.frame)
                val offer = OfferView(attemptId, file.modelId, "chat", rec.B, rec.maxTokens.toLong(), rec.deadlineMs, rec.stream)
                val d = ps.decide(offer, loop.now)
                val (st, _) = stDigestText()
                when (val reply = d.reply) {
                    is LenderReply.Accept -> {
                        val f = built(FrameKind.INFER_ACCEPT, listOf("attemptId" to JString(attemptId), "fileSha256" to JString(sha), "servedModel" to JString(file.modelId), "st" to st), stream, attemptId)
                        s.conn.setPayload(f.text)
                        l.accept(f.frame)
                        loop.after(r / 2, "accept-arrives") { requesterReceivesAccept(f) }
                    }
                    is LenderReply.Decline -> {
                        val f = built(
                            FrameKind.INFER_DECLINE,
                            listOf("attemptId" to JString(attemptId), "code" to JString(reply.code.name), "retryAfterMs" to JInt(reply.retryAfterMs), "st" to st), stream, attemptId, reply.code.name,
                        )
                        s.conn.setPayload(f.text)
                        l.decline(f.frame)
                        loop.after(r / 2, "decline-arrives") { requesterReceivesDecline(f, reply.code.name, reply.retryAfterMs) }
                    }
                    is LenderReply.Error -> {
                        val f = payloads.error(reply.code.name, stream, attemptId)
                        s.conn.setPayload(f.text)
                        l.decline(f.frame)
                        loop.after(r / 2, "error-arrives") { requesterReceivesDecline(f, reply.code.name, 30_000) }
                    }
                }
            }

            private fun requesterReceivesDecline(f: Built, code: String, retryAfter: Long) {
                if (finished || aborted || !connectionOk()) return
                answered = true
                s.conn.deliver(true, f.frame)
                ra.receive(f.frame)
                piggybackFrom(f.text!!)
                ra.finish(503, code, null)
                log.declined = true
                log.answeredAt = loop.now
                log.status = code
                release()
                emit("link-rtt", "peer" to peerId, "ms" to r)
                val d = Failover.decide(AttemptPhase.OFFERING, ctx, AttemptEvent.Decline(code, retryAfter))!!
                apply(d, retryAfter)
                if (code == "DUPLICATE_ATTEMPT") dupArmed = false
                finished = true
                next()
            }

            private fun apply(d: xyz.mdhv.asom.lab.router.Decision, retryAfter: Long? = null) {
                if (d.backoffMs != null) emit("backoff", "peer" to peerId, "retryAfterMs" to (retryAfter ?: d.backoffMs))
                when (d.breaker) {
                    BreakerEffect.TRANSPORT_FAILURE, BreakerEffect.FAILURE -> emit("breaker-fail", "peer" to peerId)
                    else -> Unit
                }
                when (d.tracker) {
                    TrackerEffect.FAILED_OBSERVATION -> emit("obs-failed", "peer" to peerId, "sha" to sha, "backend" to ps.spec.backend)
                    TrackerEffect.MEMORY_DISCREPANT -> emit("obs-oom", "peer" to peerId, "sha" to sha, "backend" to ps.spec.backend)
                    TrackerEffect.NONE -> Unit
                }
            }

            private fun onOfferTimeout() {
                answered = true
                val cancel = payloads.cancel(attemptId, stream, "timeout")
                s.conn.setPayload(cancel.text)
                try {
                    ra.send(cancel.frame)
                } catch (_: xyz.mdhv.asom.lab.ledger.SessionClosedException) {
                }
                ra.finish(504, "OFFER_TIMEOUT", null)
                log.status = "OFFER_TIMEOUT"
                release()
                apply(Failover.decide(AttemptPhase.OFFERING, ctx, AttemptEvent.OfferTimeout)!!)
                closeSession(peerId, "offer-timeout")
                finished = true
                next()
            }

            private fun requesterReceivesAccept(f: Built) {
                if (finished || aborted || !connectionOk()) return
                answered = true
                log.answeredAt = loop.now
                s.conn.deliver(true, f.frame)
                ra.receive(f.frame)
                piggybackFrom(f.text!!)
                emit("link-rtt", "peer" to peerId, "ms" to r)
                if (worseNow() || !eligibleNow()) {
                    val cancel = payloads.cancel(attemptId, stream, "reeval")
                    s.conn.setPayload(cancel.text)
                    ra.send(cancel.frame)
                    s.conn.deliver(false, cancel.frame)
                    la?.receiveOther(cancel.frame)
                    ra.finish(499, "CANCELLED_BEFORE_BODY", 0)
                    log.status = "CANCELLED_BEFORE_BODY"
                    log.registryOkAtBody = eligibleNow()
                    release()
                    finished = true
                    next()
                    return
                }
                val body = payloads.raw(FrameKind.INFER_BODY, rec.B.toInt(), stream, attemptId)
                log.registryOkAtBody = eligibleNow()
                s.conn.setPayload(null)
                ra.send(body.frame)
                trace.add(ContentSent(selfName, rec.id, attemptId, LabEgress.peerClass))
                log.bodySent = true
                log.bodyAt = loop.now
                tBodyMs = loop.now
                observer.onBodySent(loop.now)
                emit("same-file", "peer" to peerId, "sha" to sha)
                rl.note(LabEgress.peerClass, contentSent = true, served = false)
                val upload = xyz.mdhv.asom.lab.router.Sat.ceilDiv(rec.B * 8, effectiveKbps())
                loop.after(r / 2 + upload, "body-arrives") { lenderReceivesBody(body) }
                val head = cfg.headTimeoutMs(a.estimate?.ttftMs ?: 5_000)
                loop.after(head, "head-timeout") { if (!headSeen && !finished && !aborted) onHeadTimeout() }
            }

            private fun effectiveKbps(): Long = model.peers.getValue(peerId).link?.kbps?.takeIf { it > 0 } ?: linkSpecs.getValue(peerId).kbps

            private fun worseNow(): Boolean {
                val q = model.query(events.last { it.kind == "request" && it.str("id") == rec.id })
                val res = try {
                    val snap = model.snapshot(loop.now)
                    MeshRouter().plan(q, snap.copy(peers = snap.peers.map { n -> if (n.nodeId == peerId) n.copy(ownReservationsMs = maxOf(0L, n.ownReservationsMs - reservation)) else n }))
                } catch (x: MeshPlanException) {
                    return false
                }
                val mine = res.attempts.firstOrNull { it.tier == Tier.PEER && it.nodeId == peerId && it.file?.fileSha256 == sha } ?: return true
                val other = res.attempts.firstOrNull { it.tier != Tier.CLOUD && !(it.tier == Tier.PEER && it.nodeId == peerId) } ?: return false
                return Failover.acceptedIsWorse(mine.score!!.total, other.score!!.total)
            }

            private fun lenderReceivesBody(body: Built) {
                if (finished || aborted) return
                if (!connectionOk()) return
                if (ps.armedBeforeHeadMs != -1L) {
                    val d = ps.armedBeforeHeadMs
                    ps.armedBeforeHeadMs = -1
                    s.conn.deliver(false, body.frame)
                    val ok = la!!.receiveBody(body.frame) { payloads.decline(attemptId, stream, "PEER_UNAVAILABLE").frame }
                    if (ok) trace.add(EngineRead(peerId, attemptId))
                    vanish(ps, d)
                    return
                }
                s.conn.deliver(false, body.frame)
                val ok = la!!.receiveBody(body.frame) { payloads.decline(attemptId, stream, "PEER_UNAVAILABLE").frame }
                if (!ok) return
                trace.add(EngineRead(peerId, attemptId))
                ps.inflight++
                val job = ps.job(sha, rec.P.toLong(), rec.N.toLong(), loop.now)
                val served = ServedRecord(file.modelId, 200, "done", rec.P.toLong())
                loop.at(job.headAt, "lender-head") { lenderHead(served) }
                val perChunk = (rec.N * 4L) / job.chunkAt.size
                job.chunkAt.forEachIndexed { i, t -> loop.at(t, "lender-chunk") { lenderChunk(i, perChunk + (if (i == job.chunkAt.size - 1) (rec.N * 4L) % job.chunkAt.size else 0L)) } }
                loop.at(job.endAt, "lender-end") { lenderEnd(served) }
            }

            private fun lenderHead(served: ServedRecord) {
                if (!ps.alive || (generation[peerId] ?: 0) != gen || lostArmed) return
                val f = payloads.head(attemptId, stream, served)
                s.conn.setPayload(f.text)
                la!!.send(f.frame)
                loop.after(r / 2, "head-arrives") {
                    if (finished || aborted || !connectionOk()) return@after
                    s.conn.deliver(true, f.frame)
                    ra.receive(f.frame)
                    observer.onHead(loop.now)
                    headSeen = true
                    log.status = "RECEIVING"
                }
            }

            private fun lenderChunk(i: Int, bytes: Long) {
                if (!ps.alive || (generation[peerId] ?: 0) != gen || lostArmed) return
                if (i == 1 && ps.armedMidStreamMs != -1L) {
                    val d = ps.armedMidStreamMs
                    ps.armedMidStreamMs = -1
                    lostArmed = true
                    vanish(ps, d)
                    loop.after(2 * r + 200, "loss-detected") { if (!finished && !aborted) onConnectionLost() }
                    return
                }
                val text = ("data: " + Jcs.serializeToString(JObject(listOf("choices" to JArray(listOf(JObject(listOf("delta" to JObject(listOf("content" to JString("y".repeat(bytes.toInt()))))))))))) + "\n\n")
                val raw = text.toByteArray()
                val frame = FrameSpec(FrameKind.INFER_CHUNK, raw.size, stream, attemptId)
                s.conn.setPayload(null)
                la!!.send(frame)
                loop.after(r / 2, "chunk-arrives") {
                    if (finished || aborted || !connectionOk()) return@after
                    s.conn.deliver(true, frame)
                    ra.receive(frame)
                    observer.onChunk(loop.now, raw)
                    if (rec.stream) deliver(log)
                }
            }

            private fun lenderEnd(served: ServedRecord) {
                if (!ps.alive || (generation[peerId] ?: 0) != gen || lostArmed) return
                val (st, _) = stDigestText()
                val payload = JObject(served.endPayload(attemptId).members + ("st" to st))
                val text = Jcs.serializeToString(payload)
                val frame = FrameSpec(FrameKind.INFER_END, text.toByteArray().size, stream, attemptId)
                s.conn.setPayload(text)
                val ok = la!!.finish(frame, served)
                ps.inflight = maxOf(0, ps.inflight - 1)
                if (!ok) return
                loop.after(r / 2, "end-arrives") {
                    if (finished || aborted || !connectionOk()) return@after
                    s.conn.deliver(true, frame)
                    ra.receive(frame)
                    observer.onEnd(loop.now, text.toByteArray())
                    piggybackFrom(text)
                    requesterCompletes(text)
                }
            }

            private fun requesterCompletes(endText: String) {
                val outBytes = observer.outBytes()
                val tokens = ClaimTracker.outTokEst(outBytes, cfg.bpt(sha))
                tokensOut = tokens
                ra.finish(200, null, tokens)
                finished = true
                release()
                val heldCommit = model.peers.getValue(peerId).cache.doc?.commit
                val claimCommit = model.peers.getValue(peerId).claimCommit[sha]
                val link = model.peers.getValue(peerId).link
                val netMs = if (link != null) Estimator.warmNetMs(link, rec.B, cfg) else 0L
                emit(
                    "obs", "peer" to peerId, "sha" to sha, "backend" to ps.spec.backend, "done" to (observer.terminal == "done"), "tBody" to tBodyMs, "tEnd" to (observer.tEndMs() ?: loop.now),
                    "outBytes" to outBytes, "P" to rec.P.toLong(), "maxTokens" to rec.maxTokens.toLong(), "netMs" to netMs, "concurrent" to (concurrentOnPeer()),
                    "acceptedSha" to sha, "heldBackend" to ps.spec.backend, "heldCommit" to heldCommit, "claimCommit" to claimCommit,
                )
                trackDiscrepancy()
                emit("breaker-ok", "peer" to peerId)
                log.status = "ok"
                log.endedAt = loop.now
                deliver(log)
                rl.note(LabEgress.peerClass, contentSent = true, served = true, provider = "peer:${peerId}", model = file.modelId)
                finish(200, a, peerId)
            }

            private fun concurrentOnPeer(): Boolean = requests.any { r -> r !== rec && r.attempts.any { it.target == peerId && it.bodySent && it.endedAt == null && it.status != "ok" } }

            private fun trackDiscrepancy() {
                val key = ClaimKey(peerId, sha, ps.spec.backend)
                val ts = model.book.states[key] ?: return
                if (ClaimTracker.stateOf(ts) == ClaimState.DISCREPANT && keptToDiscrepant[peerId] == null) {
                    keptToDiscrepant[peerId] = ts.ratios.size
                    if (firstDiscrepantKept == null) firstDiscrepantKept = ts.ratios.size
                }
            }

            private fun onHeadTimeout() {
                val d = Failover.decide(AttemptPhase.BODY_SENT, ctx, AttemptEvent.HeadTimeout)!!
                val cancel = payloads.cancel(attemptId, stream, "head-timeout")
                s.conn.setPayload(cancel.text)
                try {
                    ra.send(cancel.frame)
                } catch (_: xyz.mdhv.asom.lab.ledger.SessionClosedException) {
                }
                ra.finish(502, d.rowStatus, null)
                log.status = d.rowStatus
                log.endedAt = loop.now
                finished = true
                release()
                apply(d)
                closeSession(peerId, "head-timeout")
                next()
            }

            private fun onConnectionLost() {
                val phase = if (headSeen) AttemptPhase.RECEIVING else AttemptPhase.BODY_SENT
                val d = Failover.decide(phase, ctx, AttemptEvent.ConnectionLost)!!
                ra.finish(502, d.rowStatus, null)
                log.status = d.rowStatus
                log.endedAt = loop.now
                finished = true
                release()
                apply(d)
                closeSession(peerId, "lost")
                if (d.action == Action.FAIL_IN_BAND) {
                    rec.interrupted = true
                    rec.error = "MESH_STREAM_INTERRUPTED"
                    finish(502, null)
                } else {
                    next()
                }
            }
        }

        private fun idOf(a: PlannedAttempt): String = when (a.tier) {
            Tier.CLOUD -> "cloud:${a.cloud!!.provider.id}/${a.cloud!!.modelId}"
            else -> "${a.tier.name}:${a.nodeId}/${a.file!!.fileSha256.take(8)}"
        }
    }

    private fun idOf(a: PlannedAttempt): String = when (a.tier) {
        Tier.CLOUD -> "cloud:${a.cloud!!.provider.id}/${a.cloud!!.modelId}"
        else -> "${a.tier.name}:${a.nodeId}/${a.file!!.fileSha256.take(8)}"
    }

    private fun targetOf(a: PlannedAttempt): String = when (a.tier) {
        Tier.CLOUD -> "cloud:${a.cloud!!.provider.id}"
        Tier.SELF -> selfName
        Tier.PEER -> a.nodeId!!
    }

    /** B4: the greedy per-request hindsight placement (not optimal): the same plan, computed from the TRUTH of every peer instead of its claim. */
    private fun hindsight(q: MeshQuery, snap: xyz.mdhv.asom.lab.router.MeshSnapshot): String? {
        val truthPeers = snap.peers.map { n ->
            val spec = peerSims.getValue(n.nodeId).spec
            n.copy(priors = spec.files.associate { f ->
                val t = spec.truth.getValue(f.fileSha256)
                ClaimKey(n.nodeId, f.fileSha256, spec.backend) to PerfPrior(listOf(512 to t.decodeMilliTokPerSec), t.prefillMilliTokPerSec, t.ttft0Ms, t.steadyMilliTokPerSec, t.throttleOnsetMs, null, 100_000, 6_000_000_000, emptySet())
            })
        }
        val tracker = truthPeers.flatMap { n -> n.files.map { f -> ClaimKey(n.nodeId, f.fileSha256, n.state?.backend ?: peerSims.getValue(n.nodeId).spec.backend) to TrackerState(claimSeq = 1, ratios = List(6) { 1000L }) } }.toMap()
        val hs = snap.copy(peers = truthPeers, tracker = tracker, caps = emptyMap(), peerPenalty = emptyMap())
        return try {
            MeshRouter().plan(q, hs).attempts.firstOrNull { it.tier != Tier.CLOUD }?.let { if (it.tier == Tier.SELF) selfName else it.nodeId }
        } catch (x: MeshPlanException) {
            null
        }
    }

    companion object {
        fun load(scenarioFile: File, repoRoot: File, seed: Long? = null, variant: Variant = Variant.B3): Simulation {
            val sc = ScenarioLoader.parse(scenarioFile.readBytes(), seed)
            val cat = CatalogueParser.parse(File(repoRoot, sc.catalogue).readText())
            return Simulation(sc, cat, variant)
        }
    }
}
