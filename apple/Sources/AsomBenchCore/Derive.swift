import AsomJSON

public struct TestResult: Sendable {
    public let test: BenchDocument.Test
    public let stat: TestStat
    /// Prefill only: the conservative (upper) median of the kept whole spans, and the kept spans ascending.
    public let ttftMicros: Int64?
    public let keptWholeSorted: [Int64]
}

public struct TierResult: Sendable {
    public let tier: BenchDocument.Tier
    public let pin: PinnedTier
    public let tests: [TestResult]
    /// pass, warn, fail or not-run
    public let numerics: String
    public let deviationPermille: Int64?
    /// Load is a duration: the upper median of the warm loads (benchmark.md 9.2).
    public let loadWarmMicros: Int64
    public let flags: [String]

    public func result(_ name: String) -> TestResult? { tests.first { $0.test.name == name } }
    public var numericsFailed: Bool { numerics == "fail" }
}

public struct MaxHold: Sendable, Equatable {
    public let weightBytes: Int64
    public let kvRatioPermille: Int64
    public let approxParamsQ4: Int64
    public let largestLoadedTier: String
}

public struct Q7B: Sendable, Equatable {
    /// measured, estimated, cannot-hold or not-measured
    public let basis: String
    public let decodeMtps: Int64?
    public let ttft512Micros: Int64?
    /// comfortable, usable, too-slow, cannot-hold or not-measured
    public let verdict: String
}

public struct Answer2000: Sendable, Equatable {
    public let tier: String
    public let depthRatioPermille: Int64
    public let micros: Int64
    public let thermalModel: Bool
}

public struct Throttle: Sendable, Equatable {
    public let tier: String
    public let onsetMs: Int64?
    public let stabilityPermille: Int64
    public let testedMs: Int64
}

public struct Role: Sendable, Equatable {
    public let code: String
    public let t2PlateauMtps: Int64?
    public let t3PlateauMtps: Int64?
    /// True when the deciding plateau is estimated from the stability of another tier's heat test.
    public let estimatedBasis: Bool
}

public struct Answers: Sendable {
    public let usableMemoryBytes: Int64
    public let safetyPermille: Int64
    public let maxHold: MaxHold?
    public let q7b: Q7B
    public let answer2000: Answer2000?
    public let throttle: Throttle?
    public let role: Role
    public let overallConfidence: Confidence
    public let headlineTier: String?
}

public struct Derived: Sendable {
    public let tiers: [TierResult]
    public let sustain: SustainResult?
    public let sustainTier: String?
    public let answers: Answers

    public func tier(_ id: String) -> TierResult? { tiers.first { $0.tier.tier == id } }
}

public enum Editorial {
    public static let comfortableMtps: Int64 = 10_000
    public static let comfortableTtftMicros: Int64 = 2_000_000
    public static let usableMtps: Int64 = 4_000
    public static let usableTtftMicros: Int64 = 10_000_000
    public static let strongProviderMtps: Int64 = 10_000
    public static let smallProviderMtps: Int64 = 10_000
    public static let helperMtps: Int64 = 8_000
}

public enum Derivation {
    public static let swapCapBytes: Int64 = 268_435_456

    /// derive(bench), M04. Every multiplication is checked; an overflow throws `BenchArithmeticError`.
    public static func derive(_ doc: BenchDocument) throws -> Derived {
        let pins = BenchSet.pins(forSet: doc.benchSet)!
        var tiers: [TierResult] = []
        let contention = max(doc.run.contentionBeforePermille, doc.run.contentionAfterPermille)
        for tier in doc.tiers {
            let pin = BenchSet.pin(tier: tier.tier, in: pins)!
            var context = TestContext()
            context.warmStart = tier.startThermal != "cool"
            context.contentionPermille = contention
            context.virtualized = doc.device.virtualized
            context.streamTiming = doc.harness.timingSource == "stream"
            context.restarted = tier.restarts > 0
            context.swapped = (tier.swapDeltaBytes ?? 0) > swapCapBytes
            var results: [TestResult] = []
            for test in tier.tests {
                let rates = try test.samples.map { try Stats.rate(tokens: test.tokens, micros: $0) }
                let stat = try Stats.stat(rates: rates, context: context)
                var ttft: Int64?
                var wholeSorted: [Int64] = []
                if let whole = test.wholeSamples {
                    wholeSorted = stat.keptIdx.map { whole[$0] }.sorted()
                    if stat.value != nil { ttft = wholeSorted[wholeSorted.count / 2] }
                }
                results.append(TestResult(test: test, stat: stat, ttftMicros: ttft, keptWholeSorted: wholeSorted))
            }
            let (verdict, deviation) = try numerics(tier.numerics)
            var flags: [String] = []
            if context.swapped { flags.append("SWAPPED") }
            tiers.append(TierResult(tier: tier, pin: pin, tests: results, numerics: verdict, deviationPermille: deviation,
                                    loadWarmMicros: Stats.upperMedian(tier.loadWarmMicros), flags: flags))
        }

        var sustain: SustainResult?
        if let s = doc.sustain, let t = doc.tiers.first(where: { $0.tier == s.tier }) {
            sustain = try SustainDerivation.derive(s, startedCool: t.startThermal == "cool" && doc.run.startThermal == "cool")
        }
        let answers = try makeAnswers(doc, tiers: tiers, sustain: sustain, pins: pins)
        return Derived(tiers: tiers, sustain: sustain, sustainTier: doc.sustain?.tier, answers: answers)
    }

