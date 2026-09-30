import Foundation
import AsomBenchCore
import AsomDSSE
import AsomJSON
@testable import AsomManifest
import XCTest

/// The FILE projection (LAB_SPEC.md 4.7, design 5.8) end to end, and the public derivative (manifest.md 12.2, design 5.9).
final class ProjectionTests: XCTestCase {
    private let day: Int64 = 86_400_000

    /// Own payload -> file body -> file payload, sealed by `key` with `signer.spki`, exactly as an export would be.
    private func exportFile(key: LabKey, nodeId: String? = nil, mutateBody: (JValue) -> JValue = { $0 }) throws -> (document: [UInt8], payload: JValue) {
        let own = try Fixture.basePayload()
        let body = mutateBody(try FileProjection.body(ownObject: own, exportNodeId: nodeId ?? key.nodeId))
        let payload: JValue = .object([
            "schema": .string("asom.manifest/1"), "schemaMinor": .int(0), "body": body,
            "presentation": .object(["issuedAtMs": .int(Fixture.nowMs - Fixture.nowMs % day)]),
        ])
        return (try Fixture.seal(payload, key: key, includeSpki: true), payload)
    }

    func testFileBodyStripsEverythingTheFileFormDropsAndNothingElse() throws {
        let own = try Fixture.basePayload()
        let file = try FileProjection.body(ownObject: own, exportNodeId: "X")
        let ownBody = try XCTUnwrap(own.member("body"))
        XCTAssertNil(file.member("seq"))
        XCTAssertNotNil(ownBody.member("seq"))
        XCTAssertEqual(file.member("audience")?.stringValue, "file")
        XCTAssertEqual(file.member("subject"), .object(["nodeId": .string("X"), "keyAlg": .string("ES256"), "keyStorage": .string("ephemeral")]))
        XCTAssertNil(file.value(at: [.key("device"), .key("platformIds")]))
        XCTAssertNotNil(ownBody.value(at: [.key("device"), .key("platformIds")]))
        XCTAssertNil(file.value(at: [.key("device"), .key("os"), .key("securityPatch")]))
        XCTAssertNotNil(ownBody.value(at: [.key("device"), .key("os"), .key("securityPatch")]))
        XCTAssertEqual(file.value(at: [.key("device"), .key("os"), .key("family")]), ownBody.value(at: [.key("device"), .key("os"), .key("family")]))
        XCTAssertEqual(file.member("producer"), ownBody.member("producer"))
        XCTAssertEqual(file.value(at: [.key("device"), .key("soc")]), ownBody.value(at: [.key("device"), .key("soc")]))
        for row in try XCTUnwrap(file.member("results")?.elements) {
            XCTAssertEqual(row.value(at: [.key("measuredAtMs")])?.intValue.map { $0 % day }, 0, "measured on a day boundary")
            XCTAssertEqual(row.value(at: [.key("conditions")])?.members?.map { $0.name }.sorted(), ["charging", "thermalStart"])
        }
        XCTAssertEqual(file.value(at: [.key("bench"), .key("run"), .key("batteryStartPermille")]), .null)
        XCTAssertEqual(file.value(at: [.key("bench"), .key("run"), .key("screenOn")]), .null)
        XCTAssertEqual(file.value(at: [.key("bench"), .key("device"), .key("osBuild")]), .null)
        XCTAssertEqual(file.value(at: [.key("bench"), .key("device"), .key("gpuDriver")]), .null)
        XCTAssertEqual(file.value(at: [.key("bench"), .key("run"), .key("startedAtMs")])?.intValue.map { $0 % day }, 0)
        XCTAssertEqual(file.value(at: [.key("bench"), .key("run"), .key("endedAtMs")])?.intValue.map { $0 % day }, 0)
    }

    func testTheNodeKeyNeverAppearsInAFileBody() throws {
        let own = try Fixture.basePayload()
        let nik = try labKey("key1").nodeId
        let bytes = String(decoding: try JCS.serialize(try FileProjection.body(ownObject: own, exportNodeId: "EXPORT")), as: UTF8.self)
        XCTAssertFalse(bytes.contains(nik))
        for forbidden in ["platformIds", "securityPatch", "\"seq\"", "challenge", "expiresAtMs", "strongbox"] { XCTAssertFalse(bytes.contains(forbidden), forbidden) }
    }

