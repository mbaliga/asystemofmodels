import AsomBenchCore
import AsomDSSE
import AsomJSON
@testable import AsomManifest
import XCTest

/// Review fixes CV-1 to CV-5 and CV-8 (apple/ERRATA.md ERR-FX-CV*).
final class FixCryptoManifestTests: XCTestCase {
    private func filePayload(minor: Int64, presentation extra: [JMember] = []) throws -> JValue {
        let key1 = try labKey("key1")
        let body = try FileProjection.body(ownObject: try Fixture.basePayload(), exportNodeId: key1.nodeId)
        let pres: JValue = .object([JMember(name: "issuedAtMs", value: .int(1_790_640_000_000))] + extra)
        return .object(["schema": .string("asom.manifest/1"), "schemaMinor": .int(minor), "body": body, "presentation": pres])
    }

    private func verifyFile(_ payload: JValue) throws -> Result<Verified, VerifyFailure> {
        let key1 = try labKey("key1")
        let doc = try Fixture.seal(payload, key: key1, includeSpki: true)
        return ManifestVerifier.verify(document: doc, context: VerifyContext(mode: .file, confFloor: Fixture.confFloor, productionKeys: false, nowMs: Fixture.nowMs))
    }

    func testAFilePresentationIsExactlyIssuedAtMsWhateverTheMinor() throws {
        XCTAssertEqual(code(try verifyFile(try filePayload(minor: 0))), "ok")
        XCTAssertEqual(code(try verifyFile(try filePayload(minor: 1))), "ok", "M02-121: minor 1 with no extra member")
        let extra = [JMember(name: "exportedAtMs", value: .int(1_790_676_061_234))]
        XCTAssertEqual(code(try verifyFile(try filePayload(minor: 1, presentation: extra))), "SCHEMA_INVALID", "M03-193: an exact export time must not ride along")
        XCTAssertEqual(code(try verifyFile(try filePayload(minor: 0, presentation: extra))), "SCHEMA_INVALID")
        // an own presentation still tolerates an unknown member above the known minor
        let own = try Fixture.basePayload().setting([.key("schemaMinor")], to: .int(1)).setting(presentation + [.key("note")], to: .int(1))
        guard case let .success(ok) = try Fixture.verifyMesh(own) else { return XCTFail("an own presentation at minor 1 with an unknown member was refused") }
        XCTAssertEqual(ok.unknownFields, 1)
    }

    func testDefaultContextsRefuseTheTestOnlyKeys() throws {
        let key1 = try labKey("key1")
        XCTAssertTrue(VerifyContext(mode: .mesh, confFloor: Fixture.confFloor, nowMs: Fixture.nowMs).productionKeys)
        let ctx = VerifyContext(mode: .mesh, pinnedSpki: key1.spki, expectedChallenge: try Fixture.challenge(), confFloor: Fixture.confFloor, nowMs: Fixture.nowMs)
        let doc = try Fixture.seal(try Fixture.basePayload(), key: key1)
        guard case let .failure(f) = ManifestVerifier.verify(document: doc, context: ctx) else { return XCTFail("a default context accepted a TEST-ONLY key") }
        XCTAssertEqual(f.code.rawValue, "TEST_ONLY_KEY")
    }

    func testTheChallengeComparisonDoesNotFoldTheLengths() {
        let want = [UInt8](repeating: 7, count: 32)
        XCTAssertTrue(ManifestVerifier.constantTimeEqual(want, want))
        for extra in [1, 224, 256, 512] {
            XCTAssertFalse(ManifestVerifier.constantTimeEqual(want, want + [UInt8](repeating: 0, count: extra)), "+\(extra)")
            XCTAssertFalse(ManifestVerifier.constantTimeEqual(want + [UInt8](repeating: 0, count: extra), want), "+\(extra) first")
        }
    }

    func testEveryDuplicatedFactMustAgreeWithBench() throws {
        let cases: [(String, [Step], JValue)] = [
            ("producer confVersion", [.key("body"), .key("producer"), .key("harness"), .key("confVersion")], .string("0.0.1")),
            ("producer engine commit", [.key("body"), .key("producer"), .key("engine"), .key("commit")], .string("ffffffff")),
            ("producer engine commit, 40 hex", [.key("body"), .key("producer"), .key("engine"), .key("commit")], .string(String(repeating: "0", count: 40))),
            ("producer engine name", [.key("body"), .key("producer"), .key("engine"), .key("name")], .string("other-engine")),
            ("producer engine buildFlags", [.key("body"), .key("producer"), .key("engine"), .key("buildFlags")], .array([.string("X=1")])),
            ("device memory total", [.key("body"), .key("device"), .key("memory"), .key("totalBytes")], .int(64_000_000_000)),
            ("device os family", [.key("body"), .key("device"), .key("os"), .key("family")], .string("ios")),
            ("device os version", [.key("body"), .key("device"), .key("os"), .key("version")], .string("99")),
            ("device vendor", [.key("body"), .key("device"), .key("vendor")], .string("Other")),
            ("device model", [.key("body"), .key("device"), .key("model")], .string("Other Phone")),
            ("device soc name", [.key("body"), .key("device"), .key("soc"), .key("name")], .string("Other SoC")),
        ]
        var exercised = 0
        for (name, path, value) in cases {
            XCTAssertEqual(code(try Fixture.verifyMesh(try Fixture.basePayload().setting(path, to: value))), "INCONSISTENT", name)
            exercised += 1
        }
        XCTAssertEqual(exercised, cases.count)
        // an abbreviation of the bench commit is the same commit; the class is an open enum and is not tied to the closed bench form
        let full = try XCTUnwrap(try Fixture.basePayload().value(at: [.key("body"), .key("bench"), .key("harness"), .key("engine"), .key("commit")])?.stringValue)
        XCTAssertEqual(code(try Fixture.verifyMesh(try Fixture.basePayload().setting([.key("body"), .key("producer"), .key("engine"), .key("commit")], to: .string(String(full.prefix(7)))))), "ok")
        XCTAssertEqual(code(try Fixture.verifyMesh(try Fixture.basePayload().setting([.key("body"), .key("device"), .key("class")], to: .string("toaster")))), "ok", "M02-122")
    }

    func testEvidenceItemsOverTheItemLimitAreContainerInvalid() throws {
        let key1 = try labKey("key1")
        let doc = try DSSEEnvelope.seal(payload: try JCS.serialize(try Fixture.basePayload()), signer: key1.signer)
        guard case let .object(m) = try Fixture.parse(doc) else { return XCTFail("the container is not an object") }
        let members = m.filter { $0.name != "evidence" }
        func verify(_ item: JValue) throws -> String {
            let withEvidence = try JCS.serialize(.object(members + [JMember(name: "evidence", value: .array([item]))]))
            return code(ManifestVerifier.verify(document: withEvidence, context: try Fixture.meshContext(key: key1)))
        }
        XCTAssertEqual(try verify(.object([JMember(name: "x", value: .string(String(repeating: "a", count: 33_000)))])), "CONTAINER_INVALID", "M03-208")
        XCTAssertEqual(try verify(.object([JMember(name: "x", value: .string(String(repeating: "a", count: 20_000)))])), "ok")
    }
}
