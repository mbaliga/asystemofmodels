import AsomBenchCore
@testable import AsomRouterCore
import XCTest

/// The parts of LAB_SPEC.md 6.6 that span more than one file of a peer or more than one claim seq: the discard-budget record that a new
/// claim seq must keep, the inherited DISCREPANT, the per-peer 7-day penalty ("disc = 700; 400 if >= 2 keys of this peer are DISCREPANT (for >= 7 days,
/// doubling on repeat)") and the cross-file `disc`. Expected numbers are computed by hand from the spec text and the worked numbers (a peer at half its
/// claim: elapsed 39,911 ms, ratio 500; an honest one: 20,500 ms, ratio 973; a 40-byte answer after 200,000 ms: predicted 5,461, ratio 27).
final class PeerClaimBookTests: XCTestCase {
    static let claim = ClaimTrackerTests.claim
    static let day: Int64 = 86_400_000

    func result(bytes: Int64 = 1200, elapsed: Int64 = 39_911) throws -> AttemptResult {
        let a = AttemptInput(
            tBodyMs: 0, chunks: [ContentChunk(tMs: elapsed, bytes: bytes)], end: InferEnd(tMs: elapsed, payload: ClaimTrackerTests.done), promptTokens: 500, promptBytes: 5120,
            maxTokens: 1024, link: ClaimTrackerTests.link, claim: Self.claim, claimFileSha256: ClaimTrackerTests.sha
        )
        return try AttemptEvaluator.evaluate(a)
    }

    func feed(_ book: inout PeerClaimBook, file: String, count: Int, at: Int64, bytes: Int64 = 1200, elapsed: Int64 = 39_911) throws {
        let r = try result(bytes: bytes, elapsed: elapsed)
        for _ in 0..<count { try book.onObservation(file: file, result: r, atWallMs: at) }
    }

    func state(_ t: ClaimTracker) throws -> TrackerView { try t.view(claim: Self.claim, promptTokens: 500) }

    // MARK: ERR-FX-RT-3: a new claim seq keeps the discard-budget record

    func testANewClaimSeqKeepsTheDiscardBudgetClampAndRestartsOnlyTheWindow() throws {
        var t = ClaimTracker()
        let truncated = try result(bytes: 40, elapsed: 200_000)
        XCTAssertEqual(truncated.ratio, 27)
        XCTAssertEqual(truncated.predictedMs, 5461)
        XCTAssertEqual(truncated.discard, .short)
        for _ in 0..<4 { t.onObservation(truncated) }
        var v = try state(t)
        XCTAssertTrue(v.budgetTripped)
        XCTAssertEqual(v.state, .weak)
        t.onNewClaimSeq()
        XCTAssertEqual(t.recent.count, 4, "the record of the last 20 candidate observations survives")
        XCTAssertEqual(t.window, [])
        v = try state(t)
        XCTAssertEqual(v.n, 0)
        XCTAssertTrue(v.budgetTripped)
        XCTAssertEqual(v.state, .weak, "WEAK is not inherited by restarting W; the budget record is what keeps it")
        XCTAssertEqual(v.minRatio, 27)
        XCTAssertEqual(v.tracked, TrackedRates(prefill: 2700, decodeAtP: 540, steady: 540))
        // Every truncated answer under 8 bytes: the clamp is 0.
        var tiny = ClaimTracker()
        let under = try result(bytes: 4, elapsed: 1000)
        for _ in 0..<4 { tiny.onObservation(under) }
        tiny.onNewClaimSeq()
        XCTAssertEqual(try state(tiny).tracked, TrackedRates(prefill: 0, decodeAtP: 0, steady: 0))
        // The record ages out through new observations: 5 new kept ones leave 4 discarded of 9, which is not more than half.
        for _ in 0..<5 { t.onObservation(try result(elapsed: 20_500)) }
        XCTAssertEqual(t.recent.count, 9)
        XCTAssertFalse(try state(t).budgetTripped, "4 discarded of 9 is not more than half")
        XCTAssertEqual(try state(t).state, .corroborated)
    }

    // MARK: The inherited DISCREPANT, through the claim-body gate

