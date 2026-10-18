@testable import AsomBenchCore
import AsomJSON
import XCTest

/// Sustained-load maths (benchmark.md 6.3), the five answers (12.4, 12.5) and the projection (13.3). The numbers asserted
/// here are the worked example of benchmark.md 12.7 and 13.3, which the spec states in prose and JSON, not vector output.
final class DeriveTests: XCTestCase {
    private func example(file: Bool = false) throws -> BenchDocument {
        switch decodeBench(try designExample()) {
        case let .success(d): return d
        case let .failure(e): throw e
        }
    }

    private func sustain(_ windows: [(Int64, Int64)], reason: String = "TIME_CAP", cool: Bool = true) throws -> SustainResult {
        let w = windows.enumerated().map { BenchDocument.Window(tStartMs: Int64($0.offset) * 15_000, tokens: $0.element.0, micros: $0.element.1, thermalCode: 0) }
        return try SustainDerivation.derive(
            BenchDocument.Sustain(tier: "T3", windowMs: 15_000, capMs: 600_000, endReason: reason, headroomAtOnsetPermille: nil, windows: w),
            startedCool: cool
        )
    }

    func testWorkedExampleSustainedPhase() throws {
        let d = try Derivation.derive(try example())
        let s = try XCTUnwrap(d.sustain)
        XCTAssertEqual(s.peakMtps, 7583)
        XCTAssertEqual(s.plateauMtps, 5033)
        XCTAssertEqual(s.onsetMs, 195_000)
        XCTAssertEqual(s.stabilityPermille, 663)
        XCTAssertEqual(s.durationMs, 435_000)
        XCTAssertEqual(s.thermalCodeAtOnset, 2)
        XCTAssertEqual(s.headroomAtOnsetPermille, 830)
        XCTAssertEqual(s.confidence, .high)
    }

    func testFlatTenMinutesHasNoOnsetAndStabilityOneThousand() throws {
        let s = try sustain([(Int64, Int64)](repeating: (100, 15_000_000), count: 40))
        XCTAssertNil(s.onsetMs)
        XCTAssertEqual(s.stabilityPermille, 1000)
        XCTAssertEqual(s.durationMs, 600_000)
        XCTAssertEqual(s.confidence, .high)
        XCTAssertNil(s.thermalCodeAtOnset)
    }

    func testPeakIsTakenOnlyFromTheFirstTwoMinutes() throws {
        var w = [(Int64, Int64)](repeating: (100, 15_000_000), count: 20)
        w[12] = (200, 15_000_000)     // starts at 180 s
        let s = try sustain(w)
        XCTAssertEqual(s.peakMtps, 6666)
        XCTAssertEqual(s.plateauMtps, 6666)
        // Windows starting at 135 s, 150 s and 165 s are over the 120 s limit, three of them so that smoothing keeps the height.
        var late = [(Int64, Int64)](repeating: (100, 15_000_000), count: 20)
        for k in 9...11 { late[k] = (200, 15_000_000) }
        XCTAssertEqual(try sustain(late).peakMtps, 6666)
        // Just inside the limit (90 s, 105 s, 120 s is out; 90 and 105 count) it counts.
        var inside = [(Int64, Int64)](repeating: (100, 15_000_000), count: 20)
        for k in 6...8 { inside[k] = (200, 15_000_000) }
        XCTAssertEqual(try sustain(inside).peakMtps, 13333)
    }

    func testOnsetNeedsThreeConsecutiveSmoothedWindowsBelowNinetyPercent() throws {
        var w = [(Int64, Int64)](repeating: (100, 15_000_000), count: 20)
        w[10] = (50, 15_000_000)
        w[11] = (50, 15_000_000)
        XCTAssertNil(try sustain(w).onsetMs, "two low windows are smoothed away")
        for k in 10...12 { w[k] = (50, 15_000_000) }
        XCTAssertNotNil(try sustain(w).onsetMs)
    }

    func testOnsetThresholdIsStrictlyBelowNinetyPercentOfPeak() throws {
        // peak 10000 -> threshold 9000; windows at exactly 9000 are not below it.
        var w = [(Int64, Int64)](repeating: (10_000, 1_000_000_000), count: 12)
        for k in 5..<12 { w[k] = (9_000, 1_000_000_000) }
        XCTAssertNil(try sustain(w).onsetMs)
        for k in 5..<12 { w[k] = (8_999, 1_000_000_000) }
        XCTAssertNotNil(try sustain(w).onsetMs)
    }

