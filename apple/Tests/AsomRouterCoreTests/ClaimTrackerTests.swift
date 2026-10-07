import AsomBenchCore
import AsomJSON
@testable import AsomRouterCore
import XCTest

/// The claim tracker of LAB_SPEC.md 6.6. The expected numbers here are computed by hand from the spec's formulas and worked numbers
/// (E1 = 11, E5 = 5,000, outTokEst = 300, E7 = 14,950, predicted = 19,961), not copied from the vector files; the vector files are run by
/// the conformance kit. Every law keeps a counter of the cases that exercised it and fails when the count is zero.
final class ClaimTrackerTests: XCTestCase {
    static let claim = ClaimRow(
        decodeAt: [DecodePoint(contextTokens: 512, milliTokPerSec: 20_000)], prefillMilliTokPerSec: 100_000, ttft0Ms: 0,
        steadyMilliTokPerSec: 20_000, throttleOnsetMs: nil
    )
    static let link = LinkEstimate(rttMs: 10, kbps: 100_000, sessionWarm: true)
    static let sha = String(repeating: "a", count: 64)
    static let done = "{\"attemptId\":\"a\",\"status\":200,\"terminal\":\"done\"}"

    func attempt(
        bytes: Int64 = 1200, elapsed: Int64 = 20_500, claim: ClaimRow = ClaimTrackerTests.claim, end: InferEnd?? = nil, concurrent: Bool = false,
        link: LinkEstimate = ClaimTrackerTests.link, maxTokens: Int64 = 1024, bpt: BytesPerToken = BytesPerToken(), accepted: String? = nil,
        heldBackend: String? = nil, claimBackend: String? = nil, heldCommit: String? = nil, claimCommit: String? = nil, promptTokens: Int64 = 500
    ) -> AttemptInput {
        let endValue: InferEnd? = end ?? InferEnd(tMs: elapsed, payload: Self.done)
        return AttemptInput(
            tBodyMs: 0, chunks: [ContentChunk(tMs: elapsed, bytes: bytes)], end: endValue, promptTokens: promptTokens, promptBytes: 5120, maxTokens: maxTokens,
            link: link, claim: claim, bpt: bpt, concurrent: concurrent, acceptedFileSha256: accepted, claimFileSha256: Self.sha, heldBackend: heldBackend,
            claimBackend: claimBackend, heldCommit: heldCommit, claimCommit: claimCommit
        )
    }

    func tracker(ratios: [Int64] = [], recent: [RecentObservation] = [], strikes: Int = 0, inherited: Bool = false) -> ClaimTracker {
        ClaimTracker(window: ratios, recent: recent, strikes: strikes, inheritedDiscrepant: inherited)
    }

    func view(_ t: ClaimTracker, disc: Int64 = 700, capRef: CapRef? = nil) throws -> TrackerView {
        try t.view(claim: Self.claim, promptTokens: 500, disc: disc, capRef: capRef)
    }

    // MARK: The worked numbers of the spec

    func testWorkedNumbers() throws {
        let honest = try AttemptEvaluator.evaluate(attempt())
        XCTAssertEqual(honest.outBytes, 1200)
        XCTAssertEqual(honest.outTokEst, 300)
        XCTAssertEqual(honest.predictedMs, 19_961, "E1 11 + E5 5,000 + E7 14,950")
        XCTAssertEqual(honest.elapsedMs, 20_500)
        XCTAssertEqual(honest.ratio, 973)
        XCTAssertNil(honest.discard)
        let half = try AttemptEvaluator.evaluate(attempt(elapsed: 39_911))
        XCTAssertEqual(half.ratio, 500)
        let padded = try AttemptEvaluator.evaluate(attempt(bytes: 3000))
        XCTAssertEqual(padded.outTokEst, 750)
        XCTAssertEqual(padded.predictedMs, 42_461)
        XCTAssertEqual(padded.ratio, 2071)
        XCTAssertEqual(try AttemptEvaluator.netMs(Self.link, bytes: 5120), 11)
    }

