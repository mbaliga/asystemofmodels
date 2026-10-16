import AsomBenchCore
import AsomJSON

/// Step 11 of the verifier: a hand-written typed decoder (LAB_SPEC.md section 4.6, design 5.2). The JSON Schema in
/// lab/conformance is documentation and a test oracle only; this decoder is the normative one.
public enum ManifestDecoder {
    public static let knownMinor: Int64 = 0
    static let keyStorages = ["strongbox", "tee", "secure-enclave", "tpm", "os-keystore", "file", "unknown", "ephemeral"]
    static let osFamilies = BenchDocument.platforms
    static let powerMethods = ["battery-current", "battery-level", "rapl", "pmic", "unavailable"]
    static let maxRate: Int64 = 1_000_000_000
    static let maxTtftMicros: Int64 = 3_600_000_000

    /// `SCHEMA_MAJOR_UNKNOWN` for "asom.manifest/N" with N > 1 (canonical decimal), else `SCHEMA_INVALID`.
    public static func decode(_ object: JValue) -> Result<Manifest, RejectCode> {
        guard case .object = object else { return .failure(.schemaInvalid) }
        guard let schema = object.member("schema")?.stringValue else { return .failure(.schemaInvalid) }
        if !codeUnitsEqual(schema, "asom.manifest/1") {
            return .failure(newerMajor(schema) ? .schemaMajorUnknown : .schemaInvalid)
        }
        do {
            return .success(try decodeChecked(object))
        } catch is SchemaViolation {
            return .failure(.schemaInvalid)
        } catch {
            return .failure(.schemaInvalid)
        }
    }

    static func newerMajor(_ schema: String) -> Bool {
        let head = Array("asom.manifest/".utf8)
        let bytes = Array(schema.utf8)
        guard bytes.count > head.count, bytes.starts(with: head) else { return false }
        let digits = Array(bytes[head.count...])
        return digits.allSatisfy { $0 >= 0x30 && $0 <= 0x39 } && digits.first != 0x30 && digits != [0x31]
    }

    static func decodeChecked(_ value: JValue) throws -> Manifest {
        let minor = try ObjectReader.asInt(try requiredMember(value, "schemaMinor"), 0...1000, "payload.schemaMinor")
        let state = DecodeState(tolerateUnknown: minor > knownMinor)
        let root = try ObjectReader(value, "payload", state)
        try root.constant("schema", "asom.manifest/1")
        _ = try root.int("schemaMinor", 0...1000)

        let bodyValue = try root.required("body")
        if SchemaRules.containsForbiddenName(bodyValue) { throw SchemaViolation("payload.body", "a forbidden member name (P7)") }
        let body = try ObjectReader(bodyValue, "payload.body", state)
        let audience: Audience
        switch try body.oneOf("audience", ["own", "file"]) {
        case "own": audience = .own
        default: audience = .file
        }
        var seq: Int64?
        if audience == .own {
            seq = try body.int("seq", 1...9_007_199_254_740_991)
        } else if body.has("seq") {
            throw SchemaViolation("payload.body.seq", "the file audience carries no seq (P2)")
        }
        let subject = try decodeSubject(try body.object("subject"), audience: audience)
        let producer = try decodeProducer(try body.object("producer"))
        let device = try decodeDevice(try body.object("device"), audience: audience)
        let resultsValue = try body.required("results")
        guard case let .array(rowValues) = resultsValue, rowValues.count <= 64 else { throw SchemaViolation("payload.body.results", "not an array of at most 64") }
        var rows: [Manifest.Row] = []
        for (k, item) in rowValues.enumerated() { rows.append(try decodeRow(item, "payload.body.results[\(k)]", audience: audience, state: state)) }
        let bench = try BenchDocument.decode(try body.required("bench"), fileForm: audience == .file, state: state)
        try body.finish()

        // a FILE presentation is exactly { issuedAtMs }, whatever the minor (P3, ERR-FX-CV4): it is read with a state that tolerates nothing
        let p = audience == .file
            ? try ObjectReader(try root.required("presentation"), "payload.presentation", DecodeState(tolerateUnknown: false))
            : try root.object("presentation")
        let issued = try p.int("issuedAtMs", SchemaRules.epochLow...(SchemaRules.epochHigh - 1))
        var expires: Int64?
        var challenge: String?
        switch audience {
        case .own:
            expires = try p.int("expiresAtMs", SchemaRules.epochLow...(SchemaRules.epochHigh - 1))
            let c = try p.required("challenge")
            if !c.isNull {
                let text = try ObjectReader.asString(c, SchemaRules.isB64u43, "payload.presentation.challenge")
                guard let raw = Base64Strict.decodeEither(text), raw.count == 32 else {
                    throw SchemaViolation("payload.presentation.challenge", "not 32 bytes of strict base64url")
                }
                challenge = text
            }
        case .file:
            guard issued % SchemaRules.dayMs == 0 else { throw SchemaViolation("payload.presentation.issuedAtMs", "the file form truncates to the day (P3)") }
            if p.has("expiresAtMs") || p.has("challenge") { throw SchemaViolation("payload.presentation", "the file form carries only issuedAtMs (P3)") }
        }
        try p.finish()
        try root.finish()

        return Manifest(
            schemaMinor: minor, audience: audience, seq: seq, subject: subject, producer: producer, device: device, rows: rows,
            resultsValue: resultsValue, bench: bench, issuedAtMs: issued, expiresAtMs: expires, challenge: challenge,
            unknownFields: state.unknownMembers
        )
    }