    func testPlateauNotReachedFlagAndTailOfOnsetWindows() throws {
        var w = [(Int64, Int64)](repeating: (100, 15_000_000), count: 30)
        for k in 30..<34 { w.append((80, 15_000_000)); _ = k }
        let s = try sustain(w, reason: "THERMAL_SOFT")
        XCTAssertEqual(s.onsetMs, 450_000)
        XCTAssertEqual(s.stabilityPermille, 800)
        XCTAssertTrue(s.flags.contains("PLATEAU_NOT_REACHED"))
        XCTAssertEqual(s.confidence, .medium)
    }

    func testConfidenceRulesOfSection63() throws {
        let flat = { (n: Int) in [(Int64, Int64)](repeating: (100, 15_000_000), count: n) }
        XCTAssertEqual(try sustain(flat(40), cool: false).confidence, .low, "a sustained phase that starts warm is low")
        XCTAssertEqual(try sustain(flat(10), reason: "USER_STOP").confidence, .low)
        XCTAssertEqual(try sustain(flat(20), reason: "TIME_CAP").confidence, .medium)
        XCTAssertEqual(try sustain(flat(32), reason: "TIME_CAP").confidence, .high, "TIME_CAP with duration >= 480 s")
        XCTAssertEqual(try sustain(flat(31), reason: "TIME_CAP").confidence, .medium)
        XCTAssertEqual(try sustain(flat(5), reason: "PLATEAU").confidence, .high)
        let hard = try sustain(flat(24), reason: "THERMAL_HARD")
        XCTAssertEqual(hard.confidence, .medium)
        XCTAssertTrue(hard.flags.contains("HARD_CEILING"))
        XCTAssertFalse(try sustain(flat(24), reason: "USER_STOP").flags.contains("HARD_CEILING"))
    }

    func testWorkedExampleAnswers() throws {
        let a = try Derivation.derive(try example()).answers
        XCTAssertEqual(a.usableMemoryBytes, 7_200_000_000)
        XCTAssertEqual(a.safetyPermille, 750)
        XCTAssertEqual(a.q7b, Q7B(basis: "measured", decodeMtps: 7394, ttft512Micros: 8_572_100, verdict: "usable"))
        XCTAssertEqual(a.maxHold, MaxHold(weightBytes: 6_147_702_857, kvRatioPermille: 120, approxParamsQ4: 10_078_201_404, largestLoadedTier: "T3"))
        XCTAssertEqual(a.answer2000, Answer2000(tier: "T3", depthRatioPermille: 892, micros: 355_499_032, thermalModel: true))
        XCTAssertEqual(a.throttle, Throttle(tier: "T3", onsetMs: 195_000, stabilityPermille: 663, testedMs: 435_000))
        XCTAssertEqual(a.role.code, "occasional-helper")
        XCTAssertEqual(a.role.t2PlateauMtps, 9608)
        XCTAssertEqual(a.role.t3PlateauMtps, 5033)
        XCTAssertEqual(a.overallConfidence, .high)
    }

    func testVerdictThresholds() {
        XCTAssertEqual(Derivation.verdict(decode: 10_000, ttft: 2_000_000), "comfortable")
        XCTAssertEqual(Derivation.verdict(decode: 9_999, ttft: 2_000_000), "usable")
        XCTAssertEqual(Derivation.verdict(decode: 10_000, ttft: 2_000_001), "usable")
        XCTAssertEqual(Derivation.verdict(decode: 4_000, ttft: 10_000_000), "usable")
        XCTAssertEqual(Derivation.verdict(decode: 3_999, ttft: 10_000_000), "too-slow")
        XCTAssertEqual(Derivation.verdict(decode: 4_000, ttft: 10_000_001), "too-slow")
    }

    func testRoleTableFirstMatchWins() {
        func role(_ platform: String, _ form: String, t2: Int64?, t3: Int64?) -> String {
            Derivation.roleFor(platform: platform, form: form, t2: t2.map { ($0, false) }, t3: t3.map { ($0, false) }).code
        }
        XCTAssertEqual(role("ios", "desktop", t2: 99_999, t3: 99_999), "requester-foreground-helper")
        XCTAssertEqual(role("linux", "desktop", t2: 20_000, t3: 10_000), "strong-provider")
        XCTAssertEqual(role("linux", "desktop", t2: 20_000, t3: 9_999), "small-model-provider")
        XCTAssertEqual(role("linux", "laptop", t2: 10_000, t3: nil), "small-model-provider")
        XCTAssertEqual(role("linux", "laptop", t2: 9_999, t3: 50_000), "requester")
        XCTAssertEqual(role("android", "phone", t2: 8_000, t3: nil), "occasional-helper")
        XCTAssertEqual(role("android", "phone", t2: 7_999, t3: nil), "requester")
        XCTAssertEqual(role("android", "phone", t2: nil, t3: nil), "requester")
    }

