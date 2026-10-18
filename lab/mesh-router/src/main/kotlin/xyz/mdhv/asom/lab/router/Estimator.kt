package xyz.mdhv.asom.lab.router

/** E0..E12 of LAB_SPEC 6.4: integers, floor division unless `ceilDiv`, saturating at 2^53 - 1. Pure functions of their arguments. */
object Estimator {
    /** E0: `clamp(cap ?: appEwma ?: 256, 1, 32768)`. */
    fun outTokens(cap: Int?, appEwma: Int?, cfg: MeshConfig): Long = (cap?.toLong() ?: appEwma?.toLong() ?: cfg.outTokensDefault).coerceIn(1, cfg.outTokensMax)

    /**
     * The claimed decode rate at context [ctx]: piecewise linear over the curve, floor division, clamped to the curve's ends
     * (R3-CLOSURE-5 (10): the claim decode is the curve at P, and every point of the curve is scaled by the same tracked ratio).
     */
    fun decodeAtCtx(curve: List<Pair<Int, Long>>, ctx: Long): Long {
        require(curve.isNotEmpty() && curve.size <= 4) { "a decode curve has 1..4 points" }
        for (i in 1 until curve.size) require(curve[i].first > curve[i - 1].first) { "curve contexts ascend" }
        if (ctx <= curve.first().first) return curve.first().second
        if (ctx >= curve.last().first) return curve.last().second
        var i = 0
        while (curve[i + 1].first <= ctx) i++
        val (c0, v0) = curve[i]
        val (c1, v1) = curve[i + 1]
        return v0 + Math.floorDiv((v1 - v0) * (ctx - c0), (c1 - c0).toLong())
    }

    /** E1. `kbps` is the requester's own measurement, or the path default until one exists. */
    fun netMs(link: LinkStats, promptBytes: Long, cfg: MeshConfig): Long {
        val kbps = if (link.kbps > 0) link.kbps else cfg.pathKbps.getValue(link.path)
        val transfer = Sat.ceilDiv(Sat.mul(promptBytes, 8), kbps)
        val cold = if (link.sessionWarm) 0 else Sat.add(Sat.mul(3, link.rttMs), cfg.handshakeExtraMs)
        return Sat.add(link.rttMs, transfer, cold)
    }

    /** The warm-path E1 (rtt plus transfer only), which the claim tracker uses because `tBody` is taken after the handshake (R3-CLOSURE-5 (11)). */
    fun warmNetMs(link: LinkStats, promptBytes: Long, cfg: MeshConfig): Long = netMs(link.copy(sessionWarm = true), promptBytes, cfg)

    fun loadMs(fileBytes: Long, bytesPerMs: Long): Long = Sat.ceilDiv(fileBytes, bytesPerMs)

    /** E5. */
    fun prefillMs(promptTokens: Long, preEff: Long, ttft0Ms: Long): Long = Sat.add(Sat.ceilDiv(Sat.mul(promptTokens, 1_000_000), preEff), ttft0Ms)

    /** E7 (LAB_SPEC 6.4, `thermalAwareDecode`). [m] is `N - 1`. */
    fun thermalAwareDecode(m: Long, dec: Long, steady: Long, onsetMs: Long?, busyMs: Long, queueMs: Long, prefillMs: Long, hot: Boolean): Long {
        if (m <= 0) return 0
        val already = Sat.add(busyMs, queueMs, prefillMs)
        if (hot || (onsetMs != null && already >= onsetMs)) return Sat.ceilDiv(Sat.mul(m, 1_000_000), minOf(dec, steady))
        if (onsetMs == null) return Sat.ceilDiv(Sat.mul(m, 1_000_000), dec)
        val coolMs = onsetMs - already
        val tokCool = Sat.floorDiv(Sat.mul(coolMs, dec), 1_000_000)
        if (m <= tokCool) return Sat.ceilDiv(Sat.mul(m, 1_000_000), dec)
        return Sat.add(coolMs, Sat.ceilDiv(Sat.mul(m - tokCool, 1_000_000), steady))
    }

    /** E11. */
    fun energyMilliJ(powerMilliW: Long, activeMs: Long): Long = Sat.floorDiv(Sat.mul(powerMilliW, activeMs), 1_000)

    /** E12; [designMilliWh] must be positive. */
    fun batteryUsedPermille(energyMilliJ: Long, designMilliWh: Long): Long = Sat.ceilDiv(Sat.mul(energyMilliJ, 1_000), Sat.mul(designMilliWh, 3_600))
}