    func testNetMsIsTheWarmPathWhateverTheSessionSaysAndHasCeilings() throws {
        var cold = Self.link
        cold.sessionWarm = false
        XCTAssertEqual(try AttemptEvaluator.netMs(cold, bytes: 5120), 11, "E-38: no 3 x rtt + handshake term; tBody is taken after the handshake")
        XCTAssertEqual(try AttemptEvaluator.evaluate(attempt(link: cold)), try AttemptEvaluator.evaluate(attempt()), "a cold session changes nothing in the tracker")
        XCTAssertEqual(try AttemptEvaluator.netMs(Self.link, bytes: 12_500), 10 + 1, "ceilDiv(100,000 / 100,000) = 1")
        XCTAssertEqual(try AttemptEvaluator.netMs(Self.link, bytes: 12_501), 10 + 2, "a single bit more rounds up")
        XCTAssertThrowsError(try AttemptEvaluator.netMs(LinkEstimate(rttMs: 1, kbps: 0, sessionWarm: true), bytes: 1))
        XCTAssertThrowsError(try AttemptEvaluator.netMs(Self.link, bytes: Int64.max))
    }

    // MARK: thermalAwareDecode

    func testDecodeBranches() throws {
        var checked = 0
        func ms(_ m: Int64, dec: Int64 = 20_000, steady: Int64 = 10_000, onset: Int64? = nil, prefill: Int64 = 5000) throws -> Int64 {
            checked += 1
            return try AttemptEvaluator.decodeMs(m: m, dec: dec, steady: steady, onsetMs: onset, prefillMs: prefill)
        }
        XCTAssertEqual(try ms(0), 0)
        XCTAssertEqual(try ms(-5), 0)
        XCTAssertEqual(try ms(299), 14_950, "no onset: the decode rate throughout")
        XCTAssertEqual(try ms(299, onset: 5000), try AttemptEvaluator.ceilDiv(299_000_000, 10_000), "already == onset: min(dec, steady)")
        XCTAssertEqual(try ms(299, onset: 4000), 29_900, "already past the onset")
        XCTAssertEqual(try ms(299, dec: 8000, steady: 10_000, onset: 5000), try AttemptEvaluator.ceilDiv(299_000_000, 8000), "already == onset takes the first branch, min(dec, steady), even when steady > dec")
        XCTAssertEqual(try ms(299, dec: 8000, steady: 10_000, onset: 4000), try AttemptEvaluator.ceilDiv(299_000_000, 8000), "min picks the smaller rate")
        XCTAssertEqual(try ms(30, onset: 8000), 1500, "m <= tokCool (60): all at the decode rate")
        XCTAssertEqual(try ms(60, onset: 8000), 3000)
        XCTAssertEqual(try ms(61, onset: 8000), 3000 + 100, "one token beyond tokCool: coolMs + ceilDiv(1e6, steady)")
        XCTAssertEqual(try ms(299, onset: 8000), 26_900, "coolMs 3,000 plus 239 tokens at the steady rate")
        XCTAssertGreaterThan(checked, 8)
    }

    // MARK: Discard

    func testDiscardOrderOverEveryCombination() throws {
        var combos = 0
        var seen = Set<Discard>()
        for incomplete in [false, true] {
            for bytes in [Int64(100), 1000, 9000] {
                for concurrent in [false, true] {
                    for settings in [false, true] {
                        let end: InferEnd?? = incomplete ? .some(InferEnd(tMs: 20_500, payload: "{\"terminal\":\"error\"}")) : nil
                        let r = try AttemptEvaluator.evaluate(attempt(bytes: bytes, end: end, concurrent: concurrent, accepted: settings ? String(repeating: "f", count: 64) : nil))
                        let want: Discard? = incomplete ? .incomplete : bytes < 128 ? .short : bytes > 8192 ? .overlong : concurrent ? .concurrent : settings ? .settings : nil
                        XCTAssertEqual(r.discard, want, "incomplete \(incomplete) bytes \(bytes) concurrent \(concurrent) settings \(settings)")
                        if let d = r.discard { seen.insert(d) }
                        combos += 1
                    }
                }
            }
        }
        XCTAssertEqual(combos, 24)
        XCTAssertEqual(seen, Set(Discard.allCases), "non-vacuity: every discard kind was produced")
    }

