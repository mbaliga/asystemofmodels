import AsomBenchCore

/// Internal consistency (manifest.md section 8.4): claims that contradict each other are rejected, not warned about.
/// Every product and sum is overflow-checked; an overflow means INCONSISTENT (LAB_SPEC.md 4.6, "Arithmetic").
public enum Consistency {
    /// nil when consistent, else a short reason (for diagnostics; the verifier only needs the verdict).
    public static func violation(_ m: Manifest) -> String? {
        do {
            return try check(m)
        } catch {
            return "arithmetic overflow"
        }
    }

    static func check(_ m: Manifest) throws -> String? {
        var seen = Set<String>()
        for row in m.rows {
            if !seen.insert(row.fileSha256 + "|" + row.backend).inserted { return "two rows share (fileSha256, backend)" }
            if row.completed > row.planned || row.discarded > row.completed { return "run counts" }
            for p in row.prefill {
                if orderViolated(p.rate) || orderViolated(p.ttft) { return "percentile order" }
                if p.promptTokens > row.ctxTokens { return "prompt longer than the context" }
                // ttft.p50 * rate.p50 * 10 < promptTokens * 10^9 * 9: TTFT under 90% of the implied prompt time.
                let lhs = try Checked.mul(try Checked.mul(p.ttft.p50, p.rate.p50), 10)
                let rhs = try Checked.mul(try Checked.mul(p.promptTokens, 1_000_000_000), 9)
                if lhs < rhs { return "TTFT shorter than the prefill rate allows" }
            }
            for d in row.decode {
                if orderViolated(d.rate) { return "percentile order" }
                if try Checked.add(d.contextTokens, d.genTokens) > row.ctxTokens { return "context overflow" }
            }
            if let s = row.sustained {
                guard let first = s.curve.first, first.tMs == 0 else { return "curve does not start at 0" }
                for k in 1..<s.curve.count where s.curve[k].tMs <= s.curve[k - 1].tMs { return "curve not increasing" }
                if let last = s.curve.last, last.tMs > s.durationMs { return "curve runs past durationMs" }
                if let onset = s.throttleOnsetMs, onset > s.durationMs { return "onset after the end" }
                if let top = s.curve.map({ $0.rate }).max(), s.steady > top { return "steady state above every curve point" }
            }
            if row.peakProcessBytes > m.device.totalBytes || row.availableBeforeLoadBytes > m.device.totalBytes { return "memory above total" }
            if row.measuredAtMs > m.issuedAtMs { return "measured after issued" }
            if row.powerMethod == "unavailable", row.avgMilliW != nil { return "power reported by an unavailable method" }
        }
        return nil
    }

    private static func orderViolated(_ r: Manifest.Rate) -> Bool {
        if let p10 = r.p10, p10 > r.p50 { return true }
        if let p90 = r.p90, p90 < r.p50 { return true }
        return false
    }
}
