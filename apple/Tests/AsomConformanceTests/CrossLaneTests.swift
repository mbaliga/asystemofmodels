import AsomBenchCore
import AsomDSSE
import AsomJSON
import Foundation
import XCTest
@testable import AsomConformanceKit

/// The cross-lane fixtures (apple/crosslane: documents signed by this lane's signer, verified by the JVM lane through
/// apple/ci/crosslane.py) and the other direction (documents signed by the JVM lane, verified here). LAB, oracle self.
final class CrossLaneTests: XCTestCase {
    private func labDirectory(file: StaticString = #filePath) throws -> String {
        let env = ProcessInfo.processInfo.environment["ASOM_CONFORMANCE_DIR"] ?? ""
        if !env.isEmpty, R3.isR3Directory(env), FileManager.default.fileExists(atPath: env + "/keys/TEST-ONLY-keys.json") { return env }
        var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
        for _ in 0..<10 {
            let candidate = dir.appendingPathComponent("lab/conformance").path
            if R3.isR3Directory(candidate) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("lab/conformance (confVersion 0.2.0) not found")
    }

    private func fixtureDirectory(file: StaticString = #filePath) throws -> String {
        var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
        for _ in 0..<10 {
            let candidate = dir.appendingPathComponent("crosslane").path
            if R3.isR3Directory(candidate) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("apple/crosslane not found")
    }

    private func payload(of document: JValue?) -> [UInt8]? {
        guard let text = document?.stringValue, let c = try? Conformance.parseValue(Array(text.utf8)),
              let p = c.member("dsse")?.member("payload")?.stringValue else { return nil }
        return Base64Strict.decodeEither(p)
    }

    func testTheCommittedFixturesVerifyHereAndCarryBothOutcomes() throws {
        let dir = try fixtureDirectory()
        let rows = try R3.run(families: ["M02", "M03"], in: dir)
        XCTAssertEqual(rows.count, 37)
        XCTAssertEqual(R3.mismatches(rows).map { "\($0.id): \($0.reason)" }, [])
        var ok = 0, rejects: [String: Int] = [:]
        for r in rows {
            switch r.observed {
            case .ok: ok += 1
            case let .reject(code): rejects[code.rawValue, default: 0] += 1
            case .notImplemented: XCTFail("\(r.vector.id) not run")
            }
        }
        XCTAssertEqual(ok, 23)
        XCTAssertEqual(rejects.values.reduce(0, +), 14)
        XCTAssertGreaterThanOrEqual(rejects.count, 12, "non-vacuity: the reject vectors cover many distinct codes")
        for code in ["KEY_NOT_PINNED", "EXPIRED", "NONCE_MISMATCH", "TEST_ONLY_KEY", "FINGERPRINT_MISMATCH", "SIGNATURE_INVALID", "NOT_YET_VALID", "TIER_INSUFFICIENT",
                     "NON_CANONICAL", "SUBJECT_KEY_MISMATCH", "ROLLBACK", "EQUIVOCATION"] {
            XCTAssertGreaterThan(rejects[code] ?? 0, 0, "no fixture exercises \(code)")
        }
        for r in rows where r.vector.id.hasPrefix("M02-91") || r.vector.id == "M02-901" { if case .ok = r.observed {} else { XCTFail(r.vector.id) } }
        XCTAssertTrue(R3.lines(rows).allSatisfy { !$0.contains("HARNESS_ERROR") })
    }

    func testEveryCommittedSignatureIsLowSExceptTheDeliberateTwinAndTheTamperedOne() throws {
        let rows = try R3.run(families: ["M02", "M03"], in: try fixtureDirectory())
        var lowS = 0, highS = 0
        for r in rows {
            guard let text = r.vector.input.member("document")?.stringValue, let c = try? Conformance.parseValue(Array(text.utf8)),
                  let sigText = c.member("dsse")?.member("signatures")?.elements?.first?.member("sig")?.stringValue,
                  let sig = Base64Strict.decodeEither(sigText) else { XCTFail(r.vector.id); continue }
            XCTAssertEqual(sig.count, 64, r.vector.id)
            if SignatureCodec.isLowS(sig) { lowS += 1 } else { highS += 1; XCTAssertEqual(r.vector.id, "M02-902", "only the high-S twin may be high-S") }
        }
        XCTAssertEqual(highS, 1)
        XCTAssertEqual(lowS, 36)
    }

    func testRegeneratingGivesTheSamePayloadsAndFreshSignatures() throws {
        let lab = try labDirectory()
        let committed = try fixtureDirectory()
        let scratch = NSTemporaryDirectory() + "asom-crosslane-" + UUID().uuidString
        defer { try? FileManager.default.removeItem(atPath: scratch) }
        let summary = try CrossLane.generate(labDir: lab, into: scratch)
        XCTAssertEqual(summary.m02, 23)
        XCTAssertEqual(summary.m03, 14)
        var compared = 0, signaturesDiffer = 0
        for family in ["M02", "M03"] {
            let old = try R3.loadVectors(family: family, in: committed), new = try R3.loadVectors(family: family, in: scratch)
            XCTAssertEqual(old.map(\.id), new.map(\.id))
            for (a, b) in zip(old, new) {
                XCTAssertEqual(payload(of: a.input.member("document")), payload(of: b.input.member("document")), "\(a.id): the payload is deterministic")
                XCTAssertEqual(try JCS.serialize(a.input.member("context") ?? .null), try JCS.serialize(b.input.member("context") ?? .null), a.id)
                if a.input.member("document") != b.input.member("document") { signaturesDiffer += 1 }
                compared += 1
            }
        }
        XCTAssertEqual(compared, 37)
        XCTAssertGreaterThan(signaturesDiffer, 30, "non-vacuity: ES256 here is random-nonce, so a regeneration changes the signature bytes")
        XCTAssertTrue(R3.mismatches(try R3.run(families: ["M02", "M03"], in: scratch)).isEmpty)
    }

    func testJvmSignedDocumentsPassTheDsseLayerHere() throws {
        // The lab's M02 and M03 documents were signed by the JVM lane. Every one whose verdict is `ok`, or a reject at a step after
        // the signature (the JVM lane got past step 10), must pass steps 1 to 10 in this lane's verifier.
        let lab = try labDirectory()
        let late: Set<String> = ["SUBJECT_KEY_MISMATCH", "NOT_YET_VALID", "EXPIRED", "TTL_INVALID", "NONCE_MISMATCH", "INCONSISTENT", "DERIVATION_MISMATCH", "AUDIENCE_MISMATCH",
                                 "ROLLBACK", "EQUIVOCATION", "TIER_INSUFFICIENT", "SCHEMA_INVALID"]
        var checked = 0, accepted = 0, lateRejects = 0, ecdsaSignaturesChecked = 0
        for family in ["M02", "M03"] {
            for v in try R3.loadVectors(family: family, in: lab) {
                let jvmReachedStep11: Bool
                switch v.expect {
                case .ok: jvmReachedStep11 = true; accepted += 1
                case let .reject(code): jvmReachedStep11 = late.contains(code); if jvmReachedStep11 { lateRejects += 1 }
                }
                guard jvmReachedStep11 else { continue }
                let ctx = try R3.verifyContext(v.input, .specLiteral)
                let dsseContext = DSSEContext(
                    mode: ctx.mode, pinnedSpki: ctx.pinnedSpki, comparedFingerprint: ctx.comparedFingerprint, compareMethod: ctx.compareMethod,
                    productionKeys: ctx.productionKeys
                )
                switch DSSEEnvelope.verify(document: try R3.documentBytes(v.input), context: dsseContext) {
                case .success: ecdsaSignaturesChecked += 1
                case let .failure(code): XCTFail("\(v.id): the JVM lane got past the signature, this lane says \(code.rawValue)")
                }
                checked += 1
            }
        }
        XCTAssertGreaterThan(accepted, 15)
        XCTAssertGreaterThan(lateRejects, 10)
        XCTAssertEqual(checked, ecdsaSignaturesChecked)
        XCTAssertGreaterThan(checked, 30, "non-vacuity: JVM-signed documents whose signature this lane verified")
    }
}
