import AsomJSON

extension JValue {
    public static func object(_ pairs: KeyValuePairs<String, JValue>) -> JValue {
        .object(pairs.map { JMember(name: $0.key, value: $0.value) })
    }

    public static func int(_ v: Int) -> JValue { .int(Int64(v)) }
    public static func ints(_ vs: [Int64]) -> JValue { .array(vs.map { .int($0) }) }
    public static func strings(_ vs: [String]) -> JValue { .array(vs.map { .string($0) }) }
    public static func nullable(_ v: Int64?) -> JValue { v.map { .int($0) } ?? .null }
}

/// What the projection does where benchmark.md 13.3 and design B7 leave the row flags open (apple/ERRATA.md F-1).
/// The default is the literal list of 13.3. The other value is a diagnostic for the lane diff only: it adds
/// `thermal-drift` for an M04 THERMAL_DRIFT, which is what the JVM lane emits.
public struct ProjectionPolicy: Sendable, Equatable {
    public var flagThermalDrift: Bool
    public init(flagThermalDrift: Bool) { self.flagThermalDrift = flagThermalDrift }
    public static let specLiteral = ProjectionPolicy(flagThermalDrift: false)
    public static let diagnosticDriftFlag = ProjectionPolicy(flagThermalDrift: true)
}

public enum Audience: String, Sendable {
    case own
    case file
}

/// `project(derive(bench))` (benchmark.md section 13.3): the manifest's `results` rows, from the document only.
/// The FILE form (design 5.8, LAB_SPEC.md 4.1 P8) omits the battery level, the screen state and the SoC temperature.
public enum Projection {
    public static let batchTokens: Int64 = 512

    /// A tier can only be carried when it has a prefill and a decode point and every one of them has a value.
    static func isProjectable(_ t: TierResult) -> Bool {
        let pre = t.tests.filter { $0.test.kind == .prefill }
        let dec = t.tests.filter { $0.test.kind == .decode }
        guard !pre.isEmpty, !dec.isEmpty, pre.count <= 4, dec.count <= 4 else { return false }
        return t.tests.allSatisfy { $0.stat.value != nil }
    }

