@testable import AsomBenchCore
import XCTest

/// M04 statistics (benchmark.md section 9, design B7). The seeds of section 9.5 are the anchors: their inputs and
/// outputs are in the spec text, not taken from a vector file.
final class StatsTests: XCTestCase {
    private func stat(_ seconds: [Double], tokens: Int64 = 128, _ context: TestContext = TestContext()) throws -> TestStat {
        let rates = try seconds.map { try Stats.rate(tokens: tokens, micros: Int64($0 * 1_000_000)) }
        return try Stats.stat(rates: rates, context: context)
    }

    func testSeedM04_001OutlierIsExcludedAndValueIsLowerMedian() throws {
        let s = try stat([17.28, 17.35, 17.31, 17.265, 23.0])
        XCTAssertEqual(s.value, 7394)
        XCTAssertEqual(s.kept, 4)
        XCTAssertEqual(s.keptIdx, [0, 1, 2, 3])
        XCTAssertEqual(s.flags, ["OUTLIER_EXCLUDED"])
        XCTAssertEqual(s.confidence, .high)
    }

    func testSeedM04_002bTwentyNinePercentLowRepIsExcluded() throws {
        let s = try stat([10, 10, 10, 10, 14])
        XCTAssertEqual(s.kept, 4)
        XCTAssertEqual(s.flags, ["OUTLIER_EXCLUDED"])
        XCTAssertEqual(s.confidence, .high)
        XCTAssertEqual(s.value, 12800)
    }

    func testSeedM04_002aTwentyThreePercentLowRepStaysAndSpreadIs230() throws {
        let s = try stat([10, 10, 10, 10, 13])
        XCTAssertEqual(s.kept, 5)
        XCTAssertEqual(s.relSpreadPermille, 230)
        XCTAssertEqual(s.confidence, .low)
    }

    func testUnstableKeepsEverythingWhenMoreThanAFifthDeviate() throws {
        // Two deviant reps of five exceed floor(5/5) = 1.
        let s = try stat([10, 10.1, 9.9, 12.5, 12.8])
        XCTAssertEqual(s.kept, 5)
        XCTAssertTrue(s.flags.contains("UNSTABLE"))
        XCTAssertEqual(s.confidence, .low)
    }

    func testFloorOfOneFifthAllowsNoExclusionForFourReps() throws {
        let s = try stat([14, 10, 10, 10])
        XCTAssertEqual(s.kept, 4)
        XCTAssertEqual(s.flags, ["UNSTABLE"])
    }

    func testMadBoundaryIsInclusiveAt4449MilliMads() throws {
        // med 10000, deviations [0,1000,1000,0,d]: MAD 1000. d = 4449 is exactly 4449 milli-MADs and stays; 4450 goes.
        let at = try Stats.stat(rates: [10_000, 11_000, 9_000, 10_000, 14_449] as [Int64], context: TestContext())
        XCTAssertEqual(at.kept, 5)
        XCTAssertFalse(at.flags.contains("OUTLIER_EXCLUDED"))
        let over = try Stats.stat(rates: [10_000, 11_000, 9_000, 10_000, 14_450] as [Int64], context: TestContext())
        XCTAssertEqual(over.kept, 4)
        XCTAssertEqual(over.flags, ["OUTLIER_EXCLUDED"])
    }

    func testMadZeroRuleCutsAtTwentyFivePercentOfTheMedian() throws {
        let at = try Stats.stat(rates: [100, 100, 100, 100, 125], context: TestContext())
        XCTAssertEqual(at.kept, 5, "exactly 25% from the median is kept")
        let over = try Stats.stat(rates: [100, 100, 100, 100, 126], context: TestContext())
        XCTAssertEqual(over.kept, 4)
        // Finer: median 1000, so the cut is 250 exactly; 1250 stays, 1251 goes.
        XCTAssertEqual(try Stats.stat(rates: [1000, 1000, 1000, 1000, 1250], context: TestContext()).kept, 5)
        XCTAssertEqual(try Stats.stat(rates: [1000, 1000, 1000, 1000, 1251], context: TestContext()).kept, 4)
    }

    func testOneRepIsInsufficientAndReportsNoValue() throws {
        let s = try Stats.stat(rates: [500], context: TestContext())
        XCTAssertNil(s.value)
        XCTAssertEqual(s.confidence, .insufficient)
        XCTAssertEqual(s.kept, 1)
        XCTAssertEqual(s.relSpreadPermille, 0)
    }

    func testTwoAgreeingRepsAreLow() throws {
        let s = try Stats.stat(rates: [500, 500], context: TestContext())
        XCTAssertEqual(s.value, 500)
        XCTAssertEqual(s.confidence, .low)
    }