    func testByteBoundaries() throws {
        XCTAssertEqual(try AttemptEvaluator.evaluate(attempt(bytes: 127)).discard, .short)
        XCTAssertNil(try AttemptEvaluator.evaluate(attempt(bytes: 128)).discard)
        XCTAssertNil(try AttemptEvaluator.evaluate(attempt(bytes: 8192)).discard)
        XCTAssertEqual(try AttemptEvaluator.evaluate(attempt(bytes: 8193)).discard, .overlong)
        var checked = 0
        for (maxTokens, cap, capPermille) in [(Int64(1), Int64(8), Int64(8000)), (3, 24, 8000), (1024, 8193, 8001), (1024, 4096, 4000), (7, 56, 8000)] {
            let bpt = BytesPerToken(permille: 4000, capPermille: capPermille)
            // The short threshold (128) can exceed a tiny cap: SHORT is checked first, so only caps >= 128 can show OVERLONG.
            if cap >= 128 {
                XCTAssertNil(try AttemptEvaluator.evaluate(attempt(bytes: cap, maxTokens: maxTokens, bpt: bpt)).discard, "bytes == cap \(cap)")
                XCTAssertEqual(try AttemptEvaluator.evaluate(attempt(bytes: cap + 1, maxTokens: maxTokens, bpt: bpt)).discard, .overlong, "cap \(cap) + 1")
                checked += 1
            } else {
                XCTAssertEqual(try AttemptEvaluator.evaluate(attempt(bytes: cap + 1, maxTokens: maxTokens, bpt: bpt)).discard, .short, "SHORT outranks OVERLONG")
                checked += 1
            }
        }
        XCTAssertEqual(checked, 5)
    }

    func testInferEndIsReadStrictlyAndOnlyForTerminal() throws {
        var checked = 0
        for (payload, done) in [
            ("{\"terminal\":\"done\"}", true),
            ("{\"attemptId\":\"a\",\"status\":200,\"terminal\":\"done\",\"usage\":{\"completion_tokens\":100000},\"ttftMs\":1,\"totalMs\":2}", true),
            ("{\"terminal\":\"DONE\"}", false), ("{\"terminal\":\"done \"}", false), ("{\"terminal\":\"cancelled\"}", false), ("{\"terminal\":\"oom\"}", false),
            ("{\"terminal\":true}", false), ("{\"terminal\":null}", false), ("{\"status\":200}", false), ("{}", false), ("[]", false), ("\"done\"", false), ("", false),
            ("{\"terminal\":\"done\"", false), ("{\"terminal\":\"done\",\"terminal\":\"done\"}", false), ("{\"terminal\":\"done\"} x", false),
            ("{\"terminal\":\"done\",\"ttftMs\":1.5}", false), ("\u{FEFF}{\"terminal\":\"done\"}", false),
        ] as [(String, Bool)] {
            XCTAssertEqual(InferEnd(tMs: 0, payload: payload).terminalIsDone, done, payload)
            checked += 1
        }
        XCTAssertEqual(checked, 18)
        XCTAssertEqual(try AttemptEvaluator.evaluate(attempt(end: .some(nil))).discard, .incomplete, "a lost stream has no INFER_END")
    }

    func testSettingsComparison() throws {
        var checked = 0
        func discard(_ a: AttemptInput) throws -> Discard? { checked += 1; return try AttemptEvaluator.evaluate(a).discard }
        XCTAssertNil(try discard(attempt(accepted: Self.sha)))
        XCTAssertEqual(try discard(attempt(accepted: String(repeating: "b", count: 64))), .settings)
        XCTAssertNil(try discard(attempt(heldBackend: "metal", claimBackend: "metal")))
        XCTAssertEqual(try discard(attempt(heldBackend: "vulkan", claimBackend: "opencl")), .settings)
        XCTAssertEqual(try discard(attempt(heldBackend: "vulkan", claimBackend: nil)), .settings, "a held backend against a claim row without one differs")
        XCTAssertNil(try discard(attempt(heldBackend: nil, claimBackend: "opencl")), "nothing held, nothing to differ")
        XCTAssertNil(try discard(attempt(heldCommit: "aaaaaaa", claimCommit: "aaaaaaa")))
        XCTAssertEqual(try discard(attempt(heldCommit: "bbbbbbb", claimCommit: "aaaaaaa")), .settings)
        XCTAssertEqual(try discard(attempt(heldCommit: "bbbbbbb", claimCommit: nil)), .settings)
        XCTAssertEqual(checked, 9)
    }

