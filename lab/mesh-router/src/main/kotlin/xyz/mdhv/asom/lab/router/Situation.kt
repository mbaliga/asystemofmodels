package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.Staleness

/**
 * The fast fields of a peer after the staleness rules of LAB_SPEC 6.5: FRESH and WARM as received, STALE with the pessimistic substitution of
 * `:mesh-policy`, EXPIRED as last received (the filters that read them are skipped and S6 charges 50%). A peer never heard from has no state: it is
 * EXPIRED with placeholder fast fields, and F4 removes it (the file list it holds is unknown).
 */
internal fun fastViewOf(n: NodeView, nowMonoMs: Long): FastView {
    val d = n.state
    if (d == null || n.stateRxMonoMs == null) {
        return FastView(Freshness.EXPIRED, Fsm.OFF, Governor.RUN, 1, 2, "unknown", false, null, null, null)
    }
    val digestFr = LiveStateCache.classify(d, n.stateRxMonoMs, n.sessionOpen, n.goawaySeen, n.stateRegressed, nowMonoMs)
    val powerFr = n.powerFreshness?.let { maxOf(it, digestFr) } ?: digestFr
    val fast = Staleness.fastFields(digestFr, d)
    val powerFast = Staleness.fastFields(powerFr, d)
    return FastView(
        freshness = maxOf(digestFr, powerFr), fsm = d.fsm, governor = d.governor,
        thermalBand = fast?.thermalBand ?: d.thermalBand, queueBucket = fast?.queueBucket ?: d.queueBucket,
        powerSource = d.powerSource, charging = d.charging, batteryBand = if (powerFast != null) powerFast.batteryBand else d.batteryBand,
        backend = d.backend, held = d.held.toSet(), digestFreshness = digestFr, powerFreshness = powerFr,
    )
}

internal sealed interface PriorResolution {
    class Ok(val claim: PerfPrior, val key: ClaimKey, val rates: TrackedRates, val state: ClaimState) : PriorResolution

    class Missing(val reason: String) : PriorResolution
}

internal object Priors {
    fun backendOf(n: NodeView, f: FileKey, fast: FastView?): String? {
        if (n.tier == Tier.SELF) return n.self?.backend
        fast?.backend?.let { return it }
        return n.priors.keys.filter { it.nodeId == n.nodeId && it.fileSha256 == f.fileSha256 }.map { it.backend }.distinct().singleOrNull()
    }

    fun claimRow(n: NodeView, f: FileKey, fast: FastView?): Pair<ClaimKey, PerfPrior>? {
        val backend = backendOf(n, f, fast) ?: return null
        val key = ClaimKey(n.nodeId, f.fileSha256, backend)
        return n.priors[key]?.let { key to it }
    }

    /** The precondition of [Estimator.decodeAtCtx]: one to four points with strictly ascending contexts. A claim that breaks it is F8, not an exception. */
    private fun curveValid(c: List<Pair<Int, Long>>): Boolean = c.size in 1..4 && c.zipWithNext().all { (a, b) -> b.first > a.first }

    /** F8's inputs: the tracked rates of the claim row, or the reason there is no usable prior. SELF uses its own calibration as measured (LOCAL_MEASURED). */
    fun resolve(n: NodeView, f: FileKey, fast: FastView?, promptTokens: Long, s: MeshSnapshot): PriorResolution {
        val row = claimRow(n, f, fast) ?: return PriorResolution.Missing("no-prior")
        val (key, claim) = row
        if (claim.decodeAt.isEmpty() || claim.prefillMilliTokPerSec <= 0 || claim.steadyMilliTokPerSec <= 0 || claim.decodeAt.any { it.second <= 0 }) {
            return PriorResolution.Missing("claim-rate<=0")
        }
        if (!curveValid(claim.decodeAt)) return PriorResolution.Missing("claim-curve-invalid")
        val claimDecode = Estimator.decodeAtCtx(claim.decodeAt, promptTokens)
        if (n.tier == Tier.SELF) {
            return PriorResolution.Ok(claim, key, TrackedRates(claim.prefillMilliTokPerSec, claimDecode, claim.steadyMilliTokPerSec), ClaimState.LOCAL_MEASURED)
        }
        val ts = ClaimTracker.stateAt(s.tracker, key)
        if (ts?.memoryDiscrepant == true) return PriorResolution.Missing("memory-discrepant")
        val cfg = s.config
        val ck = CeilingKey(f.modelId, key.backend, n.deviceClass)
        val ceiling = ClaimTracker.capRef(cfg.signedReferenceP90[ck], cfg.classCeilings[ck])
        val disc = ClaimTracker.disc(n.nodeId, s.tracker, s.peerPenalty, s.wallNowMs)
        val rates = ClaimTracker.tracked(claim, promptTokens, ts, ceiling, disc)
        if (rates.prefill <= 0 || rates.decodeAtP <= 0 || rates.steady <= 0) return PriorResolution.Missing("tracked-rate<=0")
        return PriorResolution.Ok(claim, key, rates, ClaimTracker.stateOf(ts))
    }
}
