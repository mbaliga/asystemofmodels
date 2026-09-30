package xyz.mdhv.asom.lab.manifest

import xyz.mdhv.asom.lab.bench.Checked

/**
 * `consistency(O)` of manifest.md 8.4 (unchanged in r3): a signer cannot claim self-contradictory things and still be used.
 * Every multiplication is checked; an overflow surfaces as `BenchArithmeticException`, which the verifier maps to `INCONSISTENT`.
 * Returns the reason of the first violation, or null.
 */
object Consistency {
    fun check(o: ManifestObj): String? {
        val b = o.body
        val issued = o.presentation.issuedAtMs
        val seen = HashSet<Pair<String, String>>()
        for (r in b.results) {
            if (!seen.add(r.fileSha256 to r.backend)) return "duplicate (fileSha256, backend) row"
            if (r.runs.completed > r.runs.planned || r.runs.discarded > r.runs.completed) return "runs"
            val pcts = r.prefill.flatMap { listOf(it.milliTokPerSec, it.ttftMicros) } + r.decode.map { it.milliTokPerSec }
            for (p in pcts) {
                if (p.p10 != null && p.p10 > p.p50) return "p10 above p50"
                if (p.p90 != null && p.p90 < p.p50) return "p90 below p50"
            }
            for (p in r.prefill) {
                if (p.promptTokens > r.settings.ctxTokens) return "prompt tokens above the context"
                // TTFT cannot be much shorter than the prompt time implied by the same point's prefill rate
                if (Checked.mul(Checked.mul(p.ttftMicros.p50, p.milliTokPerSec.p50), 10L) < Checked.mul(Checked.mul(p.promptTokens, Checked.NANO), 9L)) return "ttft shorter than the prefill time"
            }
            for (d in r.decode) if (Checked.add(d.contextTokens, d.genTokens) > r.settings.ctxTokens) return "decode past the context"
            val s = r.sustained
            if (s != null) {
                val c = s.curve
                if (c.first().tMs != 0L) return "curve does not start at 0"
                for (i in 1 until c.size) if (c[i].tMs <= c[i - 1].tMs) return "curve is not strictly increasing"
                if (c.last().tMs > s.durationMs) return "curve runs past the duration"
                if (s.throttleOnsetMs != null && s.throttleOnsetMs > s.durationMs) return "throttle onset past the duration"
                if (s.steadyMilliTokPerSec > c.maxOf { it.milliTokPerSec }) return "steady state above every curve point"
            }
            if (r.memory.peakProcessBytes > b.device.memoryTotalBytes) return "peak memory above total"
            if (r.memory.availableBeforeLoadBytes > b.device.memoryTotalBytes) return "available memory above total"
            if (r.measuredAtMs > issued) return "measured after issued"
            if (r.power.method == "unavailable" && r.power.avgMilliW != null) return "power reading with an unavailable method"
        }
        return null
    }
}