    private static func requiredMember(_ v: JValue, _ name: String) throws -> JValue {
        guard let m = v.member(name) else { throw SchemaViolation("payload.\(name)", "missing") }
        return m
    }

    private static func decodeSubject(_ o: ObjectReader, audience: Audience) throws -> Manifest.Subject {
        let nodeId = try o.string("nodeId", SchemaRules.isB64u43)
        try o.constant("keyAlg", "ES256")
        let storage = try o.oneOf("keyStorage", keyStorages)
        if audience == .file, storage != "ephemeral" { throw SchemaViolation(o.path, "the file form is signed by a per-export key (P4)") }
        try o.finish()
        return Manifest.Subject(nodeId: nodeId, keyStorage: storage)
    }

    private static func decodeProducer(_ o: ObjectReader) throws -> Manifest.Producer {
        let app = try o.id("app")
        let appVersion = try o.string("appVersion", SchemaRules.isSemver)
        let h = try o.object("harness")
        let harness = Manifest.Harness(
            id: try h.id("id"), version: try h.string("version", SchemaRules.isSemver),
            methodologyId: try h.string("methodologyId", SchemaRules.isMethodID),
            confVersion: try h.string("confVersion", SchemaRules.isSemver)
        )
        try h.finish()
        let e = try o.object("engine")
        let engine = Manifest.ProducerEngine(
            name: try e.id("name"), commit: try e.string("commit", SchemaRules.isCommit7to40),
            buildFlags: try BenchDocument.decodeTextSet(e, "buildFlags", max: 16)
        )
        try e.finish()
        try o.finish()
        return Manifest.Producer(app: app, appVersion: appVersion, harness: harness, engine: engine)
    }

