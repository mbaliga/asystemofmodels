@testable import AsomBenchCore
import AsomDSSE
import AsomJSON
import Foundation
import XCTest

/// Design revision 4, benchmark.md R4-B-01 (end reasons), R4-B-02 (ceiling rows and order), R4-B-06 (quick and ci plans), R4-B-07 (consent ticks).
/// Every law keeps a counter of the cases that exercised it and fails when the count is zero.
final class RevisionFourTests: XCTestCase {
    private func inputs(_ platform: String, form: String = "phone", code: Int64 = 0, headroom: Int64? = nil, batteryDeciC: Int64? = nil,
                        level: Int64? = 700, lowPower: Bool = false) -> CeilingInputs {
        CeilingInputs(platform: platform, form: form, thermalCode: code, headroomPermille: headroom, batteryTempDeciC: batteryDeciC,
                      powerSource: "ac", batteryLevelPermille: level, lowPowerMode: lowPower, gpuBusyHeldMs: 0)
    }

    func testALowLevelAndAPowerSaverSwitchHaveTheirOwnHardCeilingNames() throws {
        var cases = 0
        func check(_ i: CeilingInputs, _ want: Ceiling, _ line: UInt = #line) throws {
            XCTAssertEqual(try Ceilings.evaluate(i), want, "\(i)", line: line)
            cases += 1
        }
        try check(inputs("android", level: 199), .hard("BATTERY_LOW"))
        try check(inputs("android", level: 200), .none)
        for platform in ["ios", "ipados"] {
            let form = platform == "ios" ? "phone" : "tablet"
            try check(inputs(platform, form: form, level: 199), .hard("BATTERY_LOW"))
            try check(inputs(platform, form: form, level: 200), .none)
            try check(inputs(platform, form: form, lowPower: true), .hard("POWER_SAVER"))
            try check(inputs(platform, form: form), .none)
        }
        // Android has no runtime battery-saver or Low Power Mode ceiling in 11.3 (the saver is a preflight gate).
        try check(inputs("android", lowPower: true), .none)
        XCTAssertGreaterThanOrEqual(cases, 10, "non-vacuity")
    }

    func testAmongSeveralHardCeilingsThermalComesFirstThenBatteryThenTheRestAndHardBeforeSoft() throws {
        var cases = 0
        func check(_ i: CeilingInputs, _ want: Ceiling, _ line: UInt = #line) throws {
            XCTAssertEqual(try Ceilings.evaluate(i), want, "\(i)", line: line)
            cases += 1
        }
        try check(inputs("android", code: 3, batteryDeciC: 445, level: 150), .hard("THERMAL_HARD"))
        try check(inputs("android", batteryDeciC: 445, level: 150), .hard("BATTERY_TEMP"))
        try check(inputs("android", code: 2, headroom: 960, level: 150), .hard("BATTERY_LOW"))
        try check(inputs("ios", code: 3, level: 150, lowPower: true), .hard("THERMAL_HARD"))
        try check(inputs("ipados", form: "tablet", code: 3, lowPower: true), .hard("THERMAL_HARD"))
        try check(inputs("ios", level: 150, lowPower: true), .hard("BATTERY_LOW"))
        try check(inputs("ipados", form: "tablet", level: 150, lowPower: true), .hard("BATTERY_LOW"))
        XCTAssertGreaterThanOrEqual(cases, 7, "non-vacuity")
    }

    func testPlatformsWithoutARowAreRefusedByName() {
        var cases = 0
        for platform in ["windows", "ubuntu-touch"] {
            XCTAssertThrowsError(try Ceilings.evaluate(inputs(platform, form: "desktop")), platform) { XCTAssertEqual($0 as? UnknownCeilingPlatform, UnknownCeilingPlatform(platform: platform)) }
            cases += 1
        }
        XCTAssertEqual(cases, 2)
    }

    func testTheClosedReasonListsCarryTheNewNames() {
        for reason in ["BATTERY_LOW", "POWER_SAVER", "DEVICE_BUSY"] {
            XCTAssertTrue(BenchDocument.abortReasons.contains(reason), reason)
            XCTAssertTrue(BenchDocument.endReasons.contains(reason), reason)
        }
    }

    private func sustainBlock(reason: String) -> BenchDocument.Sustain {
        let windows = (0..<40).map { BenchDocument.Window(tStartMs: Int64($0) * 15_000, tokens: 100, micros: 15_000_000, thermalCode: 0) }
        return BenchDocument.Sustain(tier: "T3", windowMs: 15_000, capMs: 600_000, endReason: reason, headroomAtOnsetPermille: nil, windows: windows)
    }

