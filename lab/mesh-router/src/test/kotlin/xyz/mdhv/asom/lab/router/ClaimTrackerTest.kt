package xyz.mdhv.asom.lab.router

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M08 worked numbers (LAB_SPEC 6.6) and the properties the tracker must keep: it only lowers a claim, and nothing a peer says can raise one. */
class ClaimTrackerTest {
    private val key = ClaimKey("peer-x", W.SHA_A, "metal")
    private val claim = PerfPrior(listOf(512 to 20_000L), 100_000, 0, 20_000, null, null, 100_000, 6_000_000_000, emptySet())
    private val bpt = Bpt(4_000, 8_000)
    private val link = LinkStats(10, 100_000, xyz.mdhv.asom.lab.ledger.PeerPath.LAN, false, true, 5)

    private fun obs(bytes: Long = 1_200, elapsed: Long = 20_500, done: Boolean = true, maxTokens: Long = 1_024, concurrent: Boolean = false, sha: String? = null) = Observation(
        key, done, 0, elapsed, bytes, 500, maxTokens, Estimator.warmNetMs(link, 5_120, MeshConfig()), concurrent, sha, null, null, null,
    )

    @Test
    fun theSpecsWorkedNumbers() {
        val honest = ClaimTracker.evaluate(obs(), claim, bpt)
        assertEquals(300, honest.outTokEst)
        assertEquals(19_961, honest.predictedMs)
        assertEquals(973, honest.ratio)
        assertNull(honest.discard)
        val half = ClaimTracker.evaluate(obs(elapsed = 39_911), claim, bpt)
        assertEquals(500, half.ratio)
    }

    @Test
    fun aPeerAtHalfItsClaimIsDiscrepantAfterFiveAndPlacedAtItsTrueSpeed() {
        var book = TrackerBook()
        repeat(5) { book = ClaimTracker.onObservation(book, obs(elapsed = 39_911), claim, bpt, 1_000) }
        val ts = book.states.getValue(key)
        assertEquals(ClaimState.DISCREPANT, ClaimTracker.stateOf(ts))
        val rates = ClaimTracker.tracked(claim, 500, ts, null, 700)
        assertEquals(50_000, rates.prefill)
        assertEquals(10_000, rates.decodeAtP)
        assertEquals(10_000, rates.steady)
    }

    @Test
    fun anHonestBusyPeerIsCorroboratedAndPlacedAtItsMedian() {
        val ts = TrackerState(ratios = listOf(400, 450, 900, 950, 960))
        assertEquals(950, ClaimTracker.best(ts))
        assertEquals(ClaimState.CORROBORATED, ClaimTracker.stateOf(ts))
        assertEquals(20_000L * 900 / 1000, ClaimTracker.trackedRate(20_000, ts, null, 700))
    }

    /**
     * R3-OVERCLAIM-1. The design says padding inflates the apparent speed "at most about 2x". It does not: an answer is capped only at maxTokens x bptCap bytes, so
     * filler up to that cap inflates `predicted` by (predicted at the cap) / (predicted at the true length). At the spec's own numbers that is about 5.4x, and a peer
     * truly 5x slower than it claims still reads CORROBORATED. What is bounded is only the placement: `effRatio = min(1000, median)` never exceeds the claim.
     */
    @Test
    fun paddingToTheCapInflatesTheRatioByFarMoreThanTwo() {
        val trueBytes = 1_200L
        val capBytes = 1_024L * 8_000 / 1_000
        val honestPredicted = ClaimTracker.evaluate(obs(bytes = trueBytes), claim, bpt).predictedMs
        val paddedPredicted = ClaimTracker.evaluate(obs(bytes = capBytes), claim, bpt).predictedMs
        assertEquals(19_961, honestPredicted)
        assertEquals(107_361, paddedPredicted)
        assertTrue(paddedPredicted * 100 / honestPredicted >= 500, "the inflation is ${paddedPredicted * 100 / honestPredicted}/100, not about 2x")
        for (slowdown in listOf(2L, 3L, 5L)) {
            val elapsed = 20_500L + (slowdown - 1) * 19_961
            val padded = ClaimTracker.evaluate(obs(bytes = capBytes, elapsed = elapsed), claim, bpt)
            assertNull(padded.discard, "a padded answer at the cap is kept")
            assertTrue(padded.ratio >= ClaimTracker.CORR, "a peer $slowdown x slower than claimed reads ${padded.ratio} permille with padding")
        }
        var book = TrackerBook()
        repeat(5) { book = ClaimTracker.onObservation(book, obs(bytes = capBytes, elapsed = 20_500L + 4 * 19_961), claim, bpt, 1_000) }
        assertEquals(ClaimState.CORROBORATED, ClaimTracker.stateOf(book.states.getValue(key)))
        val rates = ClaimTracker.tracked(claim, 500, book.states.getValue(key), null, 700)
        assertEquals(claim.prefillMilliTokPerSec, rates.prefill, "the placement rate is the claim, never above it")
        assertTrue(rates.decodeAtP <= 20_000)
    }

