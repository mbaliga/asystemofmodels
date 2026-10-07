package xyz.mdhv.asom.lab.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

/** Fixes of the independent review (BRP-01, -02, -04, -05) and the pinned facts of BRP-03, -07 and -08. Evidence label: LAB, oracle: self. */
class FixBenchDeriveTest {
    private val phone = ExampleDocs.phoneDoc()

    private fun failNumerics(doc: BenchDoc, tier: String): BenchDoc =
        doc.copy(tiers = doc.tiers.map { if (it.tier == tier) it.copy(numerics = it.numerics.copy(milliNatsPerToken = 2_300)) else it })

    private fun fastDesktop(sustainTier: String): BenchDoc {
        val d = phone.copy(device = phone.device.copy(platform = "linux", form = "desktop", unifiedMemory = false), memory = phone.memory.copy(availAtStartBytes = 48_000_000_000L))
        return d.copy(
            tiers = d.tiers.map { t -> t.copy(tests = t.tests.map { x -> x.copy(samples = x.samples.map { it / 4 }, wholeSamples = x.wholeSamples?.map { it / 4 }) }) },
            sustain = d.sustain!!.copy(tier = sustainTier, windows = d.sustain!!.windows.map { it.copy(tokens = it.tokens * 4) }),
        )
    }

    // ---------------------------------------------------------------------------------------------------- BRP-01

    @Test
    fun aSustainBlockOnAFailedNumericsTierIsIgnoredByEveryAnswer() {
        val c = Counter("BRP-01 sustain on a failed tier")
        val d = Derive.derive(failNumerics(phone, "T3"))
        assertEquals("fail", d.tier("T3")!!.numerics.verdict)
        assertNull(d.answers.throttle, "the throttle answer must not come from the tier that failed numerics")
        c.hit()
        assertNull(d.answers.role.t3PlateauMtps)
        c.hit()
        assertNull(d.answers.role.t2PlateauMtps, "the T2 plateau estimate must not borrow the stability of the failed tier")
        c.hit()
        assertEquals("requester", d.answers.role.code)
        c.hit()
        c.requireNonVacuous(4)
    }

    @Test
    fun aFastButWrongBackendIsNotAStrongProvider() {
        val c = Counter("BRP-01 desktop role")
        for (sustainTier in listOf("T3", "T2")) {
            val control = Derive.derive(fastDesktop(sustainTier))
            if (sustainTier == "T3") assertEquals("strong-provider", control.answers.role.code, "control: the same document with sound numerics")
            else assertNotEquals("requester", control.answers.role.code)
            c.hit()
            val wrong = Derive.derive(failNumerics(fastDesktop(sustainTier), "T3"))
            assertNotEquals("strong-provider", wrong.answers.role.code, "sustain on $sustainTier, T3 numerics fail")
            assertNull(wrong.answers.role.t3PlateauMtps)
            c.hit()
        }
        c.requireNonVacuous(4)
    }

    @Test
    fun aFailedTiersSustainDoesNotLowerTheOverallConfidence() {
        val short = phone.sustain!!.copy(endReason = "USER_STOP", windows = phone.sustain!!.windows.take(10))
        val sound = Derive.derive(phone.copy(sustain = short))
        assertEquals(Confidence.LOW, sound.answers.overallConfidence, "control: a short heat test on a sound tier caps the overall confidence")
        val failed = Derive.derive(failNumerics(phone.copy(sustain = short), "T3"))
        assertEquals(Confidence.HIGH, failed.answers.overallConfidence)
    }

    // ---------------------------------------------------------------------------------------------------- BRP-02

    private fun windowsOf(tokens: List<Long>): List<BWindow> = tokens.mapIndexed { i, t -> BWindow(i * 15_000L, t, 1_000_000_000L, 0) }

    @Test
    fun theOnsetThresholdIsTheFloorOfNineHundredPermillePeak() {
        val c = Counter("BRP-02 onset boundary")
        // peak 7583: 900 * 7583 / 1000 = 6824.7, floor 6824. A smoothed 6824 is NOT below 6824.
        val atFloor = SustainMath.analyze(windowsOf(listOf(7583L, 7583, 7583) + List(6) { 6824L }))
        assertEquals(7583L, atFloor.peak)
        assertNull(atFloor.onsetIdx, "6824 < floor(6824.7) is false")
        c.hit()
        val below = SustainMath.analyze(windowsOf(listOf(7583L, 7583, 7583) + List(6) { 6823L }))
        assertEquals(3, below.onsetIdx)
        c.hit()
        // 900 * 10000 / 1000 = 9000 exactly: the same law for a peak whose product is a multiple of 1000
        assertNull(SustainMath.analyze(windowsOf(listOf(10_000L, 10_000, 10_000) + List(6) { 9000L })).onsetIdx)
        c.hit()
        assertEquals(3, SustainMath.analyze(windowsOf(listOf(10_000L, 10_000, 10_000) + List(6) { 8999L })).onsetIdx)
        c.hit()
        val s = BSustain("T3", 15_000, 600_000, "TIME_CAP", null, windowsOf(listOf(7583L, 7583, 7583) + List(6) { 6824L }))
        val d = Derive.deriveSustainStandalone(s, "cool")
        assertNull(d.onsetMs)
        assertEquals(null, d.thermalCodeAtOnset)
        c.hit()
        c.requireNonVacuous(5)
    }

    // ---------------------------------------------------------------------------------------------------- BRP-04

