import Foundation
import AsomDSSE
import AsomJSON

public enum Expectation: Equatable, Sendable {
    case ok
    case reject(RejectCode)
}

public enum Verdict: Equatable, Sendable {
    case ok
    case reject(RejectCode)

    /// The lines format of LAB_SPEC.md section 3.3.
    public func line(id: String) -> String {
        switch self {
        case .ok: return "\(id) ok"
        case let .reject(code): return "\(id) reject \(code.rawValue)"
        }
    }
}

/// What half I0a can say about one vector.
public enum Classification: Equatable, Sendable {
    /// The DSSE layer (verifier steps 1 to 10) settles the vector. Printed in `lines` mode.
    case decided(Verdict)
    /// Steps 1 to 10 hold and steps 11 and later (AsomManifest, half I0b) would decide. Never printed as `ok`.
    case passedDsseLayer
    /// A TOFU vector that LAB_SPEC.md section 4.9 retires.
    case retiredR3
}

extension Classification: CustomStringConvertible {
    public var description: String {
        switch self {
        case let .decided(.reject(code)): return "decided reject \(code.rawValue)"
        case .decided(.ok): return "decided ok"
        case .passedDsseLayer: return "dsse-layer-pass (steps 11+ are half I0b)"
        case .retiredR3: return "retired-r3"
        }
    }
}

public struct VectorResult: Sendable {
    public let id: String
    public let family: String
    public let expected: Expectation
    public let classification: Classification
}

public struct ConformanceError: Error, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

public enum Conformance {
    /// The r0 vector generation. The r3 set (0.2.0) is produced later by the lab and needs the half I0b verifier.
    public static let supportedConfVersion = "0.1.0"
    public static let retiredIds: Set<String> = ["M02-102", "M02-103", "M03-122"]
    public static let implementedFamilies: Set<String> = ["M02", "M03"]

    /// Reject codes that verifier steps 1 to 10 can produce.
    public static let dsseLayerCodes: Set<RejectCode> = [
        .tooLarge, .malformedJSON, .invalidUnicode, .nonIntegerNumber, .numberRange, .duplicateKey, .trailingData,
        .containerVersionUnknown, .containerInvalid, .schemaMajorUnknown, .payloadTypeUnsupported, .signatureCount,
        .encoding, .signatureEncoding, .keyNotPinned, .algUnsupported, .testOnlyKey, .fingerprintMismatch,
        .signatureInvalid, .nonCanonical,
    ]
    /// Codes that the DSSE layer produces but that step 11 can produce too (a newer major inside the payload).
    static let alsoRaisedLater: Set<RejectCode> = [.schemaMajorUnknown]

    // MARK: - Directories and files

    /// An explicit path wins, then ASOM_CONFORMANCE_DIR, then `lab/conformance` if it exists, else the r0 vectors
    /// under docs/design/mesh/manifest-vectors. The default is looked up from `startingAt` and its parents.
    public static func resolveDirectory(explicit: String?, environment: [String: String], startingAt: String) -> String? {
        if let explicit, !explicit.isEmpty { return explicit }
        if let fromEnv = environment["ASOM_CONFORMANCE_DIR"], !fromEnv.isEmpty { return fromEnv }
        return findUpwards(startingAt: startingAt, candidates: ["lab/conformance", "docs/design/mesh/manifest-vectors"])
    }

    /// The r0 directory only. What the unit tests use when ASOM_CONFORMANCE_DIR is not set.
    public static func r0Directory(startingAt: String) -> String? {
        findUpwards(startingAt: startingAt, candidates: ["docs/design/mesh/manifest-vectors"])
    }

    private static func findUpwards(startingAt: String, candidates: [String]) -> String? {
        var dir = URL(fileURLWithPath: startingAt).standardizedFileURL
        for _ in 0..<10 {
            for candidate in candidates {
                let path = dir.appendingPathComponent(candidate).path
                var isDir: ObjCBool = false
                if FileManager.default.fileExists(atPath: path, isDirectory: &isDir), isDir.boolValue { return path }
            }
            let parent = dir.deletingLastPathComponent()
            if parent.path == dir.path { break }
            dir = parent
        }
        return nil
    }