    func testTimingUsesTheLaterOfEndAndLastChunkAndNeverZero() throws {
        func result(chunks: [ContentChunk], end: InferEnd?, tBody: Int64 = 0) throws -> AttemptResult {
            var a = attempt()
            a.chunks = chunks
            a.end = end
            a.tBodyMs = tBody
            return try AttemptEvaluator.evaluate(a)
        }
        let done = InferEnd(tMs: 20_500, payload: Self.done)
        XCTAssertEqual(try result(chunks: [ContentChunk(tMs: 30_000, bytes: 1200)], end: done).elapsedMs, 30_000, "a chunk after the end frame counts")
        XCTAssertEqual(try result(chunks: [ContentChunk(tMs: 10_000, bytes: 1200)], end: done).elapsedMs, 20_500)
        XCTAssertEqual(try result(chunks: [ContentChunk(tMs: 0, bytes: 1200)], end: InferEnd(tMs: 0, payload: Self.done)).elapsedMs, 1)
        XCTAssertEqual(try result(chunks: [ContentChunk(tMs: 20_000, bytes: 1200)], end: done, tBody: 20_000).elapsedMs, 500)
        XCTAssertEqual(try result(chunks: [ContentChunk(tMs: 20_000, bytes: 1200)], end: nil).elapsedMs, 20_000, "no end frame: the last chunk")
        XCTAssertEqual(try result(chunks: [], end: nil).elapsedMs, 1)
        XCTAssertEqual(try result(chunks: [ContentChunk(tMs: 10, bytes: 1200)], end: InferEnd(tMs: 20, payload: Self.done)).ratio, 5000, "RATIO_CAP")
        let chunky = try result(chunks: (0..<300).map { ContentChunk(tMs: Int64($0) * 60, bytes: 4) }, end: done)
        XCTAssertEqual(chunky.outBytes, 1200, "outBytes is the sum of the parsed text, however the chunks were split")
        XCTAssertEqual(chunky.ratio, 973)
        let oneByte = try result(chunks: (0..<1200).map { ContentChunk(tMs: Int64($0) * 16, bytes: 1) }, end: done)
        XCTAssertEqual(oneByte.ratio, 973)
        XCTAssertThrowsError(try result(chunks: [ContentChunk(tMs: 1, bytes: Int64.max), ContentChunk(tMs: 2, bytes: 1)], end: done))
    }

    func testPeerDeclaredNumbersCannotMoveTheResult() throws {
        let honest = try AttemptEvaluator.evaluate(attempt())
        let lying = "{\"attemptId\":\"a\",\"status\":200,\"terminal\":\"done\",\"usage\":{\"completion_tokens\":100000,\"prompt_tokens\":1},\"ttftMs\":1,\"totalMs\":2,\"decodeMilliTokPerSec\":900000000,\"outBytes\":99999999}"
        let r = try AttemptEvaluator.evaluate(attempt(end: .some(InferEnd(tMs: 20_500, payload: lying))))
        XCTAssertEqual(r, honest)
    }

    // MARK: Curve

