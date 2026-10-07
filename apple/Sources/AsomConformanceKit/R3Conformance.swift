import AsomBenchCore
import AsomDSSE
import AsomJSON
import AsomManifest
import Foundation

/// The r3 vector set (lab/conformance, confVersion 0.2.0) run by the Swift lane, written from LAB_SPEC.md sections 3.3,
/// 4.6 to 4.8 and 5. `lines` prints this lane's own verdict per vector, in the format of the JVM runner's lines mode.
/// `check` additionally compares the observed value with the vector's `expect` (a self-oracled expectation).
public enum R3 {
    public static let supportedConfVersion = "0.2.0"
    public static let families = ["M01", "M02", "M03", "M04", "M05", "M06", "M08"]
    /// Directories of lab/conformance that hold vector files of these families.
    static let vectorDirs = ["json", "manifest", "bench", "router"]

    public enum Expect {
        case ok(JValue)
        case reject(String)
    }

    public struct Vector {
        public let id: String
        public let family: String
        public let file: String
        public let description: String
        public let input: JValue
        public let expect: Expect
    }

    public enum Observed {
        case ok(JValue)
        case reject(RejectCode)
        /// A vector kind this lane does not implement (the run-plan, governor and executor vectors of M04).
        case notImplemented(String)
    }

    public struct Row {
        public let vector: Vector
        public let observed: Observed
    }

    // MARK: - Loading

