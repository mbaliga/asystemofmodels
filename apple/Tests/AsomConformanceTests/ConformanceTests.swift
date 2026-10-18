import Foundation
import XCTest
import AsomJSON
@testable import AsomConformanceKit

final class ConformanceTests: XCTestCase {
    private func vectorDirectory(file: StaticString = #filePath) throws -> String {
        let here = URL(fileURLWithPath: "\(file)").deletingLastPathComponent().path
        let environment = ProcessInfo.processInfo.environment
        // The r0 tests only read an r0 directory; an r3 directory (lab/conformance, VERSION 0.2.0) belongs to R3ConformanceTests.
        if let fromEnv = environment["ASOM_CONFORMANCE_DIR"], !fromEnv.isEmpty, !R3.isR3Directory(fromEnv) { return fromEnv }
        return try XCTUnwrap(Conformance.r0Directory(startingAt: here), "docs/design/mesh/manifest-vectors not found")
    }

    private func evaluate(_ family: String) throws -> [VectorResult] {
        try XCTUnwrap(try Conformance.evaluate(family: family, in: try vectorDirectory()), "\(family) vector file not found")
    }

    func testEveryVectorAgreesWithItsOwnExpectation() throws {
        var total = 0
        var decided = 0, passed = 0, retired = 0
        var decidedCodes = Set<String>()
        for family in ["M02", "M03"] {
            for r in try evaluate(family) {
                XCTAssertNil(Conformance.disagreement(r), r.id)
                total += 1
                switch r.classification {
                case let .decided(v):
                    decided += 1
                    if case let .reject(c) = v { decidedCodes.insert(c.rawValue) }
                case .passedDsseLayer: passed += 1
                case .retiredR3: retired += 1
                }
            }
        }
        XCTAssertEqual(total, 37, "the r0 M02 and M03 vector count")
        XCTAssertEqual(decided, 14, "vectors settled at verifier steps 1 to 10")
        XCTAssertEqual(passed, 20, "vectors that pass steps 1 to 10 and wait for the half I0b verifier")
        XCTAssertEqual(retired, 3, "M02-102, M02-103, M03-122 (TOFU, retired by LAB_SPEC section 4.9)")
        XCTAssertEqual(decidedCodes, [
            "SIGNATURE_INVALID", "SIGNATURE_ENCODING", "KEY_NOT_PINNED", "NON_CANONICAL", "DUPLICATE_KEY", "NON_INTEGER_NUMBER",
            "PAYLOAD_TYPE_UNSUPPORTED", "SIGNATURE_COUNT", "SCHEMA_MAJOR_UNKNOWN", "TRAILING_DATA", "ENCODING", "MALFORMED_JSON",
        ])
    }

    func testLinesFormat() throws {
        var log: [String] = []
        let lines = try Conformance.lines(families: ["M02", "M03"], in: try vectorDirectory()) { log.append($0) }
        XCTAssertEqual(lines.count, 14)
        XCTAssertEqual(lines, lines.sorted(), "sorted by id")
        let pattern = try NSRegularExpression(pattern: "^M0[23]-[0-9]{3} (ok|reject [A-Z_]+)$")
        for line in lines {
            XCTAssertEqual(pattern.numberOfMatches(in: line, range: NSRange(line.startIndex..., in: line)), 1, line)
        }
        XCTAssertTrue(lines.contains("M03-123 reject TRAILING_DATA"))
        XCTAssertTrue(lines.contains("M03-104 reject NON_CANONICAL"))
        XCTAssertFalse(lines.contains { $0.hasPrefix("M02-") }, "no accept vector is printed: ok needs steps 11 and later")
        XCTAssertFalse(lines.contains { $0.hasSuffix(" ok") })
        XCTAssertEqual(log.count, 2)
    }

    func testFamiliesWithoutAnImplementationAreReportedNotImplemented() throws {
        var log: [String] = []
        let lines = try Conformance.lines(families: ["M01", "M05", "M06"], in: try vectorDirectory()) { log.append($0) }
        XCTAssertTrue(lines.isEmpty)
        XCTAssertEqual(log.count, 3)
        XCTAssertTrue(log.allSatisfy { $0.contains("not-implemented") })
    }

