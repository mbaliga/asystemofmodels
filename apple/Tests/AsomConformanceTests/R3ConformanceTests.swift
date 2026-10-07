import AsomBenchCore
import AsomDSSE
import AsomManifest
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
        try ciList("known-disagreements.txt", file: file)
    }

    private func ciList(_ name: String, file: StaticString = #filePath) throws -> Set<String> {
        var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
        for _ in 0..<10 {
            let candidate = dir.appendingPathComponent("ci/" + name)
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
        throw XCTSkip("apple/ci/\(name) not found")
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

    /// F-1 (apple/ERRATA.md, LF-1): the one cause of every listed disagreement, shown mechanically. For each listed vector the document's own
    /// `results` equal this lane's spec-literal projection once the `thermal-drift` row flag is removed from them, and the spec-literal
    /// projection never carries that flag. No step order, no other flag, no number is involved.
    func testTheOnlyDifferenceInEveryListedVectorIsTheThermalDriftRowFlag() throws {
        let dir = try labDirectory()
        let listed = try knownDisagreements()
        var checked = 0, rowsWithTheFlag = 0, flagsSeenInSpecLiteral = 0
        for family in ["M02", "M03", "M05", "M06"] {
            for v in try R3.loadVectors(family: family, in: dir) where listed.contains(v.id) {
                let container = try Conformance.parseValue(try R3.documentBytes(v.input))
                guard let b64 = container.member("dsse")?.member("payload")?.stringValue, let payload = Base64Strict.decodeEither(b64) else { return XCTFail(v.id) }
                guard case let .success(manifest) = ManifestDecoder.decode(try Conformance.parseValue(payload)) else { return XCTFail("\(v.id): payload does not decode") }
                let specLiteral = try Projection.project(manifest.bench, audience: manifest.audience)
                for row in specLiteral { if (row.member("flags")?.elements ?? []).contains(where: { $0.stringValue == "thermal-drift" }) { flagsSeenInSpecLiteral += 1 } }
                var embedded: [JValue] = []
                for row in manifest.resultsValue.elements ?? [] {
                    guard case let .object(members) = row else { return XCTFail(v.id) }
                    embedded.append(.object(members.map { m in
                        guard m.name == "flags", let flags = m.value.elements else { return m }
                        if flags.contains(where: { $0.stringValue == "thermal-drift" }) { rowsWithTheFlag += 1 }
                        return JMember(name: "flags", value: .array(flags.filter { $0.stringValue != "thermal-drift" }))
                    }))
                }
                XCTAssertEqual(try JCS.serialize(.array(embedded)), try JCS.serialize(.array(specLiteral)), "\(v.id): more than the flag differs")
                checked += 1
            }
        }
        XCTAssertEqual(checked, listed.count, "every listed vector was examined")
        XCTAssertEqual(flagsSeenInSpecLiteral, 0)
        XCTAssertGreaterThanOrEqual(rowsWithTheFlag, listed.count, "non-vacuity: every listed document carries the flag on at least one row")
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
                     "M04/test", "M04/sustain", "M04/percentile", "M04/doc", "M04/plan", "M04/consent", "M04/fsm", "M04/ceilings", "M04/pins", "M08/evaluate", "M08/sequence", "M08/state", "M08/claimBody", "M08/inherit", "M08/claimBudget", "M08/penalty", "M08/disc", "M05/manifest", "M05/export", "M05/body", "M05/mlperf", "M06/q2", "M06/public", "M06/fileProjection"] {
            XCTAssertGreaterThan(perKind[kind] ?? 0, 0, "kind \(kind) exercised no vector")
        }
        XCTAssertEqual(Set(skipped.keys), ["M04/trace", "M04/plan", "M04/consent"],
                       "the only vectors this lane does not run: the executor traces, the plans the spec gives no data for, and the consent sheet whose wording it does not give; the four M08 tracker kinds the JVM router fix group added (ERR-FX-RT-3/4/8) are run (apple/ERRATA.md ERR-FX-M08-1)")
        for kind in ["M08/inherit", "M08/claimBudget", "M08/penalty", "M08/disc"] { XCTAssertNil(skipped[kind], "\(kind) is run now") }
        XCTAssertEqual(skipped["M04/trace"], 26)
        XCTAssertEqual(skipped["M04/plan"], 2)
        XCTAssertEqual(skipped["M04/consent"], 1)
        XCTAssertEqual(perKind["M04/plan"], 1)
        XCTAssertEqual(perKind["M04/consent"], 5)
        XCTAssertEqual(perKind["M04/fsm"], 1)
        XCTAssertEqual(perKind["M04/ceilings"], 9)
        XCTAssertEqual(perKind["M04/pins"], 1)
        XCTAssertEqual(perKind["M08/evaluate"], 28)
        XCTAssertEqual(perKind["M08/sequence"], 26)
        XCTAssertEqual(perKind["M08/state"], 7)
        XCTAssertEqual(perKind["M08/claimBody"], 2)
        XCTAssertEqual(perKind["M08/inherit"], 3)
        XCTAssertEqual(perKind["M08/claimBudget"], 2)
        XCTAssertEqual(perKind["M08/penalty"], 4)
        XCTAssertEqual(perKind["M08/disc"], 5)
        XCTAssertEqual(perFamily["M08"], 77, "63 before the router fix-wave kinds, 14 with them")
        XCTAssertGreaterThanOrEqual(perFamily["M03"] ?? 0, 70)
        XCTAssertGreaterThanOrEqual(perFamily["M01"] ?? 0, 100)
    }

    func testTheNotImplementedListIsExactlyTheVectorsThisLaneDoesNotProduce() throws {
        let rows = try R3.run(families: R3.families, in: try labDirectory())
        let unproduced = Set(rows.compactMap { r -> String? in if case .notImplemented = r.observed { return r.vector.id } else { return nil } })
        let listed = try ciList("not-implemented.txt")
        XCTAssertEqual(unproduced, listed, "apple/ci/not-implemented.txt is the explicit list: no stale entry, no silent gap")
        XCTAssertGreaterThan(listed.count, 0)
        let produced = Set(R3.lines(rows).compactMap { $0.split(separator: " ").first.map(String.init) })
        XCTAssertTrue(produced.isDisjoint(with: listed), "a vector cannot be both produced and listed as not implemented")
        XCTAssertEqual(produced.count + listed.count, rows.count, "every vector of the lab files is either produced or listed")
    }

    func testLinesFormatAndOrder() throws {
        let rows = try R3.run(families: R3.families, in: try labDirectory())
        let lines = R3.lines(rows)
        XCTAssertGreaterThan(lines.count, 250)
        let sorted = lines.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
        XCTAssertEqual(lines, sorted)
        let pattern = try NSRegularExpression(pattern: "^M0[1-68]-[0-9A-Za-z]{3,12} (ok|reject [A-Z_]+)$")
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
