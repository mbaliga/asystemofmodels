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

    private fun outBytesOf(vararg pieces: String): Long {
        val o = AttemptObserver()
        o.onBodySent(0)
        pieces.forEachIndexed { i, c -> o.onChunk(100L + i, sse(c)) }
        o.onEnd(200, "{\"terminal\":\"done\"}".toByteArray())
        return o.outBytes()
    }

    /** LAB_SPEC R4-L-19 (builder action 6): invisible padding is removed before the bytes are counted. Fails on the code that counts raw bytes (3,000 and 3,600, not 1,200). */
    @Test
    fun invisiblePaddingDoesNotCountTowardsOutBytes() {
        val real = "x".repeat(1_200)
        assertEquals(1_200, outBytesOf(real + "\n".repeat(600) + " ".repeat(600) + "\t".repeat(600)), "trailing whitespace")
        assertEquals(1_200, outBytesOf("x".repeat(600) + "​".repeat(300) + "﻿".repeat(100) + "x".repeat(600) + "⁠".repeat(200)), "zero-width characters")
        assertEquals(1_200, outBytesOf("é".repeat(600)), "NFC composes 3-byte decomposed pairs into 2-byte letters")
        assertEquals(3_000, outBytesOf(real + "y".repeat(1_800)), "visible filler is the stated residual and still counts")
        val padded = ClaimTracker.evaluate(
            Observation(key, true, 0, 20_500, outBytesOf(real + "​".repeat(600) + " ".repeat(1_200)), 500, 1_024, 11, false, null, null, null, null), claim, bpt,
        )
        assertEquals(973, padded.ratio, "invisible padding in the honest time is the honest ratio")
    }

    /** ERR-FX3-TP-1 (review R4TB-03): default-ignorable padding that is not Cf was counted (300 to 400 bytes per 100 characters). */
    @Test
    fun defaultIgnorablePaddingThatIsNotAFormatCharacterIsRemoved() {
        val families = mapOf(
            "variation selector 16" to "\uFE0F",
            "variation selector 17 (supplementary)" to "\uDB40\uDD00",
            "combining grapheme joiner" to "\u034F",
            "hangul choseong filler" to "\u115F",
            "hangul filler" to "\u3164",
            "halfwidth hangul filler" to "\uFFA0",
            "braille pattern blank" to "\u2800",
            "khmer inherent vowel" to "\u17B4",
            "mongolian free variation selector" to "\u180B",
            "tag character" to "\uDB40\uDC41",
            "soft hyphen" to "\u00AD",
        )
        var cases = 0
        for ((name, pad) in families) {
            assertEquals(1_200, outBytesOf("x".repeat(600) + pad.repeat(100) + "x".repeat(600)), name)
            assertEquals(1_200, outBytesOf("x".repeat(600), pad.repeat(100), "x".repeat(600)), "$name across chunks")
            cases++
        }
        assertTrue(cases >= 11, "families exercised: $cases")
    }

    /** The invisible set is spelled out, so the verdict does not follow the Unicode tables of the running JDK. Every code point of it is removed; a sample of visible ones survives. */
    @Test
    fun everyCodePointOfTheNamedInvisibleSetIsRemovedAndVisibleOnesSurvive() {
        var removed = 0
        var i = 0
        while (i < AnswerText.INVISIBLE.size) {
            for (cp in AnswerText.INVISIBLE[i]..AnswerText.INVISIBLE[i + 1]) {
                assertEquals("xx", AnswerText.normalize("x" + String(Character.toChars(cp)) + "x"), "U+" + cp.toString(16).uppercase())
                removed++
            }
            i += 2
        }
        assertTrue(removed > 4_000, "code points checked: $removed")
        var visible = 0
        for (cp in listOf(0x41, 0x301, 0x300, 0x5D9, 0x93C, 0x1F600, 0x2603, 0x3042, 0xAC00, 0x20AC, 0x958, 0x1160 + 1, 0x2801, 0xFE10, 0x180A, 0x17B3, 0x17B6)) {
            val n = AnswerText.normalize("x" + String(Character.toChars(cp)) + "x")
            assertTrue(n.codePointCount(0, n.length) >= 3, "U+" + cp.toString(16) + " is visible")
            visible++
        }
        assertTrue(visible >= 17)
    }

    /** ERR-FX3-TP-2: NFC can expand (U+0958 3 -> 6 bytes, U+1D160 4 -> 12), so the counted bytes are capped at the same text without NFC. */
    @Test
    fun normalisationNeverCountsMoreBytesThanTheAnswerWasSentWith() {
        assertEquals(1_200, outBytesOf("x".repeat(300) + "\u0958".repeat(300)), "U+0958 would be 2,100 after NFC")
        assertEquals(1_200, outBytesOf("x".repeat(400) + "\uD834\uDD60".repeat(200)), "U+1D160 would be 2,800 after NFC")
        val alphabet = listOf("x", "\u0958", "\uD834\uDD60", "e", "\u0301", "\u00E9", "\u212B", "\u2126", "\u200B", " ", "\n", "\uFE0F", "\u0344")
        val r = SplittableRandom(23)
        var cases = 0
        var expanded = 0
        repeat(600) {
            val text = (0 until r.nextInt(1, 120)).joinToString("") { alphabet[r.nextInt(alphabet.size)] }
            val counted = AnswerText.normalizedBytes(text)
            assertTrue(counted <= text.toByteArray(Charsets.UTF_8).size, "monotone over ${text.map { it.code.toString(16) }}")
            assertEquals(counted, outBytesOf(text))
            cases++
            if (AnswerText.normalize(text).toByteArray(Charsets.UTF_8).size > text.toByteArray(Charsets.UTF_8).size) expanded++
        }
        assertTrue(cases >= Laws.FLOOR && expanded > 20, "cases $cases, NFC-expanded $expanded")
    }

    /** ERR-FX3-TP-4 (review R4TB-05): a formatted honest answer loses a fifth of its raw bytes; the bytes-per-token table is on the normalised basis, so it reads as the plain answer does. */
    @Test
    fun aFormattedHonestAnswerOnTheNormalisedBasisIsCorroboratedGrade() {
        val formatted = "  r = f(y)\n".repeat(133) + "xxxx"
        assertEquals(1_467, formatted.toByteArray().size)
        val bytes = outBytesOf(formatted)
        assertEquals(1_200, bytes)
        val honest = ClaimTracker.evaluate(Observation(key, true, 0, 20_500, bytes, 500, 1_024, 11, false, null, null, null, null), claim, bpt)
        assertEquals(973, honest.ratio)
        assertTrue(honest.ratio >= ClaimTracker.CORR)
        val slow = ClaimTracker.evaluate(Observation(key, true, 0, 25_901, bytes, 500, 1_024, 11, false, null, null, null, null), claim, bpt)
        assertEquals(770, slow.ratio, "a lender at 90 percent of its claim, judged by a table measured on raw bytes: below CORR, so the table must be normalised")
    }

    @Test
    fun theNormalisedLengthDoesNotDependOnHowTheAnswerIsCutIntoChunks() {
        val alphabet = listOf("\uFE0F", "\u034F", "\u2800", "x", "y", " ", " ", "\n", "​", "e", "́", "é", " ", " ", " ", "　", "\u0007", "﻿", "क", "़")
        val r = SplittableRandom(19)
        var cases = 0
        var shrank = 0
        repeat(600) {
            val text = (0 until r.nextInt(1, 200)).joinToString("") { alphabet[r.nextInt(alphabet.size)] }
            val cuts = (0 until r.nextInt(0, 8)).map { r.nextInt(0, text.length + 1) }.toSortedSet().toList()
            val pieces = (listOf(0) + cuts + listOf(text.length)).zipWithNext { a, b -> text.substring(a, b) }
            val whole = outBytesOf(text)
            assertEquals(whole, outBytesOf(*pieces.toTypedArray()), "cut at $cuts of ${text.map { it.code.toString(16) }}")
            assertEquals(AnswerText.normalizedBytes(text), whole)
            cases++
            if (whole < text.toByteArray().size) shrank++
        }
        assertTrue(cases >= Laws.FLOOR && shrank > 100, "cases $cases, shrunk $shrank")
    }

    @Test
    fun theNormaliserFollowsTheSpecsOrder() {
        assertEquals("", AnswerText.normalize(""))
        assertEquals("", AnswerText.normalize(" \n\t​ "))
        assertEquals("a b", AnswerText.normalize("  a      b  "))
        assertEquals("ab", AnswerText.normalize("a\nb"), "a newline is a control character: removed, not collapsed")
        assertEquals("a b", AnswerText.normalize("a \u0007 b"), "control characters go first, so the two spaces meet and collapse")
        assertEquals("a b", AnswerText.normalize("a ​ b"))
        assertEquals("é", AnswerText.normalize("é"))
        assertEquals("áb".let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }, AnswerText.normalize("áb"))
        assertEquals("क़", AnswerText.normalize("क़"), "NFC can expand: U+0958 has a composition exclusion")
    }

    @Test
    fun aStreamBeyondAnyHonestAnswerIsSaturatedAndDiscardedAsOverlong() {
        val o = AttemptObserver()
        o.onBodySent(0)
        val piece = "x".repeat(65_536)
        repeat(70) { o.onChunk(100L + it, sse(piece)) }
        o.onEnd(200, "{\"terminal\":\"done\"}".toByteArray())
        assertEquals(Sat.MAX, o.outBytes())
        val ev = ClaimTracker.evaluate(Observation(key, true, 0, 200, o.outBytes(), 500, 1_024, 11, false, null, null, null, null), claim, bpt)
        assertEquals(DiscardReason.OVERLONG, ev.discard)
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