    func testQuestionOneIsEstimatedFromT2ThroughTheOriginWhenT3FitsButWasNotMeasured() throws {
        // Remove T3 (and the heat test that rides on it) from the design example.
        let value = try designExample()
            .setting([.key("tiers")], to: .array(Array((try XCTUnwrap(try designExample().value(at: [.key("tiers")])?.elements)).prefix(2))))
            .setting([.key("sustain")], to: .null)
        let a = try Derivation.derive(try XCTUnwrap(try? decodeBench(value).get())).answers
        XCTAssertEqual(a.q7b.basis, "estimated")
        XCTAssertEqual(a.q7b.decodeMtps, 7198)
        XCTAssertEqual(a.q7b.verdict, "usable")
    }

    func testQuestionOneCannotHoldWhenT3DoesNotFit() throws {
        let value = try designExample()
            .setting([.key("tiers")], to: .array(Array((try XCTUnwrap(try designExample().value(at: [.key("tiers")])?.elements)).prefix(2))))
            .setting([.key("sustain")], to: .null)
            .setting([.key("memory"), .key("availAtStartBytes")], to: .int(5_000_000_000))
        let a = try Derivation.derive(try XCTUnwrap(try? decodeBench(value).get())).answers
        XCTAssertEqual(a.usableMemoryBytes, 3_750_000_000)
        XCTAssertEqual(a.q7b.basis, "cannot-hold")
    }

    func testMemoryLimitIsTheMinimumOfTheNonNullLimits() throws {
        let base = try designExample().setting([.key("memory"), .key("processLimitBytes")], to: .int(4_000_000_000))
            .setting([.key("memory"), .key("gpuWorkingSetBytes")], to: .int(6_000_000_000))
        let a = try Derivation.derive(try XCTUnwrap(try? decodeBench(base).get())).answers
        XCTAssertEqual(a.usableMemoryBytes, 3_000_000_000)
    }

    func testFailedNumericsTierIsSkippedByEveryAnswer() throws {
        // T3's reference is 1880 milli-nats; a measurement of 2400 is a 276 permille deviation.
        let value = try designExample().setting([.key("tiers"), .at(2), .key("numerics"), .key("milliNatsPerToken")], to: .int(2400))
        let d = try Derivation.derive(try XCTUnwrap(try? decodeBench(value).get()))
        XCTAssertEqual(d.tier("T3")?.numerics, "fail")
        XCTAssertEqual(d.answers.maxHold?.largestLoadedTier, "T2")
        XCTAssertEqual(d.answers.q7b.basis, "estimated")
        XCTAssertFalse(d.answers.answer2000?.thermalModel ?? true)
    }

    func testNumericsVerdictBoundaries() throws {
        func v(_ nll: Int64, _ ref: Int64?) throws -> String { try Derivation.numerics(BenchDocument.Numerics(milliNatsPerToken: nll, refMilliNatsPerToken: ref)).0 }
        XCTAssertEqual(try v(1020, 1000), "pass")
        XCTAssertEqual(try v(1021, 1000), "warn")
        XCTAssertEqual(try v(1050, 1000), "warn")
        XCTAssertEqual(try v(1051, 1000), "fail")
        XCTAssertEqual(try v(950, 1000), "warn")
        XCTAssertEqual(try v(1000, nil), "not-run", "a result is never pass without a reference")
    }

