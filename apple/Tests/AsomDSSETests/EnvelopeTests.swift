import XCTest
import AsomJSON
@testable import AsomDSSE

final class EnvelopeTests: XCTestCase {
    private let payload = b("{\"hello\":\"world\",\"n\":1}")
    private let manifestType = DSSEEnvelope.manifestPayloadType

    private func pae(_ payload: [UInt8], _ type: String? = nil) -> [UInt8] {
        PAE.encode(payloadType: type ?? manifestType, payload: payload)
    }

    /// A container signed by `key`, ready to be broken by `edit`. Signs over `signedPayload`, carries `payload`.
    private func container(
        _ key: TestKey, payload carried: [UInt8]? = nil, signedPayload: [UInt8]? = nil, signedType: String? = nil,
        edit: (inout ContainerBuilder) -> Void = { _ in }
    ) throws -> [UInt8] {
        let carriedPayload = carried ?? payload
        var builder = ContainerBuilder(payload: carriedPayload)
        builder.signatures = [key.signer.sign(pae(signedPayload ?? carriedPayload, signedType))]
        builder.keyid = key.nodeId
        builder.signerSpkiB64 = Base64Strict.encode(key.spki)
        edit(&builder)
        return try builder.build()
    }

    private func code(_ result: Result<DSSEVerified, RejectCode>) -> String {
        if case let .failure(c) = result { return c.rawValue }
        return "ok"
    }

    private func mesh(_ key: TestKey, production: Bool = false) -> DSSEContext {
        DSSEContext(mode: .mesh, pinnedSpki: key.spki, productionKeys: production)
    }

    func testMeshAcceptAndTheVerifiedFields() throws {
        let key1 = try loadTestKey("key1")
        let doc = try DSSEEnvelope.seal(payload: payload, signer: key1.signer)
        let verified = try DSSEEnvelope.verify(document: doc, context: mesh(key1)).get()
        XCTAssertEqual(verified.pin, .pinned)
        XCTAssertEqual(verified.nodeId, key1.nodeId)
        XCTAssertEqual(verified.payload, payload)
        XCTAssertEqual(verified.payloadType, manifestType)
        XCTAssertEqual(verified.object.member("n")?.intValue, 1)
        XCTAssertEqual(verified.signerSpki, key1.spki)
    }

    func testAcceptVariants() throws {
        let key1 = try loadTestKey("key1")
        var accepted = 0
        func expectOk(_ name: String, _ doc: [UInt8], _ context: DSSEContext? = nil) {
            XCTAssertEqual(code(DSSEEnvelope.verify(document: doc, context: context ?? mesh(key1))), "ok", name)
            accepted += 1
        }
        let low = key1.signer.sign(pae(payload))
        expectOk("high-S twin", try container(key1) { $0.signatures = [SignatureCodec.flipS(low)] })
        expectOk("url-safe unpadded base64", try container(key1) { $0.encode = Base64Strict.encodeURL })
        expectOk("no keyid", try container(key1) { $0.keyid = nil })
        expectOk("no signer.spki in MESH", try container(key1) { $0.signerSpkiB64 = nil })
        expectOk("unknown container members are ignored", try container(key1) { $0.extraMembers = [JMember(name: "zzz", value: .array([.int(1)]))] })
        let plain = try container(key1)
        expectOk("trailing whitespace is not trailing data", plain + b(" \n\t\r"))
        expectOk("leading whitespace", b("\n ") + plain)
        var padded = plain
        padded.append(contentsOf: [UInt8](repeating: 0x20, count: 524_288 - plain.count))
        XCTAssertEqual(padded.count, 524_288)
        expectOk("exactly 524288 bytes", padded)
        XCTAssertEqual(accepted, 8)
    }

