package xyz.mdhv.asom.lab.sim

import xyz.mdhv.asom.lab.policy.AvailabilityFsm
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.FsmConfig
import xyz.mdhv.asom.lab.policy.FsmEvent
import xyz.mdhv.asom.lab.policy.FsmState
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.HostInput
import xyz.mdhv.asom.lab.policy.LenderDecision
import xyz.mdhv.asom.lab.policy.LenderDecisionTable
import xyz.mdhv.asom.lab.policy.LenderLimits
import xyz.mdhv.asom.lab.policy.LenderLocalView
import xyz.mdhv.asom.lab.policy.LenderReply
import xyz.mdhv.asom.lab.policy.LenderSituation
import xyz.mdhv.asom.lab.policy.OfferView
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.policy.PresenceSignals
import xyz.mdhv.asom.lab.router.Estimator
import xyz.mdhv.asom.lab.router.PerfPrior

/** The times of one served job, all on the lender's side, from the moment the body reached it. */
class JobTimes(val loadMs: Long, val prefillMs: Long, val decodeMs: Long, val chunkAt: List<Long>, val headAt: Long, val endAt: Long)

/**
 * A simulated lender (a paired own device that lends). It owns its truth, its availability FSM (the real `:mesh-policy` one), its single-flight queue
 * (`maxConcurrent` 1: a second offer is declined `PEER_BUSY`) and the decision table of design 4.3 (the real `LenderDecisionTable`).
 */
class SimPeer(val spec: SimNodeSpec, private val rnd: SimRandom) {
    var alive = true
    var commit = "0123abc"
    var claimScale: Long = spec.claimScalePermille
    var thermalBand = 0
    var governor = Governor.RUN
    var powerSource: String = spec.power.source
    var batteryPermille: Int? = spec.power.batteryPermille
    var stormUntil = -1L
    var stateDropUntil = -1L
    var stateDelayUntil = -1L
    var stateDelayMs = 0L
    var clockSkewMs = 0L
    var stateSeq = 0L
    var inflight = 0
    var armedMidStreamMs: Long? = -1
    var armedBeforeHeadMs: Long? = -1
    val seen = HashSet<String>()
    private val reqTimes = ArrayDeque<Long>()
    private val lastUsed = HashMap<String, Long>()
    private var busySince: Long? = null
    private var lastBusyEnd = -1L
    private val fsm = AvailabilityFsm(FsmConfig(pf = false, holdDownMs = 600_000, graceMs = 30_000))
    var fsmState = FsmState()
        private set

    fun conditionsOk(): Boolean = powerSource == "ac" && thermalBand < 2 && (batteryPermille ?: 1000) >= 200

    fun step(e: FsmEvent, now: Long) {
        fsmState = fsm.step(fsmState, e, now)
    }

    fun start(now: Long) {
        step(FsmEvent.Enable, now)
        step(FsmEvent.Input(HostInput.POWER_SOURCE, conditionsOk = conditionsOk()), now)
    }

    fun presence(now: Long) = step(FsmEvent.Input(HostInput.INPUT_ACTIVITY), now)

    fun conditionChanged(now: Long) = step(FsmEvent.Input(HostInput.POWER_SOURCE, conditionsOk = conditionsOk()), now)

    fun fsmAt(now: Long): Fsm {
        step(FsmEvent.Tick, now)
        return fsmState.fsm
    }

    fun band(): BatteryBand? = if (powerSource != "battery") null else when (val b = batteryPermille) {
        null -> null
        else -> if (b >= 800) BatteryBand.GE80 else if (b >= 500) BatteryBand.B50_79 else if (b >= 200) BatteryBand.B20_49 else BatteryBand.LT20
    }