    @Test
    fun aTierWithAnInsufficientTestIsNotProjectedSoNoRowOverclaims() {
        val c = Counter("BRP-04 insufficient test")
        val extra = BTest(TestSpec.parse("tg128@d4096")!!, listOf(15_000_000L), null)
        val doc = phone.copy(tiers = phone.tiers.map { if (it.tier == "T2") it.copy(tests = it.tests + extra) else it })
        val d = Derive.derive(doc)
        assertEquals(Confidence.INSUFFICIENT, d.tier("T2")!!.test("tg128@d4096")!!.confidence)
        c.hit()
        val rows = Project.results(d, Audience.OWN)
        val flagsOf = rows.map { r -> ((r as JObject)["flags"] as JArray).items.map { (it as JString).value } }
        val modelOf = rows.map { r -> ((r as JObject)["modelId"] as JString).value }
        for ((m, f) in modelOf.zip(flagsOf)) {
            if (m == "qwen3-4b") fail("T2 has a test without a value; no row may carry it as confidence-high (flags $f)")
            c.hit()
        }
        assertEquals(2, rows.size)
        c.requireNonVacuous(3)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    @Test
    fun prefillAtDepthIsRefusedByTheDecoder() {
        val good = BenchCodec.encode(phone)
        val deep = BJ.remove(BJ.set(good, listOf("tiers", 0, "tests", 0, "test"), JString("pp512@d2048")), listOf("tiers", 0, "tests", 0, "wholeSamples"))
        val ex = assertFailsWith<SchemaViolation> { BenchCodec.decode(deep, RdContext(false)) }
        assertTrue(ex.why.contains("depth"), "the refusal is the prefill-at-depth rule (${ex.path}: ${ex.why})")
        // control: a decode point at depth carries no whole spans and decodes; the refusal above is about the prefill depth alone
        val deepDecode = BJ.set(good, listOf("tiers", 0, "tests", 1, "test"), JString("tg128@d4096"))
        BenchCodec.decode(deepDecode, RdContext(false))
    }

    // ---------------------------------------------------------------------------------------------------- BRP-05

    @Test
    fun aSustainedPhaseThatStartsWarmIsLowWhicheverRecordSaysSo() {
        val c = Counter("BRP-05 warm sustain start")
        val tierWarm = phone.copy(tiers = phone.tiers.map { if (it.tier == "T3") it.copy(startThermal = "warm", startThermalCode = 1) else it })
        assertEquals("cool", tierWarm.run.startThermal)
        assertEquals(Confidence.LOW, Derive.derive(tierWarm).sustain!!.confidence, "run cool, sustain tier warm")
        c.hit()
        val runWarm = phone.copy(run = phone.run.copy(startThermal = "warm"))
        assertEquals(Confidence.LOW, Derive.derive(runWarm).sustain!!.confidence, "run warm, sustain tier cool")
        c.hit()
        assertEquals(Confidence.HIGH, Derive.derive(phone).sustain!!.confidence, "control: both cool")
        c.hit()
        // another tier warm does not matter, only the sustain tier and the run start
        val otherWarm = phone.copy(tiers = phone.tiers.map { if (it.tier == "T1") it.copy(startThermal = "warm", startThermalCode = 1) else it })
        assertEquals(Confidence.HIGH, Derive.derive(otherWarm).sustain!!.confidence)
        c.hit()
        c.requireNonVacuous(4)
    }

    // ---------------------------------------------------------------------------------------------------- BRP-03 and BRP-07: facts pinned, behaviour unchanged

    @Test
    fun theRowFlagsRestartedSwappedAndThermalDriftAreStillTheJvmAdditionsPendingTheOwnerRuling() {
        val c = Counter("BRP-03 and BRP-07 row flags")
        val doc = phone.copy(tiers = phone.tiers.map { if (it.tier == "T2") it.copy(restarts = 1, swapDeltaBytes = 300_000_000L) else it })
        val rows = Project.results(Derive.derive(doc), Audience.OWN)
        val t2 = rows.first { (it as JObject)["modelId"] == JString("qwen3-4b") } as JObject
        val flags = (t2["flags"] as JArray).items.map { (it as JString).value }
        assertTrue("restarted" in flags, "$flags")
        c.hit()
        assertTrue("swapped" in flags, "$flags")
        c.hit()
        val drift = phone.copy(tiers = phone.tiers.map { t ->
            if (t.tier != "T2") t
            else t.copy(tests = t.tests.map { x -> if (x.spec.name == "tg128@d0") x.copy(samples = listOf(10_000_000L, 10_400_000, 10_800_000, 11_200_000, 11_600_000)) else x })
        })
        val dt2 = Project.results(Derive.derive(drift), Audience.OWN).first { (it as JObject)["modelId"] == JString("qwen3-4b") } as JObject
        assertTrue("thermal-drift" in (dt2["flags"] as JArray).items.map { (it as JString).value })
        c.hit()
        c.requireNonVacuous(3)
    }

    // ---------------------------------------------------------------------------------------------------- BRP-08: the order of the outlier and drift rules

    @Test
    fun theSeedsOfSection95KeepTheOutlierRuleBeforeTheDriftRule() {
        // 9.5 pins M04-001 (last rep 33% slower) and M04-002b (last rep 40% slower) as OUTLIER_EXCLUDED and high: drift is judged on the kept reps.
        val a = Stats.stat(listOf(17_280_000L, 17_350_000, 17_310_000, 17_265_000, 23_000_000).map { Checked.rate(128, it) })
        assertEquals(listOf("OUTLIER_EXCLUDED"), a.flags)
        val b = Stats.stat(listOf(10_000_000L, 10_000_000, 10_000_000, 10_000_000, 14_000_000).map { Checked.rate(128, it) })
        assertEquals(listOf("OUTLIER_EXCLUDED"), b.flags)
        assertFalse(a.drift || b.drift)
    }
}
