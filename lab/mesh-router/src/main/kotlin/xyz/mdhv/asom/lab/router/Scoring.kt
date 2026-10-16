package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Freshness

/** The estimator and score of LAB_SPEC 6.4 assembled for one candidate. Nothing here reads a clock: `nowMonoMs` is in the snapshot. */
internal object Scoring {
    fun powerMilliW(node: NodeView, claim: PerfPrior, cfg: MeshConfig): Long = claim.powerMilliW ?: cfg.powerMilliW.getValue(node.deviceClass)

    private fun onBatteryNotCharging(node: NodeView, fast: FastView?): Boolean =
        if (node.tier == Tier.SELF) node.self!!.onBattery && !node.self.charging else fast!!.powerSource == "battery" && !fast.charging

    private fun designMilliWh(node: NodeView): Long? = if (node.tier == Tier.SELF) node.self!!.batteryDesignMilliWh else node.batteryDesignMilliWh

    fun estimate(node: NodeView, f: FileKey, fast: FastView?, ok: PriorResolution.Ok, q: MeshQuery, s: MeshSnapshot, outTokens: Long): Estimate {
        val cfg = s.config
        val self = node.tier == Tier.SELF
        val link = node.link
        val rtt = if (self) 0L else link!!.rttMs
        val netMs = if (self) 0L else Estimator.netMs(link!!, q.promptBytes, cfg)
        val loadMs = if (self) {
            if (f.fileSha256 in node.self!!.loaded) 0L else Estimator.loadMs(f.fileBytes, cfg.loadRate(node.deviceClass))
        } else {
            val last = node.lastSameFileMonoMs[f.fileSha256]
            if (last != null && s.nowMonoMs - last <= node.peer!!.limits.idleUnloadMs) 0L else Estimator.loadMs(f.fileBytes, cfg.loadRate(node.deviceClass))
        }
        val queueMs = if (self) node.self!!.localQueueMs else maxOf(node.ownReservationsMs, cfg.queueBucketMs[fast!!.queueBucket])
        val rates = ok.rates
        val claim = ok.claim
        val prefillMs = Estimator.prefillMs(q.promptTokens.toLong(), rates.prefill, claim.ttft0Ms)
        val busy = if (self) node.self!!.busyForMs else 0L
        val hot = if (self) node.self!!.thermalCode >= 2 else fast!!.thermalBand >= 1
        val decodeMs = Estimator.thermalAwareDecode(outTokens - 1, rates.decodeAtP, rates.steady, claim.throttleOnsetMs, busy, queueMs, prefillMs, hot)
        val ttftMs = Sat.add(netMs, loadMs, queueMs, prefillMs, if (self) 0L else Sat.ceilDiv(rtt, 2))
        val totalMs = Sat.add(ttftMs, decodeMs)
        val energy = Estimator.energyMilliJ(powerMilliW(node, claim, cfg), Sat.add(prefillMs, decodeMs))
        val design = designMilliWh(node)
        val used = if (onBatteryNotCharging(node, fast) && design != null && design > 0) Estimator.batteryUsedPermille(energy, design) else 0L
        return Estimate(outTokens, netMs, loadMs, queueMs, rates.prefill, prefillMs, rates.decodeAtP, decodeMs, ttftMs, totalMs, energy, used)
    }

    /** `lowBatteryMult`: 1000 (SELF >= 500 permille; peer band ge80 or 50-79), 2000 (200..499; 20-49), 4000 (< 200; lt20; unknown). */
    private fun lowBatteryMult(node: NodeView, fast: FastView?): Long =
        if (node.tier == Tier.SELF) {
            val b = node.self!!.batteryPermille
            when {
                b == null -> 4_000
                b >= 500 -> 1_000
                b >= 200 -> 2_000
                else -> 4_000
            }
        } else {
            when (fast!!.batteryBand) {
                BatteryBand.GE80, BatteryBand.B50_79 -> 1_000
                BatteryBand.B20_49 -> 2_000
                BatteryBand.LT20, null -> 4_000
            }
        }

    /** [bestRank] is null for a concrete model (no rank term) and, for a virtual selector, the best rank in the scored set (null when nothing is ranked). */
    fun score(node: NodeView, f: FileKey, fast: FastView?, est: Estimate, q: MeshQuery, s: MeshSnapshot, virtual: Boolean, bestRank: Int?): ScoreBreakdown {
        val cfg = s.config
        val self = node.tier == Tier.SELF
        val s1 = Sat.add(est.totalMs, if (q.stream) est.ttftMs else 0L)
        val s2 = if (est.batteryUsedPermille == 0L) 0L else Sat.floorDiv(Sat.mul(Sat.mul(est.batteryUsedPermille, cfg.msPerBatteryPermille), lowBatteryMult(node, fast)), 1_000)
        val small = node.deviceClass == DeviceClass.PHONE || node.deviceClass == DeviceClass.TABLET || node.deviceClass == DeviceClass.HANDHELD
        val hot = if (self) node.self!!.thermalCode >= 1 else fast!!.thermalBand >= 1
        val s3 = if (small && hot) {
            val hp = if (self && node.self!!.userActive) cfg.heatPermilleUserActive else cfg.heatPermille
            Sat.floorDiv(Sat.mul(Sat.add(est.prefillMs, est.decodeMs), hp), 1_000)
        } else {
            0L
        }
        val s4 = if (self) 0L else cfg.peerBiasMs
        var s5 = cfg.quantPenalty(f.quant)
        if (virtual && bestRank != null) {
            val rank = f.catalogueRank ?: (bestRank + cfg.unrankedRankOffset)
            s5 = Sat.add(s5, Sat.mul((rank - bestRank).toLong(), cfg.msPerRankStep))
        }
        val s6 = when (fast?.freshness) {
            Freshness.STALE -> Sat.floorDiv(Sat.mul(est.totalMs, cfg.stalePermille), 1_000)
            Freshness.EXPIRED -> Sat.floorDiv(Sat.mul(est.totalMs, cfg.expiredPermille), 1_000)
            else -> 0L
        }
        return ScoreBreakdown(s1, s2, s3, s4, s5, s6)
    }

    /** `decEff >= minDecode and ttft <= maxTtft and total <= deadline`; SELF keeps its v2 position, so the gate is not applied to it (D9). */
    fun usable(node: NodeView, est: Estimate, q: MeshQuery, cfg: MeshConfig): Boolean =
        node.tier == Tier.SELF || (est.decEff >= cfg.minDecodeMilliTokPerSec && est.ttftMs <= cfg.maxTtftMs && est.totalMs <= q.deadlineMs)
}
