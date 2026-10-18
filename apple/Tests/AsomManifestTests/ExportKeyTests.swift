import AsomBenchCore
import AsomDSSE
import AsomJSON
@testable import AsomManifest
import XCTest

/// LAB_SPEC R4-L-13 (REVISION_4 builder action 14): the per-export key of a FILE document is generated inside the production entry point
/// `ManifestSigner.signPresentation`, and a caller can neither choose it nor make it the node key. The injected-key seam exists for the
/// cross-lane fixtures only. LAB, `swift test`; NOT DEVICE EVIDENCE.
final class ExportKeyTests: XCTestCase {
    private let production = ManifestSigner.SelfCheck(confFloor: Fixture.confFloor)

    private func ownBody() throws -> JValue {
        try XCTUnwrap(try Fixture.basePayload().member("body"))
    }

    private func containerParts(_ document: [UInt8]) throws -> (payload: JValue, keyid: String) {
        let container = try Fixture.parse(document)
        let payload = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(container.member("dsse")?.member("payload")?.stringValue)))
        let keyid = try XCTUnwrap(container.member("dsse")?.member("signatures")?.elements?.first?.member("keyid")?.stringValue)
        return (try Fixture.parse(payload), keyid)
    }

    private func export(node: ES256Signer? = nil, now: Int64 = Fixture.nowMs) throws -> ManifestSigner.Signed {
        try ManifestSigner.signPresentation(bodyOwn: try ownBody(), audience: .file, challenge: nil, nodeKey: node, nowMs: now, check: production)
    }

    func testEveryProductionExportIsMadeByAKeyOfItsOwnThatTheDocumentNamesConsistently() throws {
        let key1 = try labKey("key1")
        var spkis = Set<[UInt8]>(), ids = Set<String>(), fingerprints = Set<String>()
        var exercised = 0
        for i in 0..<16 {
            let signed = try export(node: i % 2 == 0 ? nil : key1.signer)
            let (payload, keyid) = try containerParts(signed.document)
            XCTAssertTrue(spkis.insert(signed.signerSpki).inserted, "export \(i) reused a key")
            XCTAssertTrue(ids.insert(signed.nodeId).inserted)
            XCTAssertTrue(fingerprints.insert(try XCTUnwrap(signed.exportFingerprint)).inserted)
            XCTAssertEqual(keyid, signed.nodeId, "the DSSE keyid is the export key")
            XCTAssertEqual(payload.member("body")?.member("subject")?.member("nodeId")?.stringValue, signed.nodeId, "the body names the export key")
            XCTAssertEqual(signed.nodeId, NodeIdentity.nodeId(spki: signed.signerSpki))
            XCTAssertEqual(signed.exportFingerprint, NodeIdentity.exportFingerprint(spki: signed.signerSpki))
            XCTAssertEqual(signed.exportFingerprintDisplay, NodeIdentity.exportFingerprintDisplay(spki: signed.signerSpki))
            let dsse = DSSEContext(mode: .file, pinnedSpki: nil, comparedFingerprint: nil, compareMethod: nil, productionKeys: true)
            guard case .success = DSSEEnvelope.verify(document: signed.document, context: dsse) else { return XCTFail("export \(i): the DSSE layer refused it") }
            exercised += 1
        }
        XCTAssertEqual(exercised, 16, "non-vacuity: sixteen production exports")
        XCTAssertEqual(spkis.count, 16)
    }

    func testNeitherTheNodeKeyNorAnyPublishedTestKeyEverSignsAProductionExport() throws {
        let published = try ["key1", "key2", "key3", "key4"].map { try labKey($0) }
        var exercised = 0
        for node in published {
            let signed = try export(node: node.signer)
            for other in published {
                XCTAssertNotEqual(signed.signerSpki, other.spki, "a production export was signed by the published key \(other.nodeId)")
                XCTAssertNotEqual(signed.nodeId, other.nodeId)
            }
            XCTAssertFalse(NodeIdentity.testOnlyNodeIds.contains(signed.nodeId))
            exercised += 1
        }
        XCTAssertEqual(exercised, 4, "non-vacuity: every published key was offered as the node key")
    }

    func testAProductionExportIsTheFileProjectionOfTheBodyAndNothingElse() throws {
        var exercised = 0
        for _ in 0..<3 {
            let signed = try export()
            let (payload, _) = try containerParts(signed.document)
            let body = try XCTUnwrap(payload.member("body"))
            let expected = try FileProjection.body(ownObject: .object([JMember(name: "body", value: try ownBody())]), exportNodeId: signed.nodeId)
            XCTAssertEqual(try JCS.serialize(body), try JCS.serialize(expected))
            XCTAssertNil(body.member("seq"))
            XCTAssertEqual(body.member("subject")?.member("keyStorage")?.stringValue, "ephemeral")
            XCTAssertEqual(payload.member("presentation")?.members?.map(\.name), ["issuedAtMs"])
            exercised += 1
        }
        XCTAssertEqual(exercised, 3)
    }

    func testAProductionExportVerifiesForAProductionVerifierUnderItsOwnFingerprintOnly() throws {
        var exercised = 0
        let a = try export(), b = try export()
        for (signed, other) in [(a, b), (b, a)] {
            var ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, productionKeys: true, nowMs: Fixture.nowMs)
            XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: ctx)), "ok", "production verifier, fingerprint not compared")
            ctx.comparedFingerprint = signed.exportFingerprint
            ctx.compareMethod = .typed
            XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: ctx)), "ok")
            if case let .success(v) = ManifestVerifier.verify(document: signed.document, context: ctx) { XCTAssertEqual(v.pin, .pinnedByFingerprint(.typed)) } else { XCTFail("verify") }
            ctx.comparedFingerprint = other.exportFingerprint
            XCTAssertEqual(code(ManifestVerifier.verify(document: signed.document, context: ctx)), "FINGERPRINT_MISMATCH", "another export's fingerprint pins nothing here")
            exercised += 1
        }
        XCTAssertEqual(exercised, 2)
    }

    func testAnOwnPresentationIsSignedByTheNodeKeyAndGeneratesNoExportKey() throws {
        let key1 = try labKey("key1")
        let challenge = try Fixture.challenge()
        let lenient = ManifestSigner.SelfCheck(confFloor: Fixture.confFloor, productionKeys: false)
        let signed = try ManifestSigner.signPresentation(bodyOwn: try ownBody(), audience: .own, challenge: challenge, nodeKey: key1.signer, nowMs: Fixture.nowMs, check: lenient)
        XCTAssertEqual(signed.signerSpki, key1.spki)
        XCTAssertEqual(signed.nodeId, key1.nodeId)
        XCTAssertNil(signed.exportFingerprint)
        XCTAssertNil(signed.exportFingerprintDisplay)
        XCTAssertEqual(try containerParts(signed.document).keyid, key1.nodeId)
    }

    func testTheProductionEntryPointHasNoWayToNameAnExportKey() throws {
        let shape: (JValue, Audience, [UInt8]?, ES256Signer?, Int64, ManifestSigner.SelfCheck, ProjectionPolicy) throws -> ManifestSigner.Signed = ManifestSigner.signPresentation
        let key3 = try labKey("key3")
        let signed = try shape(try ownBody(), .file, nil, key3.signer, Fixture.nowMs, production, .specLiteral)
        XCTAssertNotEqual(signed.signerSpki, key3.spki, "a key passed in the only key slot is the node key and never signs a file export")
    }
}
