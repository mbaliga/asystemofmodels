@testable import AsomBenchCore
import AsomDSSE
import AsomJSON
import XCTest

/// The run plan, the consent token, the governor state machine, the runtime ceilings and the bench-set pins (benchmark.md 5.2, 11.1,
/// 11.3, 11.4, 4.2). Every law keeps a counter of the cases that exercised it and fails when the count is zero.
final class ProtocolTests: XCTestCase {
    // MARK: Run plan

    func testStandardPlanIsTheSpecJsonAndItsHashMatchesTheVector() throws {
        let want = try labVector("bench/M04-derive.json", "M04-402").member("expect")?.member("ok")
        XCTAssertEqual(try RunPlan.planSha256("standard"), want?.member("planSha256")?.stringValue)
        XCTAssertEqual(Int64(try XCTUnwrap(RunPlan.jcsBytes("standard")).count), want?.member("jcsBytes")?.intValue)
        let text = String(decoding: try XCTUnwrap(RunPlan.jcsBytes("standard")), as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("{\"charger\":{\"handheld\":\"required\""), "JCS sorts the members")
        XCTAssertTrue(text.contains("\"wallCapMs\":{\"desktop\":2700000"))
    }

    func testPlansTheSpecDoesNotGiveInFullAreNotInvented() {
        var checked = 0
        for id in ["quick", "ci", "sustained", "battery", "extended", "", "Standard"] {
            XCTAssertNil(RunPlan.jcsValue(id), id)
            XCTAssertNil(try RunPlan.jcsBytes(id), id)
            checked += 1
        }
        XCTAssertEqual(RunPlan.compiled, ["standard"])
        XCTAssertGreaterThan(checked, 5, "non-vacuity: plans without data were asked for")
    }

    // MARK: Consent

    private func standard(download: Int64 = 4_300_000_000, tiers: [String] = ["T1", "T2"], optIn: Bool = true, today: Bool = false) -> ConsentInputs {
        ConsentInputs(plan: "standard", downloadBytes: download, tiers: tiers, t3OptInOffered: optIn, sustainedToday: today)
    }

    func testTheStandardSheetIsTheSpecTextByteForByte() {
        let want = """
        Run the standard device test?
        - Takes about 20-30 minutes. You can stop at any time.
        - Makes the device warm and uses power. Keep it on its charger.
        - Downloads 4.3 GB of test models over Wi-Fi first (T1, T2).
          [ ] Also test 8B models (+5.0 GB). Without this, 8B speed is estimated.
        - Nothing is uploaded. Results stay on this device unless you share them.
        - If the device gets too hot, the test stops by itself.
        [Not now]   [Start]

        """
        XCTAssertEqual(ConsentSheet.text(standard()), want)
        XCTAssertEqual(NodeIdentity.sha256Hex(Array(want.utf8)), "29b4829edc7754a2d727cdd12fa863f4b3944f738bdf317c4d2230183a1b1a8d", "the vector's textSha256")
        XCTAssertTrue(ConsentSheet.wordingIsSpecified(standard()))
        for unspecified in [standard(download: 0), standard(today: true), ConsentInputs(plan: "quick", downloadBytes: 1, tiers: [], t3OptInOffered: false, sustainedToday: false)] {
            XCTAssertFalse(ConsentSheet.wordingIsSpecified(unspecified))
        }
    }

    func testTheSheetSizeFormatter() {
        var checked = 0
        for (bytes, text) in [(4_300_000_000, "4.3 GB"), (4_349_999_999, "4.3 GB"), (4_350_000_000, "4.4 GB"), (1_000_000_000, "1.0 GB"), (5_027_783_488, "5.0 GB"),
                              (999_999_999, "1000 MB"), (999_499_999, "999 MB"), (300_000_000, "300 MB")] as [(Int64, String)] {
            XCTAssertEqual(ConsentSheet.formatBytes(bytes), text, "\(bytes)")
            checked += 1
        }
        XCTAssertEqual(checked, 8)
    }

    func testEveryChangeOfTheInputsChangesTheTextAndSoTheHash() {
        let base = ConsentSheet.textSha256(ConsentSheet.text(standard()))
        var differing = 0
        for changed in [standard(download: 4_400_000_000), standard(tiers: ["T1"]), standard(optIn: false), standard(today: true),
                        ConsentInputs(plan: "quick", downloadBytes: 4_300_000_000, tiers: ["T1", "T2"], t3OptInOffered: true, sustainedToday: false)] {
            XCTAssertNotEqual(ConsentSheet.textSha256(ConsentSheet.text(changed)), base)
            differing += 1
        }
        XCTAssertEqual(differing, 5)
    }

