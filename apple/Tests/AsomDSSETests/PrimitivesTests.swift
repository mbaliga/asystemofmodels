import XCTest
import AsomJSON
@testable import AsomDSSE

final class PAETests: XCTestCase {
    func testSpecExample() {
        let pae = PAE.encode(payloadType: "application/vnd.asom.manifest.v1+json", payload: b("{\"a\":1}"))
        XCTAssertEqual(String(decoding: pae, as: UTF8.self), "DSSEv1 37 application/vnd.asom.manifest.v1+json 7 {\"a\":1}")
        XCTAssertEqual(pae.count, 57)
    }

    func testExampleFileFromManifestVectors() throws {
        let payload = try repoBytes("docs/design/mesh/manifest-vectors/example-payload.jcs.json")
        let expected = try repoBytes("docs/design/mesh/manifest-vectors/example-pae.bin")
        XCTAssertGreaterThan(payload.count, 1000)
        XCTAssertEqual(PAE.encode(payloadType: DSSEEnvelope.manifestPayloadType, payload: payload), expected)
    }

    func testLengthsAreBytesNotCharacters() {
        let pae = PAE.encode(payloadType: "t\u{e9}", payload: b("\u{1F600}"))
        XCTAssertEqual(String(decoding: pae, as: UTF8.self), "DSSEv1 3 t\u{e9} 4 \u{1F600}")
        XCTAssertEqual(PAE.encode(payloadType: "", payload: []), b("DSSEv1 0  0 "))
        let big = PAE.encode(payloadType: "x", payload: [UInt8](repeating: 0x41, count: 1000))
        XCTAssertTrue(big.starts(with: b("DSSEv1 1 x 1000 ")))
    }
}

final class SPKITests: XCTestCase {
    private func key1Spki() throws -> [UInt8] { try loadTestKey("key1").spki }

    func testAcceptsBothTestKeys() throws {
        for name in ["key1", "key2"] {
            let k = try loadTestKey(name)
            XCTAssertEqual(k.spki.count, 91)
            XCTAssertNoThrow(try SPKI.validate(k.spki).get())
            XCTAssertEqual(k.signer.spki, k.spki, "the signer derives the same SPKI as the published one")
        }
    }

    func testStrictRejectTable() throws {
        let good = try key1Spki()
        func mutated(_ index: Int, _ value: UInt8) -> [UInt8] { var c = good; c[index] = value; return c }
        let x = Array(good[27..<59])
        let compressed = hexBytes("3039301306072a8648ce3d020106082a8648ce3d030107032200") + [0x02] + x
        let p = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"
        let table: [(String, [UInt8])] = [
            ("empty", []),
            ("truncated by one", Array(good.dropLast())),
            ("trailing byte", good + [0x00]),
            ("trailing garbage", good + [0x30, 0x00]),
            ("compressed point", compressed),
            ("uncompressed marker 0x06", mutated(26, 0x06)),
            ("uncompressed marker 0x02", mutated(26, 0x02)),
            ("wrong outer length", mutated(1, 0x58)),
            ("wrong curve OID byte", mutated(22, 0x08)),
            ("BIT STRING tag changed", mutated(23, 0x04)),
            ("wrong key OID byte", mutated(12, 0x02)),
            ("BIT STRING unused bits not zero", mutated(25, 0x01)),
            ("P-384 header", hexBytes("3076301006072a8648ce3d020106052b81040022036200") + [UInt8](repeating: 4, count: 97)),
            ("y off the curve (last bit flipped)", mutated(90, good[90] ^ 0x01)),
            ("x off the curve", mutated(58, good[58] ^ 0x01)),
            ("all-zero point", Array(good[0..<27]) + [UInt8](repeating: 0, count: 64)),
            ("x equal to p", Array(good[0..<27]) + hexBytes(p) + Array(good[59..<91])),
            ("y equal to p", Array(good[0..<59]) + hexBytes(p)),
            ("same bytes, 90-byte prefix only", Array(good[0..<90])),
        ]
        var exercised = 0
        for (name, spki) in table {
            switch SPKI.validate(spki) {
            case .success: XCTFail("\(name) accepted")
            case let .failure(code): XCTAssertEqual(code, .algUnsupported, name)
            }
            exercised += 1
        }
        XCTAssertEqual(exercised, table.count)
        XCTAssertGreaterThan(exercised, 10)
    }