    func testInheritanceThroughTheGateHoldsNineGoodObservationsAndClearsAtTen() throws {
        var t = ClaimTracker()
        for _ in 0..<5 { t.onObservation(try result()) }
        var gate = ClaimBodyGate()
        XCTAssertTrue(gate.accept(seq: 1, atMs: 0))
        XCTAssertTrue(gate.accept(seq: 2, atMs: 86_400_000))
        t.onNewClaimSeq()
        var states: [ClaimState] = []
        for _ in 0..<10 { t.onObservation(try result(elapsed: 19_000)); states.append(try state(t).state) }
        XCTAssertEqual(states, [ClaimState](repeating: .discrepant, count: 9) + [.corroborated])
        // A window that was not DISCREPANT is not inherited.
        var fine = ClaimTracker()
        for _ in 0..<5 { fine.onObservation(try result(elapsed: 20_500)) }
        fine.onNewClaimSeq()
        var fineStates: [ClaimState] = []
        for _ in 0..<5 { fine.onObservation(try result(elapsed: 19_000)); fineStates.append(try state(fine).state) }
        XCTAssertEqual(fineStates, [.unverified, .unverified, .unverified, .unverified, .corroborated])
        // Ten observations whose best is below CORR do not clear it.
        var slow = ClaimTracker()
        for _ in 0..<5 { slow.onObservation(try result()) }
        slow.onNewClaimSeq()
        for _ in 0..<10 { slow.onObservation(try result()) }
        XCTAssertEqual(try state(slow).state, .discrepant)
    }

    // MARK: The penalty latch

    func testOneDiscrepantFileLatchesNoPenalty() throws {
        var book = PeerClaimBook()
        try feed(&book, file: "A", count: 5, at: 1000)
        XCTAssertNil(book.penalty)
        XCTAssertEqual(book.discrepantFileCount, 1)
        XCTAssertEqual(book.discPermille(atWallMs: 1000), 700)
        XCTAssertEqual(book.discPermille(atWallMs: 5000), 700)
        // Many observations of one file never latch: the count is files, not observations.
        try feed(&book, file: "A", count: 30, at: 9000)
        XCTAssertNil(book.penalty)
        XCTAssertEqual(book.discrepantFileCount, 1)
    }

