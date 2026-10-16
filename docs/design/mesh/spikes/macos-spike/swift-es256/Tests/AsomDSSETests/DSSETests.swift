import XCTest
import Foundation
@testable import AsomDSSE
final class DSSETests: XCTestCase {
    func testPaeMatchesExampleFile() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../../manifest-vectors").standardized
        let pae = try Data(contentsOf: dir.appendingPathComponent("example-pae.bin"))
        let payload = try Data(contentsOf: dir.appendingPathComponent("example-payload.jcs.json"))
        XCTAssertEqual(DSSE.pae(payloadType: "application/vnd.asom.manifest.v1+json", payload: payload), pae)
    }
    func testRejectsNonCanonicalSpki() {
        XCTAssertNil(DSSE.p256Key(spki: Data(repeating: 0, count: 91)))
        XCTAssertNil(DSSE.p256Key(spki: Data(DSSE.p256SpkiPrefix + [0x02] + Array(repeating: 1, count: 32)))) // compressed point
    }
}