    private static func decodeDevice(_ o: ObjectReader, audience: Audience) throws -> Manifest.Device {
        let cls = try o.id("class")
        let vendor = try o.text("vendor")
        let model = try o.text("model")

        if o.has("platformIds") {
            if audience == .file { throw SchemaViolation("\(o.path).platformIds", "the file form carries no platformIds (P8)") }
            let p = try o.object("platformIds")
            for name in ["brand", "device", "manufacturer", "model", "product"] where p.has(name) { _ = try p.text(name) }
            try p.finish()
        }
        let hasPlatformIds = audience == .own && o.has("platformIds")

        let osr = try o.object("os")
        let family = try osr.oneOf("family", osFamilies)
        let version = try osr.text("version")
        var patch: String?
        if osr.has("securityPatch") {
            if audience == .file { throw SchemaViolation("\(osr.path).securityPatch", "the file form carries no securityPatch (P8)") }
            patch = try osr.string("securityPatch", SchemaRules.isDate)
        }
        try osr.finish()

        let s = try o.object("soc")
        let socVendor = try s.text("vendor")
        let socName = try s.text("name")
        let cpu = try s.object("cpu")
        let cores = try cpu.int("logicalCores", 1...1024)
        for c in try cpu.objects("clusters", count: 0...8) {
            _ = try c.int("cores", 1...1024)
            _ = try c.int("maxKHz", 0...9_007_199_254_740_991)
            try c.finish()
        }
        try cpu.finish()
        try s.finish()

        let mem = try o.object("memory")
        let total = try mem.int("totalBytes", 0...SchemaRules.maxBytes)
        try mem.finish()

        for a in try o.objects("accelerators", count: 0...8) {
            _ = try a.id("kind"); _ = try a.text("vendor"); _ = try a.text("name")
            var seen: [String] = []
            for (j, api) in try a.array("apis", count: 0...8).enumerated() {
                let id = try ObjectReader.asString(api, SchemaRules.isID, "\(a.path).apis[\(j)]")
                guard !seen.contains(id) else { throw SchemaViolation(a.path, "duplicate api") }
                seen.append(id)
            }
            _ = try a.nullableInt("dedicatedBytes", 0...SchemaRules.maxBytes)
            try a.finish()
        }

        let power = try o.object("power")
        _ = try power.bool("battery")
        if power.has("batteryDesignMilliWh") { _ = try power.nullableInt("batteryDesignMilliWh", 0...9_007_199_254_740_991) }
        try power.finish()

        let th = try o.object("thermal")
        let cooling = try th.id("cooling")
        _ = try th.id("stateSource")
        try th.finish()

        try o.finish()
        return Manifest.Device(
            deviceClass: cls, vendor: vendor, model: model,
            os: Manifest.OS(family: family, version: version, securityPatch: patch),
            soc: Manifest.SoC(vendor: socVendor, name: socName, logicalCores: cores),
            totalBytes: total, cooling: cooling, hasPlatformIds: hasPlatformIds
        )
    }

    private static func rate(_ o: ObjectReader, _ name: String, max: Int64) throws -> Manifest.Rate {
        let r = try o.object(name)
        func opt(_ n: String) throws -> Int64? { r.has(n) ? try r.int(n, 1...max) : nil }
        let p10 = try opt("p10")
        let p50 = try r.int("p50", 1...max)
        let p90 = try opt("p90")
        try r.finish()
        return Manifest.Rate(p10: p10, p50: p50, p90: p90)
    }