    func testTokenLaws() throws {
        var counts: [String: Int] = [:]
        func count(_ law: String) { counts[law, default: 0] += 1 }
        let inputs = standard()
        let right = ConsentSheet.textSha256(ConsentSheet.text(inputs))

        // A hash that is not the hash of the sheet shown mints nothing (B5), including a one-bit change in any byte.
        var gate = ConsentGate()
        for index in 0..<right.count {
            var wrong = right
            wrong[index] ^= 0x01
            guard case .failure = gate.confirm(sheetFor: inputs, shownSha256: wrong, nowMs: 0) else { return XCTFail("minted for a wrong hash at byte \(index)") }
            count("hash-binding")
        }
        for wrong in [[UInt8](), Array(right.dropLast()), right + [0]] {
            guard case .failure = gate.confirm(sheetFor: inputs, shownSha256: wrong, nowMs: 0) else { return XCTFail("minted for a wrong length") }
            count("hash-length")
        }

        // Expiry: 5 minutes after minting, exclusive; and a clock that went backwards is not a token's lifetime.
        for (age, ok) in [(0, true), (1, true), (299_999, true), (300_000, false), (300_001, false), (-1, false), (Int64.max, false)] as [(Int64, Bool)] {
            var g = ConsentGate()
            let token = try XCTUnwrap(try? g.confirm(sheetFor: inputs, shownSha256: right, nowMs: 1_000).get())
            let (now, overflow) = Int64(1_000).addingReportingOverflow(age)
            let result = g.consume(token, plan: "standard", nowMs: overflow ? Int64.max : now)
            if ok { XCTAssertNoThrow(try result.get(), "age \(age)") } else { XCTAssertThrowsError(try result.get(), "age \(age)") }
            count("expiry")
        }

        // One run only.
        var once = ConsentGate()
        let token = try XCTUnwrap(try? once.confirm(sheetFor: inputs, shownSha256: right, nowMs: 0).get())
        XCTAssertNoThrow(try once.consume(token, plan: "standard", nowMs: 1).get())
        XCTAssertThrowsError(try once.consume(token, plan: "standard", nowMs: 2).get())
        XCTAssertThrowsError(try once.consume(token, plan: "standard", nowMs: 3).get())
        count("single-use")

        // A token of one gate is not valid in another gate's ledger of spent tokens only by id, so two tokens never share an id.
        var two = ConsentGate()
        let a = try XCTUnwrap(try? two.confirm(sheetFor: inputs, shownSha256: right, nowMs: 0).get())
        let b = try XCTUnwrap(try? two.confirm(sheetFor: inputs, shownSha256: right, nowMs: 0).get())
        XCTAssertNotEqual(a, b)
        XCTAssertNoThrow(try two.consume(a, plan: "standard", nowMs: 1).get())
        XCTAssertNoThrow(try two.consume(b, plan: "standard", nowMs: 1).get(), "spending one token does not spend its sibling")
        count("distinct-tokens")

        // Exactly the plan whose sheet was shown.
        for other in ["quick", "ci", "Standard", "standard ", ""] {
            var g = ConsentGate()
            let t = try XCTUnwrap(try? g.confirm(sheetFor: inputs, shownSha256: right, nowMs: 0).get())
            XCTAssertThrowsError(try g.consume(t, plan: other, nowMs: 1).get(), other)
            XCTAssertNoThrow(try g.consume(t, plan: "standard", nowMs: 1).get(), "a refused spend leaves the token unspent: \(other)")
            count("plan-cover")
        }

        for law in ["hash-binding", "hash-length", "expiry", "single-use", "distinct-tokens", "plan-cover"] {
            XCTAssertGreaterThan(counts[law] ?? 0, 0, "non-vacuity: law \(law) ran no case")
        }
        XCTAssertEqual(counts["hash-binding"], 32)
    }

    // MARK: Governor

    func testEveryPairIsDecidedAndExactlyTheTwentyOneEdgesAreAllowed() {
        var allowed = 0, refused = 0
        for from in GovernorState.allCases {
            for to in GovernorState.allCases {
                if Governor.isEdge(from, to) {
                    allowed += 1
                } else {
                    refused += 1
                }
            }
        }
        XCTAssertEqual(allowed, 21)
        XCTAssertEqual(refused, 79)
        XCTAssertEqual(Governor.edges.count, 21)
        XCTAssertEqual(Set(Governor.edges).count, 21)
    }

