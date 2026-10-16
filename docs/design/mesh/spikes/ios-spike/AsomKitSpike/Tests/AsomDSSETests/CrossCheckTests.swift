#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation
import XCTest
@testable import AsomDSSE

/// Mirrors manifest-vectors/VerifyDsse.java exactly (regex field pull from each vector's raw document,
/// key1 SPKI, 64-octet P1363 signature) so the Swift result can be diffed line-by-line with crosscheck.out.
final class CrossCheckTests: XCTestCase {
    static let vectorsDir: URL = {
        if let p = ProcessInfo.processInfo.environment["ASOM_VECTORS_DIR"] { return URL(fileURLWithPath: p) }
        // spike layout: mesh/platforms/ios-spike/AsomKitSpike/Tests/AsomDSSETests/<this file>
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("manifest-vectors")
    }()

    func field(_ doc: String, _ name: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: "\"\(name)\":\"([^\"]*)\"") else { return nil }
        guard let m = re.firstMatch(in: doc, range: NSRange(doc.startIndex..., in: doc)),
              let r = Range(m.range(at: 1), in: doc) else { return nil }
        return String(doc[r])
    }

    func testCrossCheckAgainstJCA() throws {
        let key1 = try [UInt8](Data(contentsOf: Self.vectorsDir.appendingPathComponent("test-key1-spki.der")))
        var lines: [String] = []
        for file in ["M02-verify-accept.json", "M03-verify-reject.json"] {
            let data = try Data(contentsOf: Self.vectorsDir.appendingPathComponent(file))
            let obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            for v in obj["vectors"] as! [[String: Any]] {
                let id = v["id"] as! String
                let doc = (v["input"] as! [String: Any])["document"] as! String
                guard let p = field(doc, "payload"), let s = field(doc, "sig"), let pt = field(doc, "payloadType") else {
                    lines.append("\(id) skipped (unparsed)"); continue
                }
                guard let payload = AsomDSSE.b64(p), let sig = AsomDSSE.b64(s) else { lines.append("\(id) base64-error"); continue }
                var ok = false
                if sig.count == 64 { ok = (try? AsomDSSE.verify(spkiDER: key1, payloadType: pt, payload: payload, sigRaw: sig)) ?? false }
                lines.append("\(id) sigValidUnderKey1=\(ok)")
            }
        }
        let out = lines.sorted().joined(separator: "\n")
        print("SWIFT-CROSSCHECK-BEGIN\n\(out)\nSWIFT-CROSSCHECK-END")
        // Expected pattern, copied from crosscheck.out (JCA SHA256withECDSAinP1363Format, JDK 21.0.10):
        let javaFalse: Set<String> = ["M03-101", "M03-102", "M03-103", "M03-113", "M03-118", "M03-122", "M03-125"]
        for line in lines {
            let id = String(line.split(separator: " ")[0])
            XCTAssertEqual(line, "\(id) sigValidUnderKey1=\(!javaFalse.contains(id))", "disagrees with JCA: \(line)")
        }
        XCTAssertEqual(lines.count, 37)
    }

    func testHighSAcceptedAndLowSNormalisation() throws {
        // Producer side: count how often the platform signer emits high-S (the profile requires low-S output).
        let sk = P256.Signing.PrivateKey()
        let spki = [UInt8](sk.publicKey.derRepresentation)
        XCTAssertEqual(spki.count, 91)
        XCTAssertEqual(Array(spki[0..<26]), AsomDSSE.spkiPrefix)
        let msg = AsomDSSE.pae(payloadType: "application/vnd.asom.manifest.v1+json", payload: Array("{}".utf8))
        var high = 0
        let rounds = 400
        for _ in 0..<rounds {
            let raw = [UInt8](try sk.signature(for: msg).rawRepresentation)
            XCTAssertEqual(raw.count, 64)
            if AsomDSSE.isHighS(raw) { high += 1 }
            let low = AsomDSSE.lowS(raw)
            XCTAssertFalse(AsomDSSE.isHighS(low))
            XCTAssertTrue(try AsomDSSE.verify(spkiDER: spki, payloadType: "application/vnd.asom.manifest.v1+json",
                                              payload: Array("{}".utf8), sigRaw: low))
            // Verifier side: the high-S twin must also verify (profile: verifiers accept high-S).
            XCTAssertTrue(try AsomDSSE.verify(spkiDER: spki, payloadType: "application/vnd.asom.manifest.v1+json",
                                              payload: Array("{}".utf8), sigRaw: AsomDSSE.flipS(raw)))
        }
        print("SIGNER-HIGH-S \(high)/\(rounds)")
    }

    func testStrictSPKIRejectsCompressedAndTrailing() throws {
        let spki = [UInt8](P256.Signing.PrivateKey().publicKey.derRepresentation)
        XCTAssertThrowsError(try AsomDSSE.publicKey(spkiDER: spki + [0x00]))
        var compressed = spki; compressed[26] = 0x02
        XCTAssertThrowsError(try AsomDSSE.publicKey(spkiDER: compressed))
        XCTAssertNoThrow(try AsomDSSE.publicKey(spkiDER: spki))
    }
}