    func testTheCurveEquationIsCheckedByOurOwnArithmetic() {
        let gx = hexBytes("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
        let gy = hexBytes("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
        XCTAssertTrue(P256Curve.isOnCurve(x: gx, y: gy), "the P-256 base point")
        var badY = gy
        badY[31] ^= 0x01
        XCTAssertFalse(P256Curve.isOnCurve(x: gx, y: badY))
        var badX = gx
        badX[0] ^= 0x80
        XCTAssertFalse(P256Curve.isOnCurve(x: badX, y: gy))
        XCTAssertFalse(P256Curve.isOnCurve(x: [UInt8](repeating: 0, count: 32), y: [UInt8](repeating: 0, count: 32)))
    }

    func testFieldArithmeticIdentities() {
        let p = P256Curve.p
        let one = U256(hex: "0000000000000000000000000000000000000000000000000000000000000001")
        let pMinusOne = p.subtracting(one)
        XCTAssertEqual(P256Curve.mulMod(pMinusOne, pMinusOne), one, "(-1)*(-1) = 1")
        XCTAssertEqual(P256Curve.addMod(pMinusOne, one), U256.zero, "p-1 + 1 = 0")
        XCTAssertEqual(P256Curve.addMod(pMinusOne, pMinusOne), p.subtracting(U256(hex: "0000000000000000000000000000000000000000000000000000000000000002")))
        XCTAssertEqual(P256Curve.subMod(U256.zero, one), pMinusOne)
        XCTAssertEqual(P256Curve.mulMod(pMinusOne, U256.zero), U256.zero)
        XCTAssertEqual(P256Curve.n.halved.adding(P256Curve.n.halved).0.adding(one).0, P256Curve.n, "n is odd: 2*floor(n/2)+1 = n")
        XCTAssertEqual(U256(bigEndian: P256Curve.n.bigEndianBytes), P256Curve.n)
    }
}

final class ES256Tests: XCTestCase {
    func testSignVerifyBothKeysAndHighSTwin() throws {
        var verified = 0
        var highSTwinsAccepted = 0
        for name in ["key1", "key2"] {
            let key = try loadTestKey(name)
            for i in 0..<15 {
                let message = b("message \(name) \(i)")
                let sig = key.signer.sign(message)
                XCTAssertEqual(sig.count, 64)
                XCTAssertTrue(SignatureCodec.isLowS(sig))
                XCTAssertNoThrow(try ES256.verify(spki: key.spki, message: message, signature: sig).get())
                verified += 1
                let twin = SignatureCodec.flipS(sig)
                XCTAssertFalse(SignatureCodec.isLowS(twin))
                XCTAssertNoThrow(try ES256.verify(spki: key.spki, message: message, signature: twin).get(), "high-S is accepted (M02-105)")
                highSTwinsAccepted += 1
            }
        }
        XCTAssertEqual(verified, 30)
        XCTAssertEqual(highSTwinsAccepted, 30)
    }

    func testRejects() throws {
        let key1 = try loadTestKey("key1")
        let key2 = try loadTestKey("key2")
        let message = b("payload")
        let sig = key1.signer.sign(message)
        func code(_ r: Result<Void, RejectCode>) -> String { if case let .failure(c) = r { return c.rawValue } else { return "ok" } }
        var flipped = message
        flipped[0] ^= 1
        var badR = sig
        badR[5] ^= 0x10
        let n = P256Curve.n.bigEndianBytes
        let zero = [UInt8](repeating: 0, count: 32)
        let table: [(String, String)] = [
            ("wrong message", code(ES256.verify(spki: key1.spki, message: flipped, signature: sig))),
            ("wrong key", code(ES256.verify(spki: key2.spki, message: message, signature: sig))),
            ("r bit flipped", code(ES256.verify(spki: key1.spki, message: message, signature: badR))),
            ("r = 0", code(ES256.verify(spki: key1.spki, message: message, signature: zero + Array(sig[32..<64])))),
            ("s = 0", code(ES256.verify(spki: key1.spki, message: message, signature: Array(sig[0..<32]) + zero))),
            ("r = n", code(ES256.verify(spki: key1.spki, message: message, signature: n + Array(sig[32..<64])))),
            ("s = n", code(ES256.verify(spki: key1.spki, message: message, signature: Array(sig[0..<32]) + n))),
            ("s all ones", code(ES256.verify(spki: key1.spki, message: message, signature: Array(sig[0..<32]) + [UInt8](repeating: 0xFF, count: 32)))),
            ("63 bytes", code(ES256.verify(spki: key1.spki, message: message, signature: Array(sig.dropLast())))),
            ("65 bytes", code(ES256.verify(spki: key1.spki, message: message, signature: sig + [0]))),
            ("empty", code(ES256.verify(spki: key1.spki, message: message, signature: []))),
            ("DER instead of raw", code(ES256.verify(spki: key1.spki, message: message, signature: try SignatureCodec.rawToDer(sig).get()))),
            ("bad key", code(ES256.verify(spki: Array(key1.spki.dropLast()), message: message, signature: sig))),
        ]
        let expected = [
            "SIGNATURE_INVALID", "SIGNATURE_INVALID", "SIGNATURE_INVALID", "SIGNATURE_INVALID", "SIGNATURE_INVALID", "SIGNATURE_INVALID",
            "SIGNATURE_INVALID", "SIGNATURE_INVALID", "SIGNATURE_ENCODING", "SIGNATURE_ENCODING", "SIGNATURE_ENCODING", "SIGNATURE_ENCODING",
            "ALG_UNSUPPORTED",
        ]
        XCTAssertEqual(table.count, expected.count)
        for (row, want) in zip(table, expected) { XCTAssertEqual(row.1, want, row.0) }
        XCTAssertEqual(table.count, 13)
    }

    /// The r0 seed vectors M02-001 and M02-002: raw r||s over the JCS bytes, produced by a different implementation
    /// (Python cryptography) and confirmed with OpenSSL. r has a leading zero octet.
    func testSeedSignaturesFromAnotherImplementation() throws {
        let seeds = try repoJSON("docs/design/mesh/conformance-examples/seed-vectors.json")
        let spki = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(seeds.member("KEY")?.member("spki_der_b64")?.stringValue)))
        let m02 = try XCTUnwrap(seeds.member("M02")?.elements)
        let signedText = b(try XCTUnwrap(m02[0].member("signed_text")?.stringValue))
        var accepted = 0
        for vector in m02 {
            let sig = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(vector.member("sig_raw_b64url")?.stringValue)))
            XCTAssertEqual(sig.count, 64)
            XCTAssertEqual(sig[0], 0x00, "leading zero octet of r is kept")
            XCTAssertNoThrow(try ES256.verify(spki: spki, message: signedText, signature: sig).get(), vector.member("id")?.stringValue ?? "")
            accepted += 1
        }
        XCTAssertEqual(accepted, 2)
        let lowS = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(m02[0].member("sig_raw_b64url")?.stringValue)))
        let highS = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(m02[1].member("sig_raw_b64url")?.stringValue)))
        XCTAssertTrue(SignatureCodec.isLowS(lowS))
        XCTAssertFalse(SignatureCodec.isLowS(highS))
        XCTAssertEqual(SignatureCodec.flipS(lowS), highS, "M02-002 is the high-S twin of M02-001")
        XCTAssertEqual(SignatureCodec.normaliseLowS(highS), lowS)

        let m03 = try XCTUnwrap(seeds.member("M03")?.elements)
        var changed = signedText
        changed[changed.firstIndex(of: b("5")[0])!] = b("6")[0]
        guard case let .failure(c1) = ES256.verify(spki: spki, message: changed, signature: lowS) else { return XCTFail("M03-001 accepted") }
        XCTAssertEqual(c1, .signatureInvalid)
        let otherKeySig = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(m03[2].member("sig_raw_b64url")?.stringValue)))
        guard case let .failure(c3) = ES256.verify(spki: spki, message: signedText, signature: otherKeySig) else { return XCTFail("M03-003 accepted") }
        XCTAssertEqual(c3, .signatureInvalid)
    }

    func testEphemeralPerExportKeys() throws {
        var ids = Set<String>()
        for _ in 0..<5 {
            let signer = ES256Signer.generateEphemeral()
            XCTAssertEqual(signer.spki.count, 91)
            XCTAssertNoThrow(try SPKI.validate(signer.spki).get())
            XCTAssertFalse(NodeIdentity.testOnlyNodeIds.contains(NodeIdentity.nodeId(spki: signer.spki)))
            ids.insert(NodeIdentity.nodeId(spki: signer.spki))
            let sig = signer.sign(b("m"))
            XCTAssertNoThrow(try ES256.verify(spki: signer.spki, message: b("m"), signature: sig).get())
        }
        XCTAssertEqual(ids.count, 5, "every export gets a fresh key")
        XCTAssertThrowsError(try ES256Signer(rawScalar: [UInt8](repeating: 0, count: 32)))
        XCTAssertThrowsError(try ES256Signer(rawScalar: [1, 2, 3]))
    }
}

