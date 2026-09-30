/// Peak, onset, plateau, stability of the sustained-load phase (benchmark.md section 6.3, integer arithmetic).
public struct SustainResult: Sendable, Equatable {
    public let peakMtps: Int64
    public let plateauMtps: Int64
    public let onsetMs: Int64?
    public let stabilityPermille: Int64
    public let durationMs: Int64
    public let thermalCodeAtOnset: Int64?
    public let headroomAtOnsetPermille: Int64?
    public let confidence: Confidence
    public let flags: [String]
    /// Raw per-window rates, in window order (the curve of the manifest projection).
    public let windowRates: [Int64]
}

public enum SustainDerivation {
    public static let peakWindowMs: Int64 = 120_000
    public static let settleMs: Int64 = 60_000
    public static let plateauWindows = 8
    public static let onsetPermille: Int64 = 900
    /// Ceilings that end the phase and keep its windows (design B9). Other aborts are safety aborts.
    public static let hardCeilingReasons: Set<String> = ["THERMAL_HARD", "BATTERY_TEMP"]

    public static func derive(_ s: BenchDocument.Sustain, startedCool: Bool) throws -> SustainResult {
        let n = s.windows.count
        let raw = try s.windows.map { try Stats.rate(tokens: $0.tokens, micros: $0.micros) }
        var smooth: [Int64] = []
        for i in 0..<n {
            smooth.append(Stats.lowerMedian(Array(raw[max(0, i - 1)...min(n - 1, i + 1)])))
        }
        var peakIdx = 0
        var best: Int64 = -1
        for i in 0..<n where s.windows[i].tStartMs < peakWindowMs && smooth[i] > best {
            best = smooth[i]
            peakIdx = i
        }
        let peak = smooth[peakIdx]
        guard peak > 0 else { throw BenchArithmeticError(operation: "sustained peak of zero") }

        let threshold = try Checked.mulDiv(onsetPermille, peak, 1000)
        var onset: Int?
        var i = peakIdx + 1
        while i + 2 <= n - 1 {
            if smooth[i] < threshold, smooth[i + 1] < threshold, smooth[i + 2] < threshold {
                onset = i
                break
            }
            i += 1
        }

        var flags: [String] = []
        var tail: [Int64]
        if let o = onset {
            let settled = (0..<n).filter { s.windows[$0].tStartMs >= s.windows[o].tStartMs + settleMs }.map { smooth[$0] }
            if settled.isEmpty {
                tail = Array(smooth[o...])
                flags.append("PLATEAU_NOT_REACHED")
            } else {
                tail = Array(settled.suffix(plateauWindows))
            }
        } else {
            tail = Array(smooth.suffix(plateauWindows))
        }
        let plateau = Stats.lowerMedian(tail)
        let stability = try Checked.mulDiv(plateau, 1000, peak)
        let duration = try Checked.add(s.windows[n - 1].tStartMs, s.windowMs)

        var confidence: Confidence
        if !startedCool {
            confidence = .low
        } else if s.endReason == "PLATEAU" || (s.endReason == "TIME_CAP" && duration >= 480_000) {
            confidence = .high
        } else if duration >= 300_000 {
            confidence = .medium
        } else {
            confidence = .low
        }
        if hardCeilingReasons.contains(s.endReason) {
            flags.append("HARD_CEILING")
            confidence = min(confidence, .medium)
        }
        return SustainResult(
            peakMtps: peak, plateauMtps: plateau,
            onsetMs: onset.map { s.windows[$0].tStartMs },
            stabilityPermille: stability, durationMs: duration,
            thermalCodeAtOnset: onset.map { s.windows[$0].thermalCode },
            headroomAtOnsetPermille: onset == nil ? nil : s.headroomAtOnsetPermille,
            confidence: confidence, flags: flags, windowRates: raw
        )
    }
}
