import AsomBenchCore
import AsomJSON

/// Own body to file body (LAB_SPEC.md 4.7 `projectFile`, design 5.8, ERRATA R3-CLOSURE-1): a new subject (the
/// per-export key, `ephemeral`), no `platformIds`, no `securityPatch`, no `seq`, day-granular times, no battery
/// level, screen state, SoC temperature, OS build or GPU driver, and `results` recomputed from the file-form bench.
public enum FileProjection {
    public struct Failure: Error, Equatable { public let code: RejectCode }

    /// `ownObject` is a whole `asom.manifest/1` payload object in the own form. Returns the file `body`.
    public static func body(ownObject: JValue, exportNodeId: String, policy: ProjectionPolicy = .specLiteral) throws -> JValue {
        guard let ownBody = ownObject.member("body")?.members else { throw Failure(code: .schemaInvalid) }
        guard let bench = ownBody.first(where: { $0.name == "bench" })?.value else { throw Failure(code: .schemaInvalid) }
        let fileBenchValue = Projection.fileBench(bench)
        let decoded: BenchDocument
        do {
            decoded = try BenchDocument.decode(fileBenchValue, fileForm: true, state: DecodeState(tolerateUnknown: true))
        } catch {
            throw Failure(code: .schemaInvalid)
        }
        let results: [JValue]
        do {
            results = try Projection.project(decoded, audience: .file, policy: policy)
        } catch {
            throw Failure(code: .inconsistent)
        }

        func dropping(_ names: Set<String>, from v: JValue) -> JValue {
            guard case let .object(m) = v else { return v }
            return .object(m.filter { !names.contains($0.name) })
        }
        func replacing(_ name: String, in v: JValue, _ change: (JValue) -> JValue) -> JValue {
            guard case let .object(m) = v else { return v }
            return .object(m.map { $0.name == name ? JMember(name: name, value: change($0.value)) : $0 })
        }

        var members: [JMember] = []
        for m in ownBody {
            switch m.name {
            case "seq":
                continue
            case "audience":
                members.append(JMember(name: "audience", value: .string("file")))
            case "subject":
                members.append(JMember(name: "subject", value: .object([
                    "nodeId": .string(exportNodeId), "keyAlg": .string("ES256"), "keyStorage": .string("ephemeral"),
                ])))
            case "device":
                var device = dropping(["platformIds"], from: m.value)
                device = replacing("os", in: device) { dropping(["securityPatch"], from: $0) }
                members.append(JMember(name: "device", value: device))
            case "bench":
                members.append(JMember(name: "bench", value: fileBenchValue))
            case "results":
                members.append(JMember(name: "results", value: .array(results)))
            default:
                members.append(m)
            }
        }
        return .object(members)
    }
}

/// The public derivative (manifest.md section 12.2 as amended by design 5.9): unsigned, allow-listed, quantised to two
/// significant digits, month-granular. Never a key, node id, seq, challenge, exact timestamp, platform id, security
/// patch, build, driver, storage class, evidence, settings or temperature.
public enum PublicDerivative {
    public struct AllowList: Sendable {
        public var engineCommits: [String]
        public var harnessVersions: [String]
        public var vendors: [String]
        public var models: [String]

        public init(engineCommits: [String], harnessVersions: [String], vendors: [String], models: [String]) {
            self.engineCommits = engineCommits
            self.harnessVersions = harnessVersions
            self.vendors = vendors
            self.models = models
        }
    }

    /// Two significant digits, half up; values below 100 are unchanged.
    public static func q2(_ x: Int64) -> Int64 {
        if x < 100 { return x }
        var unit: Int64 = 10
        while x / unit >= 100 { unit *= 10 }
        return (x + unit / 2) / unit * unit
    }

    static let ramClassesGiB: [Int64] = [1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048]