final class SignatureCodecTests: XCTestCase {
    func testProducerAlwaysEmitsLowSAndTheNormaliserActuallyRuns() throws {
        let key = try loadTestKey("key1")
        var highBefore = 0
        var lowAfter = 0
        for i in 0..<300 {
            let message = b("producer \(i)")
            let raw = key.signer.signUnnormalised(message)
            if !SignatureCodec.isLowS(raw) { highBefore += 1 }
            let sig = key.signer.sign(message)
            XCTAssertTrue(SignatureCodec.isLowS(sig), "signature \(i) is high-S")
            XCTAssertNoThrow(try ES256.verify(spki: key.spki, message: message, signature: sig).get())
            lowAfter += 1
        }
        XCTAssertEqual(lowAfter, 300)
        XCTAssertGreaterThan(highBefore, 0, "non-vacuity: the library returned at least one high-S signature to normalise")
    }

    func testLowSBoundary() {
        let r = [UInt8](repeating: 0x11, count: 32)
        let halfN = P256Curve.n.halved
        let one = U256(hex: "0000000000000000000000000000000000000000000000000000000000000001")
        func raw(_ s: U256) -> [UInt8] { r + s.bigEndianBytes }
        XCTAssertTrue(SignatureCodec.isLowS(raw(halfN)), "s = floor(n/2) is low")
        XCTAssertFalse(SignatureCodec.isLowS(raw(halfN.adding(one).0)), "s = floor(n/2)+1 is high")
        XCTAssertEqual(SignatureCodec.normaliseLowS(raw(halfN)), raw(halfN))
        XCTAssertEqual(SignatureCodec.normaliseLowS(raw(halfN.adding(one).0)), raw(halfN), "n - (floor(n/2)+1) = floor(n/2)")
        XCTAssertEqual(SignatureCodec.normaliseLowS(raw(one)), raw(one))
        XCTAssertEqual(SignatureCodec.normaliseLowS(raw(P256Curve.n.subtracting(one))), raw(one))
        for s in [one, halfN, halfN.adding(one).0, P256Curve.n.subtracting(one)] {
            XCTAssertEqual(SignatureCodec.flipS(SignatureCodec.flipS(raw(s))), raw(s), "flip is an involution")
            XCTAssertEqual(Array(SignatureCodec.normaliseLowS(raw(s))[0..<32]), r, "r is unchanged")
        }
    }