    func testDecodeCurve() throws {
        let c = ClaimRow(
            decodeAt: [DecodePoint(contextTokens: 512, milliTokPerSec: 20_000), DecodePoint(contextTokens: 2048, milliTokPerSec: 10_000), DecodePoint(contextTokens: 8192, milliTokPerSec: 4000)],
            prefillMilliTokPerSec: 1, ttft0Ms: 0, steadyMilliTokPerSec: 1, throttleOnsetMs: nil
        )
        var checked = 0
        for (p, want) in [(Int64(0), Int64(20_000)), (512, 20_000), (1280, 15_000), (2048, 10_000), (2049, 9999), (5120, 7000), (8192, 4000), (100_000, 4000)] {
            XCTAssertEqual(try c.decodeRate(atContext: p), want, "context \(p)")
            checked += 1
        }
        XCTAssertEqual(checked, 8)
        XCTAssertThrowsError(try ClaimRow(decodeAt: [], prefillMilliTokPerSec: 1, ttft0Ms: 0, steadyMilliTokPerSec: 1, throttleOnsetMs: nil).decodeRate(atContext: 1))
        let bad = ClaimRow(decodeAt: [DecodePoint(contextTokens: 100, milliTokPerSec: 1), DecodePoint(contextTokens: 100, milliTokPerSec: 2)], prefillMilliTokPerSec: 1, ttft0Ms: 0, steadyMilliTokPerSec: 1, throttleOnsetMs: nil)
        XCTAssertThrowsError(try bad.decodeRate(atContext: 100), "a curve that is not strictly ascending is refused, whatever the query")
        let descending = ClaimRow(decodeAt: [DecodePoint(contextTokens: 300, milliTokPerSec: 1), DecodePoint(contextTokens: 100, milliTokPerSec: 2)], prefillMilliTokPerSec: 1, ttft0Ms: 0, steadyMilliTokPerSec: 1, throttleOnsetMs: nil)
        XCTAssertThrowsError(try descending.decodeRate(atContext: 200))
    }

    // MARK: State

    func testStateThresholdsAndMinState() throws {
        var perState: [ClaimState: Int] = [:]
        for (best, want) in [(Int64(5000), ClaimState.corroborated), (1000, .corroborated), (800, .corroborated), (799, .weak), (600, .weak), (599, .discrepant), (0, .discrepant)] {
            for n in [5, 6, 20] {
                let v = try view(tracker(ratios: [Int64](repeating: best, count: n)))
                XCTAssertEqual(v.state, want, "best \(best) n \(n)")
                XCTAssertEqual(v.best, best)
                perState[v.state, default: 0] += 1
            }
        }
        for n in 0...4 {
            let v = try view(tracker(ratios: [Int64](repeating: 100, count: n)))
            XCTAssertEqual(v.state, .unverified, "fewer than MIN_STATE kept ratios is UNVERIFIED whatever they say (n = \(n))")
            perState[v.state, default: 0] += 1
        }
        for s in [ClaimState.unverified, .corroborated, .weak, .discrepant] { XCTAssertGreaterThan(perState[s] ?? 0, 0, "non-vacuity: \(s)") }
    }

    func testBestIsTheUpperQuartileNearestRank() throws {
        // x[(3n)/4] of an ascending ramp 1...n is the value at that index plus one.
        var checked = 0
        for n in 1...20 {
            let ramp = (1...n).map { Int64($0) * 10 }
            let v = try view(tracker(ratios: ramp.reversed()))
            XCTAssertEqual(v.best, ramp[(3 * n) / 4], "n = \(n)")
            checked += 1
        }
        XCTAssertEqual(checked, 20)
        XCTAssertEqual(try view(tracker(ratios: [973, 973, 500])).best, 973)
        XCTAssertNil(try view(tracker()).best)
    }

    func testWindowKeepsTheLastTwentyKeptRatios() throws {
        var t = ClaimTracker()
        let honest = try AttemptEvaluator.evaluate(attempt())
        let slow = try AttemptEvaluator.evaluate(attempt(elapsed: 39_911))
        for _ in 0..<5 { t.onObservation(slow) }
        for _ in 0..<20 { t.onObservation(honest) }
        XCTAssertEqual(t.window.count, 20)
        XCTAssertEqual(Set(t.window), [973])
        XCTAssertEqual(t.recent.count, 20)
        XCTAssertEqual(try view(t).state, .corroborated)
        XCTAssertEqual(ClaimTracker(window: [Int64](repeating: 1, count: 30), recent: [], strikes: 0, inheritedDiscrepant: false).window.count, 20)
    }

