import AsomJSON
import XCTest
@testable import AsomDSSE

/// Review fixes CV-2, CV-5, CV-6 and CV-10 (apple/ERRATA.md ERR-FX-CV2, -CV5, -CV6, -CV10).
final class FixCryptoTests: XCTestCase {
    private let prefix = hexBytes("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    private let smallY = hexBytes("459243b9aa581806fe913bce99817ade11ca503c64d9a3c533415c083248fbcc")
    private let smallXPlusP = hexBytes("ffffffff00000001000000000000000000000001000000000000000000000004")

    private func be32(_ small: UInt8) -> [UInt8] { [UInt8](repeating: 0, count: 31) + [small] }

    /// x = 5 is on the curve (y above); x + p is the same point written with a coordinate that is not reduced, so it would be a
    /// second encoding of one key with a different node id.
    func testANonReducedCoordinateIsRefusedAndTheReducedOneIsNot() {
        let canonical = prefix + [0x04] + be32(5) + smallY
        let nonReduced = prefix + [0x04] + smallXPlusP + smallY
        XCTAssertTrue(P256Curve.isOnCurve(x: be32(5), y: smallY))
        XCTAssertFalse(P256Curve.isOnCurve(x: smallXPlusP, y: smallY), "x + p is refused by the range clause")
        XCTAssertNoThrow(try SPKI.validate(canonical).get())
        switch SPKI.validate(nonReduced) {
        case .success: XCTFail("a non-reduced coordinate was accepted")
        case let .failure(code): XCTAssertEqual(code, .algUnsupported)
        }
        XCTAssertNotEqual(NodeIdentity.nodeId(spki: canonical), NodeIdentity.nodeId(spki: nonReduced))
    }

    func testAFingerprintWhoseLengthDiffersByAMultipleOf256IsNotAMatch() throws {
        let k = try loadTestKey("key1")
        let plain = NodeIdentity.exportFingerprint(spki: k.spki)
        XCTAssertEqual(plain.count, 26)
        XCTAssertTrue(NodeIdentity.fingerprintMatches(plain, spki: k.spki))
        for extra in [1, 255, 256, 512, 1024] {
            XCTAssertFalse(NodeIdentity.fingerprintMatches(plain + String(repeating: "A", count: extra), spki: k.spki), "26 + \(extra) characters")
        }
        XCTAssertFalse(NodeIdentity.fingerprintMatches(String(plain.dropLast()), spki: k.spki), "one short")
    }

    func testOnlyAsciiLettersAreUppercasedWhenAFingerprintIsNormalised() throws {
        XCTAssertEqual(NodeIdentity.normalizeTypedFingerprint("a-b c"), Array("ABC".utf8))
        let k = try loadTestKey("key2")
        let plain = NodeIdentity.exportFingerprint(spki: k.spki)
        XCTAssertTrue(plain.contains("I"))
        let folded = String(plain.lowercased().map { $0 == "i" ? "\u{131}" : $0 })
        XCTAssertFalse(NodeIdentity.fingerprintMatches(folded, spki: k.spki), "U+0131 is not an I")
        XCTAssertTrue(NodeIdentity.fingerprintMatches(plain.lowercased(), spki: k.spki))
    }

    func testADefaultContextIsAProductionContext() throws {
        XCTAssertTrue(DSSEContext(mode: .mesh).productionKeys, "fail closed: the TEST-ONLY deny-list applies unless a caller turns it off")
        XCTAssertTrue(DSSEContext(mode: .file).productionKeys)
    }
}