    func testAnExportedFileVerifiesAsSignerUnverifiedUntilTheFingerprintIsCompared() throws {
        let export = try ES256Signer.generateEphemeral()
        let key = LabKey(signer: export, spki: export.spki, nodeId: NodeIdentity.nodeId(spki: export.spki))
        let file = try exportFile(key: key)
        let ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, nowMs: Fixture.nowMs)
        guard case let .success(ok) = ManifestVerifier.verify(document: file.document, context: ctx) else { return XCTFail("file rejected") }
        XCTAssertEqual(ok.pin, .signerUnverified)
        XCTAssertEqual(ok.tier, .a0)
        XCTAssertNil(ok.manifest.seq)
        XCTAssertNil(ok.manifest.expiresAtMs)

        var typed = ctx
        typed.comparedFingerprint = NodeIdentity.exportFingerprintDisplay(spki: export.spki).lowercased().replacingOccurrences(of: "-", with: " ")
        typed.compareMethod = .qr
        guard case let .success(ok2) = ManifestVerifier.verify(document: file.document, context: typed) else { return XCTFail("compared file rejected") }
        XCTAssertEqual(ok2.pin, .pinnedByFingerprint(.qr))

        var wrong = ctx
        wrong.comparedFingerprint = NodeIdentity.exportFingerprintDisplay(spki: try labKey("key2").spki)
        wrong.compareMethod = .typed
        XCTAssertEqual(code(ManifestVerifier.verify(document: file.document, context: wrong)), "FINGERPRINT_MISMATCH")
    }

    func testFileRejectsWhatTheFileFormForbids() throws {
        let export = try ES256Signer.generateEphemeral()
        let key = LabKey(signer: export, spki: export.spki, nodeId: NodeIdentity.nodeId(spki: export.spki))
        let ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, nowMs: Fixture.nowMs)
        func verdict(_ mutate: @escaping (JValue) -> JValue) throws -> String {
            code(ManifestVerifier.verify(document: try exportFile(key: key, mutateBody: mutate).document, context: ctx))
        }
        XCTAssertEqual(try verdict { $0 }, "ok")
        XCTAssertEqual(try verdict { $0.setting([.key("seq")], to: .int(5)) }, "SCHEMA_INVALID", "seq in a file")
        XCTAssertEqual(try verdict { $0.setting([.key("subject"), .key("keyStorage")], to: .string("strongbox")) }, "SCHEMA_INVALID")
        XCTAssertEqual(try verdict { $0.setting([.key("device"), .key("platformIds")], to: .object([])) }, "SCHEMA_INVALID")
        XCTAssertEqual(try verdict { $0.setting([.key("device"), .key("os"), .key("securityPatch")], to: .string("2026-09-01")) }, "SCHEMA_INVALID")
        XCTAssertEqual(try verdict { $0.setting([.key("results"), .at(0), .key("measuredAtMs")], to: .int(1_790_640_000_001)) }, "SCHEMA_INVALID")
        XCTAssertEqual(try verdict { $0.setting([.key("results"), .at(0), .key("conditions"), .key("screenOn")], to: .bool(true)) }, "SCHEMA_INVALID")
        XCTAssertEqual(try verdict { $0.setting([.key("bench"), .key("device"), .key("osBuild")], to: .string("B1")) }, "SCHEMA_INVALID")
        XCTAssertEqual(try verdict { $0.setting([.key("results"), .at(0), .key("decode"), .at(0), .key("milliTokPerSec"), .key("p50")], to: .int(20_964)) }, "DERIVATION_MISMATCH")
        XCTAssertEqual(try verdict { $0.setting([.key("subject"), .key("nodeId")], to: .string(try! labKey("key2").nodeId)) }, "SUBJECT_KEY_MISMATCH", "the subject is the export key")
    }

    /// At minor 1 unknown members are tolerated, so the explicit file rules are the only thing refusing these.
    func testFileRulesHoldWhereUnknownMembersAreTolerated() throws {
        let export = try ES256Signer.generateEphemeral()
        let key = LabKey(signer: export, spki: export.spki, nodeId: NodeIdentity.nodeId(spki: export.spki))
        let ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, nowMs: Fixture.nowMs)
        func payload(_ mutate: (JValue) -> JValue) throws -> [UInt8] {
            let body = mutate(try FileProjection.body(ownObject: try Fixture.basePayload(), exportNodeId: key.nodeId))
            let p: JValue = .object(["schema": .string("asom.manifest/1"), "schemaMinor": .int(1), "body": body,
                                     "presentation": .object(["issuedAtMs": .int(Fixture.nowMs - Fixture.nowMs % day)])])
            return try Fixture.seal(p, key: key, includeSpki: true)
        }
        XCTAssertEqual(code(ManifestVerifier.verify(document: try payload { $0 }, context: ctx)), "ok")
        XCTAssertEqual(code(ManifestVerifier.verify(document: try payload { $0.setting([.key("seq")], to: .int(5)) }, context: ctx)), "SCHEMA_INVALID", "seq")
        XCTAssertEqual(code(ManifestVerifier.verify(document: try payload { $0.setting([.key("device"), .key("platformIds")], to: .object([])) }, context: ctx)), "SCHEMA_INVALID", "platformIds")
        XCTAssertEqual(code(ManifestVerifier.verify(document: try payload { $0.setting([.key("device"), .key("os"), .key("securityPatch")], to: .string("2026-09-01")) }, context: ctx)), "SCHEMA_INVALID", "securityPatch")
        XCTAssertEqual(code(ManifestVerifier.verify(document: try payload { $0.setting([.key("subject"), .key("keyStorage")], to: .string("tee")) }, context: ctx)), "SCHEMA_INVALID", "keyStorage")
        XCTAssertEqual(code(ManifestVerifier.verify(document: try payload { $0.setting([.key("results"), .at(0), .key("conditions"), .key("batteryStartPermille")], to: .int(5)) }, context: ctx)), "SCHEMA_INVALID", "battery")
    }

    func testAFileWithoutSignerSpkiOrWithALyingKeyidIsRefused() throws {
        let export = try ES256Signer.generateEphemeral()
        let key = LabKey(signer: export, spki: export.spki, nodeId: NodeIdentity.nodeId(spki: export.spki))
        let file = try exportFile(key: key)
        let ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, nowMs: Fixture.nowMs)
        let container = try Fixture.parse(file.document)
        XCTAssertEqual(code(ManifestVerifier.verify(document: try JCS.serialize(container.removing([.key("signer")])), context: ctx)), "KEY_NOT_PINNED")
        let lie = container.setting([.key("dsse"), .key("signatures"), .at(0), .key("keyid")], to: .string(try labKey("key2").nodeId))
        XCTAssertEqual(code(ManifestVerifier.verify(document: try JCS.serialize(lie), context: ctx)), "KEY_NOT_PINNED")
    }

    func testTheFileFormNeverEmbedsThePinnedNodeKey() throws {
        // Exporting with the NIK as the subject would make every export linkable; the projection requires the caller's
        // per-export key id, and a file whose subject is another node is refused at step 12.
        let export = try ES256Signer.generateEphemeral()
        let key = LabKey(signer: export, spki: export.spki, nodeId: NodeIdentity.nodeId(spki: export.spki))
        let file = try exportFile(key: key, nodeId: try labKey("key1").nodeId)
        let ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, nowMs: Fixture.nowMs)
        XCTAssertEqual(code(ManifestVerifier.verify(document: file.document, context: ctx)), "SUBJECT_KEY_MISMATCH")
    }

    // MARK: public derivative

    func testQ2IsTwoSignificantDigitsHalfUpAndIdempotent() {
        let table: [(Int64, Int64)] = [(0, 0), (7, 7), (99, 99), (100, 100), (149, 150), (150, 150), (994, 990), (995, 1000), (11_234, 11_000),
                                       (18_400, 18_000), (212_000, 210_000), (2_480_000, 2_500_000), (9_950, 10_000), (99_999, 100_000)]
        for (x, want) in table { XCTAssertEqual(PublicDerivative.q2(x), want, "q2(\(x))") }
        var rng = SplitMix64(seed: 7)
        var checked = 0
        for _ in 0..<2000 {
            let x = Int64(rng.next() % 10_000_000_000)
            let q = PublicDerivative.q2(x)
            XCTAssertEqual(PublicDerivative.q2(q), q, "idempotent at \(x)")
            XCTAssertLessThanOrEqual(abs(q - x) * 100, max(x, 1) * 5 + 50, "within half a unit of the second digit at \(x)")
            checked += 1
        }
        XCTAssertEqual(checked, 2000)
    }

    private func derivative(catalogue: [String], allow: PublicDerivative.AllowList? = nil, mutate: (JValue) -> JValue = { $0 }) throws -> JValue? {
        guard case let .success(ok) = try Fixture.verifyMesh(mutate(try Fixture.basePayload())) else { throw MissingRepoFile(description: "base rejected") }
        let a = allow ?? PublicDerivative.AllowList(engineCommits: ["01234567"], harnessVersions: ["1.0.0", "0.2.0"], vendors: ["Example"], models: ["Phone X1 (synthetic)"])
        return try PublicDerivative.make(from: ok.manifest, catalogue: Set(catalogue), allow: a)
    }

    private let t2 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5"
    private let t1 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a"

    func testPublicDerivativeCarriesOnlyTheAllowList() throws {
        let d = try XCTUnwrap(try derivative(catalogue: [t1, t2]))
        let text = String(decoding: try JCS.serialize(d), as: UTF8.self)
        for forbidden in ["nodeId", "seq", "challenge", "platformIds", "securityPatch", "keyStorage", "keyid", "signer", "evidence", "osBuild", "gpuDriver",
                          "fingerprint", "audience", "threads", "gpuLayers", "vaw93hb8", "batteryStart", "screenOn", "socStart", "1790676000000", "custom\":\"custom"] {
            XCTAssertFalse(text.contains(forbidden), forbidden)
        }
        XCTAssertEqual(d.value(at: [.key("schema")])?.stringValue, "asom.bench-public/1")
        XCTAssertEqual(d.value(at: [.key("device"), .key("ramClassGiB")])?.intValue, 16)
        XCTAssertEqual(d.value(at: [.key("device"), .key("osMajor")])?.intValue, 16)
        let rows = try XCTUnwrap(d.member("results")?.elements)
        XCTAssertEqual(rows.count, 1, "only the row that carries a heat test")
        XCTAssertEqual(rows[0].member("measuredMonth")?.stringValue, "2026-09")
        XCTAssertEqual(rows[0].member("steadyMilliTokPerSec")?.intValue, 9_700)
        XCTAssertEqual(rows[0].member("throttleOnsetSec")?.intValue, 140)
        XCTAssertEqual(rows[0].member("peakProcessMB")?.intValue, 3_100)
        XCTAssertEqual(rows[0].member("curve")?.elements?.count, 12)
        for pair in rows[0].member("curve")?.elements ?? [] {
            for cell in pair.elements ?? [] { let v = try XCTUnwrap(cell.intValue); XCTAssertEqual(PublicDerivative.q2(v), v) }
        }
    }

    func testRowsOutsideTheCatalogueAreDroppedAndAnEmptyResultIsNoUpload() throws {
        XCTAssertNil(try derivative(catalogue: [t1]))
        XCTAssertNil(try derivative(catalogue: []))
        XCTAssertNotNil(try derivative(catalogue: [t2]))
    }

    func testCommitAndVersionsOffTheReleaseListAreCustomAndVendorModelAreOther() throws {
        let off = PublicDerivative.AllowList(engineCommits: ["ffffffff"], harnessVersions: ["9.9.9"], vendors: [], models: [])
        let d = try XCTUnwrap(try derivative(catalogue: [t2], allow: off))
        XCTAssertEqual(d.value(at: [.key("engine"), .key("commit")])?.stringValue, "custom")
        XCTAssertEqual(d.value(at: [.key("harness"), .key("version")])?.stringValue, "custom")
        XCTAssertEqual(d.value(at: [.key("harness"), .key("confVersion")])?.stringValue, "custom")
        XCTAssertEqual(d.value(at: [.key("device"), .key("vendor")])?.stringValue, "other")
        XCTAssertEqual(d.value(at: [.key("device"), .key("model")])?.stringValue, "other")
        XCTAssertEqual(d.value(at: [.key("harness"), .key("methodologyId")])?.stringValue, "asom-bench-method/1")
    }

    func testCurveSelectionIsTwelveEvenlySpacedPointsWithBothEnds() {
        let curve = (0..<25).map { Manifest.CurvePoint(tMs: Int64($0) * 15_000, rate: Int64(1000 + $0)) }
        let picked = PublicDerivative.pickCurve(curve)
        XCTAssertEqual(picked.count, 12)
        XCTAssertEqual(picked.first?.tMs, 0)
        XCTAssertEqual(picked.last?.tMs, 360_000)
        XCTAssertEqual(picked.map { $0.tMs / 15_000 }, [0, 2, 4, 7, 9, 11, 13, 15, 17, 20, 22, 24])
        XCTAssertEqual(PublicDerivative.pickCurve(Array(curve.prefix(12))).count, 12)
        XCTAssertEqual(PublicDerivative.pickCurve(Array(curve.prefix(5))).count, 5)
    }

    func testMonthAndCivilDate() {
        XCTAssertEqual(PublicDerivative.month(0), "1970-01")
        XCTAssertEqual(PublicDerivative.month(1_790_668_801_000), "2026-09")
        XCTAssertEqual(PublicDerivative.month(1_804_982_399_999), "2027-03")
        XCTAssertEqual(PublicDerivative.civil(days: 19_782).0, 2024)
        XCTAssertEqual(PublicDerivative.month(1_709_164_800_000), "2024-02", "the leap day")
        XCTAssertEqual(PublicDerivative.month(1_709_251_200_000), "2024-03")
    }
}