    /** The claim this node publishes for one file: its truth scaled by `claimScalePermille` (the liar of SC06 has 2000). */
    fun claim(sha: String): PerfPrior {
        val t = spec.truth.getValue(sha)
        fun sc(x: Long) = x * claimScale / 1000
        return PerfPrior(
            listOf(512 to sc(t.decodeMilliTokPerSec)), sc(t.prefillMilliTokPerSec), t.ttft0Ms, sc(t.steadyMilliTokPerSec), t.throttleOnsetMs, null, 100_000, 6_000_000_000, emptySet(),
        )
    }

    fun localView(now: Long): LenderLocalView {
        fsmAt(now)
        return LenderLocalView(
            fsm = fsmState.fsm, powerSource = powerSource, charging = false, batteryBand = band(), batteryPercentExact = batteryPermille?.let { it / 10 }, thermalBand = thermalBand,
            governor = governor, backend = spec.backend, commit = commit, confVersion = "0.2.0", held = spec.files.map { it.fileSha256 }.sorted(), localQueued = 0, peerQueued = inflight,
            loadedModels = emptyList(), freeMemoryBytes = 0, manifestSeq = null, manifestDigest = null,
            presence = PresenceSignals(false, 0, false, null, false, null, null, 0),
        )
    }

    /** The decision table (design 4.3, LAB_SPEC 7.2) with this lender's own situation. A storm answers `PEER_BUSY` to everything. */
    fun decide(o: OfferView, now: Long): LenderDecision {
        fsmAt(now)
        if (now < stormUntil) return LenderDecision(LenderReply.Decline(xyz.mdhv.asom.lab.policy.DeclineCode.PEER_BUSY, LenderDecisionTable.BUSY_RETRY_MS), "7")
        while (reqTimes.isNotEmpty() && now - reqTimes.first() >= 60_000) reqTimes.removeFirst()
        val last = fsmState.lastPresenceMs
        val holdLeft = if (last != null) maxOf(0L, last + 600_000 - now) else 0L
        val sit = LenderSituation(
            PeerStatus.PAIRED, true, o.attemptId in seen, spec.files.any { it.modelId == o.model }, LenderLimits(), fsmState.fsm == Fsm.SERVING, holdLeft > 0, holdLeft, false, 0, inflight, reqTimes.size,
        )
        val d = LenderDecisionTable.decide(o, sit)
        reqTimes.addLast(now)
        seen.add(o.attemptId)
        return d
    }

    /** The job's times from the moment the body arrives, using the node's TRUTH (with a small deterministic noise): load, prefill, first token, decode, chunks. */
    fun job(sha: String, promptTokens: Long, outTokens: Long, arrival: Long): JobTimes {
        val t = spec.truth.getValue(sha)
        val f = SimRandom.lognormal(1000, 60, rnd.truth)
        val file = spec.files.first { it.fileSha256 == sha }
        val cold = lastUsed[sha]?.let { arrival - it > 300_000 } ?: true
        val load = if (cold) Estimator.loadMs(file.fileBytes, MeshDefaults.loadRate(spec)) else 0L
        val prefill = Estimator.prefillMs(promptTokens, t.prefillMilliTokPerSec, t.ttft0Ms) * f / 1000
        val busyFor = if (busySince != null && arrival - lastBusyEnd < 60_000) arrival - busySince!! else 0L
        val decode = Estimator.thermalAwareDecode(outTokens - 1, t.decodeMilliTokPerSec, t.steadyMilliTokPerSec, t.throttleOnsetMs, busyFor, 0, prefill, false) * f / 1000
        val head = arrival + load + prefill
        val n = minOf(12L, outTokens).toInt().coerceAtLeast(1)
        val chunks = (0 until n).map { i -> head + decode * (i + 1) / n }
        val end = head + decode
        if (busySince == null || arrival - lastBusyEnd >= 60_000) busySince = arrival
        lastBusyEnd = end
        lastUsed[sha] = end
        return JobTimes(load, prefill, decode, chunks, head, end)
    }
}

object MeshDefaults {
    fun loadRate(spec: SimNodeSpec): Long = xyz.mdhv.asom.lab.router.MeshConfig().loadRate(spec.deviceClass)
}