    /// Deviation permille = |nll - ref| * 1000 / ref; pass at 20, warn at 50, fail above. No reference: not-run.
    static func numerics(_ n: BenchDocument.Numerics) throws -> (String, Int64?) {
        guard let ref = n.refMilliNatsPerToken else { return ("not-run", nil) }
        let diff = n.milliNatsPerToken >= ref ? n.milliNatsPerToken - ref : ref - n.milliNatsPerToken
        let dev = try Checked.mulDiv(diff, 1000, ref)
        return (dev <= 20 ? "pass" : dev <= 50 ? "warn" : "fail", dev)
    }

    static func makeAnswers(_ doc: BenchDocument, tiers: [TierResult], sustain rawSustain: SustainResult?, pins: [PinnedTier]) throws -> Answers {
        var limit = doc.memory.availAtStartBytes
        if let p = doc.memory.processLimitBytes { limit = min(limit, p) }
        if let g = doc.memory.gpuWorkingSetBytes { limit = min(limit, g) }
        let safety = BenchSet.safetyPermille(form: doc.device.form)!
        let usable = try Checked.mulDiv(limit, safety, 1000)

        let usableTiers = tiers.filter { !$0.numericsFailed }
        let headline = usableTiers.last
        // A heat test on a tier whose numerics failed feeds no answer (lab ERR-FX2-1).
        let sustain = usableTiers.contains(where: { $0.tier.tier == doc.sustain?.tier }) ? rawSustain : nil

        // Q2
        var maxHold: MaxHold?
        if let l = headline {
            let kvRatio = try Checked.div(try Checked.mul(try Checked.mul(l.tier.kvBytesPerToken, BenchSet.kvContextTokens), 1000), l.tier.bytes)
            let room = max(0, try Checked.sub(usable, BenchSet.overheadBytes))
            let weight = try Checked.div(try Checked.mul(room, 1000), try Checked.add(1000, kvRatio))
            let params = try Checked.mulDiv(weight, 1000, 610)
            maxHold = MaxHold(weightBytes: weight, kvRatioPermille: kvRatio, approxParamsQ4: params, largestLoadedTier: l.tier.tier)
        }

        // Q1
        let q7b = try question1(tiers: tiers, pins: pins, usable: usable)

        // Q3
        var answer2000: Answer2000?
        if let h = headline, let dec = h.result("tg128@d0")?.stat.value, let ttft = h.result("pp512@d0")?.ttftMicros {
            var depthRatio: Int64 = 1000
            if let deep = h.result("tg128@d2048")?.stat.value { depthRatio = try Checked.mulDiv(deep, 1000, dec) }
            let target: Int64 = 2000
            if let s = sustain, doc.sustain?.tier == h.tier.tier {
                let effPeak = try Checked.mulDiv(s.peakMtps, depthRatio, 1000)
                let effPlat = try Checked.mulDiv(s.plateauMtps, depthRatio, 1000)
                var gen: Int64
                if let onsetMs = s.onsetMs {
                    let onsetMicros = try Checked.mul(onsetMs, 1000)
                    let byOnset = try Checked.mulDiv(effPeak, onsetMicros, 1_000_000_000)
                    if byOnset >= target {
                        gen = try Checked.mulDiv(target, 1_000_000_000, effPeak)
                    } else {
                        gen = try Checked.add(onsetMicros, try Checked.mulDiv(try Checked.sub(target, byOnset), 1_000_000_000, effPlat))
                    }
                } else {
                    gen = try Checked.mulDiv(target, 1_000_000_000, effPeak)
                }
                answer2000 = Answer2000(tier: h.tier.tier, depthRatioPermille: depthRatio, micros: try Checked.add(ttft, gen), thermalModel: true)
            } else {
                let rate = try Checked.mulDiv(dec, depthRatio, 1000)
                let gen = try Checked.mulDiv(target, 1_000_000_000, rate)
                answer2000 = Answer2000(tier: h.tier.tier, depthRatioPermille: depthRatio, micros: try Checked.add(ttft, gen), thermalModel: false)
            }
        }

        // Q4
        var throttle: Throttle?
        if let s = sustain, let tier = doc.sustain?.tier {
            throttle = Throttle(tier: tier, onsetMs: s.onsetMs, stabilityPermille: s.stabilityPermille, testedMs: s.durationMs)
        }

        // Q5
        func plateau(_ id: String) throws -> (Int64, Bool)? {
            guard let s = sustain else { return nil }
            if doc.sustain?.tier == id { return (s.plateauMtps, false) }
            guard let t = usableTiers.first(where: { $0.tier.tier == id }), let dec = t.result("tg128@d0")?.stat.value else { return nil }
            return (try Checked.mulDiv(dec, s.stabilityPermille, 1000), true)
        }
        let p2 = try plateau("T2")
        let p3 = try plateau("T3")
        let role = roleFor(platform: doc.device.platform, form: doc.device.form, t2: p2, t3: p3)

        // Overall confidence: the minimum over the headline tier's results and the sustained result.
        var overall: Confidence = .high
        if let h = headline {
            for r in h.tests { overall = min(overall, r.stat.confidence) }
        } else {
            overall = .insufficient
        }
        if let s = sustain { overall = min(overall, s.confidence) }

        return Answers(usableMemoryBytes: usable, safetyPermille: safety, maxHold: maxHold, q7b: q7b, answer2000: answer2000,
                       throttle: throttle, role: role, overallConfidence: overall, headlineTier: headline?.tier.tier)
    }