    private func rawOf(rHex: String, sHex: String) -> [UInt8] {
        hexBytes(String(repeating: "0", count: 64 - rHex.count) + rHex + String(repeating: "0", count: 64 - sHex.count) + sHex)
    }

    func testRawDerRoundTripsOnShapedValues() throws {
        let cases: [(String, [UInt8], String)] = [
            ("small r and s", rawOf(rHex: "01", sHex: "02"), "3006020101020102"),
            ("high bit set in both (33-byte DER integers)", hexBytes(String(repeating: "ff", count: 64)), "3046022100" + String(repeating: "ff", count: 32) + "022100" + String(repeating: "ff", count: 32)),
            ("leading zero r, next byte below 0x80 (31-byte DER integer)", rawOf(rHex: "7f" + String(repeating: "11", count: 30), sHex: "01"), "3024021f7f" + String(repeating: "11", count: 30) + "020101"),
            ("leading zero r, next byte high (kept as 0x00)", rawOf(rHex: "fe" + String(repeating: "11", count: 30), sHex: "01"), "3025022000fe" + String(repeating: "11", count: 30) + "020101"),
            ("leading zero s", rawOf(rHex: "01", sHex: "7f" + String(repeating: "22", count: 30)), "3024020101021f7f" + String(repeating: "22", count: 30)),
            ("both leading zeros, two zero octets in r", rawOf(rHex: "0102", sHex: "0304"), "3008020201020202" + "0304"),
            ("zero scalars survive the codec (range is checked at verify)", [UInt8](repeating: 0, count: 64), "3006020100020100"),
        ]
        var exercised = 0
        for (name, raw, derHex) in cases {
            let der = try SignatureCodec.rawToDer(raw).get()
            XCTAssertEqual(hexString(der), derHex, name)
            XCTAssertEqual(try SignatureCodec.derToRaw(der).get(), raw, name)
            exercised += 1
        }
        XCTAssertEqual(exercised, cases.count)
    }

