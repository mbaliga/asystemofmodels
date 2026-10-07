package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Dest
import xyz.mdhv.asom.lab.policy.Eligibility
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.PeerEligibility
import xyz.mdhv.asom.lab.policy.PeerRegistryView
import xyz.mdhv.asom.lab.policy.PeerStatus

/** The first failing hard filter of one (node, file) pair, or null (LAB_SPEC 6.3). `F12_USER_ACTIVE` does not exist in r3. */
internal object HardFilters {
    val CODES = listOf(
        "F1_ELIGIBILITY", "F2_NOT_PAIRED", "F3_NO_SCOPE", "F4_MODEL", "F5_CONTEXT", "F6_MEMORY", "F7_BODY", "F8_CLAIM", "F9_AVAILABILITY",
        "F10_THERMAL", "F11_POWER", "F13_METERED", "F14_BREAKER", "F15_DECLINE_BACKOFF", "F16_EMBED_IDENTITY",
    )

    class Fail(val code: String, val detail: String?)

    /**
     * [outTokens] is E0. [prior] is the claim resolution of F8. Rows are evaluated in table order and the first failing one is returned, so the
     * exclusion list never depends on iteration order.
     */
    fun firstFailing(
        node: NodeView,
        f: FileKey,
        fast: FastView?,
        prior: PriorResolution,
        p: Set<Dest>,
        q: MeshQuery,
        s: MeshSnapshot,
        outTokens: Long,
    ): Fail? {
        val isPeer = node.tier == Tier.PEER
        val peer = node.peer
        val now = s.nowMonoMs
        val digestExpired = fast?.digestFreshness == Freshness.EXPIRED
        val powerExpired = fast?.powerFreshness == Freshness.EXPIRED
        if (isPeer) {
            val row = peer ?: return Fail("F1_ELIGIBILITY", "no peer row")
            when (val e = PeerEligibility.evaluate(p, PeerRegistryView(if (row.paired) PeerStatus.PAIRED else PeerStatus.SUSPENDED, row.routeEnabled, row.inferGrantedToMe))) {
                Eligibility.Eligible -> Unit
                is Eligibility.Excluded -> return Fail(e.code, null)
            }
        }
        // F4
        if (isPeer) {
            val held = fast?.held
            if (held == null) return Fail("F4_MODEL", "no state: held models unknown")
            if (f.fileSha256 !in held) return Fail("F4_MODEL", "not held")
        }
        if (Priors.claimRow(node, f, fast)?.second?.flags?.contains("numerics-fail") == true) return Fail("F4_MODEL", "numerics-fail")
        // F5
        val modelCtx = f.contextTokens ?: return Fail("F5_CONTEXT", "model context unknown")
        val limit = node.maxContextTokens?.let { minOf(it, modelCtx) } ?: modelCtx
        if (Sat.add(q.promptTokens.toLong(), outTokens) > limit) return Fail("F5_CONTEXT", "P+N > $limit")
        // F6
        if (node.tier == Tier.SELF) {
            val self = node.self!!
            if (f.fileSha256 !in self.loaded) {
                val kv = Priors.claimRow(node, f, fast)?.second?.kvBytesPerToken
                if (kv != null) {
                    val need = Sat.add(f.fileBytes, Sat.mul(kv, Sat.add(q.promptTokens.toLong(), outTokens)), s.config.memorySlackBytes)
                    val avail = self.availBytes
                    if (avail == null || avail < need) return Fail("F6_MEMORY", if (avail == null) "free memory unknown" else "free $avail < $need")
                }
            }
        }
        // F7
        if (isPeer) {
            val lim = peer!!.limits
            if (q.promptBytes > lim.maxBodyBytes) return Fail("F7_BODY", "body")
            if (outTokens > lim.maxTokens) return Fail("F7_BODY", "tokens")
        }
        // F8
        if (prior is PriorResolution.Missing) return Fail("F8_CLAIM", prior.reason)
        if (isPeer) {
            if (node.link == null) return Fail("F8_CLAIM", "link-stats-missing")
            val onBattery = fast!!.powerSource == "battery" && !fast.charging
            if (onBattery && (node.batteryDesignMilliWh == null || node.batteryDesignMilliWh <= 0)) return Fail("F8_CLAIM", "battery-capacity-undefined")
        }
        // F9
        if (isPeer && !digestExpired && fast!!.fsm != Fsm.SERVING) return Fail("F9_AVAILABILITY", fast.fsm.name)
        // F10
        if (isPeer) {
            if (!digestExpired && (fast!!.thermalBand == 2 || fast.governor == Governor.HOLD)) return Fail("F10_THERMAL", null)
        } else {
            val self = node.self!!
            if (self.thermalCode >= 3 || self.governor == Governor.HOLD) return Fail("F10_THERMAL", null)
        }
        // F11
        if (isPeer && !powerExpired) {
            val fv = fast!!
            if (fv.powerSource == "battery" && !fv.charging) {
                val band = fv.batteryBand
                if (peer!!.requireCharging || band == null || band == BatteryBand.B20_49 || band == BatteryBand.LT20) return Fail("F11_POWER", band?.wire ?: "band unknown")
            }
        }
        // F13, F14, F15
        if (isPeer) {
            if (node.link!!.metered && !q.app.allowMeshOnMetered) return Fail("F13_METERED", null)
            val b = node.breaker
            if (b.coolingUntilMonoMs != null && now < b.coolingUntilMonoMs) return Fail("F14_BREAKER", null)
            if (b.declineBackoffUntilMonoMs != null && now < b.declineBackoffUntilMonoMs) return Fail("F15_DECLINE_BACKOFF", null)
        }
        // F16
        if (q.op == "embeddings" && (q.embeddingIdentity == null || q.embeddingIdentity != f.fileSha256)) return Fail("F16_EMBED_IDENTITY", null)
        return null
    }
}
