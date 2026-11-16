import AsomBenchCore
import AsomDSSE
import AsomJSON
@testable import AsomManifest
import Foundation
import XCTest

struct LabKey {
    let signer: ES256Signer
    let spki: [UInt8]
    let nodeId: String
}

func hexToBytes(_ hex: String) -> [UInt8] {
    var out: [UInt8] = []
    var it = hex.makeIterator()
    while let hi = it.next(), let lo = it.next() { out.append(UInt8(String([hi, lo]), radix: 16)!) }
    return out
}

func labKey(_ name: String) throws -> LabKey {
    let entry = try XCTUnwrap(try labFile("keys/TEST-ONLY-keys.json").member(name))
    let scalar = hexToBytes(try XCTUnwrap(entry.member("d_hex")?.stringValue))
    let spki = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(entry.member("spki_b64")?.stringValue)))
    return LabKey(signer: try ES256Signer(rawScalar: scalar), spki: spki, nodeId: try XCTUnwrap(entry.member("nodeId")?.stringValue))
}

enum Fixture {
    static let nowMs: Int64 = 1_790_676_060_000
    static let confFloor = "0.2.0"

    /// The payload of M02-101 (MESH, key1, own audience, T1 and T2 with a heat test on T2) with `results` re-projected by
    /// this lane. The vector's own results carry the JVM lane's `thermal-drift` row flag, which the spec-literal reading
    /// of benchmark.md 13.3 does not produce (apple/ERRATA.md F-1); everything else is the vector's.
    static func basePayload() throws -> JValue {
        let original = try vectorPayload("M02-101")
        let bench = try XCTUnwrap(original.value(at: [.key("body"), .key("bench")]))
        let doc = try BenchDocument.decode(bench, fileForm: false, state: DecodeState(tolerateUnknown: false))
        return original.setting([.key("body"), .key("results")], to: .array(try Projection.project(doc, audience: .own)))
    }

    static func vectorPayload(_ id: String) throws -> JValue {
        let vector = try labVector("manifest/M02-verify-accept.json", id)
        let container = try parse(Array(try XCTUnwrap(vector.member("input")?.member("document")?.stringValue).utf8))
        let payload = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(container.member("dsse")?.member("payload")?.stringValue)))
        return try parse(payload)
    }

    static func parse(_ bytes: [UInt8]) throws -> JValue {
        switch StrictJSON.parse(bytes) {
        case let .success(v): return v
        case let .failure(c): throw MissingRepoFile(description: "not strict JSON: \(c)")
        }
    }

    static func challenge() throws -> [UInt8] {
        let text = try XCTUnwrap(try labFile("keys/TEST-ONLY-keys.json").member("challenge1_b64u")?.stringValue)
        return try XCTUnwrap(Base64Strict.decodeEither(text))
    }

    static func seal(_ payload: JValue, key: LabKey, includeSpki: Bool = false) throws -> [UInt8] {
        try DSSEEnvelope.seal(payload: try JCS.serialize(payload), signer: key.signer, includeSignerSpki: includeSpki)
    }

    static func meshContext(key: LabKey, mutate: (inout VerifyContext) -> Void = { _ in }) throws -> VerifyContext {
        var c = VerifyContext(mode: .mesh, pinnedSpki: key.spki, expectedChallenge: try challenge(), confFloor: confFloor, productionKeys: false, nowMs: nowMs)
        mutate(&c)
        return c
    }

    /// Seals `payload` under key1 and verifies it in the MESH context of M02-101, changing the context by `mutate`.
    static func verifyMesh(_ payload: JValue, mutate: (inout VerifyContext) -> Void = { _ in }) throws -> Result<Verified, VerifyFailure> {
        let key1 = try labKey("key1")
        return ManifestVerifier.verify(document: try seal(payload, key: key1), context: try meshContext(key: key1, mutate: mutate))
    }
}

/// The reject code of a verification, or "ok".
func code(_ r: Result<Verified, VerifyFailure>) -> String {
    switch r {
    case .success: return "ok"
    case let .failure(f): return f.code.rawValue
    }
}

let bodyResults: [Step] = [.key("body"), .key("results")]
let presentation: [Step] = [.key("presentation")]