    func testRawDerRoundTripsOnRealSignaturesAndFlipsSToLowS() throws {
        let key = try loadTestKey("key2")
        var exercised = 0
        var sawLongInteger = 0
        for i in 0..<300 {
            let message = b("codec \(i)")
            let raw = key.signer.signUnnormalised(message)
            let der = try SignatureCodec.rawToDer(raw).get()
            XCTAssertEqual(try SignatureCodec.derToRaw(der).get(), raw)
            let rLength = Int(der[3])
            if rLength == 33 { sawLongInteger += 1 }
            let low = SignatureCodec.normaliseLowS(try SignatureCodec.derToRaw(der).get())
            XCTAssertTrue(SignatureCodec.isLowS(low))
            XCTAssertNoThrow(try ES256.verify(spki: key.spki, message: message, signature: low).get())
            exercised += 1
        }
        XCTAssertEqual(exercised, 300)
        XCTAssertGreaterThan(sawLongInteger, 0, "non-vacuity: 33-byte DER integers were exercised")
    }

    func testDerSeedFromAnotherImplementation() throws {
        let seeds = try repoJSON("docs/design/mesh/conformance-examples/seed-vectors.json")
        let m03 = try XCTUnwrap(seeds.member("M03")?.elements)
        let der = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(m03[1].member("sig_b64url")?.stringValue)))
        XCTAssertEqual(der.count, 70)
        let m02 = try XCTUnwrap(seeds.member("M02")?.elements)
        let raw = try XCTUnwrap(Base64Strict.decodeEither(try XCTUnwrap(m02[0].member("sig_raw_b64url")?.stringValue)))
        XCTAssertEqual(try SignatureCodec.derToRaw(der).get(), raw)
        XCTAssertEqual(try SignatureCodec.rawToDer(raw).get(), der)
    }

    func testDerRejectTable() {
        let table: [(String, String)] = [
            ("empty", ""),
            ("not a sequence", "3106020101020102"),
            ("long-form length where short is required", "308106020101020102"),
            ("sequence length too long", "3007020101020102"),
            ("sequence length too short", "3005020101020102"),
            ("trailing byte", "300602010102010200"),
            ("trailing byte inside the count", "3007020101020102" + "00"),
            ("integer tag wrong", "3006040101020102"),
            ("second integer tag wrong", "3006020101040102"),
            ("zero-length integer", "3004020002 00".replacingOccurrences(of: " ", with: "")),
            ("negative r", "3006020180020102"),
            ("negative s", "3006020101020180"),
            ("non-minimal r (redundant leading zero)", "300702020001020102"),
            ("non-minimal s (redundant leading zero)", "300702010102020001"),
            ("r of 33 significant bytes", "3026022101" + String(repeating: "11", count: 32) + "020101"),
            ("integer length runs past the end", "3006020901020102"),
            ("only one integer", "3003020101"),
            ("three integers", "3009020101020102020103"),
            ("truncated", "30060201010201"),
        ]
        var exercised = 0
        for (name, hex) in table {
            let der = hexBytes(hex)
            if case let .success(raw) = SignatureCodec.derToRaw(der) { XCTFail("\(name) accepted as \(hexString(raw))") }
            else if case let .failure(code) = SignatureCodec.derToRaw(der) { XCTAssertEqual(code, .signatureEncoding, name) }
            exercised += 1
        }
        XCTAssertEqual(exercised, table.count)
        XCTAssertGreaterThan(exercised, 10)
    }

    func testRawLengthsOtherThan64AreRejected() {
        for length in [0, 1, 32, 63, 65, 71] {
            guard case let .failure(code) = SignatureCodec.rawToDer([UInt8](repeating: 1, count: length)) else {
                return XCTFail("raw length \(length) accepted")
            }
            XCTAssertEqual(code, .signatureEncoding)
        }
    }
}

