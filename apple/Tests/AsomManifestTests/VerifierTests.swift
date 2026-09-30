import AsomBenchCore
import AsomDSSE
import AsomJSON
@testable import AsomManifest
import XCTest

/// The r3 verifier (LAB_SPEC.md section 4.6), step by step. Every document here is re-signed with a TEST-ONLY key after
/// the edit, so the reject that comes back is the step's own and not a broken signature.
final class VerifierTests: XCTestCase {
    private func base() throws -> JValue { try Fixture.basePayload() }

    func testTheBaseDocumentIsAcceptedAsPinnedA1() throws {
        let v = try Fixture.verifyMesh(try base())
        guard case let .success(ok) = v else { return XCTFail("rejected: \(code(v))") }
        XCTAssertEqual(ok.pin, .pinned)
        XCTAssertEqual(ok.tier, .a1)
        XCTAssertEqual(ok.manifest.seq, 17)
        XCTAssertEqual(ok.unknownFields, 0)
        XCTAssertEqual(ok.bodyDigest.count, 43)
    }

    func testBodyDigestIsSha256OfTheJcsBodyAndIgnoresTheSignatureBytes() throws {
        let payload = try base()
        let body = try XCTUnwrap(payload.member("body"))
        let expected = Base64Strict.encodeURL(NodeIdentity.sha256(try JCS.serialize(body)))
        guard case let .success(ok) = try Fixture.verifyMesh(payload) else { return XCTFail() }
        XCTAssertEqual(ok.bodyDigest, expected)
        // A high-S twin of the same payload has the same digest (M02-105).
        let key1 = try labKey("key1")
        let container = try Fixture.seal(payload, key: key1)
        let parsed = try Fixture.parse(container)
        let sigText = try XCTUnwrap(parsed.member("dsse")?.member("signatures")?.elements?.first?.member("sig")?.stringValue)
        let flipped = SignatureCodec.flipS(try XCTUnwrap(Base64Strict.decodeEither(sigText)))
        let twin = parsed.setting([.key("dsse"), .key("signatures"), .at(0), .key("sig")], to: .string(Base64Strict.encode(flipped)))
        guard case let .success(ok2) = ManifestVerifier.verify(document: try JCS.serialize(twin), context: try Fixture.meshContext(key: key1)) else {
            return XCTFail("high-S twin rejected")
        }
        XCTAssertEqual(ok2.bodyDigest, expected)
    }

    // MARK: step 11