    func testToRefusesEveryNonEdgeAndLeavesTheStateAlone() {
        var refusedCount = 0
        for from in GovernorState.allCases {
            for to in GovernorState.allCases where !Governor.isEdge(from, to) {
                var g = Governor()
                XCTAssertTrue(walk(&g, to: from), "\(from) is reachable")
                XCTAssertThrowsError(try g.to(to), "\(from)>\(to)") { error in
                    XCTAssertEqual(error as? GovernorTransitionRefused, GovernorTransitionRefused(from: from, to: to))
                }
                XCTAssertEqual(g.state, from, "a refused transition changes nothing")
                refusedCount += 1
            }
        }
        XCTAssertEqual(refusedCount, 79)
    }

    func testTheWholeRunPathsOfTheDiagram() throws {
        var checked = 0
        let paths: [[GovernorState]] = [
            [.preflight, .idle],
            [.preflight, .awaitConsent, .idle],
            [.preflight, .awaitConsent, .preparing, .cooling, .running, .cooling, .running, .finalizing, .done],
            [.preflight, .awaitConsent, .preparing, .cooling, .finalizing, .done],
            [.preflight, .awaitConsent, .preparing, .cooling, .running, .yielded, .cooling, .running, .yielded, .finalizing, .done],
            [.preflight, .awaitConsent, .preparing, .cooling, .running, .aborting, .finalizing, .done],
            [.preflight, .awaitConsent, .preparing, .aborting, .finalizing, .done],
            [.preflight, .aborting, .finalizing, .done],
        ]
        for path in paths {
            var g = Governor()
            for step in path {
                try g.to(step)
                checked += 1
            }
            XCTAssertEqual(g.state, path.last)
        }
        XCTAssertGreaterThan(checked, 40, "non-vacuity")
        var done = Governor()
        XCTAssertTrue(walk(&done, to: .done))
        for next in GovernorState.allCases { XCTAssertThrowsError(try done.to(next), "DONE is final") }
    }

    private func walk(_ g: inout Governor, to target: GovernorState) -> Bool {
        let routes: [GovernorState: [GovernorState]] = [
            .idle: [], .preflight: [.preflight], .awaitConsent: [.preflight, .awaitConsent],
            .preparing: [.preflight, .awaitConsent, .preparing], .cooling: [.preflight, .awaitConsent, .preparing, .cooling],
            .running: [.preflight, .awaitConsent, .preparing, .cooling, .running],
            .yielded: [.preflight, .awaitConsent, .preparing, .cooling, .running, .yielded],
            .finalizing: [.preflight, .awaitConsent, .preparing, .cooling, .finalizing],
            .aborting: [.preflight, .aborting],
            .done: [.preflight, .awaitConsent, .preparing, .cooling, .finalizing, .done],
        ]
        for step in routes[target] ?? [] { if (try? g.to(step)) == nil { return false } }
        return g.state == target
    }

    func testEdgeListOrderIsTheDeclarationOrder() {
        XCTAssertEqual(Governor.edges.first, "IDLE>PREFLIGHT")
        XCTAssertEqual(Governor.edges.last, "ABORTING>FINALIZING")
        let want = try? labVector("bench/M04-derive.json", "M04-430").member("expect")?.member("ok")?.member("edges")?.elements?.compactMap { $0.stringValue }
        XCTAssertEqual(Governor.edges, want)
    }

    // MARK: Ceilings

    private func inputs(_ platform: String, form: String = "phone", code: Int64 = 0, headroom: Int64? = nil, batteryDeciC: Int64? = nil,
                        source: String = "ac", level: Int64? = 700, lowPower: Bool = false, gpu: Int64 = 0) -> CeilingInputs {
        CeilingInputs(platform: platform, form: form, thermalCode: code, headroomPermille: headroom, batteryTempDeciC: batteryDeciC,
                      powerSource: source, batteryLevelPermille: level, lowPowerMode: lowPower, gpuBusyHeldMs: gpu)
    }