    func testTwoDiscrepantFilesLatchSevenDaysAtTheObservationThatMakesTheSecondDiscrepant() throws {
        var book = PeerClaimBook()
        try feed(&book, file: "A", count: 5, at: 1000)
        try feed(&book, file: "B", count: 4, at: 2000)
        XCTAssertNil(book.penalty, "B is still UNVERIFIED at its fourth observation")
        XCTAssertEqual(book.discrepantFileCount, 1)
        try feed(&book, file: "B", count: 1, at: 2000)
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: 604_802_000, repeats: 1))
        XCTAssertEqual(book.discrepantFileCount, 2)
        XCTAssertEqual(book.discPermille(atWallMs: 2000), 400)
        XCTAssertEqual(book.discPermille(atWallMs: 604_801_999), 400)
        XCTAssertEqual(book.discPermille(atWallMs: 604_802_000), 400, "the penalty ended, but both files are still DISCREPANT")
        // A running penalty is not extended by a further observation.
        try feed(&book, file: "B", count: 1, at: 3000)
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: 604_802_000, repeats: 1))
    }

    func testThePenaltyDoublesOnEveryRepeatAndIsLatchedOnlyAfterTheLastOneEnded() throws {
        var book = PeerClaimBook()
        try feed(&book, file: "A", count: 5, at: 1000)
        try feed(&book, file: "B", count: 5, at: 2000)
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: 604_802_000, repeats: 1))
        try feed(&book, file: "B", count: 1, at: 604_801_999)
        XCTAssertEqual(book.penalty?.repeats, 1, "one millisecond before the end")
        try feed(&book, file: "B", count: 1, at: 604_802_005)
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: 604_802_005 + 14 * Self.day, repeats: 2))
        XCTAssertEqual(book.penalty?.untilWallMs, 1_814_402_005)
        try feed(&book, file: "B", count: 1, at: 1_814_402_010)
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: 1_814_402_010 + 28 * Self.day, repeats: 3))
        XCTAssertEqual(book.penalty?.untilWallMs, 4_233_602_010)
        let end3 = book.penalty!.untilWallMs
        try feed(&book, file: "A", count: 1, at: end3)
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: end3 + 56 * Self.day, repeats: 4), "an observation of the other file at the instant the penalty ends")
    }

    func testThePenaltyEndsWhenFewerThanTwoFilesStayDiscrepant() throws {
        var book = PeerClaimBook()
        try feed(&book, file: "A", count: 5, at: 1000)
        try feed(&book, file: "B", count: 5, at: 2000)
        // B recovers: twenty honest observations fill its window (best 973 >= CORR).
        try feed(&book, file: "B", count: 20, at: 3000, elapsed: 20_500)
        XCTAssertEqual(book.discrepantFileCount, 1)
        XCTAssertEqual(book.discPermille(atWallMs: 3000), 400, "the latched penalty still runs")
        XCTAssertEqual(book.discPermille(atWallMs: 604_802_000), 700, "at its end only one file is DISCREPANT")
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: 604_802_000, repeats: 1), "the record stays (the next latch doubles)")
        // And a later observation does not latch with one file left.
        try feed(&book, file: "A", count: 1, at: 700_000_000)
        XCTAssertEqual(book.penalty?.repeats, 1)
    }

    func testDiscCountsFilesDiscrepantByInheritanceToo() throws {
        func book(_ inherited: [String], penalty: DiscPenalty? = nil) -> PeerClaimBook {
            var files: [String: ClaimTracker] = [:]
            for f in inherited { files[f] = ClaimTracker(window: [], recent: [], strikes: 0, inheritedDiscrepant: true) }
            return PeerClaimBook(files: files, penalty: penalty)
        }
        XCTAssertEqual(book(["A", "B"]).discPermille(atWallMs: 1000), 400, "no observation, so no penalty was latched, yet two files are DISCREPANT")
        XCTAssertNil(book(["A", "B"]).penalty)
        XCTAssertEqual(book(["A"]).discPermille(atWallMs: 1000), 700)
        XCTAssertEqual(book([]).discPermille(atWallMs: 1000), 700)
        XCTAssertEqual(book([], penalty: DiscPenalty(untilWallMs: 5000, repeats: 1)).discPermille(atWallMs: 4999), 400, "a running penalty whatever the states are")
        XCTAssertEqual(book(["A"], penalty: DiscPenalty(untilWallMs: 5000, repeats: 1)).discPermille(atWallMs: 5000), 700, "ended at now")
        // By ratios: best < 600.
        let two = PeerClaimBook(files: [
            "A": ClaimTracker(window: [100, 100, 100, 100, 100], recent: [], strikes: 0, inheritedDiscrepant: false),
            "B": ClaimTracker(window: [100, 100, 100, 100, 100], recent: [], strikes: 0, inheritedDiscrepant: false),
        ], penalty: nil)
        XCTAssertEqual(two.discPermille(atWallMs: 1000), 400)
        // WEAK (best 600..799), a file with fewer than 5 ratios and no inheritance, and CORROBORATED are not counted.
        let notBad = PeerClaimBook(files: [
            "A": ClaimTracker(window: [600, 600, 600, 600, 600], recent: [], strikes: 0, inheritedDiscrepant: false),
            "B": ClaimTracker(window: [100, 100, 100, 100], recent: [], strikes: 0, inheritedDiscrepant: false),
            "C": ClaimTracker(window: [900, 900, 900, 900, 900], recent: [], strikes: 0, inheritedDiscrepant: false),
        ], penalty: nil)
        XCTAssertEqual(notBad.discrepantFileCount, 0)
        XCTAssertEqual(notBad.discPermille(atWallMs: 1000), 700)
        // A tripped budget makes a file WEAK, so it is not counted.
        let truncated = (0..<4).map { _ in RecentObservation(kept: false, ratio: 10, outBytes: 50) }
        let weak = PeerClaimBook(files: [
            "A": ClaimTracker(window: [100, 100, 100, 100, 100], recent: truncated, strikes: 0, inheritedDiscrepant: false),
            "B": ClaimTracker(window: [100, 100, 100, 100, 100], recent: [], strikes: 0, inheritedDiscrepant: false),
        ], penalty: nil)
        XCTAssertEqual(weak.discrepantFileCount, 1)
    }

    func testNewClaimSeqOfTwoDiscrepantFilesGivesDiscWithoutAPenalty() throws {
        var book = PeerClaimBook()
        try feed(&book, file: "A", count: 5, at: 1000)
        try feed(&book, file: "B", count: 5, at: 2000)
        let latched = book.penalty
        book.onNewClaimSeq(file: "A")
        XCTAssertEqual(book.penalty, latched)
        XCTAssertEqual(book.discrepantFileCount, 2, "both are DISCREPANT: A by inheritance, B by its ratios")
        XCTAssertEqual(book.files["A"]?.window, [])
        XCTAssertEqual(book.discPermille(atWallMs: 604_802_000), 400)
        // A file the book has never seen is a no-op.
        book.onNewClaimSeq(file: "Z")
        XCTAssertNil(book.files["Z"])
    }

    func testPenaltyDurationSaturatesInsteadOfTrapping() throws {
        var book = PeerClaimBook(files: [
            "A": ClaimTracker(window: [], recent: [], strikes: 0, inheritedDiscrepant: true),
            "B": ClaimTracker(window: [], recent: [], strikes: 0, inheritedDiscrepant: true),
        ], penalty: DiscPenalty(untilWallMs: 10, repeats: 60))
        try feed(&book, file: "B", count: 1, at: 20)
        XCTAssertEqual(book.penalty, DiscPenalty(untilWallMs: Int64.max, repeats: 61))
        XCTAssertEqual(book.discPermille(atWallMs: Int64.max - 1), 400)
    }
}