    func testDiscardedAttemptsNeverEnterTheWindowAndOverlongAddsStrikes() throws {
        var t = ClaimTracker()
        let overlong = try AttemptEvaluator.evaluate(attempt(bytes: 9000))
        XCTAssertEqual(overlong.discard, .overlong)
        for _ in 0..<3 { t.onObservation(overlong) }
        t.onObservation(try AttemptEvaluator.evaluate(attempt(bytes: 100)))
        t.onObservation(try AttemptEvaluator.evaluate(attempt(concurrent: true)))
        XCTAssertEqual(t.window, [])
        XCTAssertEqual(t.strikes, 3, "only OVERLONG strikes")
        t.onObservation(try AttemptEvaluator.evaluate(attempt()))
        XCTAssertEqual(t.window, [973])
        XCTAssertEqual(t.recent.map(\.kept), [false, false, false, false, false, true])
    }

    // MARK: Discard budget

    func testBudgetTripsOnlyWithFourOrMoreAndMoreThanHalfDiscarded() throws {
        var tripped = 0, quiet = 0
        for total in 0...20 {
            for discarded in 0...total {
                let recent = (0..<total).map { RecentObservation(kept: $0 >= discarded, ratio: 900, outBytes: 1200) }
                let v = try view(tracker(ratios: [900, 900, 900, 900, 900], recent: recent))
                let want = total >= 4 && discarded * 2 > total
                XCTAssertEqual(v.budgetTripped, want, "\(discarded) of \(total)")
                if want { tripped += 1; XCTAssertEqual(v.state, .weak) } else { quiet += 1; XCTAssertEqual(v.state, .corroborated) }
            }
        }
        XCTAssertGreaterThan(tripped, 50)
        XCTAssertGreaterThan(quiet, 50)
        // Only the last 20 count.
        let old = (0..<30).map { RecentObservation(kept: $0 >= 12, ratio: 900, outBytes: 1200) }
        XCTAssertFalse(try view(tracker(ratios: [900], recent: old)).budgetTripped, "of the last 20, 2 were discarded")
        XCTAssertEqual(ClaimTracker(window: [], recent: old, strikes: 0, inheritedDiscrepant: false).recent.count, 20)
    }

    func testMinRatioConsidersOnlyAnswersOfEightBytesOrMore() throws {
        var recent = [RecentObservation(kept: false, ratio: 5, outBytes: 7), RecentObservation(kept: false, ratio: 40, outBytes: 8), RecentObservation(kept: true, ratio: 900, outBytes: 1200)]
        XCTAssertEqual(try view(tracker(recent: recent)).minRatio, 40, "7 bytes is below the floor, 8 is not")
        recent = [RecentObservation(kept: false, ratio: 5, outBytes: 7), RecentObservation(kept: false, ratio: 6, outBytes: 0)]
        XCTAssertEqual(try view(tracker(recent: recent)).minRatio, 0, "none qualifies")
        XCTAssertEqual(try view(tracker()).minRatio, 0)
    }

    func testTrippedBudgetClampsButNeverLifts() throws {
        let four = (0..<4).map { _ in RecentObservation(kept: false, ratio: 40, outBytes: 50) }
        // n = 0: the prior is 70,000; the clamp claim x minRatio / 1000 = 100,000 x 40 / 1000 = 4,000 is lower, so it binds.
        var v = try view(tracker(recent: four))
        XCTAssertTrue(v.budgetTripped)
        XCTAssertEqual(v.tracked, TrackedRates(prefill: 4000, decodeAtP: 800, steady: 800))
        XCTAssertEqual(v.state, .weak)
        // A minRatio above the median-based rate does not lift it.
        let high = (0..<4).map { _ in RecentObservation(kept: false, ratio: 5000, outBytes: 50) }
        v = try view(tracker(ratios: [300, 300, 300], recent: high))
        XCTAssertTrue(v.budgetTripped)
        XCTAssertEqual(v.tracked.prefill, 30_000, "min(median-based 30,000, clamp 500,000)")
        // Nothing qualifies: the tracked rate is 0, and F8 then excludes the candidate.
        let tiny = (0..<4).map { _ in RecentObservation(kept: false, ratio: 99, outBytes: 3) }
        XCTAssertEqual(try view(tracker(recent: tiny)).tracked, TrackedRates(prefill: 0, decodeAtP: 0, steady: 0))
    }

