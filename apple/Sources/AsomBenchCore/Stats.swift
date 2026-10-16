/// M04 statistics and confidence (benchmark.md section 9, with design B7). Integer arithmetic only, floor division.
public enum Confidence: Int, Comparable, Sendable {
    case insufficient = 0, low, medium, high

    public static func < (a: Confidence, b: Confidence) -> Bool { a.rawValue < b.rawValue }

    public var name: String {
        switch self {
        case .insufficient: return "insufficient"
        case .low: return "low"
        case .medium: return "medium"
        case .high: return "high"
        }
    }

    public static func named(_ s: String) -> Confidence? {
        [.insufficient, .low, .medium, .high].first { $0.name == s }
    }
}

/// What a test knows about the conditions it ran in (the caps of benchmark.md section 9.4).
public struct TestContext: Sendable, Equatable {
    public var warmStart = false
    public var contentionPermille: Int64 = 0
    public var virtualized = false
    public var streamTiming = false
    public var restarted = false
    public var swapped = false

    public init(warmStart: Bool = false, contentionPermille: Int64 = 0, virtualized: Bool = false,
                streamTiming: Bool = false, restarted: Bool = false, swapped: Bool = false) {
        self.warmStart = warmStart
        self.contentionPermille = contentionPermille
        self.virtualized = virtualized
        self.streamTiming = streamTiming
        self.restarted = restarted
        self.swapped = swapped
    }
}

public struct TestStat: Sendable, Equatable {
    /// The conservative median of the kept values, or nil when fewer than two reps are kept.
    public let value: Int64?
    public let kept: Int
    public let keptIdx: [Int]
    public let relSpreadPermille: Int64
    public let confidence: Confidence
    public let flags: [String]
    /// The kept values, ascending. Percentiles are taken from here.
    public let keptSorted: [Int64]
    public let n: Int
}

public enum Stats {
    /// Tuning that benchmark.md section 9 and design B7 leave open (recorded in apple/ERRATA.md).
    /// "Reps are strictly monotone in time" needs at least this many kept reps to mean anything.
    public static let driftMonotoneMinKept = 3
    /// "First minus last exceeds 100 permille" needs at least this many kept reps.
    public static let driftSpreadMinKept = 2

    public static func lowerMedian(_ xs: [Int64]) -> Int64 {
        let s = xs.sorted()
        return s[(s.count - 1) / 2]
    }

    public static func upperMedian(_ xs: [Int64]) -> Int64 {
        let s = xs.sorted()
        return s[s.count / 2]
    }

    /// Nearest rank, x[ceil(p*n/1000) - 1], over ascending values (benchmark.md 13.3, vectorised as M04-009).
    public static func nearestRank(_ ascending: [Int64], permille p: Int64) -> Int64 {
        let n = Int64(ascending.count)
        let rank = (p * n + 999) / 1000
        return ascending[Int(min(max(rank, 1), n)) - 1]
    }

    /// Rate of one rep: tokens * 10^9 / micros, floor (milli-tokens per second).
    public static func rate(tokens: Int64, micros: Int64) throws -> Int64 {
        try Checked.mulDiv(tokens, 1_000_000_000, micros)
    }

    /// `values` are the per-rep rates in time order. Higher is better.
    public static func stat(rates values: [Int64], context: TestContext) throws -> TestStat {
        let n = values.count
        precondition(n >= 1)
        let med = lowerMedian(values)
        let devs = values.map { $0 >= med ? $0 - med : med - $0 }
        let mad = lowerMedian(devs)
        var isOutlier = [Bool](repeating: false, count: n)
        for k in 0..<n {
            if mad > 0 {
                isOutlier[k] = try Checked.mul(devs[k], 1000) > Checked.mul(4449, mad)
            } else {
                isOutlier[k] = try Checked.mul(devs[k], 1000) > Checked.mul(250, med)
            }
        }
        let outlierCount = isOutlier.filter { $0 }.count
        var flags: [String] = []
        var keep = [Bool](repeating: true, count: n)
        if outlierCount > n / 5 {
            flags.append("UNSTABLE")
        } else if outlierCount > 0 {
            for k in 0..<n where isOutlier[k] { keep[k] = false }
            flags.append("OUTLIER_EXCLUDED")
        }

        var keptIdx = (0..<n).filter { keep[$0] }
        if try isDrifting(keptIdx.map { values[$0] }) {
            flags.append("THERMAL_DRIFT")
            if !keep[0] {
                keep[0] = true
                keptIdx = (0..<n).filter { keep[$0] }
            }
            if !keep.contains(false), let at = flags.firstIndex(of: "OUTLIER_EXCLUDED") { flags.remove(at: at) }
        }
        let keptValues = keptIdx.map { values[$0] }
        let sorted = keptValues.sorted()
        let kept = sorted.count

        var value: Int64?
        var spread: Int64 = 0
        if kept >= 2 {
            let v = sorted[(kept - 1) / 2]
            value = v
            spread = try Checked.div(try Checked.mul(sorted[kept - 1] - sorted[0], 1000), v)
        }

        var confidence: Confidence
        if kept < 2 {
            confidence = .insufficient
        } else if n >= 5, kept >= 4, spread <= 50, context.contentionPermille <= 50, !flags.contains("UNSTABLE") {
            confidence = .high
        } else if kept >= 3, spread <= 150, context.contentionPermille <= 150 {
            confidence = .medium
        } else {
            confidence = .low
        }
        if confidence > .insufficient {
            if context.warmStart || context.virtualized || context.streamTiming || context.restarted { confidence = min(confidence, .medium) }
            if context.swapped || flags.contains("THERMAL_DRIFT") { confidence = min(confidence, .low) }
        }
        return TestStat(value: value, kept: kept, keptIdx: keptIdx, relSpreadPermille: spread, confidence: confidence,
                        flags: flags, keptSorted: sorted, n: n)
    }

    /// B7: strictly slowing at every step, or the last rep more than 100 permille slower than the first.
    static func isDrifting(_ inTimeOrder: [Int64]) throws -> Bool {
        let k = inTimeOrder.count
        if k >= driftMonotoneMinKept, k >= 2 {
            var strictly = true
            for i in 1..<k where !(inTimeOrder[i] < inTimeOrder[i - 1]) { strictly = false; break }
            if strictly { return true }
        }
        if k >= driftSpreadMinKept, k >= 2, let first = inTimeOrder.first, let last = inTimeOrder.last, first > 0, last < first {
            return try Checked.mulDiv(first - last, 1000, first) > 100
        }
        return false
    }
}