    func testSchemaMajorAndMinor() throws {
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("schema")], to: .string("asom.manifest/2")))), "SCHEMA_MAJOR_UNKNOWN")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("schema")], to: .string("asom.manifest/10")))), "SCHEMA_MAJOR_UNKNOWN")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("schema")], to: .string("asom.manifest/01")))), "SCHEMA_INVALID")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("schema")], to: .string("asom.other/1")))), "SCHEMA_INVALID")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().removing([.key("schema")]))), "SCHEMA_INVALID")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("schemaMinor")], to: .int(1001)))), "SCHEMA_INVALID")
    }

    func testUnknownMembersAreInvalidAtMinorZeroAndCountedAboveIt() throws {
        let extra = try base().setting([.key("body"), .key("device"), .key("npuOffloadPermille")], to: .int(5))
        XCTAssertEqual(code(try Fixture.verifyMesh(extra)), "SCHEMA_INVALID")
        let minor1 = extra.setting([.key("schemaMinor")], to: .int(1))
        guard case let .success(ok) = try Fixture.verifyMesh(minor1) else { return XCTFail("minor 1 with one unknown member rejected") }
        XCTAssertEqual(ok.unknownFields, 1)
        // Unknown members are signed, so they change the digest.
        guard case let .success(plain) = try Fixture.verifyMesh(try base()) else { return XCTFail() }
        XCTAssertNotEqual(ok.bodyDigest, plain.bodyDigest)
        let two = minor1.setting([.key("body"), .key("producer"), .key("nodeMood")], to: .string("ok"))
        guard case let .success(ok2) = try Fixture.verifyMesh(two) else { return XCTFail() }
        XCTAssertEqual(ok2.unknownFields, 2)
    }

    func testForbiddenNamesAreInvalidEvenWhereUnknownMembersAreTolerated() throws {
        for name in ["derived", "render", "textSha256", "field", "custom"] {
            let doc = try base().setting([.key("schemaMinor")], to: .int(1)).setting([.key("body"), .key("device"), .key(name)], to: .int(1))
            XCTAssertEqual(code(try Fixture.verifyMesh(doc)), "SCHEMA_INVALID", name)
        }
        let deep = try base().setting([.key("schemaMinor")], to: .int(1)).setting(bodyResults + [.at(0), .key("prefill"), .at(0), .key("custom")], to: .int(1))
        XCTAssertEqual(code(try Fixture.verifyMesh(deep)), "SCHEMA_INVALID")
    }

    func testStringLimitsAreInCodePointsAndBidiControlsAreRefused() throws {
        let path: [Step] = [.key("body"), .key("device"), .key("model")]
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(path, to: .string(String(repeating: "\u{1F600}", count: 96))))), "ok")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(path, to: .string(String(repeating: "\u{1F600}", count: 97))))), "SCHEMA_INVALID")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(path, to: .string("Phone \u{202E}evil")))), "SCHEMA_INVALID")
    }

    func testPhysicalBoundsOnRatesBytesAndTtft() throws {
        let rate: [Step] = bodyResults + [.at(0), .key("decode"), .at(0), .key("milliTokPerSec"), .key("p50")]
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(rate, to: .int(0)))), "SCHEMA_INVALID")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(rate, to: .int(1_000_000_001)))), "SCHEMA_INVALID")
        let ttft: [Step] = bodyResults + [.at(0), .key("prefill"), .at(0), .key("ttftMicros"), .key("p90")]
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(ttft, to: .int(3_600_000_001)))), "SCHEMA_INVALID")
        let bytes: [Step] = [.key("body"), .key("device"), .key("memory"), .key("totalBytes")]
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(bytes, to: .int(SchemaRules.maxBytes + 1)))), "SCHEMA_INVALID")
    }

    func testAudienceConditionalRules() throws {
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().removing([.key("body"), .key("seq")]))), "SCHEMA_INVALID", "own needs seq")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().removing(presentation + [.key("challenge")]))), "SCHEMA_INVALID", "own needs a challenge member, even null")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("body"), .key("audience")], to: .string("other")))), "SCHEMA_INVALID", "other was removed (P1)")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(presentation + [.key("challenge")], to: .string("short")))), "SCHEMA_INVALID")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("body"), .key("subject"), .key("keyStorage")], to: .string("carrier-pigeon")))), "SCHEMA_INVALID")
    }

    // MARK: steps 12 to 14

    func testSubjectMustBeTheSigningKey() throws {
        let other = try labKey("key2").nodeId
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting([.key("body"), .key("subject"), .key("nodeId")], to: .string(other)))), "SUBJECT_KEY_MISMATCH")
    }

    func testTimeWindowBoundaries() throws {
        let issued = 1_790_676_000_000 as Int64, expires = 1_790_676_600_000 as Int64
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.nowMs = expires - 1 }), "ok")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.nowMs = expires }), "EXPIRED")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.nowMs = issued - 300_000 }), "ok", "issuedAtMs == now + 300000 is allowed skew")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.nowMs = issued - 300_001 }), "NOT_YET_VALID")
        let long = try base().setting(presentation + [.key("expiresAtMs")], to: .int(issued + 600_000))
        XCTAssertEqual(code(try Fixture.verifyMesh(long)), "ok", "a TTL of exactly 600000 is allowed")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(presentation + [.key("expiresAtMs")], to: .int(issued + 600_001)))), "TTL_INVALID")
        // EXPIRED is tested before the TTL, so a zero or negative TTL is only reached while the clock is before expiry.
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(presentation + [.key("expiresAtMs")], to: .int(issued))) { $0.nowMs = issued - 10 }), "TTL_INVALID")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(presentation + [.key("expiresAtMs")], to: .int(issued - 1))) { $0.nowMs = issued - 10 }), "TTL_INVALID")
    }

    func testNonceIsRequiredAndCompared() throws {
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(presentation + [.key("challenge")], to: .null))), "NONCE_MISMATCH")
        let other = Base64Strict.encodeURL([UInt8](repeating: 7, count: 32))
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(presentation + [.key("challenge")], to: .string(other)))), "NONCE_MISMATCH")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.expectedChallenge = nil }), "NONCE_MISMATCH", "no expected challenge is never a match")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.expectedChallenge = Array($0.expectedChallenge!.dropLast()) }), "NONCE_MISMATCH")
    }

    // MARK: order

    func testStepOrderIsTheNormativeOne() throws {
        // A payload that violates two rules reports the earlier step's code.
        let subjectAndTtl = try base().setting([.key("body"), .key("subject"), .key("nodeId")], to: .string(try labKey("key2").nodeId))
            .setting(presentation + [.key("expiresAtMs")], to: .int(1_790_676_000_000))
        XCTAssertEqual(code(try Fixture.verifyMesh(subjectAndTtl)), "SUBJECT_KEY_MISMATCH", "12 before 13")
        let ttlAndNonce = try base().setting(presentation + [.key("expiresAtMs")], to: .int(1_790_677_000_000)).setting(presentation + [.key("challenge")], to: .null)
        XCTAssertEqual(code(try Fixture.verifyMesh(ttlAndNonce) { $0.nowMs = 1_790_676_060_000 }), "TTL_INVALID", "13 before 14")
        let nonceAndInconsistent = try base().setting(presentation + [.key("challenge")], to: .null)
            .setting(bodyResults + [.at(0), .key("runs"), .key("completed")], to: .int(9))
        XCTAssertEqual(code(try Fixture.verifyMesh(nonceAndInconsistent)), "NONCE_MISMATCH", "14 before 15")
        let inconsistentAndDerivation = try base().setting(bodyResults + [.at(0), .key("runs"), .key("completed")], to: .int(9))
            .setting(bodyResults + [.at(0), .key("decode"), .at(0), .key("milliTokPerSec"), .key("p50")], to: .int(20_964))
        XCTAssertEqual(code(try Fixture.verifyMesh(inconsistentAndDerivation)), "INCONSISTENT", "15 before 15a")
        let confAndResults = try base().setting(bodyResults + [.at(0), .key("decode"), .at(0), .key("milliTokPerSec"), .key("p50")], to: .int(20_964))
        XCTAssertEqual(code(try Fixture.verifyMesh(confAndResults) { $0.confFloor = "0.3.0" }), "DERIVATION_MISMATCH")
    }

    // MARK: step 15

    func testConsistencyRulesEachRejectOnTheirOwn() throws {
        let r0: [Step] = bodyResults + [.at(0)]
        let r1: [Step] = bodyResults + [.at(1)]
        let cases: [(String, JValue)] = [
            ("duplicate (fileSha256, backend)", try base().setting(bodyResults, to: .array([
                try XCTUnwrap(try base().value(at: r0)), try XCTUnwrap(try base().value(at: r1)), try XCTUnwrap(try base().value(at: r0)),
            ]))),
            ("completed > planned", try base().setting(r0 + [.key("runs"), .key("planned")], to: .int(4))),
            ("discarded > completed", try base().setting(r0 + [.key("runs"), .key("discarded")], to: .int(6))),
            ("p10 > p50", try base().setting(r0 + [.key("decode"), .at(0), .key("milliTokPerSec"), .key("p10")], to: .int(20_964))),
            ("p90 < p50", try base().setting(r0 + [.key("decode"), .at(0), .key("milliTokPerSec"), .key("p90")], to: .int(20_962))),
            ("ttft p10 > p50", try base().setting(r0 + [.key("prefill"), .at(0), .key("ttftMicros"), .key("p10")], to: .int(1_968_163))),
            ("prompt > context", try base().setting(r0 + [.key("settings"), .key("ctxTokens")], to: .int(256))),
            ("context + gen > context", try base().setting(r0 + [.key("decode"), .at(0), .key("contextTokens")], to: .int(3969))),
            ("ttft under 90% of prompt time", try base().setting(r0 + [.key("prefill"), .at(0), .key("ttftMicros"), .key("p10")], to: .int(1))
                .setting(r0 + [.key("prefill"), .at(0), .key("ttftMicros"), .key("p50")], to: .int(1_000_000))),
            ("curve does not start at zero", try base().setting(r1 + [.key("sustained"), .key("curve"), .at(0), .at(0)], to: .int(1))),
            ("curve not increasing", try base().setting(r1 + [.key("sustained"), .key("curve"), .at(1), .at(0)], to: .int(0))),
            ("curve past duration", try base().setting(r1 + [.key("sustained"), .key("durationMs")], to: .int(345_000))),
            ("onset after the end", try base().setting(r1 + [.key("sustained"), .key("throttleOnsetMs")], to: .int(375_001))),
            ("steady above every point", try base().setting(r1 + [.key("sustained"), .key("steadyMilliTokPerSec")], to: .int(14_497))),
            ("peak process above total", try base().setting(r0 + [.key("memory"), .key("peakProcessBytes")], to: .int(16_000_000_001))),
            ("available above total", try base().setting(r0 + [.key("memory"), .key("availableBeforeLoadBytes")], to: .int(16_000_000_001))),
            ("measured after issued", try base().setting(r0 + [.key("measuredAtMs")], to: .int(1_790_676_000_001))),
            ("unavailable power with a value", try base().setting(r0 + [.key("power"), .key("avgMilliW")], to: .int(5))),
        ]
        for (name, doc) in cases { XCTAssertEqual(code(try Fixture.verifyMesh(doc)), "INCONSISTENT", name) }
    }

    func testConsistencyBoundariesThatMustStayValid() throws {
        let r0: [Step] = bodyResults + [.at(0)]
        // Each edit sits exactly on a boundary, so step 15 passes; the edit itself is not what the projection derives, so
        // the next step (15a) reports DERIVATION_MISMATCH. INCONSISTENT here would mean an off-by-one in consistency().
        func passes15(_ doc: JValue, _ why: String) throws {
            XCTAssertEqual(code(try Fixture.verifyMesh(doc)), "DERIVATION_MISMATCH", why)
        }
        try passes15(try base().setting(r0 + [.key("decode"), .at(0), .key("milliTokPerSec"), .key("p10")], to: .int(20_963)), "p10 == p50")
        try passes15(try base().setting(r0 + [.key("decode"), .at(0), .key("milliTokPerSec"), .key("p90")], to: .int(20_963)), "p90 == p50")
        try passes15(try base().setting(bodyResults + [.at(1), .key("sustained"), .key("steadyMilliTokPerSec")], to: .int(14_496)), "steady == the largest curve point")
        try passes15(try base().setting(r0 + [.key("settings"), .key("ctxTokens")], to: .int(512)), "promptTokens == ctxTokens")
        try passes15(try base().setting(r0 + [.key("decode"), .at(0), .key("contextTokens")], to: .int(3968)), "context + gen == ctxTokens")
        try passes15(try base().setting(bodyResults + [.at(1), .key("sustained"), .key("throttleOnsetMs")], to: .int(375_000)), "onset == duration")
        try passes15(try base().setting(bodyResults + [.at(1), .key("sustained"), .key("durationMs")], to: .int(360_000)), "last curve point == duration")
        try passes15(try base().setting(r0 + [.key("memory"), .key("peakProcessBytes")], to: .int(16_000_000_000)), "peak == total")
        try passes15(try base().setting(r0 + [.key("measuredAtMs")], to: .int(1_790_676_000_000)), "measuredAtMs == issuedAtMs")
        try passes15(try base().setting(r0 + [.key("runs"), .key("discarded")], to: .int(5)), "discarded == completed")
        // TTFT exactly 90% of the prompt time: 460800 us * 1e6 milli-tok/s * 10 == 512 * 1e9 * 9.
        let exact = try base()
            .setting(r0 + [.key("prefill"), .at(0), .key("milliTokPerSec")], to: .object(["p50": .int(1_000_000)]))
            .setting(r0 + [.key("prefill"), .at(0), .key("ttftMicros")], to: .object(["p50": .int(460_800)]))
        try passes15(exact, "ttft == 90% of the implied prompt time")
        let under = try base()
            .setting(r0 + [.key("prefill"), .at(0), .key("milliTokPerSec")], to: .object(["p50": .int(1_000_000)]))
            .setting(r0 + [.key("prefill"), .at(0), .key("ttftMicros")], to: .object(["p50": .int(460_799)]))
        XCTAssertEqual(code(try Fixture.verifyMesh(under)), "INCONSISTENT")
    }

    func testConsistencyArithmeticIsCheckedNotWrapped() throws {
        // ttft 3.6e9 us at 1e9 milli-tokens/s: 3.6e9 * 1e9 * 10 does not fit 64 bits.
        let r0: [Step] = bodyResults + [.at(0)]
        let doc = try base()
            .setting(r0 + [.key("prefill"), .at(0), .key("milliTokPerSec")], to: .object(["p50": .int(1_000_000_000)]))
            .setting(r0 + [.key("prefill"), .at(0), .key("ttftMicros")], to: .object(["p50": .int(3_600_000_000)]))
        XCTAssertEqual(code(try Fixture.verifyMesh(doc)), "INCONSISTENT")
        // 1.9e9 * 1e9 * 10 wraps to a positive number that would pass the comparison in wrapping arithmetic.
        let wrap = try base()
            .setting(r0 + [.key("prefill"), .at(0), .key("milliTokPerSec")], to: .object(["p50": .int(1_000_000_000)]))
            .setting(r0 + [.key("prefill"), .at(0), .key("ttftMicros")], to: .object(["p50": .int(1_900_000_000)]))
        XCTAssertEqual(code(try Fixture.verifyMesh(wrap)), "INCONSISTENT")
        XCTAssertEqual(Int64(1_900_000_000).multipliedFullWidth(by: 10_000_000_000).low > 0, true, "the wrapped 64-bit product really is positive")
    }

    // MARK: steps 15a to 15c

    func testDerivationMustMatchByteForByte() throws {
        let r0: [Step] = bodyResults + [.at(0)]
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(r0 + [.key("decode"), .at(0), .key("milliTokPerSec"), .key("p50")], to: .int(20_964)))), "DERIVATION_MISMATCH")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(r0 + [.key("flags")], to: .strings(["charging"])))), "DERIVATION_MISMATCH")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().removing(bodyResults + [.at(1)]))), "DERIVATION_MISMATCH", "a missing row")
        let rows = try XCTUnwrap(try base().value(at: bodyResults)?.elements)
        XCTAssertEqual(code(try Fixture.verifyMesh(try base().setting(bodyResults, to: .array(rows.reversed())))), "DERIVATION_MISMATCH", "row order")
    }

    func testFlatteringSummariesOfUnflatteringSamplesAreRefused() throws {
        // Every tg128@d0 sample of T2 doubled after the summaries were computed (M03-176's construction).
        let samples: [Step] = [.key("body"), .key("bench"), .key("tiers"), .at(1), .key("tests"), .at(1), .key("samples")]
        let original = try XCTUnwrap(try base().value(at: samples)?.elements?.compactMap { $0.intValue })
        let doc = try base().setting(samples, to: .ints(original.map { $0 * 2 }))
        XCTAssertEqual(code(try Fixture.verifyMesh(doc)), "DERIVATION_MISMATCH")
    }

    func testConfVersionFloorAndKnownBadList() throws {
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.confFloor = "0.2.0" }), "ok")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.confFloor = "0.2.1" }), "DERIVATION_MISMATCH")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.knownBadConf = ["0.2.0"] }), "DERIVATION_MISMATCH")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.knownBadConf = ["0.2.1"] }), "ok")
        let lower = try base().setting([.key("body"), .key("bench"), .key("harness"), .key("confVersion")], to: .string("0.1.0"))
        XCTAssertEqual(code(try Fixture.verifyMesh(lower)), "DERIVATION_MISMATCH")
    }

    func testAudienceMustMatchTheContext() throws {
        let key1 = try labKey("key1")
        var ctx = try Fixture.meshContext(key: key1)
        ctx.mode = .file
        ctx.pinnedSpki = nil
        let doc = try Fixture.seal(try base(), key: key1, includeSpki: true)
        XCTAssertEqual(code(ManifestVerifier.verify(document: doc, context: ctx)), "AUDIENCE_MISMATCH")
    }

    func testEvidenceIsAtMostTwoObjects() throws {
        let key1 = try labKey("key1")
        let container = try Fixture.parse(try Fixture.seal(try base(), key: key1))
        func verify(_ evidence: JValue) throws -> String {
            code(ManifestVerifier.verify(document: try JCS.serialize(container.setting([.key("evidence")], to: evidence)), context: try Fixture.meshContext(key: key1)))
        }
        XCTAssertEqual(try verify(.array([])), "ok")
        XCTAssertEqual(try verify(.array([.object([]), .object([])])), "ok")
        XCTAssertEqual(try verify(.array([.object([]), .object([]), .object([])])), "CONTAINER_INVALID")
        XCTAssertEqual(try verify(.array([.int(1)])), "CONTAINER_INVALID")
        XCTAssertEqual(try verify(.object([])), "CONTAINER_INVALID")
        XCTAssertEqual(code(ManifestVerifier.verify(document: try JCS.serialize(container.removing([.key("evidence")])), context: try Fixture.meshContext(key: key1))), "ok", "evidence may be absent")
    }

    // MARK: steps 16 to 18

    func testRollbackAndEquivocation() throws {
        let key = try labKey("key1").nodeId + "|own"
        let digest: String = try {
            guard case let .success(ok) = try Fixture.verifyMesh(try base()) else { throw MissingRepoFile(description: "base rejected") }
            return ok.bodyDigest
        }()
        func with(_ e: RollbackEntry?) throws -> String { code(try Fixture.verifyMesh(try base()) { if let e { $0.rollback[key] = e } }) }
        XCTAssertEqual(try with(nil), "ok")
        XCTAssertEqual(try with(RollbackEntry(seq: 16, bodyDigest: "x")), "ok")
        XCTAssertEqual(try with(RollbackEntry(seq: 17, bodyDigest: digest)), "ok")
        XCTAssertEqual(try with(RollbackEntry(seq: 18, bodyDigest: digest)), "ROLLBACK")
        XCTAssertEqual(try with(RollbackEntry(seq: 17, bodyDigest: String(repeating: "A", count: 43))), "EQUIVOCATION")
        // Keyed by (peer nodeId, "own"): another peer's entry is not ours.
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.rollback[try! labKey("key2").nodeId + "|own"] = RollbackEntry(seq: 99, bodyDigest: "x") }), "ok")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.rollback[try! labKey("key1").nodeId + "|file"] = RollbackEntry(seq: 99, bodyDigest: "x") }), "ok")
    }

    func testTierFromKeyStorageAndTheRequiredTier() throws {
        for storage in ["strongbox", "tee", "secure-enclave", "tpm"] {
            guard case let .success(ok) = try Fixture.verifyMesh(try base().setting([.key("body"), .key("subject"), .key("keyStorage")], to: .string(storage))) else { return XCTFail(storage) }
            XCTAssertEqual(ok.tier, .a1, storage)
        }
        for storage in ["os-keystore", "file", "unknown", "ephemeral"] {
            guard case let .success(ok) = try Fixture.verifyMesh(try base().setting([.key("body"), .key("subject"), .key("keyStorage")], to: .string(storage))) else { return XCTFail(storage) }
            XCTAssertEqual(ok.tier, .a0, storage)
        }
        let soft = try base().setting([.key("body"), .key("subject"), .key("keyStorage")], to: .string("os-keystore"))
        XCTAssertEqual(code(try Fixture.verifyMesh(soft) { $0.requiredTier = .a1 }), "TIER_INSUFFICIENT")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.requiredTier = .a1 }), "ok")
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.requiredTier = .a2 }), "TIER_INSUFFICIENT", "A2 is never reachable in the lab")
    }

    func testTestOnlyKeysAreRefusedInProductionMode() throws {
        XCTAssertEqual(code(try Fixture.verifyMesh(try base()) { $0.productionKeys = true }), "TEST_ONLY_KEY")
        for name in ["key1", "key2", "key3", "key4"] {
            XCTAssertTrue(NodeIdentity.testOnlyNodeIds.contains(try labKey(name).nodeId), "\(name) is on the deny-list")
        }
    }

    func testDisplayRuleOnlyForStepsThirteenToSixteen() throws {
        let key1 = try labKey("key1")
        let ctx = try Fixture.meshContext(key: key1) { $0.nowMs = 1_790_676_600_000 }
        guard case let .failure(f) = ManifestVerifier.verify(document: try Fixture.seal(try base(), key: key1), context: ctx) else { return XCTFail() }
        XCTAssertEqual(f.code.rawValue, "EXPIRED")
        XCTAssertNotNil(f.displayable)
        let inconsistent = try base().setting(bodyResults + [.at(0), .key("runs"), .key("completed")], to: .int(9))
        if case let .failure(g) = try Fixture.verifyMesh(inconsistent) { XCTAssertNotNil(g.displayable, "step 15 is displayable") } else { XCTFail() }
        if case let .failure(h) = try Fixture.verifyMesh(try base().setting([.key("body"), .key("subject"), .key("nodeId")], to: .string(try labKey("key2").nodeId))) {
            XCTAssertNil(h.displayable, "step 12 is never rendered as content")
        } else { XCTFail() }
        let tier = try Fixture.verifyMesh(try base()) { $0.requiredTier = .a2 }
        if case let .failure(t) = tier { XCTAssertNil(t.displayable) } else { XCTFail() }
    }

    func testDecodedPayloadOverTwoHundredFiftySixKiBIsTooLarge() throws {
        // A valid signature over a 300 KiB payload: refused by size before anything reads it (manifest.md 4.4).
        let key1 = try labKey("key1")
        let big = Array(repeating: UInt8(0x20), count: 300 * 1024)
        let container = try DSSEEnvelope.seal(payload: big, signer: key1.signer, includeSignerSpki: false)
        XCTAssertEqual(code(ManifestVerifier.verify(document: container, context: try Fixture.meshContext(key: key1))), "TOO_LARGE")
    }
}