    func testRejectTable() throws {
        let key1 = try loadTestKey("key1")
        let key2 = try loadTestKey("key2")
        let mesh1 = mesh(key1)
        let plain = try container(key1)
        let noKeyid = try container(key1) { $0.keyid = nil }
        let compressedSpki = hexBytes("3039301306072a8648ce3d020106082a8648ce3d030107032200") + [0x02] + Array(key1.spki[27..<59])
        let sig1 = key1.signer.sign(pae(payload))
        let n = P256Curve.n.bigEndianBytes
        let derSig1 = try SignatureCodec.rawToDer(sig1).get()
        let deep17 = b(String(repeating: "[", count: 17) + String(repeating: "]", count: 17))
        func signedPayload(_ p: [UInt8]) throws -> [UInt8] { try container(key1, payload: p) }
        func withType(_ t: String) throws -> [UInt8] { try container(key1, signedType: t) { $0.payloadType = t } }
        func edited(_ edit: (inout ContainerBuilder) -> Void) throws -> [UInt8] { try container(key1, edit: edit) }

        let table: [(String, [UInt8], DSSEContext, String)] = [
            ("too large", [UInt8](repeating: 0x20, count: 524_289), mesh1, "TOO_LARGE"),
            ("empty", [], mesh1, "MALFORMED_JSON"),
            ("container is not JSON", b("not json"), mesh1, "MALFORMED_JSON"),
            ("container duplicate member", b("{\"asomCapabilityManifest\":1,") + Array(plain.dropFirst()), mesh1, "DUPLICATE_KEY"),
            ("container float", b("{\"asomCapabilityManifest\":1.0}"), mesh1, "NON_INTEGER_NUMBER"),
            ("container number range", b("{\"asomCapabilityManifest\":9007199254740992}"), mesh1, "NUMBER_RANGE"),
            ("container invalid UTF-8", [0x7B, 0x22, 0xFF, 0x22, 0x3A, 0x31, 0x7D], mesh1, "INVALID_UNICODE"),
            ("trailing data (M03-123)", plain + b(" {}"), mesh1, "TRAILING_DATA"),
            ("trailing byte", plain + b("x"), mesh1, "TRAILING_DATA"),
            ("container is an array", b("[]"), mesh1, "CONTAINER_INVALID"),
            ("container is a string", b("\"x\""), mesh1, "CONTAINER_INVALID"),
            ("no dsse", b("{\"asomCapabilityManifest\":1}"), mesh1, "CONTAINER_INVALID"),
            ("dsse is a string", try edited { $0.dsseOverride = .string("x") }, mesh1, "CONTAINER_INVALID"),
            ("dsse.payload is a number", try edited { $0.dsseOverride = .object([JMember(name: "payload", value: .int(1)), JMember(name: "payloadType", value: .string(self.manifestType)), JMember(name: "signatures", value: .array([]))]) }, mesh1, "CONTAINER_INVALID"),
            ("dsse.payloadType missing", try edited { $0.dsseOverride = .object([JMember(name: "payload", value: .string("")), JMember(name: "signatures", value: .array([]))]) }, mesh1, "CONTAINER_INVALID"),
            ("signatures is an object", try edited { $0.signaturesValueOverride = .object([]) }, mesh1, "CONTAINER_INVALID"),
            ("signature entry is a string", try edited { $0.signaturesValueOverride = .array([.string("x")]) }, mesh1, "CONTAINER_INVALID"),
            ("signature entry has no sig", try edited { $0.signaturesValueOverride = .array([.object([JMember(name: "keyid", value: .string("k"))])]) }, mesh1, "CONTAINER_INVALID"),
            ("keyid is a number", try edited { $0.signaturesValueOverride = .array([.object([JMember(name: "keyid", value: .int(1)), JMember(name: "sig", value: .string(""))])]) }, mesh1, "CONTAINER_INVALID"),
            ("FILE signer.spki is a number", try edited { $0.signerSpkiB64 = nil; $0.extraMembers = [JMember(name: "signer", value: .object([JMember(name: "spki", value: .int(1))]))] }, DSSEContext(mode: .file, productionKeys: false), "CONTAINER_INVALID"),
            ("version 2", try edited { $0.version = .int(2) }, mesh1, "CONTAINER_VERSION_UNKNOWN"),
            ("version is a string", try edited { $0.version = .string("1") }, mesh1, "CONTAINER_VERSION_UNKNOWN"),
            ("version is true", try edited { $0.version = .bool(true) }, mesh1, "CONTAINER_VERSION_UNKNOWN"),
            ("version absent", try edited { $0.version = nil }, mesh1, "CONTAINER_VERSION_UNKNOWN"),
            ("key-rollover type (M03-113)", try withType("application/vnd.asom.key-rollover.v1+json"), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("text/plain", try withType("text/plain"), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("empty type", try withType(""), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("trailing space in type", try withType(self.manifestType + " "), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("v0", try withType("application/vnd.asom.manifest.v0+json"), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("v01", try withType("application/vnd.asom.manifest.v01+json"), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("v with no number", try withType("application/vnd.asom.manifest.v+json"), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("v1x", try withType("application/vnd.asom.manifest.v1x+json"), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("v-2", try withType("application/vnd.asom.manifest.v-2+json"), mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("v2 (M03-118)", try withType("application/vnd.asom.manifest.v2+json"), mesh1, "SCHEMA_MAJOR_UNKNOWN"),
            ("v10", try withType("application/vnd.asom.manifest.v10+json"), mesh1, "SCHEMA_MAJOR_UNKNOWN"),
            ("v12345678901234567890", try withType("application/vnd.asom.manifest.v12345678901234567890+json"), mesh1, "SCHEMA_MAJOR_UNKNOWN"),
            ("no signatures", try edited { $0.signatures = [] }, mesh1, "SIGNATURE_COUNT"),
            ("two signatures (M03-117)", try edited { $0.signatures = [sig1, sig1] }, mesh1, "SIGNATURE_COUNT"),
            ("type is checked before count", try edited { $0.payloadType = "text/plain"; $0.signatures = [sig1, sig1] }, mesh1, "PAYLOAD_TYPE_UNSUPPORTED"),
            ("count is checked before encoding", try edited { $0.signatures = [sig1, sig1]; $0.payloadTextOverride = "!!" }, mesh1, "SIGNATURE_COUNT"),
            ("payload has a space", try edited { $0.payloadTextOverride = "eyJh IjoxfQ==" }, mesh1, "ENCODING"),
            ("payload has a bad character", try edited { $0.payloadTextOverride = "eyJhIjoxfQ!!" }, mesh1, "ENCODING"),
            ("sig mixes alphabets", try edited { $0.sigTextOverride = "+_" + Base64Strict.encode(sig1).dropFirst(2) }, mesh1, "ENCODING"),
            ("sig has non-zero unused bits (M03-125)", try edited { $0.sigTextOverride = String(Base64Strict.encode(sig1).dropLast(3)) + "B==" }, mesh1, "ENCODING"),
            ("sig is not base64", try edited { $0.sigTextOverride = "not base64!" }, mesh1, "ENCODING"),
            ("sig of 63 bytes", try edited { $0.signatures = [Array(sig1.dropLast())] }, mesh1, "SIGNATURE_ENCODING"),
            ("sig of 65 bytes", try edited { $0.signatures = [sig1 + [0]] }, mesh1, "SIGNATURE_ENCODING"),
            ("DER signature (M03-102)", try edited { $0.signatures = [derSig1] }, mesh1, "SIGNATURE_ENCODING"),
            ("empty signature", try edited { $0.signatures = [[]] }, mesh1, "SIGNATURE_ENCODING"),
            ("MESH without a pinned key", plain, DSSEContext(mode: .mesh, productionKeys: false), "KEY_NOT_PINNED"),
            ("signed by key2, keyid key2, pinned key1 (M03-103)", try container(key2), mesh1, "KEY_NOT_PINNED"),
            ("signed by key1, keyid names key2", try edited { $0.keyid = key2.nodeId }, mesh1, "KEY_NOT_PINNED"),
            ("keyid differs by one character", try edited { $0.keyid = String(key1.nodeId.dropLast()) + "A" }, mesh1, "KEY_NOT_PINNED"),
            ("FILE without signer.spki (M03-127)", try edited { $0.signerSpkiB64 = nil }, DSSEContext(mode: .file, productionKeys: false), "KEY_NOT_PINNED"),
            ("FILE keyid disagrees with signer.spki", try edited { $0.keyid = key2.nodeId }, DSSEContext(mode: .file, productionKeys: false), "KEY_NOT_PINNED"),
            ("key is checked before the signature", try edited { $0.keyid = key2.nodeId; $0.signatures = [sig1.map { ~$0 }] }, mesh1, "KEY_NOT_PINNED"),
            ("FILE signer.spki is not base64", try edited { $0.signerSpkiB64 = "***" }, DSSEContext(mode: .file, productionKeys: false), "ENCODING"),
            ("encoding is checked before key selection", try edited { $0.signerSpkiB64 = nil; $0.payloadTextOverride = "!" }, DSSEContext(mode: .file, productionKeys: false), "ENCODING"),
            ("pinned key is compressed", noKeyid, DSSEContext(mode: .mesh, pinnedSpki: compressedSpki, productionKeys: false), "ALG_UNSUPPORTED"),
            ("pinned key is compressed, keyid present: the keyid is checked first", plain, DSSEContext(mode: .mesh, pinnedSpki: compressedSpki, productionKeys: false), "KEY_NOT_PINNED"),
            ("pinned key has a trailing byte", noKeyid, DSSEContext(mode: .mesh, pinnedSpki: key1.spki + [0], productionKeys: false), "ALG_UNSUPPORTED"),
            ("FILE signer.spki compressed (M03-136)", try edited { $0.signerSpkiB64 = Base64Strict.encode(compressedSpki); $0.keyid = nil }, DSSEContext(mode: .file, productionKeys: false), "ALG_UNSUPPORTED"),
            ("FILE signer.spki with trailing byte (M03-137)", try edited { $0.signerSpkiB64 = Base64Strict.encode(key1.spki + [0]); $0.keyid = nil }, DSSEContext(mode: .file, productionKeys: false), "ALG_UNSUPPORTED"),
            ("production mode, key1 (M03-141)", plain, mesh(key1, production: true), "TEST_ONLY_KEY"),
            ("production mode, key2 in FILE mode", try container(key2), DSSEContext(mode: .file, productionKeys: true), "TEST_ONLY_KEY"),
            ("FILE fingerprint of another key", plain, DSSEContext(mode: .file, comparedFingerprint: NodeIdentity.exportFingerprintDisplay(spki: key2.spki), compareMethod: .typed, productionKeys: false), "FINGERPRINT_MISMATCH"),
            ("FILE fingerprint is checked before the signature", try edited { $0.signatures = [sig1.map { ~$0 }] }, DSSEContext(mode: .file, comparedFingerprint: NodeIdentity.exportFingerprintDisplay(spki: key2.spki), compareMethod: .qr, productionKeys: false), "FINGERPRINT_MISMATCH"),
            ("FILE empty typed fingerprint", plain, DSSEContext(mode: .file, comparedFingerprint: "", compareMethod: .typed, productionKeys: false), "FINGERPRINT_MISMATCH"),
            ("payload changed after signing (M03-101)", try container(key1, payload: b("{\"hello\":\"world\",\"n\":2}"), signedPayload: payload), mesh1, "SIGNATURE_INVALID"),
            ("signed under another type", try container(key1, signedType: "application/vnd.asom.key-rollover.v1+json"), mesh1, "SIGNATURE_INVALID"),
            ("signed by key2, no keyid, pinned key1", try container(key2) { $0.keyid = nil }, mesh1, "SIGNATURE_INVALID"),
            ("MESH ignores the container's signer.spki", try container(key2) { $0.keyid = nil }, mesh1, "SIGNATURE_INVALID"),
            ("r = 0", try edited { $0.signatures = [[UInt8](repeating: 0, count: 32) + Array(sig1[32..<64])] }, mesh1, "SIGNATURE_INVALID"),
            ("s = n", try edited { $0.signatures = [Array(sig1[0..<32]) + n] }, mesh1, "SIGNATURE_INVALID"),
            ("signature is checked before canonical form", try container(key1, payload: b("{ \"a\":1 }"), signedPayload: payload), mesh1, "SIGNATURE_INVALID"),
            ("pretty-printed payload, validly signed (M03-104)", try signedPayload(b("{\n \"a\": 1\n}")), mesh1, "NON_CANONICAL"),
            ("unsorted payload, validly signed", try signedPayload(b("{\"b\":1,\"a\":2}")), mesh1, "NON_CANONICAL"),
            ("payload with escapes that JCS would not emit", try signedPayload(b("{\"a\":\"\\u0041\"}")), mesh1, "NON_CANONICAL"),
            ("payload with duplicate member (M03-105)", try signedPayload(b("{\"a\":1,\"a\":2}")), mesh1, "DUPLICATE_KEY"),
            ("payload with a float (M03-106)", try signedPayload(b("{\"a\":1.5}")), mesh1, "NON_INTEGER_NUMBER"),
            ("payload with 2^53", try signedPayload(b("{\"a\":9007199254740992}")), mesh1, "NUMBER_RANGE"),
            ("payload with trailing data", try signedPayload(b("{\"a\":1} x")), mesh1, "TRAILING_DATA"),
            ("payload nested 17 deep (M03-128)", try signedPayload(deep17), mesh1, "MALFORMED_JSON"),
            ("payload with a lone surrogate", try signedPayload(b("{\"a\":\"\\ud800\"}")), mesh1, "INVALID_UNICODE"),
            ("payload with invalid UTF-8", try signedPayload([0x7B, 0x22, 0xC0, 0x80, 0x22, 0x3A, 0x31, 0x7D]), mesh1, "INVALID_UNICODE"),
            ("payload with a BOM", try signedPayload([0xEF, 0xBB, 0xBF] + b("{}")), mesh1, "MALFORMED_JSON"),
            ("empty payload", try signedPayload([]), mesh1, "MALFORMED_JSON"),
        ]

        var exercised = 0
        var perCode: [String: Int] = [:]
        for (name, doc, context, expected) in table {
            XCTAssertEqual(code(DSSEEnvelope.verify(document: doc, context: context)), expected, name)
            exercised += 1
            perCode[expected, default: 0] += 1
        }
        XCTAssertEqual(exercised, table.count)
        let required = [
            "TOO_LARGE", "MALFORMED_JSON", "INVALID_UNICODE", "NON_INTEGER_NUMBER", "NUMBER_RANGE", "DUPLICATE_KEY", "TRAILING_DATA",
            "CONTAINER_VERSION_UNKNOWN", "CONTAINER_INVALID", "SCHEMA_MAJOR_UNKNOWN", "PAYLOAD_TYPE_UNSUPPORTED", "SIGNATURE_COUNT",
            "ENCODING", "SIGNATURE_ENCODING", "KEY_NOT_PINNED", "ALG_UNSUPPORTED", "TEST_ONLY_KEY", "FINGERPRINT_MISMATCH",
            "SIGNATURE_INVALID", "NON_CANONICAL",
        ]
        for c in required { XCTAssertGreaterThan(perCode[c] ?? 0, 0, "reject code \(c) was never exercised") }
    }

    func testFileMode() throws {
        var accepted = 0
        for _ in 0..<3 {
            let export = ES256Signer.generateEphemeral()
            let doc = try DSSEEnvelope.seal(payload: payload, signer: export)
            let fingerprint = NodeIdentity.exportFingerprintDisplay(spki: export.spki)
            let notCompared = try DSSEEnvelope.verify(document: doc, context: DSSEContext(mode: .file, productionKeys: true)).get()
            XCTAssertEqual(notCompared.pin, .signerUnverified)
            let typed = try DSSEEnvelope.verify(document: doc, context: DSSEContext(mode: .file, comparedFingerprint: fingerprint, compareMethod: .typed, productionKeys: true)).get()
            XCTAssertEqual(typed.pin, .pinnedByFingerprint(.typed))
            let scanned = try DSSEEnvelope.verify(document: doc, context: DSSEContext(
                mode: .file, comparedFingerprint: fingerprint.lowercased().replacingOccurrences(of: "-", with: " "), compareMethod: .qr, productionKeys: false
            )).get()
            XCTAssertEqual(scanned.pin, .pinnedByFingerprint(.qr))
            XCTAssertEqual(scanned.nodeId, NodeIdentity.nodeId(spki: export.spki))
            accepted += 1
        }
        XCTAssertEqual(accepted, 3)
    }

    func testMeshIgnoresFingerprintAndFileIgnoresPinnedKey() throws {
        let key1 = try loadTestKey("key1")
        let key2 = try loadTestKey("key2")
        let doc = try DSSEEnvelope.seal(payload: payload, signer: key1.signer)
        let mesh = try DSSEEnvelope.verify(document: doc, context: DSSEContext(mode: .mesh, pinnedSpki: key1.spki, comparedFingerprint: "WRONG", compareMethod: .typed, productionKeys: false)).get()
        XCTAssertEqual(mesh.pin, .pinned)
        let file = try DSSEEnvelope.verify(document: doc, context: DSSEContext(mode: .file, pinnedSpki: key2.spki, productionKeys: false)).get()
        XCTAssertEqual(file.signerSpki, key1.spki, "FILE reads the key from the container, not from the pinned key")
        XCTAssertEqual(file.pin, .signerUnverified)
    }

    func testSealedContainerIsCanonicalAndUsesTheSpecShape() throws {
        let key1 = try loadTestKey("key1")
        let doc = try DSSEEnvelope.seal(payload: payload, signer: key1.signer)
        XCTAssertTrue(JCS.isCanonical(doc))
        guard case let .success(value) = StrictJSON.parse(doc) else { return XCTFail("sealed container does not parse") }
        XCTAssertEqual(value.members?.map(\.name), ["asomCapabilityManifest", "dsse", "evidence", "signer"])
        XCTAssertEqual(value.member("dsse")?.members?.map(\.name), ["payload", "payloadType", "signatures"])
        XCTAssertEqual(value.member("dsse")?.member("signatures")?.elements?.first?.member("keyid")?.stringValue, key1.nodeId)
        let sig = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(value.member("dsse")?.member("signatures")?.elements?.first?.member("sig")?.stringValue)))
        XCTAssertEqual(sig.count, 64)
        XCTAssertTrue(SignatureCodec.isLowS(sig))
        XCTAssertEqual(Base64Strict.decodeEither(try XCTUnwrap(value.member("signer")?.member("spki")?.stringValue)), key1.spki)
        let without = try DSSEEnvelope.seal(payload: payload, signer: key1.signer, includeSignerSpki: false)
        guard case let .success(bare) = StrictJSON.parse(without) else { return XCTFail("parse") }
        XCTAssertNil(bare.member("signer"))
    }

    func testSignatureLayer() throws {
        let key1 = try loadTestKey("key1")
        let key2 = try loadTestKey("key2")
        let sig = key1.signer.sign(pae(payload))
        var seen = Set<String>()
        let derSig = try SignatureCodec.rawToDer(sig).get()
        func label(_ l: SignatureLayer) -> String {
            switch l {
            case .valid: return "valid"
            case .invalid: return "invalid"
            case let .rejected(c): return "rejected \(c.rawValue)"
            }
        }
        let table: [(String, [UInt8], [UInt8], String)] = try [
            ("valid", container(key1), key1.spki, "valid"),
            ("other key", container(key1), key2.spki, "invalid"),
            ("two signatures: the first is used (M03-117)", container(key1) { $0.signatures = [sig, sig] }, key1.spki, "valid"),
            ("unsupported payload type still verifies under its own type (M03-114)", container(key1, signedType: "text/plain") { $0.payloadType = "text/plain" }, key1.spki, "valid"),
            ("changed payload (M03-101)", container(key1, payload: b("{\"hello\":\"world\",\"n\":3}"), signedPayload: payload), key1.spki, "invalid"),
            ("DER", container(key1) { $0.signatures = [derSig] }, key1.spki, "invalid"),
            ("trailing data (M03-123)", container(key1) + b(" {}"), key1.spki, "rejected TRAILING_DATA"),
            ("non-canonical base64 (M03-125)", container(key1) { $0.sigTextOverride = String(Base64Strict.encode(sig).dropLast(3)) + "B==" }, key1.spki, "rejected ENCODING"),
            ("bad key", container(key1), Array(key1.spki.dropLast()), "rejected ALG_UNSUPPORTED"),
            ("no container", b("[]"), key1.spki, "rejected CONTAINER_INVALID"),
        ]
        for (name, doc, spki, expected) in table {
            XCTAssertEqual(label(DSSEEnvelope.signatureLayer(document: doc, spki: spki)), expected, name)
            seen.insert(expected)
        }
        XCTAssertGreaterThanOrEqual(seen.count, 6)
    }
}
