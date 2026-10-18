import AsomBenchCore
import AsomJSON

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
        if let reason = sameRecord(m) { return reason }
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

    /// M2 / LM-3, one record (ERR-FX-CV1): `bench` is the only measurement source, so every fact the body states a second time must equal its
    /// `bench` twin. The engine commit may be an abbreviation of the bench commit (or the reverse). Strings compare by code units.
    /// `device.class` is NOT tied to `bench.device.form`: the class is an open enum (a viewer shows an unknown one as "other"), the form a closed one.
    private static func sameRecord(_ m: Manifest) -> String? {
        let h = m.bench.harness
        let bd = m.bench.device
        let d = m.device
        let p = m.producer
        func same(_ a: String, _ b: String) -> Bool { codeUnitsEqual(a, b) }
        if !same(p.harness.confVersion, h.confVersion) { return "producer.harness.confVersion differs from bench" }
        if !same(p.engine.name, h.engine.name) { return "producer.engine.name differs from bench" }
        let a = Array(p.engine.commit.utf8), b = Array(h.engine.commit.utf8)
        if !a.starts(with: b), !b.starts(with: a) { return "producer.engine.commit differs from bench" }
        if p.engine.buildFlags.count != h.engine.buildFlags.count || !zip(p.engine.buildFlags, h.engine.buildFlags).allSatisfy({ same($0, $1) }) {
            return "producer.engine.buildFlags differs from bench"
        }
        if d.totalBytes != bd.memTotalBytes { return "device.memory.totalBytes differs from bench" }
        if !same(d.os.family, bd.platform) { return "device.os.family differs from bench" }
        if !same(d.os.version, bd.osVersion) { return "device.os.version differs from bench" }
        if !same(d.vendor, bd.maker) { return "device.vendor differs from bench" }
        if !same(d.model, bd.model) { return "device.model differs from bench" }
        if !same(d.soc.name, bd.soc) { return "device.soc.name differs from bench" }
        return nil
    }

    private static func orderViolated(_ r: Manifest.Rate) -> Bool {
        if let p10 = r.p10, p10 > r.p50 { return true }
        if let p90 = r.p90, p90 < r.p50 { return true }
        return false
    }
}
