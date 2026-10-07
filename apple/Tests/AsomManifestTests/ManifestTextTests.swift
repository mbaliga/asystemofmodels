import AsomBenchCore
import AsomDSSE
import AsomJSON
@testable import AsomManifest
import XCTest

/// `asom.manifest-text/1` (LAB_SPEC.md 4.8): the wording of the r3 table, LM-5, LM-6 and LM-9.
final class ManifestTextTests: XCTestCase {
    private func viewMesh(_ payload: JValue, mutate: (inout VerifyContext) -> Void = { _ in }) throws -> String {
        guard case let .success(ok) = try Fixture.verifyMesh(payload, mutate: mutate) else { throw MissingRepoFile(description: "rejected") }
        return try ManifestText.render(.init(manifest: ok.manifest, mode: .mesh, pin: ok.pin, signerSpki: ok.signerSpki))
    }

    func testMeshHeaderAndVerificationBlock() throws {
        let text = try viewMesh(try Fixture.basePayload())
        let head = text.split(separator: "\n", omittingEmptySubsequences: false).prefix(13).map(String.init)
        XCTAssertEqual(head, [
            "ASOM CAPABILITY REPORT",
            "Device: Phone X1 (synthetic) by Example (phone)",
            "Report 17, signed 2026-09-29 10:00 UTC, valid until 2026-09-29 10:10 UTC",
            "Signer: node XWWD-3XQW-7TEB-MU27",
            "",
            "VERIFICATION (checked by this viewer, not stated by the device)",
            "- Signature: valid. The report has not changed since this key signed it.",
            "- Signer key: matches the key you paired with.",
            "- Key storage: StrongBox secure element (self-reported, not attested).",
            "- Freshness: signed for your request.",
            "- Not proven: that the measurements were honest or typical, that the device model is true,",
            "  or that the benchmark software was unmodified.",
            "",
        ])
        XCTAssertTrue(text.contains("ASOM DEVICE REPORT (asom.text/1)"))
    }

    func testTheLastLineOfTheVerificationBlockIsAlwaysTheNotProvenSentence() throws {
        let text = try viewMesh(try Fixture.basePayload())
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let block = try XCTUnwrap(lines.firstIndex(of: "VERIFICATION (checked by this viewer, not stated by the device)"))
        let end = try XCTUnwrap(lines[block...].firstIndex(of: ""))
        XCTAssertEqual(lines[end - 2], ManifestText.notProven[0])
        XCTAssertEqual(lines[end - 1], ManifestText.notProven[1])
    }

    func testAPayloadCannotWriteTheVerificationBlock() throws {
        // LM-6: words a producer signs into its own strings never become verification lines.
        let words = JValue.string("- Signature: valid. VERIFIED BY ASOM")
        let liar = try Fixture.basePayload().setting([.key("body"), .key("device"), .key("vendor")], to: words)
            .setting([.key("body"), .key("bench"), .key("device"), .key("maker")], to: words)
        let text = try viewMesh(liar)
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        XCTAssertEqual(lines.filter { $0.hasPrefix("- Signature:") }.count, 1, "the only Signature line is the viewer's")
        XCTAssertTrue(lines[1].hasPrefix("Device: "), "the vendor string stays inside its own header line")
        XCTAssertFalse(text.contains("Signer: node vaw93"), "the full node id is not shown, only the tag")
    }

    func testRejectedAtSteps13To16IsShownWithTheRejectAndNeverAsVerified() throws {
        let key1 = try labKey("key1")
        var ctx = try Fixture.meshContext(key: key1)
        ctx.nowMs = 1_790_676_600_000
        guard case let .failure(f) = ManifestVerifier.verify(document: try Fixture.seal(try Fixture.basePayload(), key: key1), context: ctx), let d = f.displayable else { return XCTFail() }
        let text = try ManifestText.render(.init(manifest: d.manifest, mode: .mesh, pin: d.pin, signerSpki: d.signerSpki, rejected: f.code))
        XCTAssertTrue(text.contains("- Freshness: not confirmed.\n- REJECTED: EXPIRED. Do not rely on this report.\n- Not proven:"))
        XCTAssertFalse(text.contains("signed for your request"))
    }