    func testTheNewHardCeilingsSetHardCeilingAndCapConfidenceAtMediumAndDeviceBusyDoesNot() throws {
        var cases = 0
        for reason in ["THERMAL_HARD", "BATTERY_TEMP", "BATTERY_LOW", "POWER_SAVER"] {
            let r = try SustainDerivation.derive(sustainBlock(reason: reason), startedCool: true)
            XCTAssertTrue(r.flags.contains("HARD_CEILING"), reason)
            XCTAssertEqual(r.confidence, .medium, reason)
            cases += 1
        }
        for reason in ["DEVICE_BUSY", "USER_STOP", "TIME_CAP"] {
            let r = try SustainDerivation.derive(sustainBlock(reason: reason), startedCool: true)
            XCTAssertFalse(r.flags.contains("HARD_CEILING"), reason)
            cases += 1
        }
        XCTAssertEqual(cases, 7)
    }

    // MARK: R4-B-06

    private func planBlocks() throws -> [String: JValue] {
        guard let path = repoPath("docs/design/mesh/benchmark.md"), let text = try? String(contentsOfFile: path, encoding: .utf8) else { throw MissingRepoFile(description: "benchmark.md") }
        guard let start = text.range(of: "### 5.2 Run-plan format"), let end = text.range(of: "### 5.3") else { throw MissingRepoFile(description: "benchmark.md 5.2") }
        var out: [String: JValue] = [:]
        var rest = text[start.upperBound..<end.lowerBound]
        while let open = rest.range(of: "```json\n") {
            let after = rest[open.upperBound...]
            guard let close = after.range(of: "```") else { break }
            guard case let .success(v) = StrictJSON.parse(Array(after[..<close.lowerBound].utf8)), let plan = v.member("plan")?.stringValue else {
                throw MissingRepoFile(description: "a 5.2 block that is not a plan")
            }
            out[plan] = v
            rest = after[close.upperBound...]
        }
        return out
    }

    func testQuickAndCiPlansAreTheJsonOfSection52AndHashToTheVectors() throws {
        let blocks = try planBlocks()
        XCTAssertEqual(Set(blocks.keys), ["standard", "quick", "ci"])
        var cases = 0
        for (id, vector) in [("quick", "M04-401"), ("standard", "M04-402"), ("ci", "M04-403")] {
            let want = try labVector("bench/M04-derive.json", vector).member("expect")?.member("ok")
            let block = try XCTUnwrap(blocks[id])
            let bytes = try JCS.serialize(block)
            XCTAssertEqual(try XCTUnwrap(RunPlan.jcsBytes(id)), bytes, "\(id): the data differs from the 5.2 text")
            XCTAssertEqual(try RunPlan.planSha256(id), want?.member("planSha256")?.stringValue, id)
            XCTAssertEqual(Int64(bytes.count), want?.member("jcsBytes")?.intValue, id)
            cases += 1
        }
        XCTAssertEqual(cases, 3)
        XCTAssertEqual(try RunPlan.planSha256("quick"), "39Jq5InzqMUCbHKyI4XqqvL2ddj0UCIXeFhRHaw5f-E")
        XCTAssertEqual(try RunPlan.planSha256("ci"), "ervAvYozEUXI8dn9t0fu9sJXOC16z4LpTyt6E7ZnZXo")
    }

    // MARK: R4-B-07

    func testASheetThatNeedsTheRerunTickAndDoesNotGetItMintsATokenWhoseHeatTestIsNotPermitted() throws {
        let today = ConsentInputs(plan: "standard", downloadBytes: 0, tiers: [], t3OptInOffered: false, sustainedToday: true)
        let hash = ConsentSheet.textSha256(ConsentSheet.text(today))
        var gate = ConsentGate()
        let unticked = try gate.confirm(sheetFor: today, shownSha256: hash, nowMs: 0, ticks: ConsentTicks()).get()
        XCTAssertFalse(unticked.heatTestPermitted)
        let ticked = try gate.confirm(sheetFor: today, shownSha256: hash, nowMs: 0, ticks: ConsentTicks(rerunHeatToday: true)).get()
        XCTAssertTrue(ticked.heatTestPermitted)
        let unreported = try gate.confirm(sheetFor: today, shownSha256: hash, nowMs: 0).get()
        XCTAssertFalse(unreported.heatTestPermitted)
    }
}
