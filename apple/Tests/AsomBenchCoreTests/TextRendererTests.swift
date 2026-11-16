@testable import AsomBenchCore
import AsomJSON
import XCTest

/// `asom.text/1` (benchmark.md section 12; LAB_SPEC.md 4.8).
final class TextRendererTests: XCTestCase {
    private func render(_ v: JValue, mesh: Bool = false) throws -> String {
        let doc = try XCTUnwrap(try? decodeBench(v).get())
        return TextRenderer.render(doc, try Derivation.derive(doc), options: RenderOptions(meshAvailable: mesh))
    }

    func testFormattingRulesOfSection122() {
        XCTAssertEqual(TextRenderer.rate(18_400), "18.4")
        XCTAssertEqual(TextRenderer.rate(7394), "7.3", "speeds floor, never round up")
        XCTAssertEqual(TextRenderer.rate(99), "0.0")
        XCTAssertEqual(TextRenderer.gb(2_019_377_152), "2.0 GB")
        XCTAssertEqual(TextRenderer.gb(6_147_702_857), "6.1 GB", "capacity floors")
        XCTAssertEqual(TextRenderer.gbFile(5_027_783_488), "5.0 GB")
        XCTAssertEqual(TextRenderer.gbFile(2_497_280_256), "2.5 GB", "file sizes round half up")
        XCTAssertEqual(TextRenderer.gbFile(2_450_000_000), "2.5 GB")
        XCTAssertEqual(TextRenderer.gbFile(2_449_999_999), "2.4 GB")
        XCTAssertEqual(TextRenderer.percent(permille: 663), "66%", "percentages floor")
        XCTAssertEqual(TextRenderer.duration(micros: 8_572_100, estimated: false), "8.5 s")
        XCTAssertEqual(TextRenderer.duration(micros: 195_000_000, estimated: false), "3 min 15 s")
        XCTAssertEqual(TextRenderer.duration(micros: 197_000_000, estimated: false), "3 min 15 s", "measured durations floor to 5 s")
        XCTAssertEqual(TextRenderer.duration(micros: 355_499_032, estimated: true), "about 5 min 50 s", "estimated durations floor to 10 s")
        XCTAssertEqual(TextRenderer.duration(micros: 600_000_000, estimated: false), "10 min")
        XCTAssertEqual(TextRenderer.duration(micros: 45_000_000, estimated: false), "45 s")
        XCTAssertEqual(TextRenderer.duration(micros: 120_000_000, estimated: false), "2 min")
    }

    /// Design T8, at most 72 characters per line: a word longer than a line is split, as the JVM renderer does (ERR-FX-CV1).
    func testAWordLongerThanALineIsSplitAtSeventyTwoColumns() {
        let long = String(repeating: "x", count: 100)
        XCTAssertEqual(TextRenderer.wrap(lead: "  ", text: "A " + long, cont: "    "),
                       ["  A", "    " + String(repeating: "x", count: 68), "    " + String(repeating: "x", count: 32)])
        XCTAssertEqual(TextRenderer.wrap(lead: "  ", text: "Example Phone X1 - android 16", cont: "    "), ["  Example Phone X1 - android 16"])
        XCTAssertEqual(TextRenderer.wrap(lead: "  ", text: long, cont: "    "), ["  " + String(repeating: "x", count: 70), "    " + String(repeating: "x", count: 30)])
        for line in TextRenderer.wrap(lead: "  ", text: "w " + String(repeating: "?", count: 192) + " tail", cont: "    ") { XCTAssertLessThanOrEqual(line.count, 72) }
    }

    func testNonAsciiBecomesOneQuestionMarkPerCodePoint() {
        XCTAssertEqual(TextRenderer.asciiOnly("a\u{E9}\u{1F600}b"), "a??b")
        XCTAssertEqual(TextRenderer.asciiOnly("\u{7F}"), "?")
    }

    func testOutputIsAsciiAndLfOnlyAtMostSeventyTwoColumns() throws {
        let f = try labFile("bench/M05-body.json")
        var checked = 0
        for v in f.member("vectors")?.elements ?? [] where v.member("expect")?.member("ok")?.member("text") != nil {
            guard let doc = v.member("input")?.member("benchDoc") else { continue }
            let text = try render(doc, mesh: true)
            XCTAssertTrue(text.utf8.allSatisfy { ($0 >= 0x20 && $0 < 0x7F) || $0 == 0x0A }, v.member("id")?.stringValue ?? "")
            XCTAssertTrue(text.hasSuffix("\n"))
            XCTAssertFalse(text.contains("\r"))
            for line in text.split(separator: "\n", omittingEmptySubsequences: false) { XCTAssertLessThanOrEqual(line.count, 72, String(line)) }
            checked += 1
        }
        XCTAssertGreaterThan(checked, 15, "non-vacuity: the law ran on the M05 body documents")
    }

    func testNeverNamesMlperf() throws {
        let base = try designExample()
        for mesh in [false, true] {
            XCTAssertFalse(try render(base, mesh: mesh).contains("MLPerf"))
        }
        let doc = try XCTUnwrap(try? decodeBench(base).get())
        let derived = try Derivation.derive(doc)
        for enabled in [false, true] {
            XCTAssertFalse(TextRenderer.render(doc, derived, options: RenderOptions(meshAvailable: true, mlperfNoteEnabled: enabled)).contains("MLPerf"),
                           "the note never renders on a default (Q1) run whatever the flag")
        }
    }

    func testQuestionFiveIsNotApplicableWithoutTheMesh() throws {
        let base = try designExample()
        XCTAssertTrue(try render(base).contains("Not applicable: this version does not share work between devices."))
        let mesh = try render(base, mesh: true)
        XCTAssertTrue(mesh.contains("OCCASIONAL HELPER"))
        XCTAssertTrue(mesh.contains("Basis: estimated from the measured speeds and heat test."))
    }

    func testTheFixedClosingBlockIsAlwaysPresent() throws {
        let text = try render(try designExample())
        XCTAssertTrue(text.contains("WHAT THIS REPORT DOES NOT TELL YOU"))
        XCTAssertTrue(text.contains("This text proves nothing by itself."))
        XCTAssertTrue(text.hasSuffix("- Nothing here measures how good the answers are.\n"))
    }

    func testCarriesTheDetailsTableOfTheWorkedExample() throws {
        let text = try render(try designExample())
        XCTAssertTrue(text.contains("  Qwen3-8B 4-bit     5.0GB     59.7     7.3       6.5        8.5"))
        XCTAssertTrue(text.contains("  model              size     read   write  write@2k  start@512"))
    }

    func testNothingMeasuredSaysSoInEveryAnswer() throws {
        let base = try designExample().setting([.key("tiers")], to: .array([])).setting([.key("sustain")], to: .null)
        let text = try render(base)
        XCTAssertTrue(text.contains("NOT MEASURED."))
        XCTAssertTrue(text.contains("Not measured (no model was loaded)."))
        XCTAssertTrue(text.contains("Not measured (the heat test was not run)."))
        XCTAssertTrue(text.contains("Overall confidence: INSUFFICIENT"))
    }

    func testDeviceBlockIsMarkedAsReportedByTheDeviceAndVirtualMachinesAreBannered() throws {
        let vm = try render(try designExample().setting([.key("device"), .key("virtualized")], to: .bool(true)))
        XCTAssertTrue(vm.contains("DEVICE (as reported by the device itself)"))
        XCTAssertTrue(vm.contains("VIRTUAL MACHINE"))
        XCTAssertFalse(try render(try designExample()).contains("VIRTUAL MACHINE"))
    }
}