    func testConfidenceBoundaries() throws {
        let tight: [Int64] = [10_000, 10_010, 10_020, 10_005, 10_015]
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(contentionPermille: 50)).confidence, .high)
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(contentionPermille: 51)).confidence, .medium)
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(contentionPermille: 150)).confidence, .medium)
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(contentionPermille: 151)).confidence, .low)
        // Spread of exactly 50 permille: value 1000 (lower median of five), max - min = 50.
        XCTAssertEqual(try Stats.stat(rates: [1000, 1000, 1000, 1000, 1050], context: TestContext()).confidence, .high)
        XCTAssertEqual(try Stats.stat(rates: [1000, 1000, 1000, 1000, 1051], context: TestContext()).confidence, .medium)
    }

    func testCaps() throws {
        let tight: [Int64] = [10_000, 10_010, 10_020, 10_005, 10_015]
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(warmStart: true)).confidence, .medium)
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(virtualized: true)).confidence, .medium)
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(streamTiming: true)).confidence, .medium)
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(restarted: true)).confidence, .medium)
        XCTAssertEqual(try Stats.stat(rates: tight, context: TestContext(swapped: true)).confidence, .low)
    }

    func testThermalDriftStrictlySlowingRepsAreLowAndCappedDespiteBeingInsideTheMadBand() throws {
        let s = try stat([10, 10.4, 10.8, 11.2, 11.6])
        XCTAssertTrue(s.flags.contains("THERMAL_DRIFT"))
        XCTAssertEqual(s.confidence, .low)
    }

    func testThermalDriftByFirstMinusLastOverOneHundredPermille() throws {
        XCTAssertTrue(try stat([10, 10, 10, 10, 13]).flags.contains("THERMAL_DRIFT"))
        XCTAssertFalse(try stat([10, 10, 10, 10, 10.5]).flags.contains("THERMAL_DRIFT"))
    }

    func testDriftPutsTheFastFirstRepBackAndDropsTheExclusionFlag() throws {
        let s = try stat([9, 12.5, 12.6, 12.7, 12.8])
        XCTAssertEqual(s.kept, 5)
        XCTAssertEqual(s.flags, ["THERMAL_DRIFT"])
        XCTAssertEqual(s.keptIdx, [0, 1, 2, 3, 4])
    }

    func testFlatRepsAreNotDrift() throws {
        let s = try Stats.stat(rates: [10_000, 10_000, 10_000, 10_000, 10_000], context: TestContext())
        XCTAssertFalse(s.flags.contains("THERMAL_DRIFT"))
        XCTAssertEqual(s.confidence, .high)
    }

    func testNearestRankPercentiles() {
        let v: [Int64] = [59458, 59743, 60002, 60249]
        XCTAssertEqual(Stats.nearestRank(v, permille: 100), 59458)
        XCTAssertEqual(Stats.nearestRank(v, permille: 900), 60249)
        XCTAssertEqual(Stats.nearestRank(v, permille: 500), 59743)
        XCTAssertEqual(Stats.nearestRank([7], permille: 100), 7)
        XCTAssertEqual(Stats.nearestRank([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], permille: 100), 1)
        XCTAssertEqual(Stats.nearestRank([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], permille: 101), 2)
    }

    func testMediansAreConservative() {
        XCTAssertEqual(Stats.lowerMedian([1, 2, 3, 4]), 2)
        XCTAssertEqual(Stats.upperMedian([1, 2, 3, 4]), 3)
        XCTAssertEqual(Stats.lowerMedian([5, 1, 3]), 3)
        XCTAssertEqual(Stats.upperMedian([5, 1, 3]), 3)
    }

    func testRateIsTokensTimesBillionOverMicrosFloored() throws {
        XCTAssertEqual(try Stats.rate(tokens: 128, micros: 17_280_000), 7407)
        XCTAssertEqual(try Stats.rate(tokens: 512, micros: 8_533_000), 60_002)
        XCTAssertEqual(try Stats.rate(tokens: 1, micros: 3_600_000_000), 0, "a rate below one milli-token per second floors to zero")
    }

    func testCheckedArithmeticThrowsInsteadOfWrapping() {
        XCTAssertThrowsError(try Checked.mul(Int64.max, 2))
        XCTAssertThrowsError(try Checked.mul(3_600_000_000, 1_000_000_000 * 10))
        XCTAssertThrowsError(try Checked.add(Int64.max, 1))
        XCTAssertThrowsError(try Checked.sub(Int64.min, 1))
        XCTAssertThrowsError(try Checked.div(1, 0))
        XCTAssertThrowsError(try Checked.div(-1, 3))
        XCTAssertEqual(try Checked.mul(3_037_000_499, 3_037_000_499), 9_223_372_030_926_249_001)
        XCTAssertThrowsError(try Checked.mul(3_037_000_500, 3_037_000_500))
        // The value that wraps to a positive number in a 64-bit product (M03-178's shape): 1.9e9 * 1e9 * 10.
        XCTAssertThrowsError(try Checked.mul(try Checked.mul(1_900_000_000, 1_000_000_000), 10))
    }

    func testDriftSpreadBoundaryIsOverOneHundredPermilleOfTheFirstRep() throws {
        // first 10000, last 9000: exactly 100 permille, not over. 8990: 101 permille, drift.
        let at = try Stats.stat(rates: [10_000, 10_000, 10_000, 10_000, 9_000] as [Int64], context: TestContext())
        XCTAssertFalse(at.flags.contains("THERMAL_DRIFT"))
        let over = try Stats.stat(rates: [10_000, 10_000, 10_000, 10_000, 8_990] as [Int64], context: TestContext())
        XCTAssertTrue(over.flags.contains("THERMAL_DRIFT"))
        XCTAssertEqual(over.confidence, .low)
    }

    /// Pins this lane's reading of an open point (apple/ERRATA.md E-23, F-4): three kept reps that slow at every step are drift.
    /// A reading that needs four kept reps would call this medium.
    func testThreeRepsSlowingAtEveryStepAreDriftInThisLane() throws {
        let s = try stat([10.0, 10.3, 10.6])
        XCTAssertEqual(s.kept, 3)
        XCTAssertTrue(s.flags.contains("THERMAL_DRIFT"))
        XCTAssertEqual(s.confidence, .low)
    }
}