    func testCeilingTable() throws {
        var perPlatform: [String: Int] = [:]
        func check(_ i: CeilingInputs, _ want: Ceiling, _ line: UInt = #line) throws {
            XCTAssertEqual(try Ceilings.evaluate(i), want, "\(i)", line: line)
            perPlatform[i.platform, default: 0] += 1
        }
        // Android: SEVERE and above hard; headroom 950 and battery 42.0 soft; battery 44.0 and a level under 200 hard.
        try check(inputs("android", code: 2), .none)
        try check(inputs("android", code: 3), .hard("THERMAL_HARD"))
        try check(inputs("android", code: 4), .hard("THERMAL_HARD"))
        try check(inputs("android", code: 1, headroom: 949), .none)
        try check(inputs("android", code: 1, headroom: 950), .soft("THERMAL_SOFT"))
        try check(inputs("android", batteryDeciC: 419), .none)
        try check(inputs("android", batteryDeciC: 420), .soft("THERMAL_SOFT"))
        try check(inputs("android", batteryDeciC: 439), .soft("THERMAL_SOFT"))
        try check(inputs("android", batteryDeciC: 440), .hard("BATTERY_TEMP"))
        try check(inputs("android", level: 200), .none)
        try check(inputs("android", level: 199), .hard("BATTERY_TEMP"))
        try check(inputs("android", level: nil), .none)
        try check(inputs("android", code: 2, headroom: 999, batteryDeciC: 439), .soft("THERMAL_SOFT"))
        try check(inputs("android", code: 3, headroom: 999, batteryDeciC: 450, level: 100), .hard("THERMAL_HARD"))
        // iOS: no soft ceiling; serious, Low Power Mode and a level under 20 percent are hard.
        try check(inputs("ios", code: 1), .none)
        try check(inputs("ios", code: 1, headroom: 999, batteryDeciC: 500), .none)
        try check(inputs("ios", code: 3), .hard("THERMAL_HARD"))
        try check(inputs("ios", code: 1, lowPower: true), .hard("THERMAL_HARD"))
        try check(inputs("ios", level: 199), .hard("BATTERY_TEMP"))
        try check(inputs("ios", level: 200), .none)
        // macOS: serious soft, critical hard, a laptop off AC hard.
        try check(inputs("macos", form: "laptop", code: 2), .none)
        try check(inputs("macos", form: "laptop", code: 3), .soft("THERMAL_SOFT"))
        try check(inputs("macos", form: "laptop", code: 4), .hard("THERMAL_HARD"))
        try check(inputs("macos", form: "laptop", source: "battery"), .hard("CHARGER_REMOVED"))
        try check(inputs("macos", form: "desktop", code: 0, source: "battery"), .none)
        // Linux and the Deck: code 3 soft, 4 hard, battery 45.0 hard, a game on the Deck hard after 10 s.
        try check(inputs("linux", form: "desktop", code: 2), .none)
        try check(inputs("linux", form: "desktop", code: 3), .soft("THERMAL_SOFT"))
        try check(inputs("linux", form: "desktop", code: 4), .hard("THERMAL_HARD"))
        try check(inputs("linux", form: "laptop", batteryDeciC: 449), .none)
        try check(inputs("linux", form: "laptop", batteryDeciC: 450), .hard("BATTERY_TEMP"))
        try check(inputs("linux", form: "handheld", gpu: 9_999), .none)
        try check(inputs("linux", form: "handheld", gpu: 10_000), .hard("DEVICE_BUSY"))
        try check(inputs("linux", form: "desktop", gpu: 60_000), .none)
        try check(inputs("linux", form: "handheld", code: 3, gpu: 10_000), .hard("DEVICE_BUSY"))
        for platform in ["android", "ios", "macos", "linux"] { XCTAssertGreaterThan(perPlatform[platform] ?? 0, 4, "non-vacuity: platform \(platform)") }
        XCTAssertThrowsError(try Ceilings.evaluate(inputs("windows")))
        XCTAssertEqual(Ceiling.soft("THERMAL_SOFT").text, "soft THERMAL_SOFT")
        XCTAssertEqual(Ceiling.none.text, "none")
    }

    // MARK: Pins

    func testBenchSetLoading() throws {
        let q1 = try BenchSet.load(.q1)
        XCTAssertEqual(q1.status, .proposed)
        XCTAssertEqual(q1.pins.map(\.tier), BenchSet.tierOrder)
        XCTAssertEqual(BenchSet.defaultSetIds, ["qwen3-dense-1"])
        XCTAssertFalse(BenchSet.defaultKinds.contains(.l1))
        XCTAssertThrowsError(try BenchSet.load(.l1)) { XCTAssertEqual($0 as? BenchSet.RulingRequired, BenchSet.RulingRequired()) }
        let l1 = try BenchSet.load(.l1, d18Ruling: true)
        XCTAssertEqual(l1.status, .unpinned)
        XCTAssertTrue(l1.pins.isEmpty)
        XCTAssertNil(BenchSet.id(of: .l1))
    }
}