    public static func results(doc: BenchDocument, derived: Derived, audience: Audience, policy: ProjectionPolicy = .specLiteral) throws -> [JValue] {
        var rows: [JValue] = []
        let charging = doc.run.powerSource == "ac" && BenchSet.hasBattery(form: doc.device.form)
        let contention = max(doc.run.contentionBeforePermille, doc.run.contentionAfterPermille)
        for t in derived.tiers where isProjectable(t) {
            let tier = t.tier
            func pct(_ p10: Int64, _ p50: Int64, _ p90: Int64) -> JValue { .object(["p10": .int(p10), "p50": .int(p50), "p90": .int(p90)]) }

            var prefill: [JValue] = []
            var decode: [JValue] = []
            var mostDiscards = -1
            var runs: (Int, Int)?
            for r in t.tests {
                let s = r.stat
                let rate = pct(Stats.nearestRank(s.keptSorted, permille: 100), s.value!, Stats.nearestRank(s.keptSorted, permille: 900))
                switch r.test.kind {
                case .prefill:
                    let w = r.keptWholeSorted
                    prefill.append(.object([
                        "promptTokens": .int(r.test.tokens),
                        "milliTokPerSec": rate,
                        "ttftMicros": pct(Stats.nearestRank(w, permille: 100), r.ttftMicros!, Stats.nearestRank(w, permille: 900)),
                    ]))
                case .decode:
                    decode.append(.object([
                        "contextTokens": .int(r.test.depth),
                        "genTokens": .int(r.test.tokens),
                        "milliTokPerSec": rate,
                    ]))
                }
                let discards = s.n - s.kept
                if discards > mostDiscards {
                    mostDiscards = discards
                    runs = (s.n, discards)
                }
            }

            var sustained: JValue = .null
            var confidence = t.tests.map { $0.stat.confidence }.min() ?? .insufficient
            var throttled = false
            if derived.sustainTier == tier.tier, let s = derived.sustain, let block = doc.sustain {
                var curve: [JValue] = []
                for (k, w) in block.windows.enumerated() {
                    curve.append(.array([.int(w.tStartMs), .int(s.windowRates[k]), .null, .null]))
                }
                sustained = .object([
                    "durationMs": .int(s.durationMs),
                    "intervalMs": .int(block.windowMs),
                    "steadyMilliTokPerSec": .int(s.plateauMtps),
                    "throttleOnsetMs": .nullable(s.onsetMs),
                    "curve": .array(curve),
                ])
                confidence = min(confidence, s.confidence)
                throttled = s.onsetMs != nil
            }

            var flags: [String] = []
            if charging { flags.append("charging") }
            if throttled { flags.append("thermal-throttled") }
            if contention > 50 { flags.append("background-load") }
            if t.tests.contains(where: { $0.stat.kept < 4 }) { flags.append("low-runs") }
            if t.tests.contains(where: { $0.stat.flags.contains("UNSTABLE") }) { flags.append("unstable") }
            if policy.flagThermalDrift, t.tests.contains(where: { $0.stat.flags.contains("THERMAL_DRIFT") }) { flags.append("thermal-drift") }
            if tier.startThermal != "cool" { flags.append("warm-start") }
            if t.numerics != "pass" { flags.append("numerics-\(t.numerics)") }
            flags.append("confidence-\(confidence.name)")
            flags.sort { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }

            var conditions: [(String, JValue)] = [
                ("charging", .bool(charging)),
                ("thermalStart", .string(BenchDocument.thermalNames[Int(tier.startThermalCode)])),
            ]
            if audience == .own {
                conditions.append(("batteryStartPermille", .nullable(doc.run.batteryStartPermille)))
                conditions.append(("socStartMilliC", .null))
                conditions.append(("screenOn", doc.run.screenOn.map { .bool($0) } ?? .null))
            }

            let kvCache = try Checked.mul(tier.kvBytesPerToken, tier.nCtx)
            rows.append(.object([
                "modelId": .string(t.pin.modelId),
                "fileSha256": .string(t.pin.sha256),
                "fileBytes": .int(t.pin.bytes),
                "quant": .string(t.pin.quant),
                "backend": .string(doc.harness.engine.backend),
                "settings": .object([
                    "threads": .int(doc.harness.engine.threads),
                    "gpuLayers": .int(doc.harness.engine.gpuLayers),
                    "ctxTokens": .int(tier.nCtx),
                    "batchTokens": .int(batchTokens),
                ]),
                "measuredAtMs": .int(tier.startedAtMs),
                "runs": .object(["planned": .int(runs!.0), "completed": .int(runs!.0), "discarded": .int(runs!.1)]),
                "conditions": .object(conditions.map { JMember(name: $0.0, value: $0.1) }),
                "prefill": .array(prefill),
                "decode": .array(decode),
                "sustained": sustained,
                "memory": .object([
                    "availableBeforeLoadBytes": .int(tier.availBeforeLoadBytes),
                    "peakProcessBytes": .int(tier.peakFootprintBytes),
                    "kvCacheBytes": .int(kvCache),
                ]),
                "power": .object(["method": .string("unavailable"), "avgMilliW": .null]),
                "flags": .strings(flags),
            ]))
        }
        return rows
    }

    /// `project(derive(bench))` from a validated document.
    public static func project(_ doc: BenchDocument, audience: Audience, policy: ProjectionPolicy = .specLiteral) throws -> [JValue] {
        try results(doc: doc, derived: try Derivation.derive(doc), audience: audience, policy: policy)
    }

    /// The bench half of the FILE projection (LAB_SPEC.md 4.7, design 5.8, R3-CLOSURE-1): the build, driver, battery
    /// level and screen state become null, and the run and tier start times are truncated to the UTC day.
    public static func fileBench(_ bench: JValue) -> JValue {
        func truncated(_ v: JValue) -> JValue {
            guard case let .int(ms) = v else { return v }
            return .int(ms - ms % SchemaRules.dayMs)
        }
        func mapMembers(_ v: JValue, _ change: (JMember) -> JMember) -> JValue {
            guard case let .object(members) = v else { return v }
            return .object(members.map(change))
        }
        return mapMembers(bench) { top in
            switch top.name {
            case "device":
                return JMember(name: top.name, value: mapMembers(top.value) { m in
                    m.name == "osBuild" || m.name == "gpuDriver" ? JMember(name: m.name, value: .null) : m
                })
            case "run":
                return JMember(name: top.name, value: mapMembers(top.value) { m in
                    switch m.name {
                    case "batteryStartPermille", "screenOn": return JMember(name: m.name, value: .null)
                    case "startedAtMs", "endedAtMs": return JMember(name: m.name, value: truncated(m.value))
                    default: return m
                    }
                })
            case "tiers":
                guard case let .array(tiers) = top.value else { return top }
                return JMember(name: top.name, value: .array(tiers.map { tier in
                    mapMembers(tier) { m in m.name == "startedAtMs" ? JMember(name: m.name, value: truncated(m.value)) : m }
                }))
            default:
                return top
            }
        }
    }
}