    @Test
    fun paddingOverTheCapIsDiscardedWithAStrike() {
        val e = ClaimTracker.evaluate(obs(bytes = 8_193), claim, bpt)
        assertEquals(DiscardReason.OVERLONG, e.discard)
        val book = ClaimTracker.onObservation(TrackerBook(), obs(bytes = 8_193), claim, bpt, 1_000)
        assertEquals(1, book.states.getValue(key).strikes)
        assertTrue(book.states.getValue(key).ratios.isEmpty())
    }

    @Test
    fun theDiscardRulesApplyInTheSpecsOrder() {
        assertEquals(DiscardReason.INCOMPLETE, ClaimTracker.evaluate(obs(bytes = 10, done = false, concurrent = true, sha = "z"), claim, bpt).discard)
        assertEquals(DiscardReason.SHORT, ClaimTracker.evaluate(obs(bytes = 127, concurrent = true, sha = "z"), claim, bpt).discard)
        assertEquals(DiscardReason.OVERLONG, ClaimTracker.evaluate(obs(bytes = 9_000, concurrent = true, sha = "z"), claim, bpt).discard)
        assertEquals(DiscardReason.CONCURRENT, ClaimTracker.evaluate(obs(concurrent = true, sha = "z"), claim, bpt).discard)
        assertEquals(DiscardReason.SETTINGS, ClaimTracker.evaluate(obs(sha = "z"), claim, bpt).discard)
        assertNull(ClaimTracker.evaluate(obs(sha = W.SHA_A), claim, bpt).discard)
    }

    @Test
    fun truncationTripsTheDiscardBudgetAndClampsToTheLowestRatioAmongRealAnswers() {
        var book = TrackerBook()
        repeat(4) { book = ClaimTracker.onObservation(book, obs(bytes = 20, elapsed = 200_000), claim, bpt, 1_000) }
        val ts = book.states.getValue(key)
        assertTrue(ClaimTracker.budgetTripped(ts))
        assertEquals(ClaimState.WEAK, ClaimTracker.stateOf(ts))
        val low = ts.recent.filter { it.outBytes >= 8 }.minOf { it.ratio }
        assertEquals(20_000L * low / 1000, ClaimTracker.trackedRate(20_000, ts, null, 700))
        val none = ts.copy(recent = ts.recent.map { it.copy(outBytes = 4) })
        assertEquals(0, ClaimTracker.trackedRate(20_000, none, null, 700))
    }