    /// The JCA column of manifest-vectors/crosscheck.out (VerifyDsse.java) against this lane's signature layer.
    func testSignatureLayerAgainstTheJcaColumn() throws {
        let dir = try vectorDirectory()
        let path = dir + "/crosscheck.out"
        guard let data = FileManager.default.contents(atPath: path) else {
            throw XCTSkip("no crosscheck.out in \(dir): the JCA column is only committed with the r0 vectors")
        }
        let jca = String(decoding: data, as: UTF8.self).split(separator: "\n").filter { $0.contains("sigValidUnderKey1=") }.map(String.init)
        let ours = try Conformance.signatureLayerLines(in: dir)
        XCTAssertEqual(jca.count, 37)
        XCTAssertEqual(ours.count, 37)
        var agree = 0
        var differences: [String] = []
        for (a, b) in zip(jca, ours) {
            if a == b { agree += 1 } else { differences.append("\(a) | \(b)") }
        }
        XCTAssertEqual(agree, 35)
        XCTAssertEqual(differences, [
            "M03-123 sigValidUnderKey1=true | M03-123 skipped (TRAILING_DATA)",
            "M03-125 sigValidUnderKey1=false | M03-125 skipped (ENCODING)",
        ], "the two explained differences and nothing else")
    }

    func testUnsupportedConfVersionIsRefusedNotGuessed() throws {
        let dir = NSTemporaryDirectory() + "asom-conf-\(UUID().uuidString)"
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: dir) }
        try Data("{\"family\":\"M03\",\"confVersion\":\"0.2.0\",\"vectors\":[]}".utf8).write(to: URL(fileURLWithPath: dir + "/M03-verify-reject.json"))
        XCTAssertThrowsError(try Conformance.evaluate(family: "M03", in: dir)) {
            XCTAssertTrue("\($0)".contains("0.2.0"))
        }
        let unmapped = "{\"family\":\"M02\",\"confVersion\":\"0.1.0\",\"vectors\":[{\"id\":\"M02-999\",\"input\":{\"document\":\"{}\","
            + "\"context\":{\"mode\":\"MESH\"}},\"expect\":{\"ok\":{}}}]}"
        try Data(unmapped.utf8).write(to: URL(fileURLWithPath: dir + "/M02-verify-accept.json"))
        XCTAssertThrowsError(try Conformance.evaluate(family: "M02", in: dir)) {
            XCTAssertTrue("\($0)".contains("no r3 mapping"))
        }
    }

    func testDirectoryResolution() throws {
        let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
        XCTAssertEqual(Conformance.resolveDirectory(explicit: "/x", environment: ["ASOM_CONFORMANCE_DIR": "/y"], startingAt: here), "/x")
        XCTAssertEqual(Conformance.resolveDirectory(explicit: nil, environment: ["ASOM_CONFORMANCE_DIR": "/y"], startingAt: here), "/y")
        let found = try XCTUnwrap(Conformance.resolveDirectory(explicit: nil, environment: [:], startingAt: here))
        XCTAssertTrue(found.hasSuffix("lab/conformance") || found.hasSuffix("docs/design/mesh/manifest-vectors"), found)
    }

    func testDisagreementDetection() {
        func result(_ e: Expectation, _ c: Classification) -> VectorResult {
            VectorResult(id: "X", family: "M03", expected: e, classification: c)
        }
        XCTAssertNil(Conformance.disagreement(result(.reject(.signatureInvalid), .decided(.reject(.signatureInvalid)))))
        XCTAssertNotNil(Conformance.disagreement(result(.reject(.signatureInvalid), .decided(.reject(.encoding)))))
        XCTAssertNotNil(Conformance.disagreement(result(.reject(.signatureInvalid), .passedDsseLayer)), "a DSSE-owned code cannot be waited for")
        XCTAssertNil(Conformance.disagreement(result(.reject(.schemaMajorUnknown), .passedDsseLayer)), "step 11 can raise it too")
        XCTAssertNil(Conformance.disagreement(result(.reject(RejectCode("EXPIRED")), .passedDsseLayer)))
        XCTAssertNotNil(Conformance.disagreement(result(.ok, .decided(.reject(.keyNotPinned)))))
        XCTAssertNil(Conformance.disagreement(result(.ok, .passedDsseLayer)))
        XCTAssertNil(Conformance.disagreement(result(.ok, .retiredR3)))
    }
}
