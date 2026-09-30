import AsomBenchCore
import AsomJSON
import Foundation
import XCTest
@testable import AsomConformanceKit

/// The r3 vector set (lab/conformance, confVersion 0.2.0) through the Swift lane. Every vector here is `oracle: self`;
/// agreement is cross-lane evidence and never independent evidence (apple/ERRATA.md E-02, E-17).
final class R3ConformanceTests: XCTestCase {
    private func labDirectory(file: StaticString = #filePath) throws -> String {
        let env = ProcessInfo.processInfo.environment["ASOM_CONFORMANCE_DIR"] ?? ""
        if !env.isEmpty, R3.isR3Directory(env) { return env }
        var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
        for _ in 0..<10 {
            let candidate = dir.appendingPathComponent("lab/conformance").path
            if R3.isR3Directory(candidate) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("lab/conformance (confVersion 0.2.0) not found")
    }

    private func knownDisagreements(file: StaticString = #filePath) throws -> Set<String> {
        var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
        for _ in 0..<10 {
            let candidate = dir.appendingPathComponent("ci/known-disagreements.txt")
            if let text = try? String(contentsOf: candidate, encoding: .utf8) {
                var ids = Set<String>()
                for raw in text.split(separator: "\n") {
                    let line = raw.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
                    if let id = line.split(separator: " ").first { ids.insert(String(id)) }
                }
                return ids
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("apple/ci/known-disagreements.txt not found")
    }

    func testEveryVectorAgreesOnceTheOneKnownAmbiguityIsSetAside() throws {
        let rows = try R3.run(families: R3.families, in: try labDirectory(), policy: .diagnosticDriftFlag)
        let bad = R3.mismatches(rows)
        XCTAssertEqual(bad.map { "\($0.id): \($0.reason)" }, [], "F-1 aside, the Swift lane reproduces every vector it implements")
    }

    func testSpecLiteralReadingDisagreesExactlyOnTheListedVectors() throws {
        let rows = try R3.run(families: R3.families, in: try labDirectory())
        var verdictDiffers = Set<String>()
        for r in rows {
            switch (r.observed, r.vector.expect) {
            case (.notImplemented, _): continue
            case let (.ok, .reject): verdictDiffers.insert(r.vector.id)
            case let (.reject, .ok): verdictDiffers.insert(r.vector.id)
            case let (.reject(a), .reject(b)): if a.rawValue != b { verdictDiffers.insert(r.vector.id) }
            case (.ok, .ok): continue
            }
        }
        XCTAssertEqual(verdictDiffers, try knownDisagreements(), "apple/ci/known-disagreements.txt is the explicit list")
        let sameVerdictDifferentValue = Set(R3.mismatches(rows).map { $0.id }).subtracting(verdictDiffers)
        XCTAssertEqual(sameVerdictDifferentValue, ["M04-042", "M06-201", "M06-202", "M06-203", "M06-204"],
                       "the others differ in the value: results hash, or the file body's flags")
        // Every one of them is the projection flag of F-1 and nothing else.
        for m in R3.mismatches(rows) {
            XCTAssertTrue(m.reason.contains("DERIVATION_MISMATCH") || m.reason.contains("resultsSha256") || m.reason.contains("jcsUtf8"), "\(m.id): \(m.reason)")
        }
    }

    func testNonVacuity() throws {
        let rows = try R3.run(families: R3.families, in: try labDirectory())
        var perKind: [String: Int] = [:]
        var perFamily: [String: Int] = [:]
        var skipped: [String: Int] = [:]
        for r in rows {
            let kind = r.vector.input.member("kind")?.stringValue ?? "verify"
            if case .notImplemented = r.observed { skipped["\(r.vector.family)/\(kind)", default: 0] += 1; continue }
            perKind["\(r.vector.family)/\(kind)", default: 0] += 1
            perFamily[r.vector.family, default: 0] += 1
        }
        for family in R3.families { XCTAssertGreaterThan(perFamily[family] ?? 0, 0, "family \(family) exercised no vector") }
        for kind in ["M01/canonicalize", "M01/base64Either", "M01/base64UrlNoPad", "M01/derToRaw", "M01/rawToDer", "M01/normaliseLowS", "M02/verify", "M03/verify",
                     "M04/test", "M04/sustain", "M04/percentile", "M04/doc", "M05/manifest", "M05/export", "M05/body", "M05/mlperf", "M06/q2", "M06/public", "M06/fileProjection"] {
            XCTAssertGreaterThan(perKind[kind] ?? 0, 0, "kind \(kind) exercised no vector")
        }
        XCTAssertEqual(Set(skipped.keys), ["M04/trace", "M04/plan", "M04/consent", "M04/fsm", "M04/ceilings", "M04/pins"],
                       "the only vectors this lane does not run are the M04 plan, governor and executor kinds")
        XCTAssertGreaterThanOrEqual(perFamily["M03"] ?? 0, 70)
        XCTAssertGreaterThanOrEqual(perFamily["M01"] ?? 0, 100)
    }

    func testLinesFormatAndOrder() throws {
        let rows = try R3.run(families: R3.families, in: try labDirectory())
        let lines = R3.lines(rows)
        XCTAssertGreaterThan(lines.count, 250)
        let sorted = lines.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
        XCTAssertEqual(lines, sorted)
        let pattern = try NSRegularExpression(pattern: "^M0[1-6]-[0-9A-Za-z]{3,12} (ok|reject [A-Z_]+)$")
        for line in lines { XCTAssertEqual(pattern.numberOfMatches(in: line, range: NSRange(line.startIndex..., in: line)), 1, line) }
        XCTAssertFalse(lines.contains { $0.contains("HARNESS_ERROR") }, "a harness error is a bug in this lane, not a verdict")
        XCTAssertTrue(lines.contains("M03-141 reject TEST_ONLY_KEY"))
        XCTAssertTrue(lines.contains("M03-143 reject NONCE_MISMATCH"))
        XCTAssertTrue(lines.contains("M01-149 reject INVALID_UNICODE"))
        XCTAssertTrue(lines.contains("M02-114 ok"))
    }

    func testAnUnknownFamilyOrVersionIsRefusedNotGuessed() throws {
        XCTAssertThrowsError(try R3.run(families: ["M07"], in: try labDirectory()))
        XCTAssertFalse(R3.isR3Directory("/nonexistent"))
    }
}