    func testProjectedT3RowMatchesTheSpecExcerpt() throws {
        let doc = try example()
        let rows = try Projection.project(doc, audience: .own)
        XCTAssertEqual(rows.count, 3)
        let t3 = rows[2]
        func i(_ path: [Step]) -> Int64? { t3.value(at: path)?.intValue }
        XCTAssertEqual(t3.value(at: [.key("modelId")])?.stringValue, "qwen3-8b")
        XCTAssertEqual(i([.key("fileBytes")]), 5_027_783_488)
        XCTAssertEqual(i([.key("settings"), .key("batchTokens")]), 512)
        XCTAssertEqual(i([.key("runs"), .key("planned")]), 5)
        XCTAssertEqual(i([.key("runs"), .key("discarded")]), 1)
        XCTAssertEqual(i([.key("measuredAtMs")]), 1_790_669_190_000)
        XCTAssertEqual(t3.value(at: [.key("conditions"), .key("thermalStart")])?.stringValue, "nominal")
        XCTAssertEqual(i([.key("prefill"), .at(0), .key("milliTokPerSec"), .key("p10")]), 59_458)
        XCTAssertEqual(i([.key("prefill"), .at(0), .key("milliTokPerSec"), .key("p50")]), 59_743)
        XCTAssertEqual(i([.key("prefill"), .at(0), .key("milliTokPerSec"), .key("p90")]), 60_249)
        XCTAssertEqual(i([.key("prefill"), .at(0), .key("ttftMicros"), .key("p10")]), 8_500_200)
        XCTAssertEqual(i([.key("prefill"), .at(0), .key("ttftMicros"), .key("p50")]), 8_572_100)
        XCTAssertEqual(i([.key("prefill"), .at(0), .key("ttftMicros"), .key("p90")]), 8_613_000)
        XCTAssertEqual(i([.key("decode"), .at(1), .key("contextTokens")]), 2048)
        XCTAssertEqual(i([.key("decode"), .at(1), .key("milliTokPerSec"), .key("p50")]), 6597)
        XCTAssertEqual(i([.key("sustained"), .key("durationMs")]), 435_000)
        XCTAssertEqual(i([.key("sustained"), .key("steadyMilliTokPerSec")]), 5033)
        XCTAssertEqual(i([.key("sustained"), .key("throttleOnsetMs")]), 195_000)
        XCTAssertEqual(i([.key("sustained"), .key("curve"), .at(0), .at(1)]), 7583)
        XCTAssertEqual(i([.key("sustained"), .key("curve"), .at(1), .at(1)]), 7516)
        XCTAssertEqual(i([.key("memory"), .key("kvCacheBytes")]), 603_979_776)
        XCTAssertEqual(t3.value(at: [.key("power"), .key("method")])?.stringValue, "unavailable")
        XCTAssertEqual(t3.value(at: [.key("flags")])?.elements?.compactMap { $0.stringValue }, ["charging", "confidence-high", "thermal-throttled"])
    }

    func testFileAudienceOmitsBatteryLevelScreenStateAndSocTemperature() throws {
        let rows = try Projection.project(try example(), audience: .file)
        for row in rows {
            let c = try XCTUnwrap(row.value(at: [.key("conditions")])?.members?.map { $0.name })
            XCTAssertEqual(c.sorted(), ["charging", "thermalStart"])
        }
    }

    func testFileBenchTruncatesTimesToTheDayAndNullsBuildDriverBatteryAndScreen() throws {
        let file = Projection.fileBench(try designExample())
        XCTAssertEqual(file.value(at: [.key("device"), .key("osBuild")]), .null)
        XCTAssertEqual(file.value(at: [.key("device"), .key("gpuDriver")]), .null)
        XCTAssertEqual(file.value(at: [.key("run"), .key("batteryStartPermille")]), .null)
        XCTAssertEqual(file.value(at: [.key("run"), .key("screenOn")]), .null)
        XCTAssertEqual(file.value(at: [.key("run"), .key("startedAtMs")])?.intValue, 1_790_640_000_000)
        XCTAssertEqual(file.value(at: [.key("run"), .key("endedAtMs")])?.intValue, 1_790_640_000_000)
        XCTAssertEqual(file.value(at: [.key("tiers"), .at(0), .key("startedAtMs")])?.intValue, 1_790_640_000_000)
        XCTAssertEqual(file.value(at: [.key("run"), .key("dayUtc")])?.stringValue, "2026-09-29")
        XCTAssertNoThrow(try decodeBench(file, file: true).get())
        XCTAssertThrowsError(try decodeBench(try designExample(), file: true).get(), "the own form is not a file-form document")
    }

    func testKvCacheProductOverflowIsAnArithmeticErrorNotAWrap() throws {
        let value = try designExample().setting([.key("tiers"), .at(2), .key("kvBytesPerToken")], to: .int(1 << 40))
            .setting([.key("tiers"), .at(2), .key("nCtx")], to: .int(10_000_000))
        let doc = try XCTUnwrap(try? decodeBench(value).get())
        XCTAssertThrowsError(try Projection.project(doc, audience: .own)) { XCTAssertTrue($0 is BenchArithmeticError) }
    }

    func testAnUnprojectableTierIsOmittedNotInvented() throws {
        // T1 keeps one pp512 rep only: insufficient, so it has no value and no row.
        let value = try designExample()
            .setting([.key("tiers"), .at(0), .key("tests"), .at(0), .key("samples")], to: .ints([1_968_000]))
            .setting([.key("tiers"), .at(0), .key("tests"), .at(0), .key("wholeSamples")], to: .ints([1_969_500]))
        let rows = try Projection.project(try XCTUnwrap(try? decodeBench(value).get()), audience: .own)
        XCTAssertEqual(rows.count, 2)
    }
}