    @Test
    fun aNewClaimBodyIsAcceptedOncePer24HoursAndInheritsDiscrepancy() {
        var book = TrackerBook()
        val (b1, ok1) = ClaimTracker.onClaimBody(book, key, 1, 1_000_000)
        assertTrue(ok1)
        val (b2, ok2) = ClaimTracker.onClaimBody(b1, key, 2, 1_000_000 + 3_600_000)
        assertTrue(!ok2)
        assertEquals(b1, b2)
        val (_, ok3) = ClaimTracker.onClaimBody(b1, key, 2, 1_000_000 + 86_400_000)
        assertTrue(ok3)
        book = b1
        repeat(5) { book = ClaimTracker.onObservation(book, obs(elapsed = 39_911), claim, bpt, 1_000) }
        val (b4, ok4) = ClaimTracker.onClaimBody(book, key, 5, 9_000_000_000)
        assertTrue(ok4)
        assertEquals(ClaimState.DISCREPANT, ClaimTracker.stateOf(b4.states.getValue(key)), "a restarted window inherits DISCREPANT")
        var b5 = b4
        repeat(10) { b5 = ClaimTracker.onObservation(b5, obs(elapsed = 19_000), claim, bpt, 9_000_000_100) }
        assertNotEquals(ClaimState.DISCREPANT, ClaimTracker.stateOf(b5.states.getValue(key)))
    }

    @Test
    fun twoDiscrepantKeysOfOnePeerDropTheDiscountTo400ForSevenDays() {
        var book = TrackerBook()
        val k2 = ClaimKey("peer-x", W.SHA_B, "metal")
        repeat(5) { book = ClaimTracker.onObservation(book, obs(elapsed = 39_911), claim, bpt, 1_000) }
        assertEquals(700, ClaimTracker.disc("peer-x", book.states, book.penalties, 1_000))
        repeat(5) { book = ClaimTracker.onObservation(book, obs(elapsed = 39_911).copy(key = k2), claim, bpt, 2_000) }
        assertEquals(400, ClaimTracker.disc("peer-x", book.states, book.penalties, 2_000))
        val until = book.penalties.getValue("peer-x").untilWallMs
        assertEquals(2_000 + 7 * 86_400_000L, until)
        assertEquals(400, ClaimTracker.disc("peer-x", emptyMap(), book.penalties, until - 1))
        assertEquals(700, ClaimTracker.disc("peer-x", emptyMap(), book.penalties, until))
    }

    @Test
    fun observationOnlyLowersAClaim() {
        val r = SplittableRandom(6)
        var checked = 0
        repeat(2_000) {
            val n = r.nextInt(0, 25)
            val ts = TrackerState(ratios = List(n) { (r.nextLong(1, 6_000)) }.takeLast(20), recent = List(r.nextInt(0, 21)) { ObsRecord(r.nextBoolean(), r.nextLong(0, 5_000), r.nextLong(0, 300), null) })
            val rate = r.nextLong(1, 1_000_000_000)
            val ceiling = if (r.nextBoolean()) r.nextLong(1, 2_000_000_000) else null
            val tracked = ClaimTracker.trackedRate(rate, ts, ceiling, if (r.nextBoolean()) 700 else 400)
            assertTrue(tracked <= rate, "tracked $tracked exceeds the claim $rate")
            assertTrue(tracked >= 0)
            checked++
        }
        assertTrue(checked >= Laws.FLOOR)
    }

    private fun sse(content: String) = "data: {\"choices\":[{\"delta\":{\"content\":${xyz.mdhv.asom.lab.json.Jcs.serializeToString(xyz.mdhv.asom.lab.json.JString(content))}}}]}\n\n".toByteArray()

    private fun observe(chunks: List<Pair<Long, String>>, endAt: Long, endPayload: String, headAt: Long? = 100): AttemptObserver {
        val o = AttemptObserver()
        o.onBodySent(0)
        headAt?.let { o.onHead(it) }
        chunks.forEach { (t, c) -> o.onChunk(t, sse(c)) }
        o.onEnd(endAt, endPayload.toByteArray())
        return o
    }