final class NodeIdentityTests: XCTestCase {
    func testWorkedValuesFromLabSpec() throws {
        let expected: [(String, String, String, String, String, String)] = [
            ("key1", "vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4", "xwwd3xqw7tebmu27", "XWWD-3XQW-7TEB-MU27", "XWWD3XQW7TEBMU27MLPG3CBTVY", "XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY"),
            ("key2", "idgZ8sjS2Fz_gsDBjfMRF3rH08Z6lwdee3U3OoPlVGE", "rhmbt4wi2lmfz74c", "RHMB-T4WI-2LMF-Z74C", "RHMBT4WI2LMFZ74CYDAY34YRC4", "RHMBT-4WI2L-MFZ7-4CYD-AY34-YRC4"),
        ]
        for (name, nodeId, tag, display, export, exportDisplay) in expected {
            let k = try loadTestKey(name)
            XCTAssertEqual(NodeIdentity.nodeId(spki: k.spki), nodeId)
            XCTAssertEqual(k.nodeId, nodeId, "the keys file agrees")
            XCTAssertEqual(nodeId.count, 43)
            XCTAssertEqual(NodeIdentity.nodeTag(spki: k.spki), tag)
            XCTAssertEqual(NodeIdentity.displayFingerprint(spki: k.spki), display)
            XCTAssertEqual(NodeIdentity.exportFingerprint(spki: k.spki), export)
            XCTAssertEqual(export.count, 26)
            XCTAssertEqual(NodeIdentity.exportFingerprintDisplay(spki: k.spki), exportDisplay)
            XCTAssertEqual(NodeIdentity.pin(spki: k.spki).count, 32)
            XCTAssertTrue(NodeIdentity.testOnlyNodeIds.contains(nodeId))
        }
        XCTAssertEqual(NodeIdentity.testOnlyNodeIds.count, 4, "key1, key2 and the lab's per-export key3, key4 (half I0b; checked against the lab keys file in AsomManifestTests)")
    }

