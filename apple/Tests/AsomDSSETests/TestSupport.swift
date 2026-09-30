import Foundation
import XCTest
import AsomJSON
@testable import AsomDSSE

struct MissingRepoFile: Error, CustomStringConvertible {
    let description: String
}

func b(_ s: String) -> [UInt8] { Array(s.utf8) }

func hexBytes(_ hex: String) -> [UInt8] {
    var out: [UInt8] = []
    var it = hex.makeIterator()
    while let hi = it.next(), let lo = it.next() { out.append(UInt8(String([hi, lo]), radix: 16)!) }
    return out
}

func hexString(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

func repoPath(_ relative: String, from file: StaticString = #filePath) -> String? {
    var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
    for _ in 0..<10 {
        let candidate = dir.appendingPathComponent(relative).path
        if FileManager.default.fileExists(atPath: candidate) { return candidate }
        dir = dir.deletingLastPathComponent()
    }
    return nil
}

func repoBytes(_ relative: String, file: StaticString = #filePath) throws -> [UInt8] {
    guard let path = repoPath(relative, from: file), let data = FileManager.default.contents(atPath: path) else {
        throw MissingRepoFile(description: "repository file \(relative) not found")
    }
    return [UInt8](data)
}

func repoJSON(_ relative: String, file: StaticString = #filePath) throws -> JValue {
    switch StrictJSON.parse(try repoBytes(relative, file: file)) {
    case let .success(v): return v
    case let .failure(c): throw MissingRepoFile(description: "\(relative) is not strict JSON: \(c)")
    }
}

/// The published TEST-ONLY keys (manifest-vectors/TEST-ONLY-keys.json).
struct TestKey {
    let signer: ES256Signer
    let spki: [UInt8]
    let scalar: [UInt8]
    let nodeId: String
}

func loadTestKey(_ name: String, file: StaticString = #filePath) throws -> TestKey {
    let doc = try repoJSON("docs/design/mesh/manifest-vectors/TEST-ONLY-keys.json", file: file)
    let entry = try XCTUnwrap(doc.member(name))
    let scalar = hexBytes(try XCTUnwrap(entry.member("d_hex")?.stringValue))
    let spki = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(entry.member("spki_b64")?.stringValue)))
    let signer = try ES256Signer(rawScalar: scalar)
    return TestKey(signer: signer, spki: spki, scalar: scalar, nodeId: try XCTUnwrap(entry.member("nodeId")?.stringValue))
}

/// A container built by hand so that each test can break exactly one thing.
struct ContainerBuilder {
    var version: JValue? = .int(1)
    var payload: [UInt8]
    var payloadType = DSSEEnvelope.manifestPayloadType
    var signatures: [[UInt8]] = []
    var keyid: String??
    var signerSpkiB64: String??
    var extraMembers: [JMember] = []
    var encode: ([UInt8]) -> String = Base64Strict.encode
    var payloadTextOverride: String?
    var sigTextOverride: String?
    var signaturesValueOverride: JValue?
    var dsseOverride: JValue?

    func build() throws -> [UInt8] {
        var sigObjects: [JValue] = []
        for sig in signatures {
            var members = [JMember(name: "sig", value: .string(sigTextOverride ?? encode(sig)))]
            if let keyid, let k = keyid { members.append(JMember(name: "keyid", value: .string(k))) }
            sigObjects.append(.object(members))
        }
        var top: [JMember] = []
        if let version { top.append(JMember(name: "asomCapabilityManifest", value: version)) }
        top.append(JMember(name: "dsse", value: dsseOverride ?? .object([
            JMember(name: "payload", value: .string(payloadTextOverride ?? encode(payload))),
            JMember(name: "payloadType", value: .string(payloadType)),
            JMember(name: "signatures", value: signaturesValueOverride ?? .array(sigObjects)),
        ])))
        top.append(JMember(name: "evidence", value: .array([])))
        if let signerSpkiB64, let s = signerSpkiB64 {
            top.append(JMember(name: "signer", value: .object([JMember(name: "spki", value: .string(s))])))
        }
        top.append(contentsOf: extraMembers)
        return try JCS.serialize(.object(top))
    }
}