    /**
     * No number a peer supplies can raise a claim. Everything a peer controls is varied: the members of `INFER_END` (`usage`, `ttftMs`, `totalMs`, token counts,
     * `st` digests), the time of `INFER_HEAD`, the way the answer is cut into chunks. The observation, its ratio and the tracked rates must be byte-identical to
     * the baseline, and never above the claim. A tracker that read a peer-reported token count would fail the first assertion.
     */
    @Test
    fun noPeerSuppliedNumberCanRaiseAClaim() {
        val text = "x".repeat(1_200)
        val baseline = observe(listOf(20_000L to text), 20_500, "{\"attemptId\":\"a\",\"status\":200,\"terminal\":\"done\"}")
        fun build(o: AttemptObserver) = Observation(key, o.terminal == "done", o.tBodyMs!!, o.tEndMs()!!, o.outBytes(), 500, 1_024, 11, false, W.SHA_A, null, null, null)
        val base = ClaimTracker.evaluate(build(baseline), claim, bpt)
        assertEquals(1_200, build(baseline).outBytes)
        val lies = listOf(
            "\"usage\":{\"completion_tokens\":10000000,\"prompt_tokens\":1}", "\"ttftMs\":1", "\"totalMs\":2", "\"tokens\":100000", "\"decodeMilliTokPerSec\":900000000",
            "\"st\":{\"seq\":9,\"fsm\":\"SERVING\",\"tb\":2,\"gov\":\"HOLD\",\"qb\":2}", "\"outBytes\":100000000", "\"completionTokens\":9999999",
        )
        var variants = 0
        val r = SplittableRandom(8)
        repeat(300) {
            val chosen = lies.filter { r.nextBoolean() }
            val members = listOf("\"attemptId\":\"a\"", "\"status\":200", "\"terminal\":\"done\"") + chosen
            val payload = "{" + members.shuffled(java.util.Random(r.nextLong())).joinToString(",") + "}"
            val cuts = r.nextInt(1, 40)
            val pieces = text.chunked(maxOf(1, text.length / cuts))
            val timed = pieces.mapIndexed { i, c -> (20_000L - (pieces.size - 1 - i) * r.nextInt(0, 5)) to c }
            val o = observe(timed, 20_500, payload, headAt = r.nextLong(0, 20_000))
            val ob = build(o)
            assertEquals(1_200, ob.outBytes, "lies $chosen changed the answer length")
            val ev = ClaimTracker.evaluate(ob, claim, bpt)
            assertEquals(base.ratio, ev.ratio, "lies $chosen changed the ratio")
            assertEquals(base.predictedMs, ev.predictedMs)
            variants++
        }
        assertTrue(variants >= Laws.FLOOR)
        val ts = TrackerState(ratios = List(6) { base.ratio })
        val rates = ClaimTracker.tracked(claim, 500, ts, null, 700)
        assertTrue(rates.prefill <= claim.prefillMilliTokPerSec && rates.decodeAtP <= 20_000 && rates.steady <= claim.steadyMilliTokPerSec)
        println("M08 no-peer-number iterations: $variants violations: 0")
    }

    @Test
    fun burstsSplitsAndMergesOfTheSameAnswerGiveTheSameObservation() {
        val text = "y".repeat(1_200)
        fun ob(chunks: List<Pair<Long, String>>): Pair<Long, Long> {
            val o = observe(chunks, 20_500, "{\"terminal\":\"done\"}")
            return o.outBytes() to ClaimTracker.evaluate(Observation(key, true, 0, o.tEndMs()!!, o.outBytes(), 500, 1_024, 11, false, null, null, null, null), claim, bpt).ratio
        }
        val whole = ob(listOf(20_000L to text))
        val even = ob(text.chunked(4).mapIndexed { i, c -> (i * 66L) to c })
        val burst = ob(listOf(50L to text.take(1)) + listOf(20_000L to text.drop(1)))
        val bytewise = ob(text.map { it.toString() }.mapIndexed { i, c -> (i * 16L) to c })
        assertEquals(whole, burst)
        assertEquals(whole.first, even.first)
        assertEquals(whole.first, bytewise.first)
    }
}