    // MARK: Tracked rates

    func testTrackedRatesBlendThePriorUntilThreeAndThenUseTheLowerMedian() throws {
        var checked = 0
        func tracked(_ ratios: [Int64], disc: Int64 = 700, capRef: CapRef? = nil) throws -> TrackedRates {
            checked += 1
            return try view(tracker(ratios: ratios), disc: disc, capRef: capRef).tracked
        }
        // n = 0: prior' = claim x 700 / 1000.
        XCTAssertEqual(try tracked([]), TrackedRates(prefill: 70_000, decodeAtP: 14_000, steady: 14_000))
        // n = 1: (2 x 70,000 + 100,000 x 973 / 1000) / 3 = 79,100.
        XCTAssertEqual(try tracked([973]).prefill, 79_100)
        XCTAssertEqual(try tracked([973]).decodeAtP, 15_820)
        // n = 2 with a ratio above 1000 (capped to 1000 inside the blend).
        XCTAssertEqual(try tracked([973, 1330]).prefill, 84_325)
        XCTAssertEqual(try tracked([5000, 5000]).prefill, 85_000, "both ratios count as 1000: (2 x 70,000 + 100,000 + 100,000) / 4")
        // n = 3: the lower median x[(n - 1) / 2] of the sorted ratios, capped at 1000.
        XCTAssertEqual(try tracked([973, 973, 500]).prefill, 97_300)
        XCTAssertEqual(try tracked([500, 500, 500, 973, 973, 973]).prefill, 50_000, "an even n takes the lower median")
        XCTAssertEqual(try tracked([5000, 5000, 5000]).prefill, 100_000, "never above the claim")
        XCTAssertEqual(try tracked([400, 450, 899, 950, 959]).prefill, 89_900)
        // The peer-wide discount.
        XCTAssertEqual(try tracked([], disc: 400), TrackedRates(prefill: 40_000, decodeAtP: 8000, steady: 8000))
        // capRef binds the prior while n < 3 and nothing else.
        let cap = CapRef(prefillMilliTokPerSec: 50_000, decodeMilliTokPerSec: 10_000, steadyMilliTokPerSec: 10_000)
        XCTAssertEqual(try tracked([], capRef: cap), TrackedRates(prefill: 35_000, decodeAtP: 7000, steady: 7000))
        XCTAssertEqual(try tracked([973, 973, 973], capRef: cap).prefill, 97_300)
        let above = CapRef(prefillMilliTokPerSec: 500_000, decodeMilliTokPerSec: 500_000, steadyMilliTokPerSec: 500_000)
        XCTAssertEqual(try tracked([], capRef: above).prefill, 70_000, "a capRef above the claim does not raise the prior")
        XCTAssertGreaterThan(checked, 10)
    }

    func testCapRefIsMinOfTwelveTenthsOfTheSignedReferenceAndTheClassCeiling() throws {
        XCTAssertEqual(try CapRef.rate(signedReferenceP90: nil, classCeiling: 30_000), 30_000, "no signed reference: the class ceiling only")
        XCTAssertEqual(try CapRef.rate(signedReferenceP90: 20_000, classCeiling: 30_000), 24_000)
        XCTAssertEqual(try CapRef.rate(signedReferenceP90: 30_000, classCeiling: 30_000), 30_000)
        XCTAssertEqual(try CapRef.rate(signedReferenceP90: 100_000, classCeiling: 30_000), 30_000)
        XCTAssertEqual(try CapRef.rate(signedReferenceP90: 7, classCeiling: 30_000), 8, "floor division: 7 x 12 / 10")
        XCTAssertThrowsError(try CapRef.rate(signedReferenceP90: Int64.max, classCeiling: 1))
    }

    // MARK: Inheritance across claim seqs