    public static func isR3Directory(_ dir: String) -> Bool {
        guard let data = FileManager.default.contents(atPath: dir + "/VERSION") else { return false }
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) == supportedConfVersion
    }

    public static func loadVectors(family: String, in dir: String) throws -> [Vector] {
        var out: [Vector] = []
        for sub in vectorDirs {
            let base = dir + "/" + sub
            let names = ((try? FileManager.default.contentsOfDirectory(atPath: base)) ?? []).sorted()
            for name in names where name.hasPrefix("M") && name.hasSuffix(".json") {
                let path = base + "/" + name
                let file = try Conformance.loadJSON(path)
                guard file.member("family")?.stringValue == family else { continue }
                guard file.member("confVersion")?.stringValue == supportedConfVersion else {
                    throw ConformanceError("\(path): confVersion is not \(supportedConfVersion)")
                }
                guard let vectors = file.member("vectors")?.elements else { throw ConformanceError("\(path): no vectors array") }
                for v in vectors {
                    guard let id = v.member("id")?.stringValue, let input = v.member("input"), let expect = v.member("expect") else {
                        throw ConformanceError("\(path): a vector lacks id, input or expect")
                    }
                    let e: Expect
                    if let code = expect.member("reject")?.stringValue { e = .reject(code) } else if let ok = expect.member("ok") { e = .ok(ok) } else {
                        throw ConformanceError("\(id): expect is neither ok nor reject")
                    }
                    out.append(Vector(id: id, family: family, file: sub + "/" + name, description: v.member("description")?.stringValue ?? "",
                                      input: input, expect: e))
                }
            }
        }
        return out.sorted { $0.id < $1.id }
    }

    // MARK: - Running

    public static func run(families: [String], in dir: String, policy: ProjectionPolicy = .specLiteral) throws -> [Row] {
        var rows: [Row] = []
        for f in families {
            guard self.families.contains(f) else { throw ConformanceError("unknown family \(f)") }
            for v in try loadVectors(family: f, in: dir) { rows.append(Row(vector: v, observed: observe(v, policy: policy))) }
        }
        return rows
    }

    /// One line per vector this lane runs, sorted by id: `<id> ok` or `<id> reject <CODE>`.
    public static func lines(_ rows: [Row]) -> [String] {
        var out: [String] = []
        for r in rows {
            switch r.observed {
            case .ok: out.append("\(r.vector.id) ok")
            case let .reject(code): out.append("\(r.vector.id) reject \(code.rawValue)")
            case .notImplemented: continue
            }
        }
        return out.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
    }

    /// Vectors the lane does not implement, by kind, for the summary on stderr.
    public static func notImplementedSummary(_ rows: [Row]) -> [String: Int] {
        var counts: [String: Int] = [:]
        for r in rows { if case let .notImplemented(kind) = r.observed { counts["\(r.vector.family)/\(kind)", default: 0] += 1 } }
        return counts
    }

    public struct Mismatch {
        public let id: String
        public let reason: String
    }

    /// The observed verdict and value against the vector's own expectation. Not-implemented vectors are skipped.
    public static func mismatches(_ rows: [Row]) -> [Mismatch] {
        var out: [Mismatch] = []
        for r in rows {
            switch (r.observed, r.vector.expect) {
            case (.notImplemented, _): continue
            case let (.ok(got), .ok(want)):
                let a = (try? JCS.serialize(got)) ?? [], b = (try? JCS.serialize(want)) ?? []
                if a != b { out.append(Mismatch(id: r.vector.id, reason: "value differs: " + describeDifference(got, want))) }
            case let (.reject(code), .reject(want)):
                if code.rawValue != want { out.append(Mismatch(id: r.vector.id, reason: "expected reject \(want), observed reject \(code.rawValue)")) }
            case let (.ok, .reject(want)):
                out.append(Mismatch(id: r.vector.id, reason: "expected reject \(want), observed ok"))
            case let (.reject(code), .ok):
                out.append(Mismatch(id: r.vector.id, reason: "expected ok, observed reject \(code.rawValue)"))
            }
        }
        return out
    }

    // MARK: - Observation

    public static func observe(_ v: Vector, policy: ProjectionPolicy = .specLiteral) -> Observed {
        do {
            switch v.family {
            case "M01": return try observeM01(v)
            case "M02", "M03": return try observeVerify(v, policy)
            case "M04": return try observeM04(v, policy)
            case "M05": return try observeM05(v, policy)
            case "M06": return try observeM06(v, policy)
            case "M08": return try observeM08(v)
            default: throw ConformanceError("unknown family")
            }
        } catch let error as ConformanceError {
            return .reject(RejectCode("HARNESS_ERROR: \(error.description)"))
        } catch {
            return .reject(RejectCode("HARNESS_ERROR: \(error)"))
        }
    }

    // MARK: M01

    static func observeM01(_ v: Vector) throws -> Observed {
        let kind = v.input.member("kind")?.stringValue ?? ""
        switch kind {
        case "canonicalize":
            let bytes: [UInt8]
            if let text = v.input.member("inputText")?.stringValue { bytes = Array(text.utf8) } else if let hex = v.input.member("inputHex")?.stringValue {
                bytes = try hexBytes(hex)
            } else { throw ConformanceError("\(v.id): no input") }
            switch JCS.canonicalize(bytes) {
            case let .failure(code): return .reject(code)
            case let .success(out): return .ok(.object(["text": .string(String(decoding: out, as: UTF8.self)), "utf8Hex": .string(hex(out))]))
            }
        case "base64Either", "base64UrlNoPad":
            guard let text = v.input.member("text")?.stringValue else { throw ConformanceError("\(v.id): no text") }
            let decoded = kind == "base64Either" ? Base64Strict.decodeEither(text) : Base64Strict.decodeURLNoPad(text)
            guard let bytes = decoded else { return .reject(.encoding) }
            return .ok(.object(["hex": .string(hex(bytes))]))
        case "derToRaw":
            switch SignatureCodec.derToRaw(try hexBytes(v.input.member("derHex")?.stringValue ?? "")) {
            case let .failure(code): return .reject(code)
            case let .success(raw): return .ok(.object(["rawHex": .string(hex(raw))]))
            }
        case "rawToDer":
            switch SignatureCodec.rawToDer(try hexBytes(v.input.member("rawHex")?.stringValue ?? "")) {
            case let .failure(code): return .reject(code)
            case let .success(der): return .ok(.object(["derHex": .string(hex(der))]))
            }
        case "normaliseLowS":
            let raw = try hexBytes(v.input.member("rawHex")?.stringValue ?? "")
            guard raw.count == SignatureCodec.rawLength else { return .reject(.signatureEncoding) }
            return .ok(.object(["rawHex": .string(hex(SignatureCodec.normaliseLowS(raw)))]))
        default:
            return .notImplemented(kind)
        }
    }

    // MARK: M02, M03

    static func documentBytes(_ input: JValue) throws -> [UInt8] {
        if let s = input.member("document")?.stringValue { return Array(s.utf8) }
        if let h = input.member("documentHex")?.stringValue { return try hexBytes(h) }
        if let fill = input.member("documentFill"), let byte = fill.member("byte")?.intValue, let count = fill.member("count")?.intValue {
            return [UInt8](repeating: UInt8(byte), count: Int(count))
        }
        throw ConformanceError("no document")
    }

    static func verifyContext(_ input: JValue, _ policy: ProjectionPolicy) throws -> VerifyContext {
        guard let c = input.member("context"), let mode = c.member("mode")?.stringValue, let now = input.member("nowMs")?.intValue else {
            throw ConformanceError("no context or nowMs")
        }
        guard let floor = c.member("confFloor")?.stringValue else { throw ConformanceError("no confFloor") }
        var ctx = VerifyContext(mode: mode == "MESH" ? .mesh : .file, confFloor: floor, nowMs: now)
        ctx.projectionPolicy = policy
        switch mode {
        case "MESH":
            guard let b64 = c.member("pinnedSpkiB64")?.stringValue, let spki = Base64Strict.decodeEither(b64) else { throw ConformanceError("pinnedSpkiB64") }
            ctx.pinnedSpki = spki
            if let ch = c.member("expectedChallengeB64u")?.stringValue { ctx.expectedChallenge = Base64Strict.decodeURLNoPad(ch) }
        case "FILE":
            if let f = c.member("comparedFingerprint")?.stringValue { ctx.comparedFingerprint = f }
            if let m = c.member("compareMethod")?.stringValue { ctx.compareMethod = m == "qr" ? .qr : .typed }
        default:
            throw ConformanceError("unknown mode \(mode)")
        }
        if let rb = c.member("rollback")?.members {
            for m in rb {
                guard let seq = m.value.member("seq")?.intValue, let d = m.value.member("bodyDigest")?.stringValue else { throw ConformanceError("rollback entry") }
                ctx.rollback[m.name] = RollbackEntry(seq: seq, bodyDigest: d)
            }
        }
        if let t = c.member("requiredTier")?.stringValue, let tier = AttestationTier.named(t) { ctx.requiredTier = tier }
        ctx.knownBadConf = Set((c.member("knownBadConf")?.elements ?? []).compactMap { $0.stringValue })
        if case let .bool(p)? = c.member("productionKeys") { ctx.productionKeys = p }
        return ctx
    }

    static func pinName(_ pin: PinState) -> String {
        switch pin {
        case .pinned: return "PINNED"
        case .signerUnverified: return "SIGNER_UNVERIFIED"
        case let .pinnedByFingerprint(m): return "PINNED_BY_FINGERPRINT(\(m == .qr ? "qr" : "typed"))"
        }
    }

    static func observeVerify(_ v: Vector, _ policy: ProjectionPolicy) throws -> Observed {
        let doc = try documentBytes(v.input)
        let ctx = try verifyContext(v.input, policy)
        switch ManifestVerifier.verify(document: doc, context: ctx) {
        case let .failure(f): return .reject(f.code)
        case let .success(ok):
            return .ok(.object([
                "pin": .string(pinName(ok.pin)), "tier": .string(ok.tier.name), "seq": .nullable(ok.manifest.seq),
                "bodyDigest": .string(ok.bodyDigest), "unknownFields": .int(ok.unknownFields),
            ]))
        }
    }

    // MARK: M05

    static func renderOptions(_ input: JValue) -> RenderOptions {
        var o = RenderOptions()
        if case let .bool(b)? = input.member("render")?.member("meshAvailable") { o.meshAvailable = b }
        if case let .bool(b)? = input.member("render")?.member("mlperfNoteEnabled") { o.mlperfNoteEnabled = b }
        return o
    }

    static func textResult(_ text: String) -> Observed {
        .ok(.object(["text": .string(text), "sha256": .string(NodeIdentity.sha256Hex(Array(text.utf8)))]))
    }

    static func observeM05(_ v: Vector, _ policy: ProjectionPolicy) throws -> Observed {
        let kind = v.input.member("kind")?.stringValue ?? ""
        let options = renderOptions(v.input)
        switch kind {
        case "manifest", "export":
            let doc = try documentBytes(v.input)
            let ctx = try verifyContext(v.input, policy)
            var display = false
            if case let .bool(b)? = v.input.member("displayOnReject") { display = b }
            switch ManifestVerifier.verify(document: doc, context: ctx) {
            case let .success(ok):
                if kind == "export" { return textResult(try ManifestText.exportText(ok.manifest, options: options)) }
                let viewed = ManifestText.Viewed(manifest: ok.manifest, mode: ctx.mode, pin: ok.pin, signerSpki: ok.signerSpki)
                return textResult(try ManifestText.render(viewed, options: options))
            case let .failure(f):
                guard display, let d = f.displayable else { return .reject(f.code) }
                let viewed = ManifestText.Viewed(manifest: d.manifest, mode: ctx.mode, pin: d.pin, signerSpki: d.signerSpki, rejected: f.code)
                return textResult(try ManifestText.render(viewed, options: options))
            }
        case "body":
            switch decodeBench(v.input, fileForm: false) {
            case let .failure(code): return .reject(code)
            case let .success(doc):
                do {
                    let derived = try Derivation.derive(doc)
                    return textResult(TextRenderer.render(doc, derived, options: options))
                } catch is BenchArithmeticError {
                    return .reject(.inconsistent)
                }
            }
        case "mlperf":
            switch decodeBench(v.input, fileForm: false) {
            case let .failure(code): return .reject(code)
            case let .success(doc):
                let derived = try Derivation.derive(doc)
                var results: [JValue] = []
                for c in v.input.member("cases")?.elements ?? [] {
                    var o = RenderOptions()
                    if case let .bool(b)? = c.member("mlperfNoteEnabled") { o.mlperfNoteEnabled = b }
                    let text = TextRenderer.render(doc, derived, options: o)
                    results.append(.object([
                        "benchSet": .string(c.member("benchSet")?.stringValue ?? ""),
                        "mlperfNoteEnabled": .bool(o.mlperfNoteEnabled),
                        "noteRendered": .bool(text.contains("MLPerf")),
                        "sha256": .string(NodeIdentity.sha256Hex(Array(text.utf8))),
                    ]))
                }
                return .ok(.object(["results": .array(results)]))
            }
        default:
            return .notImplemented(kind)
        }
    }

    // MARK: M04

    static func decodeBench(_ input: JValue, fileForm: Bool) -> Result<BenchDocument, RejectCode> {
        guard let value = input.member("benchDoc") else { return .failure(RejectCode("HARNESS_ERROR: no benchDoc")) }
        do {
            return .success(try BenchDocument.decode(fileForm ? Projection.fileBench(value) : value, fileForm: fileForm, state: DecodeState(tolerateUnknown: false)))
        } catch {
            return .failure(.schemaInvalid)
        }
    }

    static func intArray(_ v: JValue?) -> [Int64] { (v?.elements ?? []).compactMap { $0.intValue } }

    static func observeM04(_ v: Vector, _ policy: ProjectionPolicy) throws -> Observed {
        let kind = v.input.member("kind")?.stringValue ?? ""
        switch kind {
        case "test":
            guard let spec = v.input.member("spec")?.stringValue, let (tk, tokens, _) = BenchDocument.parseTestName(spec) else { throw ConformanceError("spec") }
            let samples = intArray(v.input.member("samples"))
            var ctx = TestContext()
            let c = v.input.member("ctx")
            if case let .bool(b)? = c?.member("warmStart") { ctx.warmStart = b }
            if let p = c?.member("contentionPermille")?.intValue { ctx.contentionPermille = p }
            if case let .bool(b)? = c?.member("virtualized") { ctx.virtualized = b }
            if case let .bool(b)? = c?.member("streamTiming") { ctx.streamTiming = b }
            if case let .bool(b)? = c?.member("restarted") { ctx.restarted = b }
            if case let .bool(b)? = c?.member("swapped") { ctx.swapped = b }
            do {
                let rates = try samples.map { try Stats.rate(tokens: tokens, micros: $0) }
                let stat = try Stats.stat(rates: rates, context: ctx)
                var members: [JMember] = [
                    JMember(name: "value", value: .nullable(stat.value)), JMember(name: "kept", value: .int(stat.kept)),
                    JMember(name: "keptIdx", value: .ints(stat.keptIdx.map { Int64($0) })),
                    JMember(name: "relSpreadPermille", value: .int(stat.relSpreadPermille)),
                    JMember(name: "confidence", value: .string(stat.confidence.name)), JMember(name: "flags", value: .strings(stat.flags)),
                ]
                if tk == .prefill, let whole = v.input.member("whole") {
                    let w = intArray(whole)
                    let kept = stat.keptIdx.map { w[$0] }.sorted()
                    members.append(JMember(name: "ttftMicros", value: stat.value == nil ? .null : .int(kept[kept.count / 2])))
                }
                return .ok(.object(members))
            } catch is BenchArithmeticError {
                return .reject(.inconsistent)
            }
        case "percentile":
            let values = intArray(v.input.member("values")).sorted()
            guard let p = v.input.member("p")?.intValue, !values.isEmpty else { throw ConformanceError("percentile input") }
            return .ok(.object(["value": .int(Stats.nearestRank(values, permille: p))]))
        case "sustain":
            let i = v.input
            var windows: [BenchDocument.Window] = []
            for w in i.member("windows")?.elements ?? [] {
                let c = intArray(w)
                guard c.count == 4 else { throw ConformanceError("window tuple") }
                windows.append(BenchDocument.Window(tStartMs: c[0], tokens: c[1], micros: c[2], thermalCode: c[3]))
            }
            let block = BenchDocument.Sustain(
                tier: i.member("tier")?.stringValue ?? "", windowMs: i.member("windowMs")?.intValue ?? 0, capMs: i.member("capMs")?.intValue ?? 0,
                endReason: i.member("endReason")?.stringValue ?? "", headroomAtOnsetPermille: i.member("headroomAtOnsetPermille")?.intValue, windows: windows
            )
            do {
                return .ok(sustainJSON(try SustainDerivation.derive(block, startedCool: i.member("startThermal")?.stringValue == "cool")))
            } catch is BenchArithmeticError {
                return .reject(.inconsistent)
            }
        case "doc":
            var audience: Audience?
            if let a = v.input.member("audience")?.stringValue { audience = Audience(rawValue: a) }
            switch decodeBench(v.input, fileForm: false) {
            case let .failure(code): return .reject(code)
            case let .success(doc):
                do {
                    let derived = try Derivation.derive(doc)
                    // A reject-vector without an audience only has to decode; the projection needs one.
                    let results = try Projection.results(doc: doc, derived: derived, audience: audience ?? .own, policy: policy)
                    let resultsBytes = try JCS.serialize(.array(results))
                    let plain = TextRenderer.render(doc, derived, options: RenderOptions(meshAvailable: false))
                    let mesh = TextRenderer.render(doc, derived, options: RenderOptions(meshAvailable: true))
                    return .ok(.object([
                        "answers": answersJSON(derived), "resultCount": .int(results.count),
                        "resultsSha256": .string(NodeIdentity.sha256Hex(resultsBytes)),
                        "textSha256": .string(NodeIdentity.sha256Hex(Array(plain.utf8))),
                        "textSha256Mesh": .string(NodeIdentity.sha256Hex(Array(mesh.utf8))),
                    ]))
                } catch is BenchArithmeticError {
                    return .reject(.inconsistent)
                }
            }
        case "plan":
            guard let id = v.input.member("plan")?.stringValue else { throw ConformanceError("\(v.id): no plan") }
            guard let bytes = try RunPlan.jcsBytes(id) else { return .notImplemented("plan-unspecified") }
            return .ok(.object(["planSha256": .string(Base64Strict.encodeURL(NodeIdentity.sha256(bytes))), "jcsBytes": .int(Int64(bytes.count))]))
        case "pins":
            let q1 = try BenchSet.load(.q1)
            let l1Refused: Bool
            do { _ = try BenchSet.load(.l1) ; l1Refused = false } catch is BenchSet.RulingRequired { l1Refused = true }
            let l1 = try BenchSet.load(.l1, d18Ruling: true)
            return .ok(.object([
                "defaultSetIds": .strings(BenchSet.defaultSetIds), "l1InDefaults": .bool(BenchSet.defaultKinds.contains(.l1)),
                "l1WithoutRulingThrows": .bool(l1Refused), "l1HasPins": .bool(!l1.pins.isEmpty), "q1Status": .string(q1.status.rawValue),
                "q1": .array(q1.pins.map { .object(["tier": .string($0.tier), "bytes": .int($0.bytes), "sha256": .string($0.sha256)]) }),
            ]))
        case "fsm":
            return .ok(.object(["edges": .strings(Governor.edges)]))
        case "ceilings":
            let i = v.input
            let thermal = i.member("thermal"), power = i.member("power"), presence = i.member("presence")
            var lowPower = false
            if case let .bool(b)? = presence?.member("lowPowerMode") { lowPower = b }
            let inputs = CeilingInputs(
                platform: i.member("platform")?.stringValue ?? "", form: i.member("form")?.stringValue ?? "",
                thermalCode: thermal?.member("code")?.intValue ?? 0, headroomPermille: thermal?.member("headroom")?.intValue,
                batteryTempDeciC: thermal?.member("batteryTempDeciC")?.intValue, powerSource: power?.member("source")?.stringValue ?? "",
                batteryLevelPermille: power?.member("level")?.intValue, lowPowerMode: lowPower, gpuBusyHeldMs: i.member("gpuBusyHeldMs")?.intValue ?? 0
            )
            return .ok(.object(["ceiling": .string(try Ceilings.evaluate(inputs).text)]))
        case "consent":
            return try observeConsent(v.input, id: v.id)
        default:
            return .notImplemented(kind)
        }
    }

    static func observeConsent(_ i: JValue, id: String) throws -> Observed {
        guard let plan = i.member("plan")?.stringValue, let bytes = i.member("downloadBytes")?.intValue,
              let mint = i.member("mintNowMs")?.intValue, let consume = i.member("consumeNowMs")?.intValue,
              let confirm = i.member("confirm")?.stringValue else { throw ConformanceError("\(id): consent input") }
        var optIn = false, today = false, twice = false
        if case let .bool(b)? = i.member("t3OptInOffered") { optIn = b }
        if case let .bool(b)? = i.member("sustainedToday") { today = b }
        if case let .bool(b)? = i.member("consumeTwice") { twice = b }
        let inputs = ConsentInputs(
            plan: plan, downloadBytes: bytes, tiers: (i.member("tiers")?.elements ?? []).compactMap { $0.stringValue },
            t3OptInOffered: optIn, sustainedToday: today
        )
        let text = ConsentSheet.text(inputs)
        let shown: [UInt8] = confirm == "match" ? ConsentSheet.textSha256(text) : [UInt8](repeating: 0, count: 32)
        var gate = ConsentGate()
        guard case let .success(token) = gate.confirm(sheetFor: inputs, shownSha256: shown, nowMs: mint) else { return .reject(RejectCode("CONSENT_REFUSED")) }
        let spend = i.member("consumePlan")?.stringValue ?? plan
        var outcome = gate.consume(token, plan: spend, nowMs: consume)
        if twice, case .success = outcome { outcome = gate.consume(token, plan: spend, nowMs: consume) }
        guard case .success = outcome else { return .reject(RejectCode("CONSENT_REFUSED")) }
        guard ConsentSheet.wordingIsSpecified(inputs) else { return .notImplemented("consent-text-unspecified") }
        return .ok(.object(["textSha256": .string(NodeIdentity.sha256Hex(Array(text.utf8))), "outcome": .string("consumed")]))
    }

    static func sustainJSON(_ s: SustainResult) -> JValue {
        .object([
            "peakMtps": .int(s.peakMtps), "plateauMtps": .int(s.plateauMtps), "onsetMs": .nullable(s.onsetMs),
            "stabilityPermille": .int(s.stabilityPermille), "durationMs": .int(s.durationMs),
            "thermalCodeAtOnset": .nullable(s.thermalCodeAtOnset), "headroomAtOnsetPermille": .nullable(s.headroomAtOnsetPermille),
            "confidence": .string(s.confidence.name), "flags": .strings(s.flags),
        ])
    }

    static func answersJSON(_ d: Derived) -> JValue {
        let a = d.answers
        func nullableObject<T>(_ v: T?, _ f: (T) -> JValue) -> JValue { v.map(f) ?? .null }
        return .object([
            "usableMemoryBytes": .int(a.usableMemoryBytes), "safetyPermille": .int(a.safetyPermille),
            "maxHold": nullableObject(a.maxHold) { m in .object([
                "weightBytes": .int(m.weightBytes), "kvRatioPermille": .int(m.kvRatioPermille),
                "approxParamsQ4": .int(m.approxParamsQ4), "largestLoadedTier": .string(m.largestLoadedTier),
            ]) },
            "q7b": .object([
                "basis": .string(a.q7b.basis), "decodeMtps": .nullable(a.q7b.decodeMtps),
                "ttft512Micros": .nullable(a.q7b.ttft512Micros), "verdict": .string(a.q7b.verdict),
            ]),
            "answer2000": nullableObject(a.answer2000) { x in .object([
                "tier": .string(x.tier), "depthRatioPermille": .int(x.depthRatioPermille), "micros": .int(x.micros), "thermalModel": .bool(x.thermalModel),
            ]) },
            "throttle": nullableObject(a.throttle) { t in .object([
                "tier": .string(t.tier), "onsetMs": .nullable(t.onsetMs), "stabilityPermille": .int(t.stabilityPermille), "testedMs": .int(t.testedMs),
            ]) },
            "role": .object([
                "code": .string(a.role.code), "t2PlateauMtps": .nullable(a.role.t2PlateauMtps), "t3PlateauMtps": .nullable(a.role.t3PlateauMtps),
            ]),
            "overallConfidence": .string(a.overallConfidence.name),
            "tiers": .array(d.tiers.map { t in .object([
                "tier": .string(t.tier.tier), "numerics": .string(t.numerics), "loadWarmMicros": .int(t.loadWarmMicros), "flags": .strings(t.flags),
                "tests": .array(t.tests.map { r in .object([
                    "test": .string(r.test.name), "value": .nullable(r.stat.value), "kept": .int(r.stat.kept),
                    "confidence": .string(r.stat.confidence.name), "flags": .strings(r.stat.flags),
                ]) }),
            ]) }),
            "sustain": d.sustain.map(sustainJSON) ?? .null,
        ])
    }

    // MARK: M06

    static func observeM06(_ v: Vector, _ policy: ProjectionPolicy) throws -> Observed {
        let kind = v.input.member("kind")?.stringValue ?? ""
        switch kind {
        case "q2":
            return .ok(.object(["q2": .ints(intArray(v.input.member("values")).map { PublicDerivative.q2($0) })]))
        case "public":
            let doc = try documentBytes(v.input)
            let ctx = try verifyContext(v.input, policy)
            let ok: Verified
            switch ManifestVerifier.verify(document: doc, context: ctx) {
            case let .failure(f): return .reject(f.code)
            case let .success(verified): ok = verified
            }
            let allow = v.input.member("allowList")
            let coarse = v.input.member("coarse")
            func strings(_ x: JValue?) -> [String] { (x?.elements ?? []).compactMap { $0.stringValue } }
            let list = PublicDerivative.AllowList(
                engineCommits: strings(allow?.member("engineCommits")), harnessVersions: strings(allow?.member("harnessVersions")),
                vendors: strings(coarse?.member("vendors")), models: strings(coarse?.member("models"))
            )
            guard let derivative = try PublicDerivative.make(from: ok.manifest, catalogue: Set(strings(v.input.member("catalogue"))), allow: list) else {
                return .ok(.object(["none": .bool(true)]))
            }
            let bytes = try JCS.serialize(derivative)
            return .ok(.object(["jcsUtf8": .string(String(decoding: bytes, as: UTF8.self)), "sha256": .string(NodeIdentity.sha256Hex(bytes))]))
        case "fileProjection":
            guard let own = v.input.member("ownObj"), let node = v.input.member("exportNodeId")?.stringValue else { throw ConformanceError("fileProjection input") }
            do {
                let body = try FileProjection.body(ownObject: own, exportNodeId: node, policy: policy)
                let bytes = try JCS.serialize(body)
                return .ok(.object(["jcsUtf8": .string(String(decoding: bytes, as: UTF8.self)), "sha256": .string(NodeIdentity.sha256Hex(bytes))]))
            } catch let f as FileProjection.Failure {
                return .reject(f.code)
            }
        default:
            return .notImplemented(kind)
        }
    }

    // MARK: - Diagnostics

    /// For the `debug` command: the projected results of a bench-document vector, as JCS text.
    public static func debugResults(id: String, in dir: String, audience: Audience, policy: ProjectionPolicy = .specLiteral) throws -> String {
        for family in ["M04", "M05"] {
            for v in try loadVectors(family: family, in: dir) where v.id == id {
                switch decodeBench(v.input, fileForm: false) {
                case let .failure(code): return "reject \(code.rawValue)"
                case let .success(doc):
                    let rows = try Projection.project(doc, audience: audience, policy: policy)
                    return String(decoding: try JCS.serialize(.array(rows)), as: UTF8.self)
                }
            }
        }
        throw ConformanceError("no bench vector \(id)")
    }

    /// For the `debug-payload` command: the projection of the `bench` inside a verify vector's payload, as JCS text.
    public static func debugPayloadResults(id: String, in dir: String, policy: ProjectionPolicy = .specLiteral) throws -> String {
        for family in ["M02", "M03", "M05", "M06"] {
            for v in try loadVectors(family: family, in: dir) where v.id == id {
                let container = try Conformance.parseValue(try documentBytes(v.input))
                guard let b64 = container.member("dsse")?.member("payload")?.stringValue, let payload = Base64Strict.decodeEither(b64) else {
                    throw ConformanceError("no payload")
                }
                let object = try Conformance.parseValue(payload)
                switch ManifestDecoder.decode(object) {
                case let .failure(code): return "decode reject \(code.rawValue)"
                case let .success(m):
                    let rows = try Projection.project(m.bench, audience: m.audience, policy: policy)
                    return String(decoding: try JCS.serialize(.array(rows)), as: UTF8.self)
                }
            }
        }
        throw ConformanceError("no vector \(id)")
    }

    // MARK: - Small helpers

    static func hex(_ bytes: [UInt8]) -> String {
        let digits = Array("0123456789abcdef")
        var out = ""
        for b in bytes { out.append(digits[Int(b >> 4)]); out.append(digits[Int(b & 0xF)]) }
        return out
    }

    static func hexBytes(_ text: String) throws -> [UInt8] {
        let chars = Array(text.utf8)
        guard chars.count % 2 == 0 else { throw ConformanceError("odd hex length") }
        var out: [UInt8] = []
        var k = 0
        func nibble(_ c: UInt8) throws -> UInt8 {
            switch c {
            case 0x30...0x39: return c - 0x30
            case 0x61...0x66: return c - 0x61 + 10
            case 0x41...0x46: return c - 0x41 + 10
            default: throw ConformanceError("bad hex digit")
            }
        }
        while k < chars.count { out.append(try nibble(chars[k]) << 4 | nibble(chars[k + 1])); k += 2 }
        return out
    }

    static func describeDifference(_ got: JValue, _ want: JValue) -> String {
        if case let .object(a) = got, case let .object(b) = want {
            for m in a {
                guard let other = want.member(m.name) else { return "member \(m.name) is not expected" }
                if (try? JCS.serialize(m.value)) != (try? JCS.serialize(other)) { return "member \(m.name): \(describeDifference(m.value, other))" }
            }
            for m in b where got.member(m.name) == nil { return "member \(m.name) is missing" }
            return "objects differ"
        }
        if case let .string(a) = got, case let .string(b) = want {
            let x = a.split(separator: "\n", omittingEmptySubsequences: false), y = b.split(separator: "\n", omittingEmptySubsequences: false)
            for k in 0..<max(x.count, y.count) {
                let l = k < x.count ? String(x[k]) : "<end>", r = k < y.count ? String(y[k]) : "<end>"
                if l != r { return "line \(k + 1): got [\(l)] want [\(r)]" }
            }
            return "strings differ"
        }
        let a = (try? JCS.serialize(got)).map { String(decoding: $0, as: UTF8.self) } ?? "?"
        let b = (try? JCS.serialize(want)).map { String(decoding: $0, as: UTF8.self) } ?? "?"
        return "got \(a.prefix(300)) want \(b.prefix(300))"
    }
}