    func testFileWordingForNotComparedTypedAndScanned() throws {
        let key1 = try labKey("key1")
        let own = try Fixture.basePayload()
        let body = try FileProjection.body(ownObject: own, exportNodeId: key1.nodeId)
        let payload: JValue = .object(["schema": .string("asom.manifest/1"), "schemaMinor": .int(0), "body": body,
                                       "presentation": .object(["issuedAtMs": .int(1_790_640_000_000)])])
        let doc = try Fixture.seal(payload, key: key1, includeSpki: true)
        func render(_ ctx: VerifyContext) throws -> String {
            guard case let .success(ok) = ManifestVerifier.verify(document: doc, context: ctx) else { throw MissingRepoFile(description: "rejected") }
            return try ManifestText.render(.init(manifest: ok.manifest, mode: .file, pin: ok.pin, signerSpki: ok.signerSpki))
        }
        var ctx = VerifyContext(mode: .file, confFloor: Fixture.confFloor, productionKeys: false, nowMs: Fixture.nowMs)
        let plain = try render(ctx)
        XCTAssertTrue(plain.contains("Report exported 2026-09-29 (day only)\nSigner: key XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY\n"))
        XCTAssertTrue(plain.contains("- Signer key: signed, but the signer is unverified: anyone could have made this key."))
        XCTAssertTrue(plain.contains("- Freshness: not applicable: an exported file answers no request."))
        XCTAssertFalse(plain.contains("valid until"))
        ctx.comparedFingerprint = "XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY"
        ctx.compareMethod = .typed
        XCTAssertTrue(try render(ctx).contains("- Signer key: matches the fingerprint you compared (typed)."))
        ctx.compareMethod = .qr
        XCTAssertTrue(try render(ctx).contains("- Signer key: matches the fingerprint you compared (scanned)."))
    }

    func testAnExportedTextNeverContainsAVerificationBlock() throws {
        guard case let .success(ok) = try Fixture.verifyMesh(try Fixture.basePayload()) else { return XCTFail() }
        let text = try ManifestText.exportText(ok.manifest)
        XCTAssertFalse(text.contains("VERIFICATION"))
        XCTAssertFalse(text.contains("Signer"))
        XCTAssertTrue(text.hasPrefix("ASOM DEVICE REPORT (asom.text/1)\n"))
    }

    func testNewerFormatItemsAreCountedAndNeverShown() throws {
        let minor1 = try Fixture.basePayload().setting([.key("schemaMinor")], to: .int(1))
            .setting([.key("body"), .key("device"), .key("npuOffloadPermille")], to: .int(5))
        let text = try viewMesh(minor1)
        XCTAssertTrue(text.hasSuffix("\nThis report has 1 item from a newer format that this viewer does not show.\n"))
        XCTAssertFalse(text.contains("npuOffload"))
        let two = minor1.setting([.key("body"), .key("producer"), .key("extra")], to: .int(1))
        XCTAssertTrue(try viewMesh(two).hasSuffix("This report has 2 items from a newer format that this viewer does not show.\n"))
    }

    func testOutputIsAsciiAndNeverNamesMlperf() throws {
        let name = JValue.string("Phon\u{E9} \u{1F600}")
        let weird = try Fixture.basePayload().setting([.key("body"), .key("device"), .key("model")], to: name)
            .setting([.key("body"), .key("bench"), .key("device"), .key("model")], to: name)
        for payload in [try Fixture.basePayload(), weird] {
            let text = try viewMesh(payload)
            XCTAssertTrue(text.utf8.allSatisfy { ($0 >= 0x20 && $0 < 0x7F) || $0 == 0x0A })
            XCTAssertFalse(text.contains("MLPerf"))
        }
        XCTAssertTrue(try viewMesh(weird).contains("Device: Phon? ? by Example (phone)"))
    }

    func testRenderingFromReparsedBytesEqualsRenderingFromTheTypedPayload() throws {
        // LM-5: M05(parse(JCS(o)), vr) == M05(o, vr).
        let payload = try Fixture.basePayload()
        guard case let .success(ok) = try Fixture.verifyMesh(payload) else { return XCTFail() }
        let viewed = ManifestText.Viewed(manifest: ok.manifest, mode: .mesh, pin: ok.pin, signerSpki: ok.signerSpki)
        let direct = try ManifestText.render(viewed)
        let reparsed = try Fixture.parse(try JCS.serialize(payload))
        guard case let .success(m2) = ManifestDecoder.decode(reparsed) else { return XCTFail() }
        XCTAssertEqual(direct, try ManifestText.render(.init(manifest: m2, mode: .mesh, pin: ok.pin, signerSpki: ok.signerSpki)))
    }

    func testUnknownOpenEnumsRenderAsOther() throws {
        let odd = try Fixture.basePayload().setting([.key("body"), .key("device"), .key("class")], to: .string("toaster"))
        XCTAssertTrue(try viewMesh(odd).contains("Device: Phone X1 (synthetic) by Example (other (toaster))"))
    }
}