    static func readFile(_ path: String) throws -> [UInt8] {
        guard let data = FileManager.default.contents(atPath: path) else { throw ConformanceError("cannot read \(path)") }
        return [UInt8](data)
    }

    /// The single file "<family>-*.json" in `dir` or `dir/manifest`, or nil when the family has no file.
    public static func vectorFilePath(family: String, in dir: String) throws -> String? {
        var found: [String] = []
        for base in [dir, dir + "/manifest"] {
            let names = (try? FileManager.default.contentsOfDirectory(atPath: base)) ?? []
            for name in names.sorted() where name.hasPrefix(family + "-") && name.hasSuffix(".json") {
                found.append(base + "/" + name)
            }
        }
        if found.count > 1 { throw ConformanceError("family \(family): more than one vector file in \(dir): \(found)") }
        return found.first
    }

    static func loadJSON(_ path: String) throws -> JValue {
        switch StrictJSON.parse(try readFile(path)) {
        case let .success(value): return value
        case let .failure(code): throw ConformanceError("\(path): not strict JSON (\(code.rawValue))")
        }
    }

    static func keyOneSpki(in dir: String) throws -> [UInt8] {
        for path in [dir + "/TEST-ONLY-keys.json", dir + "/keys/TEST-ONLY-keys.json"]
        where FileManager.default.fileExists(atPath: path) {
            let doc = try loadJSON(path)
            guard let text = doc.member("key1")?.member("spki_b64")?.stringValue,
                  let bytes = Base64Strict.decodeEither(text) else { throw ConformanceError("\(path): key1.spki_b64 unreadable") }
            return bytes
        }
        throw ConformanceError("no TEST-ONLY-keys.json under \(dir)")
    }

    // MARK: - Evaluation

    /// Runs the DSSE layer over every vector of a family. Only M02 and M03 are implemented in half I0a.
    public static func evaluate(family: String, in dir: String) throws -> [VectorResult]? {
        guard implementedFamilies.contains(family), let path = try vectorFilePath(family: family, in: dir) else { return nil }
        let file = try loadJSON(path)
        guard file.member("family")?.stringValue == family else { throw ConformanceError("\(path): family field is not \(family)") }
        let version = file.member("confVersion")?.stringValue ?? "(absent)"
        guard version == supportedConfVersion else {
            throw ConformanceError("\(path): confVersion \(version) is not supported by half I0a (\(supportedConfVersion) only)")
        }
        guard let vectors = file.member("vectors")?.elements else { throw ConformanceError("\(path): no vectors array") }
        var results: [VectorResult] = []
        for vector in vectors {
            guard let id = vector.member("id")?.stringValue else { throw ConformanceError("\(path): vector without id") }
            results.append(try evaluate(vector: vector, id: id, family: family))
        }
        return results.sorted { $0.id < $1.id }
    }

    static func expectation(of vector: JValue, id: String) throws -> Expectation {
        guard let expect = vector.member("expect") else { throw ConformanceError("\(id): no expect") }
        if let code = expect.member("reject")?.stringValue { return .reject(RejectCode(code)) }
        if expect.member("ok") != nil { return .ok }
        throw ConformanceError("\(id): expect is neither ok nor reject")
    }