    private static func decodeRow(_ value: JValue, _ path: String, audience: Audience, state: DecodeState) throws -> Manifest.Row {
        let o = try ObjectReader(value, path, state)
        let modelId = try o.id("modelId")
        let sha = try o.string("fileSha256", SchemaRules.isSha256Hex)
        _ = try o.int("fileBytes", 0...SchemaRules.maxBytes)
        let quant = try o.string("quant", SchemaRules.isQuant)
        let backend = try o.id("backend")
        let st = try o.object("settings")
        _ = try st.int("threads", 0...1024); _ = try st.int("gpuLayers", 0...100_000)
        let ctx = try st.int("ctxTokens", 1...10_000_000)
        _ = try st.int("batchTokens", 1...1_000_000)
        try st.finish()
        let measured = try o.int("measuredAtMs", SchemaRules.epochLow...(SchemaRules.epochHigh - 1))
        if audience == .file, measured % SchemaRules.dayMs != 0 { throw SchemaViolation("\(path).measuredAtMs", "the file form truncates to the day (P8)") }
        let runs = try o.object("runs")
        let planned = try runs.int("planned", 1...1000)
        let completed = try runs.int("completed", 0...1000)
        let discarded = try runs.int("discarded", 0...1000)
        try runs.finish()

        let c = try o.object("conditions")
        let charging = try c.bool("charging")
        _ = try c.id("thermalStart")
        let ownOnly = ["batteryStartPermille", "socStartMilliC", "screenOn"]
        switch audience {
        case .own:
            _ = try c.nullableInt("batteryStartPermille", 0...1000)
            _ = try c.nullableInt("socStartMilliC", -9_007_199_254_740_991...9_007_199_254_740_991)
            _ = try c.nullableBool("screenOn")
        case .file:
            if ownOnly.contains(where: { c.has($0) }) { throw SchemaViolation(c.path, "the file form drops battery level, SoC temperature and screen state (P8)") }
        }
        try c.finish()

        var prefill: [Manifest.Prefill] = []
        for (k, item) in try o.array("prefill", count: 1...4).enumerated() {
            let p = try ObjectReader(item, "\(path).prefill[\(k)]", state)
            prefill.append(Manifest.Prefill(
                promptTokens: try p.int("promptTokens", 1...10_000_000),
                rate: try rate(p, "milliTokPerSec", max: maxRate),
                ttft: try rate(p, "ttftMicros", max: maxTtftMicros)
            ))
            try p.finish()
        }
        var decode: [Manifest.Decode] = []
        for (k, item) in try o.array("decode", count: 1...4).enumerated() {
            let d = try ObjectReader(item, "\(path).decode[\(k)]", state)
            decode.append(Manifest.Decode(
                contextTokens: try d.int("contextTokens", 0...10_000_000),
                genTokens: try d.int("genTokens", 1...1_000_000),
                rate: try rate(d, "milliTokPerSec", max: maxRate)
            ))
            try d.finish()
        }

        var sustained: Manifest.Sustained?
        if let s = try o.nullableObject("sustained") {
            var curve: [Manifest.CurvePoint] = []
            for (k, item) in try s.array("curve", count: 2...240).enumerated() {
                let p = "\(s.path).curve[\(k)]"
                guard case let .array(cells) = item, cells.count == 4 else { throw SchemaViolation(p, "not a 4-tuple") }
                let t = try ObjectReader.asInt(cells[0], 0...9_007_199_254_740_991, p)
                let r = try ObjectReader.asInt(cells[1], 1...maxRate, p)
                if !cells[2].isNull { _ = try ObjectReader.asInt(cells[2], -9_007_199_254_740_991...9_007_199_254_740_991, p) }
                if !cells[3].isNull { _ = try ObjectReader.asInt(cells[3], 0...9_007_199_254_740_991, p) }
                curve.append(Manifest.CurvePoint(tMs: t, rate: r))
            }
            sustained = Manifest.Sustained(
                durationMs: try s.int("durationMs", 1000...86_400_000),
                intervalMs: try s.int("intervalMs", 100...3_600_000),
                steady: try s.int("steadyMilliTokPerSec", 1...maxRate),
                throttleOnsetMs: try s.nullableInt("throttleOnsetMs", 0...9_007_199_254_740_991),
                curve: curve
            )
            try s.finish()
        }

        let m = try o.object("memory")
        let avail = try m.int("availableBeforeLoadBytes", 0...SchemaRules.maxBytes)
        let peak = try m.int("peakProcessBytes", 0...SchemaRules.maxBytes)
        if m.has("kvCacheBytes") { _ = try m.nullableInt("kvCacheBytes", 0...SchemaRules.maxBytes) }
        try m.finish()

        let pw = try o.object("power")
        let method = try pw.oneOf("method", powerMethods)
        let avg = try pw.nullableInt("avgMilliW", 0...9_007_199_254_740_991)
        try pw.finish()

        var seen: [String] = []
        for (k, item) in try o.array("flags", count: 0...16).enumerated() {
            let f = try ObjectReader.asString(item, SchemaRules.isID, "\(path).flags[\(k)]")
            guard !seen.contains(f) else { throw SchemaViolation("\(path).flags", "duplicate flag") }
            seen.append(f)
        }
        try o.finish()
        return Manifest.Row(
            modelId: modelId, fileSha256: sha, backend: backend, quant: quant, ctxTokens: ctx, measuredAtMs: measured,
            planned: planned, completed: completed, discarded: discarded, charging: charging, prefill: prefill, decode: decode,
            sustained: sustained, availableBeforeLoadBytes: avail, peakProcessBytes: peak, powerMethod: method, avgMilliW: avg
        )
    }
}

extension RejectCode {
    public static let schemaInvalid = RejectCode("SCHEMA_INVALID")
}
