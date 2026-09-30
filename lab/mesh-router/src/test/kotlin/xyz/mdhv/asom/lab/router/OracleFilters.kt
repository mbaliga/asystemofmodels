package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor

/**
 * An independent statement of LAB_SPEC 6.3, written from the table and the world, not from `HardFilters`. It answers "does this attempt violate any row?"
 * for a plan's attempt and must say no for every attempt any generated world produces (law RL5). It uses its own freshness arithmetic.
 */
object OracleFilters {
    fun peerAllowed(w: GenWorld): Boolean {
        val pol = w.q.resolvedPolicy(w.s.config)
        return w.s.meshGlobalOn && w.q.app.meshAllowed && !w.q.app.deviceOnly && pol != Policy.LOCAL_ONLY && w.q.v1.fallback.isEmpty()
    }

    fun outTokens(w: GenWorld): Long = ((w.q.maxTokensCap ?: w.s.appEwmaOut[w.q.app.pkg] ?: 256).toLong()).coerceIn(1, 32_768)

    /** 0 fresh, 1 warm, 2 stale, 3 expired; own arithmetic. */
    fun freshness(w: GenWorld, n: NodeView): Int {
        val d = n.state ?: return 3
        val rx = n.stateRxMonoMs ?: return 3
        if (n.stateRegressed || n.goawaySeen) return 3
        val age = (w.s.nowMonoMs - rx) + minOf(d.sampledAgeMs, 60_000)
        if (!n.sessionOpen && age > 30_000) return 3
        return when {
            age <= 5_000 -> 0
            age <= 30_000 -> 1
            age <= 300_000 -> 2
            else -> 3
        }
    }

    fun violation(w: GenWorld, a: PlannedAttempt): String? {
        if (a.tier == Tier.CLOUD) return null
        val q = w.q
        val s = w.s
        val n = if (a.tier == Tier.SELF) s.self else s.peers.first { it.nodeId == a.nodeId }
        val f = a.file!!
        val nTok = outTokens(w)
        val p = q.promptTokens.toLong()
        if (a.tier == Tier.PEER) {
            if (!peerAllowed(w)) return "F1: O not in P"
            val row = n.peer!!
            if (!row.routeEnabled) return "F1: peer row denies"
            if (!row.paired) return "F2"
            if (!row.inferGrantedToMe) return "F3"
        } else {
            if (!s.self.self!!.hasEngine) return "SELF without an engine"
        }
        val backend = if (a.tier == Tier.SELF) n.self!!.backend else n.state?.backend
        if (a.tier == Tier.PEER && (n.state == null || f.fileSha256 !in n.state.held)) return "F4: not held"
        val prior = n.priors[ClaimKey(n.nodeId, f.fileSha256, backend ?: "")]
        if (prior?.flags?.contains("numerics-fail") == true) return "F4: numerics-fail"
        val ctx = minOf(f.contextTokens ?: -1, n.maxContextTokens ?: Long.MAX_VALUE)
        if (p + nTok > ctx) return "F5"
        if (a.tier == Tier.SELF && f.fileSha256 !in n.self!!.loaded) {
            val need = f.fileBytes + prior!!.kvBytesPerToken * (p + nTok) + 268_435_456L
            if ((n.self.availBytes ?: -1) < need) return "F6"
        }
        if (a.tier == Tier.PEER) {
            if (q.promptBytes > n.peer!!.limits.maxBodyBytes || nTok > n.peer.limits.maxTokens) return "F7"
        }
        if (prior == null || prior.prefillMilliTokPerSec <= 0 || prior.steadyMilliTokPerSec <= 0 || prior.decodeAt.any { it.second <= 0 }) return "F8: prior"
        val ts = s.tracker[ClaimKey(n.nodeId, f.fileSha256, backend ?: "")]
        if (ts?.memoryDiscrepant == true) return "F8: memory"
        val fr = if (a.tier == Tier.PEER) freshness(w, n) else 0
        val expired = fr == 3
        if (a.tier == Tier.PEER) {
            val d = n.state!!
            if (!expired && d.fsm != Fsm.SERVING) return "F9"
            if (!expired && (d.thermalBand == 2 || d.governor == Governor.HOLD)) return "F10"
            if (!expired && d.powerSource == "battery" && !d.charging) {
                var band = d.batteryBand
                if (fr == 2 && band != null) band = BatteryBand.entries.getOrElse(band.ordinal + 1) { BatteryBand.LT20 }
                if (n.peer!!.requireCharging || band == null || band == BatteryBand.B20_49 || band == BatteryBand.LT20) return "F11"
            }
            if (n.link!!.metered && !q.app.allowMeshOnMetered) return "F13"
            if (n.breaker.coolingUntilMonoMs != null && s.nowMonoMs < n.breaker.coolingUntilMonoMs) return "F14"
            if (n.breaker.declineBackoffUntilMonoMs != null && s.nowMonoMs < n.breaker.declineBackoffUntilMonoMs) return "F15"
        } else {
            if (n.self!!.thermalCode >= 3 || n.self.governor == Governor.HOLD) return "F10 (self)"
        }
        if (q.op == "embeddings" && f.fileSha256 != q.embeddingIdentity) return "F16"
        return null
    }
}