    static func evaluate(vector: JValue, id: String, family: String) throws -> VectorResult {
        let expected = try expectation(of: vector, id: id)
        guard let input = vector.member("input"), let text = input.member("document")?.stringValue,
              let context = input.member("context"), let mode = context.member("mode")?.stringValue else {
            throw ConformanceError("\(id): input.document or input.context.mode missing")
        }
        func result(_ c: Classification) -> VectorResult {
            VectorResult(id: id, family: family, expected: expected, classification: c)
        }
        if retiredIds.contains(id) { return result(.retiredR3) }
        let dsseContext: DSSEContext
        switch (mode, id) {
        case ("pinned", _):
            guard let b64 = context.member("pinnedSpki")?.stringValue, let spki = Base64Strict.decodeEither(b64) else {
                throw ConformanceError("\(id): pinnedSpki unreadable")
            }
            dsseContext = DSSEContext(mode: .mesh, pinnedSpki: spki)
        case ("tofu", "M03-127"):
            dsseContext = DSSEContext(mode: .file)
        default:
            throw ConformanceError("\(id): context mode \(mode) has no r3 mapping")
        }
        switch DSSEEnvelope.verify(document: Array(text.utf8), context: dsseContext) {
        case let .failure(code): return result(.decided(.reject(code)))
        case .success: return result(.passedDsseLayer)
        }
    }

    /// nil when the result agrees with the vector's own expectation, else a description of the disagreement.
    public static func disagreement(_ r: VectorResult) -> String? {
        switch (r.classification, r.expected) {
        case (.retiredR3, _):
            return nil
        case let (.decided(.reject(got)), .reject(want)):
            return got == want ? nil : "expected reject \(want), DSSE layer rejected \(got)"
        case let (.decided(v), _):
            return "expected \(r.expected), DSSE layer decided \(v)"
        case (.passedDsseLayer, .ok):
            return nil
        case let (.passedDsseLayer, .reject(want)):
            if dsseLayerCodes.contains(want) && !alsoRaisedLater.contains(want) {
                return "expected reject \(want), which steps 1 to 10 own, but the DSSE layer passed"
            }
            return nil
        }
    }

    /// The `lines` output: one `<id> ok|reject <CODE>` per vector the DSSE layer settles, sorted by id.
    public static func lines(families: [String], in dir: String, log: (String) -> Void) throws -> [String] {
        var out: [(String, String)] = []
        for family in families {
            guard let results = try evaluate(family: family, in: dir) else {
                log("\(family): not-implemented in half I0a")
                continue
            }
            var undecided = 0, retired = 0
            for r in results {
                switch r.classification {
                case let .decided(v): out.append((r.id, v.line(id: r.id)))
                case .passedDsseLayer: undecided += 1
                case .retiredR3: retired += 1
                }
            }
            log("\(family): \(results.count) vectors, \(out.filter { $0.0.hasPrefix(family + "-") }.count) printed, "
                + "\(undecided) need steps 11+ (half I0b, not printed), \(retired) retired by r3")
        }
        return out.sorted { $0.0 < $1.0 }.map { $0.1 }
    }

    /// The signature layer under TEST-ONLY key 1, in the format of manifest-vectors/crosscheck.out (the JCA column).
    /// A document the strict parser refuses is reported as `<id> skipped (<CODE>)`.
    public static func signatureLayerLines(in dir: String) throws -> [String] {
        let spki = try keyOneSpki(in: dir)
        var out: [String] = []
        for family in ["M02", "M03"] {
            guard let path = try vectorFilePath(family: family, in: dir) else { continue }
            let file = try loadJSON(path)
            guard file.member("confVersion")?.stringValue == supportedConfVersion else {
                throw ConformanceError("\(path): confVersion not supported by half I0a")
            }
            for vector in file.member("vectors")?.elements ?? [] {
                guard let id = vector.member("id")?.stringValue,
                      let text = vector.member("input")?.member("document")?.stringValue else {
                    throw ConformanceError("\(path): vector without id or document")
                }
                switch DSSEEnvelope.signatureLayer(document: Array(text.utf8), spki: spki) {
                case .valid: out.append("\(id) sigValidUnderKey1=true")
                case .invalid: out.append("\(id) sigValidUnderKey1=false")
                case let .rejected(code): out.append("\(id) skipped (\(code.rawValue))")
                }
            }
        }
        return out.sorted()
    }
}