    static func verdict(decode: Int64, ttft: Int64) -> String {
        if decode >= Editorial.comfortableMtps && ttft <= Editorial.comfortableTtftMicros { return "comfortable" }
        if decode >= Editorial.usableMtps && ttft <= Editorial.usableTtftMicros { return "usable" }
        return "too-slow"
    }

    static func question1(tiers: [TierResult], pins: [PinnedTier], usable: Int64) throws -> Q7B {
        let t3pin = BenchSet.pin(tier: "T3", in: pins)!
        if let t3 = tiers.first(where: { $0.tier.tier == "T3" }), !t3.numericsFailed,
           let dec = t3.result("tg128@d0")?.stat.value, let ttft = t3.result("pp512@d0")?.ttftMicros {
            return Q7B(basis: "measured", decodeMtps: dec, ttft512Micros: ttft, verdict: verdict(decode: dec, ttft: ttft))
        }
        let fitBytes = try Checked.add(try Checked.add(t3pin.bytes, try Checked.mul(t3pin.kvBytesPerToken, BenchSet.kvContextTokens)), BenchSet.overheadBytes)
        let fits = fitBytes <= usable
        if fits, let t2 = tiers.first(where: { $0.tier.tier == "T2" }), !t2.numericsFailed,
           let dec2 = t2.result("tg128@d0")?.stat.value, let ttft2 = t2.result("pp512@d0")?.ttftMicros {
            let dec = try Checked.mulDiv(dec2, t2.tier.bytes, t3pin.bytes)
            let ttft = try Checked.mulDiv(ttft2, t3pin.bytes, t2.tier.bytes)
            return Q7B(basis: "estimated", decodeMtps: dec, ttft512Micros: ttft, verdict: verdict(decode: dec, ttft: ttft))
        }
        return fits ? Q7B(basis: "not-measured", decodeMtps: nil, ttft512Micros: nil, verdict: "not-measured")
                    : Q7B(basis: "cannot-hold", decodeMtps: nil, ttft512Micros: nil, verdict: "cannot-hold")
    }

    static func roleFor(platform: String, form: String, t2: (Int64, Bool)?, t3: (Int64, Bool)?) -> Role {
        let t2v = t2?.0, t3v = t3?.0
        func role(_ code: String, _ estimated: Bool) -> Role {
            Role(code: code, t2PlateauMtps: t2v, t3PlateauMtps: t3v, estimatedBasis: estimated)
        }
        if platform == "ios" || platform == "ipados" { return role("requester-foreground-helper", false) }
        if ["desktop", "server"].contains(form), let p = t3, p.0 >= Editorial.strongProviderMtps { return role("strong-provider", p.1) }
        if ["desktop", "server", "laptop", "handheld"].contains(form), let p = t2, p.0 >= Editorial.smallProviderMtps { return role("small-model-provider", p.1) }
        if ["phone", "tablet"].contains(form), let p = t2, p.0 >= Editorial.helperMtps { return role("occasional-helper", p.1) }
        return role("requester", false)
    }
}