    /// nil when no result survives (nothing is sent).
    public static func make(from m: Manifest, catalogue: Set<String>, allow: AllowList) throws -> JValue? {
        var rows: [JValue] = []
        for r in m.rows {
            guard catalogue.contains(r.fileSha256), let s = r.sustained, let prefill = r.prefill.first, let decode = r.decode.first else { continue }
            let picked = pickCurve(s.curve)
            let curve: [JValue] = picked.map { .array([.int(q2($0.tMs / 1000)), .int(q2($0.rate))]) }
            rows.append(.object([
                "modelId": .string(r.modelId),
                "quant": .string(r.quant),
                "fileSha256": .string(r.fileSha256),
                "backend": .string(r.backend),
                "measuredMonth": .string(month(r.measuredAtMs)),
                "runsCompleted": .int(r.completed),
                "charging": .bool(r.charging),
                "prefillPromptTokens": .int(prefill.promptTokens),
                "prefillMilliTokPerSec": .int(q2(prefill.rate.p50)),
                "ttftMillis": .int(q2(prefill.ttft.p50 / 1000)),
                "decodeContextTokens": .int(decode.contextTokens),
                "decodeMilliTokPerSec": .int(q2(decode.rate.p50)),
                "steadyMilliTokPerSec": .int(q2(s.steady)),
                "throttleOnsetSec": s.throttleOnsetMs.map { .int(q2($0 / 1000)) } ?? .null,
                "curve": .array(curve),
                "peakProcessMB": .int(q2(r.peakProcessBytes / 1_000_000)),
                "powerMethod": .string(r.powerMethod),
                "powerMilliW": r.avgMilliW.map { .int(q2($0)) } ?? .null,
            ]))
        }
        if rows.isEmpty { return nil }
        let d = m.device
        func listed(_ v: String, _ list: [String]) -> String { list.contains(where: { codeUnitsEqual($0, v) }) ? v : "custom" }
        func coarse(_ v: String, _ list: [String]) -> String { list.contains(where: { codeUnitsEqual($0, v) }) ? v : "other" }
        let gib = (d.totalBytes + 1_073_741_823) / 1_073_741_824
        let ram = ramClassesGiB.first { $0 >= gib } ?? ramClassesGiB.last!
        let major = Int64(String(d.os.version.prefix { $0 >= "0" && $0 <= "9" })) ?? 0
        return .object([
            "schema": .string("asom.bench-public/1"),
            "device": .object([
                "class": .string(d.deviceClass),
                "vendor": .string(coarse(d.vendor, allow.vendors)),
                "model": .string(coarse(d.model, allow.models)),
                "socName": .string(d.soc.name),
                "ramClassGiB": .int(ram),
                "osFamily": .string(d.os.family),
                "osMajor": .int(major),
                "cooling": .string(d.cooling),
            ]),
            "harness": .object([
                "version": .string(listed(m.producer.harness.version, allow.harnessVersions)),
                "methodologyId": .string(m.producer.harness.methodologyId),
                "confVersion": .string(listed(m.producer.harness.confVersion, allow.harnessVersions)),
            ]),
            "engine": .object([
                "name": .string(m.producer.engine.name),
                "commit": .string(listed(m.producer.engine.commit, allow.engineCommits)),
            ]),
            "results": .array(rows),
        ])
    }

    /// At most 12 evenly spaced points, the two ends included; the index nearest to j * (n - 1) / 11.
    static func pickCurve(_ curve: [Manifest.CurvePoint]) -> [Manifest.CurvePoint] {
        let n = curve.count
        let k = 12
        if n <= k { return curve }
        return (0..<k).map { j in curve[(2 * j * (n - 1) + (k - 1)) / (2 * (k - 1))] }
    }

    /// YYYY-MM (UTC) of an epoch-millisecond instant.
    static func month(_ ms: Int64) -> String {
        let (y, m, _) = civil(days: ms / 86_400_000)
        return "\(y)-\(m < 10 ? "0" : "")\(m)"
    }

    /// Days since 1970-01-01 to a proleptic Gregorian date (Howard Hinnant's algorithm).
    static func civil(days: Int64) -> (Int64, Int64, Int64) {
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        return (m <= 2 ? y + 1 : y, m, d)
    }
}