    func testANewClaimSeqRestartsTheWindowAndInheritsDiscrepantUntilTenGoodObservations() throws {
        var t = tracker(ratios: [500, 500, 500, 500, 500], strikes: 2)
        XCTAssertEqual(try view(t).state, .discrepant)
        t.onNewClaimSeq()
        XCTAssertEqual(t.window, [])
        XCTAssertEqual(t.recent, [])
        XCTAssertEqual(t.strikes, 2, "strikes survive a new claim")
        XCTAssertTrue(t.inheritedDiscrepant)
        XCTAssertEqual(try view(t).state, .discrepant, "no observations yet, inherited")
        let good = try AttemptEvaluator.evaluate(attempt())
        var states: [ClaimState] = []
        for _ in 0..<10 {
            t.onObservation(good)
            states.append(try view(t).state)
        }
        XCTAssertEqual(Array(states.prefix(9)), [ClaimState](repeating: .discrepant, count: 9), "stays DISCREPANT through the ninth good observation")
        XCTAssertEqual(states[9], .corroborated, "the tenth clears it")
        XCTAssertFalse(t.inheritedDiscrepant)

        // Ten observations whose best is below CORR do not clear it.
        var weak = tracker(ratios: [500, 500, 500, 500, 500])
        weak.onNewClaimSeq()
        let slow = try AttemptEvaluator.evaluate(attempt(elapsed: 39_911))
        for _ in 0..<12 { weak.onObservation(slow) }
        XCTAssertTrue(weak.inheritedDiscrepant)
        XCTAssertEqual(try view(weak).state, .discrepant)

        // A key that was not DISCREPANT inherits nothing.
        var fine = tracker(ratios: [973, 973, 973, 973, 973])
        fine.onNewClaimSeq()
        XCTAssertFalse(fine.inheritedDiscrepant)
        XCTAssertEqual(try view(fine).state, .unverified)

        // The inherited flag stays with a direct state vector too.
        XCTAssertEqual(try view(tracker(ratios: [Int64](repeating: 1000, count: 9), inherited: true)).state, .discrepant)
        XCTAssertEqual(try view(tracker(ratios: [Int64](repeating: 1000, count: 10), inherited: true)).state, .corroborated)
        XCTAssertEqual(try view(tracker(ratios: [Int64](repeating: 700, count: 10), inherited: true)).state, .discrepant)
    }

    // MARK: Claim bodies

    func testAClaimBodyIsAcceptedAtMostOncePerDayAndOnlyWithAHigherSeq() {
        var checked = 0
        func run(_ events: [(Int64, Int64)]) -> [Bool] {
            var gate = ClaimBodyGate()
            checked += 1
            return events.map { gate.accept(seq: $0.0, atMs: $0.1) }
        }
        XCTAssertEqual(run([(1, 1000), (2, 3_601_000), (2, 86_401_000)]), [true, false, true])
        XCTAssertEqual(run([(5, 0), (5, 90_000_000), (4, 180_000_000)]), [true, false, false])
        XCTAssertEqual(run([(1, 0), (2, 86_399_999)]), [true, false], "24 h less one millisecond")
        XCTAssertEqual(run([(1, 0), (2, 86_400_000)]), [true, true], "exactly 24 h")
        XCTAssertEqual(run([(1, 0), (3, 86_400_000), (2, 200_000_000), (4, 200_000_000)]), [true, true, false, true], "a lower seq is refused after a higher one was accepted")
        XCTAssertEqual(run([(Int64.max, 0), (Int64.max, 90_000_000)]), [true, false])
        XCTAssertGreaterThan(checked, 5)
    }

    // MARK: Arithmetic

    func testArithmeticIsChecked() {
        var a = attempt()
        a.promptTokens = Int64.max
        XCTAssertThrowsError(try AttemptEvaluator.evaluate(a)) { XCTAssertTrue($0 is BenchArithmeticError) }
        a = attempt()
        a.claim.prefillMilliTokPerSec = 0
        XCTAssertThrowsError(try AttemptEvaluator.evaluate(a))
        a = attempt()
        a.bpt = BytesPerToken(permille: 0, capPermille: 8000)
        XCTAssertThrowsError(try AttemptEvaluator.evaluate(a))
        var huge = Self.claim
        huge.prefillMilliTokPerSec = Int64.max
        XCTAssertThrowsError(try tracker(ratios: [900, 900, 900]).view(claim: huge, promptTokens: 500))
    }
}