    func testTestOnlyKeysFileIsSelfConsistent() throws {
        for name in ["key1", "key2"] {
            let k = try loadTestKey(name)
            XCTAssertEqual(k.signer.spki, k.spki, "\(name): SPKI derived from d_hex equals spki_b64")
        }
    }

    func testBase32AgainstRFC4648Vectors() {
        let table: [(String, String)] = [("", ""), ("f", "MY"), ("fo", "MZXQ"), ("foo", "MZXW6"), ("foob", "MZXW6YQ"), ("fooba", "MZXW6YTB"), ("foobar", "MZXW6YTBOI")]
        var exercised = 0
        for (input, expected) in table {
            XCTAssertEqual(NodeIdentity.base32(b(input)), expected)
            exercised += 1
        }
        XCTAssertEqual(exercised, table.count)
    }

    func testFingerprintComparison() throws {
        let key1 = try loadTestKey("key1")
        let key2 = try loadTestKey("key2")
        let exact = "XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY"
        let accepted = [
            exact, "XWWD3XQW7TEBMU27MLPG3CBTVY", "xwwd3-xqw7t-ebmu-27ml-pg3c-btvy", "xwwd3 xqw7t ebmu 27ml pg3c btvy",
            " XWWD3 - XQW7T EBMU--27ML PG3C BTVY ", "XwWd3XqW7tEbMu27MlPg3CbTvY",
        ]
        var matched = 0
        for typed in accepted {
            XCTAssertTrue(NodeIdentity.fingerprintMatches(typed, spki: key1.spki), typed)
            matched += 1
        }
        XCTAssertEqual(matched, accepted.count)
        let refused: [(String, String)] = [
            ("other key", "RHMBT-4WI2L-MFZ7-4CYD-AY34-YRC4"),
            ("last character changed", "XWWD3-XQW7T-EBMU-27ML-PG3C-BTVZ"),
            ("first character changed", "AWWD3-XQW7T-EBMU-27ML-PG3C-BTVY"),
            ("truncated", "XWWD3-XQW7T-EBMU-27ML-PG3C-BTV"),
            ("prefix only", "XWWD3"),
            ("extra character", "XWWD3-XQW7T-EBMU-27ML-PG3C-BTVYA"),
            ("empty", ""),
            ("display fingerprint (16 chars), not the export fingerprint", "XWWD-3XQW-7TEB-MU27"),
            ("tabs are not deleted", "XWWD3\tXQW7TEBMU27MLPG3CBTVY"),
            ("underscore", "XWWD3_XQW7T_EBMU_27ML_PG3C_BTVY"),
            ("non-ASCII look-alike", "XWWD3-XQW7T-EBMU-27ML-PG3C-BTV\u{FF39}"),
        ]
        for (name, typed) in refused {
            XCTAssertFalse(NodeIdentity.fingerprintMatches(typed, spki: key1.spki), name)
        }
        XCTAssertTrue(NodeIdentity.fingerprintMatches("RHMBT-4WI2L-MFZ7-4CYD-AY34-YRC4", spki: key2.spki))
        XCTAssertEqual(refused.count, 11)
    }

    func testTypedFingerprintNormalisationIsAsciiOnly() {
        XCTAssertEqual(NodeIdentity.normalizeTypedFingerprint("ab-c d"), b("ABCD"))
        XCTAssertNotEqual(NodeIdentity.normalizeTypedFingerprint("\u{131}"), b("I"), "dotless i must not become I")
        XCTAssertNotEqual(NodeIdentity.normalizeTypedFingerprint("\u{212A}"), b("K"), "Kelvin sign must not become K")
    }
}
