import AsomBenchCore
import AsomDSSE
import AsomJSON
@testable import AsomManifest
import XCTest

/// `ManifestSigner.signPresentation` (LAB_SPEC.md 4.7). The private scalars come from lab/conformance/keys/TEST-ONLY-keys.json at test
/// time and are never copied into a source file. ES256 signatures are random-nonce here, so no test compares signature bytes.
final class SignerTests: XCTestCase {
    private let check = ManifestSigner.SelfCheck(confFloor: Fixture.confFloor, productionKeys: false)

    private func ownBody() throws -> JValue {
        try XCTUnwrap(try Fixture.basePayload().member("body"))
    }

    private func parts(_ document: [UInt8]) throws -> (object: JValue, payload: [UInt8], sig: [UInt8], keyid: String) {
        let container = try Fixture.parse(document)
        let payload = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(container.member("dsse")?.member("payload")?.stringValue)))
        let sigEntry = try XCTUnwrap(container.member("dsse")?.member("signatures")?.elements?.first)
        let sig = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(sigEntry.member("sig")?.stringValue)))
        return (try Fixture.parse(payload), payload, sig, try XCTUnwrap(sigEntry.member("keyid")?.stringValue))
    }

    // MARK: own

    func testOwnPresentationIsExactlyTheSpecShapeAndVerifies() throws {
        let key1 = try labKey("key1")
        let challenge = try Fixture.challenge()
        let signed = try ManifestSigner.signPresentation(
            bodyOwn: try ownBody(), audience: .own, challenge: challenge, nodeKey: key1.signer, nowMs: Fixture.nowMs, check: check
        )
        let p = try parts(signed.document)
        XCTAssertEqual(p.keyid, key1.nodeId)
        XCTAssertEqual(signed.nodeId, key1.nodeId)
        XCTAssertEqual(signed.signerSpki, key1.spki)
        XCTAssertNil(signed.exportFingerprint)
        XCTAssertEqual(p.object.members?.map(\.name).sorted(), ["body", "presentation", "schema", "schemaMinor"])
        XCTAssertEqual(p.object.member("schema")?.stringValue, "asom.manifest/1")
        XCTAssertEqual(p.object.member("schemaMinor")?.intValue, 0)
        let pres = try XCTUnwrap(p.object.member("presentation"))
        XCTAssertEqual(pres.members?.map(\.name).sorted(), ["challenge", "expiresAtMs", "issuedAtMs"])
        XCTAssertEqual(pres.member("issuedAtMs")?.intValue, Fixture.nowMs)
        XCTAssertEqual(pres.member("expiresAtMs")?.intValue, Fixture.nowMs + 600_000)
        XCTAssertEqual(pres.member("challenge")?.stringValue, Base64Strict.encodeURL(challenge))
        XCTAssertEqual(p.payload, try JCS.serialize(p.object), "the payload is in JCS form")
        XCTAssertEqual(signed.payload, p.payload)
        XCTAssertEqual(try JCS.serialize(try Fixture.parse(signed.document)), signed.document, "the container is in JCS form")
        XCTAssertEqual(p.sig.count, 64)
        XCTAssertTrue(SignatureCodec.isLowS(p.sig))
        XCTAssertEqual(try Fixture.parse(signed.document).member("signer")?.member("spki")?.stringValue, Base64Strict.encode(key1.spki))
        XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: try Fixture.meshContext(key: key1))), "ok")
    }

    func testEverySignatureIsLowSVerifiesAndTheNonceIsFresh() throws {
        let key1 = try labKey("key1")
        let body = try ownBody()
        let challenge = try Fixture.challenge()
        var distinct = Set<[UInt8]>()
        var lowS = 0
        for _ in 0..<64 {
            let signed = try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: challenge, nodeKey: key1.signer, nowMs: Fixture.nowMs, check: check)
            let p = try parts(signed.document)
            distinct.insert(p.sig)
            if SignatureCodec.isLowS(p.sig) { lowS += 1 }
            XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: try Fixture.meshContext(key: key1))), "ok")
        }
        XCTAssertEqual(lowS, 64, "C2: producers normalise to low-S; a raw library signature is high-S about half the time")
        XCTAssertGreaterThan(distinct.count, 60, "non-vacuity: nonces differ, so the signatures are not a replay")
    }

    func testAnOwnPresentationNeedsA32ByteChallengeAndANodeKey() throws {
        let key1 = try labKey("key1")
        let body = try ownBody()
        var refused = 0
        for challenge in [nil, [UInt8](), [UInt8](repeating: 1, count: 31), [UInt8](repeating: 1, count: 33)] as [[UInt8]?] {
            XCTAssertThrowsError(try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: challenge, nodeKey: key1.signer, nowMs: Fixture.nowMs, check: check)) {
                XCTAssertEqual($0 as? ManifestSigner.Failure, .challengeRequired)
            }
            refused += 1
        }
        XCTAssertThrowsError(try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: try Fixture.challenge(), nodeKey: nil, nowMs: Fixture.nowMs, check: check)) {
            XCTAssertEqual($0 as? ManifestSigner.Failure, .challengeRequired)
        }
        refused += 1
        XCTAssertEqual(refused, 5)
    }

    func testTheSelfCheckRefusesWhatTheVerifierWouldRefuse() throws {
        let key1 = try labKey("key1"), key2 = try labKey("key2")
        let challenge = try Fixture.challenge()
        let body = try ownBody()
        var seen = Set<String>()
        func refusal(_ body: JValue, key: LabKey = key1, selfCheck: ManifestSigner.SelfCheck? = nil, now: Int64 = Fixture.nowMs) -> String {
            do {
                _ = try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: challenge, nodeKey: key.signer, nowMs: now, check: selfCheck ?? check)
                return "signed"
            } catch let ManifestSigner.Failure.manifestUnavailable(code) {
                seen.insert(code.rawValue)
                return code.rawValue
            } catch {
                return "\(error)"
            }
        }
        XCTAssertEqual(refusal(body, key: key2), "SUBJECT_KEY_MISMATCH", "the body names key1, key2 signs")
        XCTAssertEqual(refusal(body, selfCheck: ManifestSigner.SelfCheck(confFloor: Fixture.confFloor)), "TEST_ONLY_KEY", "production keys is the default")
        XCTAssertEqual(refusal(body, selfCheck: ManifestSigner.SelfCheck(confFloor: "0.9.0", productionKeys: false)), "DERIVATION_MISMATCH", "confFloor above the document's")
        XCTAssertEqual(refusal(body, selfCheck: ManifestSigner.SelfCheck(confFloor: Fixture.confFloor, knownBadConf: ["0.2.0"], productionKeys: false)), "DERIVATION_MISMATCH")
        XCTAssertEqual(refusal(body, selfCheck: ManifestSigner.SelfCheck(confFloor: Fixture.confFloor, productionKeys: false, requiredTier: .a2)), "TIER_INSUFFICIENT")
        let edited = body.setting([.key("results"), .at(0), .key("settings"), .key("threads")], to: .int(99))
        XCTAssertEqual(refusal(edited), "DERIVATION_MISMATCH", "results that do not follow from the bench document")
        XCTAssertEqual(refusal(body.setting([.key("seq")], to: .int(-1))), "SCHEMA_INVALID")
        XCTAssertGreaterThanOrEqual(seen.count, 5, "non-vacuity: the self-check refused with several distinct codes")
    }

    func testClockAndOverflow() throws {
        let key1 = try labKey("key1")
        let body = try ownBody()
        XCTAssertThrowsError(try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: try Fixture.challenge(), nodeKey: key1.signer, nowMs: -1, check: check)) {
            XCTAssertEqual($0 as? ManifestSigner.Failure, .clockInvalid)
        }
        XCTAssertThrowsError(try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: try Fixture.challenge(), nodeKey: key1.signer, nowMs: Int64.max - 10, check: check)) {
            XCTAssertEqual($0 as? ManifestSigner.Failure, .clockInvalid)
        }
    }

    // MARK: file

    func testFilePresentationUsesOnlyTheExportKeyAndTheDayTruncatedClock() throws {
        let key1 = try labKey("key1"), key3 = try labKey("key3")
        let now: Int64 = 1_790_676_060_123
        let signed = try ManifestSigner.signPresentation(
            bodyOwn: try ownBody(), audience: .file, challenge: nil, nodeKey: key1.signer, exportKey: key3.signer, nowMs: now, check: check
        )
        let p = try parts(signed.document)
        XCTAssertEqual(p.keyid, key3.nodeId, "never the node key")
        XCTAssertEqual(signed.signerSpki, key3.spki)
        let labDisplay = try XCTUnwrap(try labFile("keys/TEST-ONLY-keys.json").member("key3")?.member("exportFingerprint")?.stringValue)
        XCTAssertEqual(signed.exportFingerprintDisplay, labDisplay, "the lab records the grouped form")
        XCTAssertEqual(try XCTUnwrap(signed.exportFingerprint), labDisplay.replacingOccurrences(of: "-", with: ""))
        XCTAssertEqual(p.object.member("presentation")?.members?.map(\.name), ["issuedAtMs"])
        XCTAssertEqual(p.object.member("presentation")?.member("issuedAtMs")?.intValue, 1_790_640_000_000)
        XCTAssertEqual((p.object.member("presentation")?.member("issuedAtMs")?.intValue ?? 1) % 86_400_000, 0)
        let body = try XCTUnwrap(p.object.member("body"))
        XCTAssertNil(body.member("seq"))
        XCTAssertEqual(body.member("audience")?.stringValue, "file")
        XCTAssertEqual(body.member("subject")?.member("nodeId")?.stringValue, key3.nodeId)
        XCTAssertEqual(body.member("subject")?.member("keyStorage")?.stringValue, "ephemeral")
        XCTAssertNil(body.value(at: [.key("device"), .key("platformIds")]))
        XCTAssertNil(body.value(at: [.key("device"), .key("os"), .key("securityPatch")]))
        let expectedBody = try FileProjection.body(ownObject: .object([JMember(name: "body", value: try ownBody())]), exportNodeId: key3.nodeId)
        XCTAssertEqual(try JCS.serialize(body), try JCS.serialize(expectedBody), "the signed body is the projection, nothing else")
        XCTAssertTrue(SignatureCodec.isLowS(p.sig))
        var ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, nowMs: now)
        XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: ctx)), "ok")
        ctx.comparedFingerprint = signed.exportFingerprint
        ctx.compareMethod = .typed
        XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: ctx)), "ok")
        ctx.comparedFingerprint = NodeIdentity.exportFingerprint(spki: try labKey("key4").spki)
        XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: ctx)), "FINGERPRINT_MISMATCH")
    }

    func testFileExportWithoutAnExportKeyIsRefusedAndAFreshKeyIsNeverTheNodeKey() throws {
        let key1 = try labKey("key1")
        XCTAssertThrowsError(try ManifestSigner.signPresentation(bodyOwn: try ownBody(), audience: .file, challenge: nil, nodeKey: key1.signer, nowMs: Fixture.nowMs, check: check)) {
            XCTAssertEqual($0 as? ManifestSigner.Failure, .missingExportKey)
        }
        // A fresh key is a different key every time; with the production default the self-check accepts it (it is not a TEST-ONLY key).
        let production = ManifestSigner.SelfCheck(confFloor: Fixture.confFloor)
        let a = try ManifestSigner.signPresentation(bodyOwn: try ownBody(), audience: .file, challenge: nil, nodeKey: nil, exportKey: ES256Signer.generateEphemeral(), nowMs: Fixture.nowMs, check: production)
        let b = try ManifestSigner.signPresentation(bodyOwn: try ownBody(), audience: .file, challenge: nil, nodeKey: nil, exportKey: ES256Signer.generateEphemeral(), nowMs: Fixture.nowMs, check: production)
        XCTAssertNotEqual(a.nodeId, b.nodeId)
        XCTAssertNotEqual(a.nodeId, key1.nodeId)
        XCTAssertEqual(a.exportFingerprint?.count, 26)
        XCTAssertFalse(NodeIdentity.testOnlyNodeIds.contains(a.nodeId))
    }

    func testAFileExportUnderATestOnlyKeyIsRefusedByAProductionSigner() throws {
        let key3 = try labKey("key3")
        XCTAssertThrowsError(try ManifestSigner.signPresentation(
            bodyOwn: try ownBody(), audience: .file, challenge: nil, nodeKey: nil, exportKey: key3.signer, nowMs: Fixture.nowMs,
            check: ManifestSigner.SelfCheck(confFloor: Fixture.confFloor)
        )) { XCTAssertEqual($0 as? ManifestSigner.Failure, .manifestUnavailable(.testOnlyKey)) }
    }

    func testABodyTheFileProjectionRefusesIsTyped() throws {
        let key3 = try labKey("key3")
        let noBench = try ownBody().removing([.key("bench")])
        XCTAssertThrowsError(try ManifestSigner.signPresentation(bodyOwn: noBench, audience: .file, challenge: nil, nodeKey: nil, exportKey: key3.signer, nowMs: Fixture.nowMs, check: check)) {
            XCTAssertEqual($0 as? ManifestSigner.Failure, .projection(.schemaInvalid))
        }
    }

    // MARK: seq

    func testSeqRule() throws {
        var checked = 0
        for (stored, now, want) in [(nil, 1_790_676_060_000, 1_790_676_060), (Int64?(5), 1_790_676_060_000, 1_790_676_060), (Int64?(1_790_676_060), 1_790_676_060_999, 1_790_676_061),
                                    (Int64?(1_790_676_100), 1_790_676_060_000, 1_790_676_101), (Int64?(0), 0, 1), (nil, 0, 0)] as [(Int64?, Int64, Int64)] {
            XCTAssertEqual(try ManifestSigner.nextSeq(stored: stored, nowMs: now), want, "stored \(String(describing: stored)) now \(now)")
            checked += 1
        }
        XCTAssertEqual(checked, 6)
        XCTAssertThrowsError(try ManifestSigner.nextSeq(stored: Int64.max, nowMs: 0))
    }
}
